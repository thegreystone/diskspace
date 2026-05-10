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

import se.hirt.diskspace.model.DirectoryNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The disk-space scanner. Each directory becomes one {@link RecursiveAction}; subdirectories are forked into a per-scan
 * {@link ForkJoinPool} so multiple subtrees grow concurrently. Work-stealing balances skewed trees automatically — a worker that finishes a
 * shallow branch grabs unstarted subtree tasks from a busier worker's deque.
 * <p>Reads {@code unix:size,blocks,fileKey,isRegularFile,isDirectory} in a single
 * {@code Files.readAttributes} call, cutting per-file syscalls in half on macOS/Linux relative to the obvious {@code BasicFileAttributes} +
 * separate {@code unix:blocks} pattern. Falls back to {@link BasicFileAttributes} on Windows where the unix view is unsupported.
 * <p>Cancellation is cooperative: each task checks {@link #cancelled} at entry and on every
 * directory entry. The pool is shut down in {@code finally} once the root task returns.
 */
public final class ParallelDirectoryScanner implements Scanner {

	private static final Logger LOG = Logger.getLogger(ParallelDirectoryScanner.class.getName());

	private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 10 Hz

	/**
	 * Files at or above this size get their own sunburst sector; smaller files are summed per-directory into a "Smaller files" aggregate
	 * sector. 1 GB decimal.
	 */
	private static final long LARGE_FILE_THRESHOLD_BYTES = 1_000_000_000L;

	private final int parallelism;
	private volatile boolean cancelled;
	private volatile ForkJoinPool pool;
	private volatile Thread coordinator;

	/**
	 * @param parallelism
	 * 		size of the per-scan ForkJoinPool. {@code 1} runs sequentially (right for spinning disks where the kernel can do readahead and
	 * 		concurrent threads only cost seeks); higher values exploit metadata-throughput parallelism on SSD/NVMe and hide RTT on network
	 * 		filesystems. See {@link Scanner#forVolume} for the mapping by storage profile.
	 */
	public ParallelDirectoryScanner(int parallelism) {
		if (parallelism < 1)
			throw new IllegalArgumentException("parallelism must be >= 1, got " + parallelism);
		this.parallelism = parallelism;
	}

	@Override
	public void scan(Path rootPath, ScanListener listener) {
		cancelled = false;
		DirectoryNode root = new DirectoryNode(null, displayName(rootPath), rootPath);
		listener.onStart(root);
		String strategy = parallelism == 1 ? "sequential" : "parallel";
		LOG.info(() -> "Scan start: " + rootPath + " (strategy=" + strategy + " parallelism=" + parallelism + ")");
		long startNanos = System.nanoTime();

		coordinator = new Thread(() -> {
			ScanContext ctx;
			try {
				ctx = new ScanContext(rootPath, root, listener);
			} catch (IOException e) {
				if (!cancelled)
					listener.onError(e);
				return;
			}
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
					ScanTiming.log(LOG, rootPath, root, startNanos, strategy + "(" + parallelism + ")");
					listener.onComplete(root);
				}
			} catch (Throwable t) {
				if (!cancelled)
					listener.onError(t);
			} finally {
				fjp.shutdownNow();
			}
		}, "DiskSpace-scan-" + rootPath);
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

	/** Per-scan shared state — the things every {@link DirScanTask} needs access to. */
	private static final class ScanContext {
		final Path rootPath;
		final DirectoryNode rootNode;
		final ScanListener listener;
		final long rootDev;
		/**
		 * Visited inode set: {@code fileKey} for every file/directory we've counted, used to dedup hardlinks (files reachable via multiple
		 * directory entries) and bind mounts / firmlinks (directories reachable via multiple paths).
		 */
		final Set<Object> seenKeys = ConcurrentHashMap.newKeySet();
		final LongAdder permDeniedCount = new LongAdder();
		final AtomicReference<String> currentPath = new AtomicReference<>();
		/** Lock around lastProgressNanos — coarse but uncontended at 10 Hz. */
		final Object progressLock = new Object();
		long lastProgressNanos;

		ScanContext(Path rootPath, DirectoryNode rootNode, ScanListener listener) throws IOException {
			this.rootPath = rootPath;
			this.rootNode = rootNode;
			this.listener = listener;
			this.rootDev = deviceOf(Files.readAttributes(rootPath, BasicFileAttributes.class).fileKey());
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
	 * One {@code RecursiveAction} per directory: lists entries, accumulates files locally, forks a child task per subdirectory, then waits
	 * for them all via {@code invokeAll}.
	 */
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
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
				for (Path entry : ds) {
					if (cancelled)
						break;
					processEntry(entry, subTasks);
				}
			} catch (AccessDeniedException ade) {
				ctx.permDeniedCount.increment();
			} catch (IOException ignore) {
				// Directory not readable; skip.
			} catch (Throwable t) {
				LOG.log(Level.FINE, "Unexpected error listing " + dir, t);
			}

			ctx.maybeEmitProgress(dir.toString());

			if (!subTasks.isEmpty() && !cancelled) {
				invokeAll(subTasks);
			}

			node.markDone();
		}

		private void processEntry(Path entry, List<DirScanTask> subTasks) {
			EntryStat stat;
			try {
				stat = readStat(entry);
			} catch (AccessDeniedException ade) {
				ctx.permDeniedCount.increment();
				return;
			} catch (IOException ioe) {
				return;
			}

			Object key = stat.fileKey();

			if (stat.isDirectory()) {
				// Don't cross into separately-mounted volumes (e.g. iOS simulator data
				// volumes under Library/Developer/CoreSimulator/Volumes).
				if (key != null && ctx.rootDev >= 0 && deviceOf(key) != ctx.rootDev)
					return;
				// Already visited via a firmlink / bind mount.
				if (key != null && !ctx.seenKeys.add(key))
					return;
				DirectoryNode childNode = node.addChild(displayName(entry), entry);
				subTasks.add(new DirScanTask(entry, childNode, ctx));
				return;
			}

			if (stat.isRegularFile()) {
				// Hardlink dedup: same fileKey reached via two directory entries → count once.
				if (key != null && !ctx.seenKeys.add(key))
					return;
				long size = stat.size();
				node.addFile(size);
				if (size >= LARGE_FILE_THRESHOLD_BYTES) {
					Path fname = entry.getFileName();
					node.addLargeFile(fname == null ? entry.toString() : fname.toString(), size);
				} else {
					node.addSmallerFileBytes(size);
				}
			}
		}
	}

	/** Stat fields we actually use, gathered in a single {@code Files.readAttributes} call. */
	private record EntryStat(long size, Object fileKey, boolean isRegularFile, boolean isDirectory) {
	}

	/**
	 * {@code true} iff this JDK accepts our optimized {@code unix:size,blocks,fileKey,isRegularFile,isDirectory} attribute query. False on
	 * Windows (no unix view) and on JDK 25+ where {@code unix:blocks} was removed from the supported attribute set — the JDK throws
	 * {@code IllegalArgumentException: 'blocks' not recognized} for every {@code readAttributes} call. Detected once at class load by
	 * actually attempting the read against the current working directory; the alternative — a per-file try/catch — cost ~243 exceptions/sec
	 * on Windows during a scan (caught silently and ignored, but {@code fillInStackTrace} dominated allocation and CPU profiles in JFR).
	 * One probe replaces millions of throws.
	 */
	private static final boolean UNIX_STAT_SUPPORTED = probeUnixStatSupport();

	private static boolean probeUnixStatSupport() {
		if (!FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) {
			return false;
		}
		try {
			Files.readAttributes(Paths.get("."), "unix:size,blocks,fileKey,isRegularFile,isDirectory", LinkOption.NOFOLLOW_LINKS);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Reads size, fileKey, and type bits in one syscall. On macOS/Linux pre-JDK-25 this also pulls {@code unix:blocks} so the size we record
	 * is physical-on-disk (blocks × 512) rather than logical, matching the sequential scanner's {@code physicalSize} behaviour. On Windows
	 * (no unix view) and on JDK 25+ (where {@code unix:blocks} was removed) we fall back to {@link BasicFileAttributes} and lose the
	 * physical-vs-logical distinction.
	 */
	private static EntryStat readStat(Path p) throws IOException {
		if (UNIX_STAT_SUPPORTED) {
			Map<String, Object> a = Files.readAttributes(p, "unix:size,blocks,fileKey,isRegularFile,isDirectory",
					LinkOption.NOFOLLOW_LINKS);
			Long blocks = (Long) a.get("blocks");
			long logicalSize = (Long) a.get("size");
			long size = blocks != null ? blocks * 512L : logicalSize;
			return new EntryStat(size, a.get("fileKey"), (Boolean) a.get("isRegularFile"), (Boolean) a.get("isDirectory"));
		}
		BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		return new EntryStat(a.size(), a.fileKey(), a.isRegularFile(), a.isDirectory());
	}

	private static String displayName(Path p) {
		Path name = p.getFileName();
		return name == null ? p.toString() : name.toString();
	}

	/**
	 * After the scan, ensure no node is left in QUEUED or SCANNING. Mirrors the sequential scanner's safety net for paths we couldn't
	 * enter.
	 */
	private static void finishUnscanned(DirectoryNode node) {
		if (node.state() != DirectoryNode.ScanState.DONE)
			node.markDone();
		for (DirectoryNode child : node.children()) {
			finishUnscanned(child);
		}
	}

	/**
	 * Extracts the device ID from a POSIX fileKey whose toString() is "(dev=0x...,ino=...)". Returns -1 if the format is not recognised
	 * (Windows, unknown OS).
	 */
	private static long deviceOf(Object fileKey) {
		if (fileKey == null)
			return -1;
		String s = fileKey.toString();
		int i = s.indexOf("dev=");
		if (i < 0)
			return -1;
		int j = s.indexOf(',', i);
		if (j < 0)
			j = s.indexOf(')', i);
		if (j < 0)
			return -1;
		try {
			return Long.decode(s.substring(i + 4, j));
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
