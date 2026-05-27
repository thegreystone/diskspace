/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.diskspace.scan;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.Pointer;
import se.hirt.diskspace.model.DirectoryNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Bulk-syscall directory scanner for macOS. Each directory is opened once with {@code open(2)} and walked via
 * {@code getattrlistbulk(2)}, which returns metadata (name + type + file ID + device ID + allocated size) for many
 * children in a single syscall instead of one {@code stat(2)} per entry. On APFS this collapses scan time roughly the
 * same way {@code MftScanner} does on Windows — the dominant cost in {@link ParallelDirectoryScanner} on macOS is
 * metadata-syscall round-trip, which this scanner amortises across ~500–800 entries per call (64 KB buffer / ~80–120 B
 * per packed entry).
 * <p>Architecture mirrors {@link ParallelDirectoryScanner}: a per-scan
 * {@link ForkJoinPool}, one {@link RecursiveAction} per directory, work-stealing for skewed trees. Each task opens its
 * own fd, drains it, closes it, then forks subtasks for child directories and waits via {@code invokeAll}. Concurrent
 * fd count is bounded by pool parallelism — well below macOS's default rlimit.
 * <p><b>Native-image only.</b> The {@code @CFunction} bindings in {@link Darwin}
 * resolve only when running as a built native-image; in JVM dev mode ({@code mvn javafx:run}) {@link #isAvailable()}
 * returns false and {@code Scanner.forVolume(...)} falls back to {@link ParallelDirectoryScanner}. The class is gated
 * with {@code @Platforms(DARWIN)} so it does not exist on non-Darwin native-image builds at all; cross-platform code
 * reaches it only via {@link se.hirt.diskspace.platform.Capabilities#NATIVE_SCANNER_PROVIDER}, whose static initializer
 * dead-strips the reference on non-matching platforms.
 * <p><b>Cross-mount detection.</b> The scan root's device ID is read up front via
 * {@code getattrlist(2)} (single-entry); each child entry's {@code ATTR_CMN_DEVID} is compared against it before
 * recursion, so we never descend into mounted volumes (iOS simulator data, disk images mounted under {@code /Volumes},
 * etc.).
 * <p><b>Hardlink dedup.</b> A concurrent set of seen file IDs gates both directory
 * descent (firmlinks / bind mounts) and file accounting (multi-hardlinked files reachable via more than one path).
 * Matches the dedup semantics of {@link ParallelDirectoryScanner}.
 * <p><b>APFS clone dedup.</b> A second concurrent set tracks seen {@code ATTR_CMNEXT_CLONEID} values — files cloned
 * from a common source (via {@code clonefile(2)} or {@code cp -c}) initially share APFS blocks via copy-on-write but
 * may diverge as either side is modified. We charge the first member of each clone family at its full apparent size
 * (shared + private blocks) and subsequent members at {@code ATTR_CMNEXT_PRIVATESIZE} only (just their CoW-modified
 * blocks, excluding the still-shared extents already counted). Total for an N-member family becomes
 * {@code shared + sum(private_i)}, which matches actual on-disk usage exactly when extents haven't been further
 * shared with files outside the family. The dev-mode {@link ParallelDirectoryScanner} fallback does not have an
 * equivalent (Java NIO doesn't expose either attribute), so dev builds still overcount.
 */
@Platforms(Platform.DARWIN.class)
public final class MacBulkScanner implements Scanner {

	private static final Logger LOG = Logger.getLogger(MacBulkScanner.class.getName());

	private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 10 Hz

	/** Files at or above this size become their own sunburst sector. 1 GB decimal — matches the other scanners. */
	private static final long LARGE_FILE_THRESHOLD_BYTES = 1_000_000_000L;

	/**
	 * Output buffer size for each {@code getattrlistbulk} call. With our 72-byte fixed area (commonattr + fileattr +
	 * 8-byte CMNEXT private_size + 8-byte CMNEXT clone_id) + name string + padding, each entry packs to ~96–136 B, so
	 * 64 KB holds ~440–700 entries per syscall. Larger buffers don't deliver meaningful additional throughput (the cost
	 * is dominated by the kernel's per-entry vnode lookups, not the per-syscall round-trip) and grow the unmanaged
	 * scratch footprint linearly with pool parallelism.
	 */
	private static final int BULK_BUFFER_SIZE = 64 * 1024;

	/**
	 * Coarse availability check. macOS + native-image runtime only — the {@link Darwin} bindings are GraalVM
	 * {@code @CFunction} stubs that throw {@code UnsatisfiedLinkError} in a plain JVM.
	 */
	public static boolean isAvailable() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!(os.contains("mac") || os.contains("darwin")))
			return false;
		return ImageInfo.inImageRuntimeCode();
	}

	private final int parallelism;
	private volatile boolean cancelled;
	private volatile ForkJoinPool pool;
	private volatile Thread coordinator;

	/**
	 * @param parallelism
	 * 		size of the per-scan ForkJoinPool. {@code 1} runs sequentially (right for spinning external HDDs); higher
	 * 		values exploit metadata-throughput parallelism on SSDs. Same per-storage-profile mapping
	 *        {@link ParallelScannerProvider#parallelismFor(se.hirt.diskspace.model.StorageProfile)} uses.
	 */
	public MacBulkScanner(int parallelism) {
		if (parallelism < 1)
			throw new IllegalArgumentException("parallelism must be >= 1, got " + parallelism);
		this.parallelism = parallelism;
	}

	@Override
	public void scan(Path rootPath, ScanListener listener) {
		cancelled = false;
		DirectoryNode root = new DirectoryNode(null, displayName(rootPath), rootPath);
		listener.onStart(root);
		LOG.info(() -> "Scan start: " + rootPath + " (strategy=bulk parallelism=" + parallelism + ")");
		long startNanos = System.nanoTime();

		coordinator = new Thread(() -> {
			long rootDev = readRootDevId(rootPath);
			if (rootDev == ROOT_DEV_FAILED) {
				if (!cancelled)
					listener.onError(
							new RuntimeException("getattrlist(root) failed; cannot start bulk scan of " + rootPath));
				return;
			}
			ScanContext ctx = new ScanContext(rootPath, root, listener, rootDev);
			ForkJoinPool fjp = new ForkJoinPool(parallelism);
			pool = fjp;
			try {
				fjp.invoke(new DirScanTask(rootPath, root, ctx));
				if (!cancelled) {
					finishUnscanned(root);
					root.sortBySizeRecursive();
					long denied = ctx.permDeniedCount.sum();
					if (denied > 0)
						listener.onPermissionsDenied(denied);
					ScanTiming.log(LOG, rootPath, root, startNanos, "bulk(" + parallelism + ")");
					listener.onComplete(root);
				}
			} catch (Throwable t) {
				if (!cancelled)
					listener.onError(t);
			} finally {
				fjp.shutdownNow();
			}
		}, "DiskSpace-bulk-" + rootPath);
		coordinator.setDaemon(true);
		coordinator.start();
	}

	@Override
	public void cancel() {
		cancelled = true;
		ForkJoinPool p = pool;
		if (p != null)
			p.shutdownNow();
		Thread c = coordinator;
		if (c != null)
			c.interrupt();
	}

	// ── Per-scan state ────────────────────────────────────────────────────

	/** Sentinel returned by {@link #readRootDevId} when the getattrlist call fails. */
	private static final long ROOT_DEV_FAILED = -1L;

	private static final class ScanContext {
		final Path rootPath;
		final DirectoryNode rootNode;
		final ScanListener listener;
		/**
		 * Unsigned 32-bit device ID of the scan root; per-entry DEVID is compared against this for cross-mount
		 * detection.
		 */
		final long rootDev;
		/** Inode-set for hardlink + firmlink dedup. Used for both files (multi-link) and directories (bind mounts). */
		final Set<Long> seenFileIds = ConcurrentHashMap.newKeySet();
		/**
		 * Clone-id set for APFS clone dedup. A non-zero {@code ATTR_CMNEXT_CLONEID} groups files that share APFS extents
		 * via {@code clonefile(2)} (or {@code cp -c}). First member encountered is charged its full {@code allocsize}
		 * (shared + private); subsequent members are charged only their {@code ATTR_CMNEXT_PRIVATESIZE} (the CoW-modified
		 * blocks unique to them). Total for an N-member family is {@code shared + sum(private_i)}, matching actual on-disk
		 * usage. Heavy on macOS where the OS uses cloning extensively (Library/Containers initialised at install,
		 * build caches, local snapshots, Docker layers).
		 */
		final Set<Long> seenCloneIds = ConcurrentHashMap.newKeySet();
		final LongAdder permDeniedCount = new LongAdder();
		final AtomicReference<String> currentPath = new AtomicReference<>();
		/** Coarse lock around lastProgressNanos — uncontended at 10 Hz. */
		final Object progressLock = new Object();
		long lastProgressNanos;

		ScanContext(Path rootPath, DirectoryNode rootNode, ScanListener listener, long rootDev) {
			this.rootPath = rootPath;
			this.rootNode = rootNode;
			this.listener = listener;
			this.rootDev = rootDev;
		}

		void maybeEmitProgress(String path) {
			long now = System.nanoTime();
			synchronized (progressLock) {
				if (now - lastProgressNanos <= PROGRESS_INTERVAL_NANOS)
					return;
				lastProgressNanos = now;
			}
			listener.onProgress(rootNode.totalFileCount(), rootNode.totalBytes(), path);
		}
	}

	/**
	 * Reads the device ID of the scan root via a single-entry {@code getattrlist(2)}. The resulting unsigned dev_t is
	 * compared (as an unsigned long) against each child's {@code ATTR_CMN_DEVID} in {@link DirScanTask} to decide
	 * whether to recurse — same {@code rootDev} cross-mount guard {@link ParallelDirectoryScanner} implements via Java
	 * NIO's {@code BasicFileAttributes.fileKey()}.
	 */
	private static long readRootDevId(Path path) {
		Pointer alist = UnmanagedMemory.malloc(Darwin.ATTRLIST_SIZE_BYTES);
		Pointer buf = UnmanagedMemory.malloc(64);
		CCharPointer pathC = Darwin.allocCString(path.toString());
		try {
			writeAttrList(alist, Darwin.ATTR_CMN_RETURNED_ATTRS | Darwin.ATTR_CMN_DEVID, 0, 0);
			int rc = Darwin.getattrlist(pathC, alist, buf, 64, Darwin.FSOPT_PACK_INVAL_ATTRS);
			if (rc != 0) {
				int err = Darwin.__error().read();
				LOG.fine(() -> "readRootDevId(" + path + ") failed errno=" + err);
				return ROOT_DEV_FAILED;
			}
			// Layout: +0 uint32 length, +4 attribute_set_t returned_attrs (20 B), +24 dev_t devid (4 B).
			int devid = buf.readInt(24);
			return ((long) devid) & 0xFFFFFFFFL;
		} finally {
			UnmanagedMemory.free(alist);
			UnmanagedMemory.free(buf);
			UnmanagedMemory.free(pathC);
		}
	}

	/**
	 * Fills the 24-byte {@code struct attrlist} at {@code alist} with the requested commonattr + fileattr bits, and
	 * — when {@code FSOPT_ATTR_CMN_EXTENDED} is in the options passed to {@code getattrlist[bulk]} — a bitmap of
	 * {@code ATTR_CMNEXT_*} bits in the {@code forkattr} slot. Callers that don't want the CMNEXT group should pass
	 * {@code 0} for {@code cmnextAttrs}.
	 */
	private static void writeAttrList(Pointer alist, int commonAttrs, int fileAttrs, int cmnextAttrs) {
		alist.writeShort(0, (short) Darwin.ATTR_BIT_MAP_COUNT);
		alist.writeShort(2, (short) 0);  // reserved
		alist.writeInt(4, commonAttrs);
		alist.writeInt(8, 0);  // volattr
		alist.writeInt(12, 0); // dirattr
		alist.writeInt(16, fileAttrs);
		alist.writeInt(20, cmnextAttrs); // forkattr / commonextattr when FSOPT_ATTR_CMN_EXTENDED
	}

	// ── Per-directory scan task ───────────────────────────────────────────

	private final class DirScanTask extends RecursiveAction {
		private final Path dir;
		private final DirectoryNode node;
		private final ScanContext ctx;

		DirScanTask(Path dir, DirectoryNode node, ScanContext ctx) {
			this.dir = dir;
			this.node = node;
			this.ctx = ctx;
		}

		@Override
		protected void compute() {
			if (cancelled)
				return;
			node.setScanning();
			ctx.currentPath.set(dir.toString());

			List<DirScanTask> subTasks = new ArrayList<>();
			int fd;
			int openErr = 0;
			CCharPointer pathC = Darwin.allocCString(dir.toString());
			try {
				fd = Darwin.open(pathC, Darwin.O_RDONLY);
				if (fd < 0)
					openErr = Darwin.__error().read();
			} finally {
				UnmanagedMemory.free(pathC);
			}

			if (fd < 0) {
				if (openErr == Darwin.EACCES)
					ctx.permDeniedCount.increment();
				final int e = openErr;
				LOG.fine(() -> "open(" + dir + ") failed errno=" + e);
				node.markDone();
				return;
			}

			try {
				readDirEntries(fd, subTasks);
			} finally {
				Darwin.close(fd);
			}

			ctx.maybeEmitProgress(dir.toString());

			if (!subTasks.isEmpty() && !cancelled)
				invokeAll(subTasks);

			node.markDone();
		}

		/**
		 * Drives the {@code getattrlistbulk} loop for {@code fd}. One attrlist + one 64 KB output buffer allocated here
		 * per task; freed in finally. Both are per-task rather than per-thread because Substrate VM forbids
		 * ThreadLocal-with-finalizer patterns and a pool here would just add complexity for a single-allocation win.
		 */
		private void readDirEntries(int fd, List<DirScanTask> subTasks) {
			Pointer alist = UnmanagedMemory.malloc(Darwin.ATTRLIST_SIZE_BYTES);
			Pointer buf = UnmanagedMemory.malloc(BULK_BUFFER_SIZE);
			try {
				int commonAttrs = Darwin.ATTR_CMN_RETURNED_ATTRS | Darwin.ATTR_CMN_NAME | Darwin.ATTR_CMN_DEVID | Darwin.ATTR_CMN_OBJTYPE | Darwin.ATTR_CMN_FILEID;
				int fileAttrs = Darwin.ATTR_FILE_ALLOCSIZE;
				// CMNEXT bits are placed in the entry buffer in ascending bit order:
				// PRIVATESIZE (0x08) lands at +56, CLONEID (0x200) at +64.
				int cmnextAttrs = Darwin.ATTR_CMNEXT_PRIVATESIZE | Darwin.ATTR_CMNEXT_CLONEID;
				writeAttrList(alist, commonAttrs, fileAttrs, cmnextAttrs);
				long opts = Darwin.FSOPT_PACK_INVAL_ATTRS | Darwin.FSOPT_ATTR_CMN_EXTENDED;

				while (!cancelled) {
					int count = Darwin.getattrlistbulk(fd, alist, buf, BULK_BUFFER_SIZE, opts);
					if (count == 0)
						break;  // end of directory
					if (count < 0) {
						int err = Darwin.__error().read();
						LOG.fine(() -> "getattrlistbulk(" + dir + ") errno=" + err);
						break;
					}

					int offset = 0;
					for (int i = 0; i < count; i++) {
						if (cancelled)
							break;
						int entryLen = buf.readInt(offset);
						if (entryLen <= 0 || offset + entryLen > BULK_BUFFER_SIZE)
							break;
						processEntry(buf, offset, subTasks);
						offset += entryLen;
					}
				}
			} finally {
				UnmanagedMemory.free(alist);
				UnmanagedMemory.free(buf);
			}
		}

		/**
		 * Parses one packed entry. Layout (with {@code FSOPT_PACK_INVAL_ATTRS | FSOPT_ATTR_CMN_EXTENDED} and the attrlist
		 * set up in {@link #readDirEntries}):
		 * <pre>
		 *   +0   uint32 entry_length
		 *   +4   attribute_set_t returned_attrs   (5 × uint32 = 20 B; ATTR_CMN_RETURNED_ATTRS is always first)
		 *   +24  attrreference_t name             (int32 dataoffset + uint32 length; ATTR_CMN_NAME)
		 *   +32  dev_t devid                      (4 B; ATTR_CMN_DEVID)
		 *   +36  fsobj_type_t objtype             (4 B enum; ATTR_CMN_OBJTYPE — VREG/VDIR/VLNK/…)
		 *   +40  uint64 fileid                    (ATTR_CMN_FILEID)
		 *   +48  off_t allocsize                  (ATTR_FILE_ALLOCSIZE — physical bytes on disk; 0 for directories)
		 *   +56  off_t private_size               (ATTR_CMNEXT_PRIVATESIZE — bytes unique to this file, not shared)
		 *   +64  uint64 clone_id                  (ATTR_CMNEXT_CLONEID — 0 if file is not in a clone family)
		 *   +72+ variable-length region: name string at (entry_start + 24 + name_dataoffset)
		 * </pre>
		 * Attributes within each group are placed in ascending bit order; {@code ATTR_CMN_RETURNED_ATTRS} (0x80000000) is
		 * special-cased to always appear first. CMNEXT attributes follow the fileattr group when
		 * {@code FSOPT_ATTR_CMN_EXTENDED} is set: {@code PRIVATESIZE} (0x08) then {@code CLONEID} (0x200).
		 */
		private void processEntry(Pointer buf, int entryOff, List<DirScanTask> subTasks) {
			int nameDataOffset = buf.readInt(entryOff + 24);
			int nameLen = buf.readInt(entryOff + 28);
			int devid = buf.readInt(entryOff + 32);
			int objtype = buf.readInt(entryOff + 36);
			long fileid = buf.readLong(entryOff + 40);
			long allocsize = buf.readLong(entryOff + 48);
			long privateSize = buf.readLong(entryOff + 56);
			long cloneId = buf.readLong(entryOff + 64);

			// Only files and directories contribute to disk usage. Skip symlinks (we don't
			// follow), sockets, fifos, char/block devices, etc. — match ParallelDirectoryScanner.
			if (objtype != Darwin.VREG && objtype != Darwin.VDIR)
				return;

			// Name is UTF-8, NUL-terminated. nameLen includes the NUL.
			if (nameLen <= 1 || nameLen > 4096)
				return;
			int nameStart = entryOff + 24 + nameDataOffset;
			int nameBytes = nameLen - 1;
			byte[] nameArr = new byte[nameBytes];
			for (int i = 0; i < nameBytes; i++)
				nameArr[i] = buf.readByte(nameStart + i);
			String name = new String(nameArr, StandardCharsets.UTF_8);
			// getattrlistbulk doesn't return "." or ".." per the man page, but be defensive.
			if (".".equals(name) || "..".equals(name))
				return;

			long entryDev = ((long) devid) & 0xFFFFFFFFL;

			if (objtype == Darwin.VDIR) {
				// Cross-mount guard: don't descend into separately-mounted volumes.
				if (entryDev != ctx.rootDev)
					return;
				// Firmlink / bind-mount dedup: a directory reachable via two parents under
				// the same device ID shows up twice in enumeration but has one inode.
				if (!ctx.seenFileIds.add(fileid))
					return;
				Path childPath = dir.resolve(name);
				DirectoryNode childNode = node.addChild(name, childPath);
				subTasks.add(new DirScanTask(childPath, childNode, ctx));
				return;
			}

			// VREG. Hardlink dedup: a file reachable via two directory entries (same inode,
			// different names) is counted once. This is the same semantics as
			// ParallelDirectoryScanner.processEntry's seenKeys check.
			if (!ctx.seenFileIds.add(fileid))
				return;
			// APFS clone dedup. clone_id == 0 means the file isn't in a clone family at all — charge full allocsize.
			// Otherwise: charge the first member of the family at allocsize (shared + private), and subsequent members
			// at privatesize only (their CoW-modified blocks). Total for an N-member family becomes shared + sum(private)
			// which equals the actual on-disk usage. This correctly handles both pristine clones (privatesize ≈ 0) and
			// heavily-diverged clones (privatesize ≈ allocsize).
			// Note: charged bytes can legitimately be 0 — empty files (allocsize == 0) and pristine
			// non-first clone-family members (privateSize == 0) both fall here. We still call addFile()
			// so totalFileCount reflects the real number of filesystem entries; dedup is a byte-accounting
			// concern, not a file-existence one. Matches ParallelDirectoryScanner / MftScanner semantics.
			long chargedSize;
			if (cloneId == 0 || ctx.seenCloneIds.add(cloneId)) {
				chargedSize = allocsize;
			} else {
				chargedSize = privateSize;
			}
			node.addFile(chargedSize);
			if (chargedSize >= LARGE_FILE_THRESHOLD_BYTES) {
				node.addLargeFile(name, chargedSize);
			} else {
				node.addSmallerFileBytes(chargedSize);
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	private static String displayName(Path p) {
		Path name = p.getFileName();
		return name == null ? p.toString() : name.toString();
	}

	/** Ensures every node in the tree has reached DONE. Mirrors ParallelDirectoryScanner's safety net. */
	private static void finishUnscanned(DirectoryNode node) {
		if (node.state() != DirectoryNode.ScanState.DONE)
			node.markDone();
		for (DirectoryNode child : node.children()) {
			finishUnscanned(child);
		}
	}
}
