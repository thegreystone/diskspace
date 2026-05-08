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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.platform.win32.WinNT.LUID;
import com.sun.jna.platform.win32.WinNT.LUID_AND_ATTRIBUTES;
import com.sun.jna.platform.win32.WinNT.TOKEN_PRIVILEGES;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.Volume;

/**
 * Master File Table scanner for NTFS volumes on Windows. Pulls MFT records in bulk via
 * {@code FSCTL_ENUM_USN_DATA} (parent/child structure + names + attributes — no sizes),
 * builds the {@link DirectoryNode} tree on the fly with deferred orphan linking, and looks
 * up file sizes in parallel via {@code OpenFileById} + {@code GetFileInformationByHandleEx}.
 *
 * <p>Why USN over {@code FSCTL_QUERY_FILE_LAYOUT}: the latter returned
 * {@code ERROR_INVALID_FUNCTION} on the target NTFS volumes under all three published IOCTL
 * variants (228/NEITHER, 228/BUFFERED, 219/BUFFERED) despite the diagnostic
 * {@code FSCTL_GET_NTFS_VOLUME_DATA} working on the same handle. USN is older, more universally
 * supported, and unlocks incremental rescan via {@code FSCTL_READ_USN_JOURNAL} as a follow-up.
 *
 * <p>Requires admin / {@code SeBackupPrivilege} + {@code SeManageVolumePrivilege} to open the
 * raw volume handle and read the MFT. Capability check in {@link #canScan(Volume)} is cached
 * per mount path for the JVM lifetime.
 */
public final class MftScanner implements Scanner {

	private static final Logger LOG = Logger.getLogger(MftScanner.class.getName());

	// ── Win32 / FSCTL constants ────────────────────────────────────────────
	private static final int FILE_DEVICE_FILE_SYSTEM = 0x9;
	private static final int METHOD_BUFFERED = 0;
	private static final int METHOD_NEITHER = 3;
	private static final int FILE_ANY_ACCESS = 0;

	/** {@code FSCTL_ENUM_USN_DATA} = function 44, METHOD_NEITHER, FILE_ANY_ACCESS = 0x900B3. */
	private static final int FSCTL_ENUM_USN_DATA =
			ctlCode(FILE_DEVICE_FILE_SYSTEM, 44, METHOD_NEITHER, FILE_ANY_ACCESS);

	/** {@code FSCTL_GET_NTFS_VOLUME_DATA} = function 25, METHOD_BUFFERED, FILE_ANY_ACCESS.
	 *  Returns NTFS_VOLUME_DATA_BUFFER from which we extract MFT size + cluster usage —
	 *  used to drive an honest progress arc during phase 1 (USN enumeration). */
	private static final int FSCTL_GET_NTFS_VOLUME_DATA =
			ctlCode(FILE_DEVICE_FILE_SYSTEM, 25, METHOD_BUFFERED, FILE_ANY_ACCESS);

	// CreateFile parameters
	private static final int GENERIC_READ = 0x80000000;
	private static final int FILE_SHARE_READ = 0x00000001;
	private static final int FILE_SHARE_WRITE = 0x00000002;
	private static final int FILE_SHARE_DELETE = 0x00000004;
	private static final int OPEN_EXISTING = 3;
	/** Required to open a directory via CreateFile and to use the volume handle as a hint
	 *  for {@code OpenFileById}. Pairs with SeBackupPrivilege which we enable up-front. */
	private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;

	// Win32 file attribute bits we care about
	private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
	private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;

	// GetLastError values
	private static final int ERROR_HANDLE_EOF = 38;

	/** {@code FILE_ID_DESCRIPTOR.Type = FileIdType}. */
	private static final int FILE_ID_TYPE = 0;
	/** Minimum access right for {@code GetFileInformationByHandleEx(FileIdBothDirectoryInfo)}.
	 *  Same numeric value as {@code FILE_READ_DATA} but with directory semantics. */
	private static final int FILE_LIST_DIRECTORY = 0x00000001;

	/** Output buffer size for each {@code FSCTL_ENUM_USN_DATA} call. 1 MB returns several
	 *  thousand records per chunk on a typical NTFS volume. */
	private static final int OUTPUT_BUFFER_SIZE = 1024 * 1024;

	/** Number of parallel workers doing directory-batched size lookups. NTFS metadata
	 *  paths serialise to some degree at the kernel level — empirically 32 workers hurt
	 *  more than 8, so we keep this low. The win comes from issuing fewer syscalls per
	 *  file (bulk dir enum), not from more concurrent syscalls. */
	private static final int SIZE_WORKER_THREADS = 8;

	/** Per-worker scratch FILE_ID_DESCRIPTOR (24 bytes) for OpenFileById. Reused across
	 *  every directory open by this thread. */
	private static final ThreadLocal<Memory> SCRATCH_DESCRIPTOR = ThreadLocal.withInitial(() -> {
		Memory m = new Memory(24);
		m.clear();
		return m;
	});

	/** Per-worker scratch buffer for {@code GetFileInformationByHandleEx} bulk directory
	 *  enumeration. 64 KB holds several hundred FILE_ID_BOTH_DIR_INFO entries — typical
	 *  Windows directories return in a single call. */
	private static final ThreadLocal<Memory> SCRATCH_DIR_BUFFER = ThreadLocal.withInitial(() -> new Memory(64 * 1024));

	// FileInformationClass values for GetFileInformationByHandleEx
	/** {@code FileIdBothDirectoryInfo} — returns FILE_ID_BOTH_DIR_INFO entries with FileId + EndOfFile. */
	private static final int FILE_ID_BOTH_DIR_INFO = 10;
	/** {@code FileIdBothDirectoryRestartInfo} — same as above but resets the enumeration cursor. */
	private static final int FILE_ID_BOTH_DIR_RESTART_INFO = 11;
	/** {@code GetLastError} value when GetFileInformationByHandleEx exhausts a directory. */
	private static final int ERROR_NO_MORE_FILES = 18;

	private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 10 Hz

	/** Files at or above this size become their own sunburst sector. 1 GB decimal. */
	private static final long LARGE_FILE_THRESHOLD_BYTES = 1_000_000_000L;

	/** NTFS volume root directory is always at MFT record index 5 with sequence 5. */
	private static final long ROOT_FILE_ID = 0x0005_000000000005L;

	/** Per-mount capability cache; see {@link #canScan(Volume)}. */
	private static final ConcurrentMap<String, Boolean> CAN_SCAN_CACHE = new ConcurrentHashMap<>();

	private volatile boolean cancelled;
	private volatile Thread coordinator;
	private volatile HANDLE volumeHandle;
	private volatile ExecutorService sizeWorkers;

	// ── Hub progress (consumed by Scanner.hubState() from the JavaFX thread) ───────────
	/** Phase 1 (USN enumeration) is bytes-blind — we know structure but not file sizes —
	 *  so the hub borrows a fraction of the arc's range to show enumeration progress.
	 *  Phase 2 (size lookup) accumulates real bytes and falls through to the default
	 *  bytes/usedBytes arc, so the look matches {@link ParallelDirectoryScanner}.
	 *  IDLE is the default; flipped on scan() entry and back on completion/cancel. */
	private enum Phase { IDLE, ENUMERATING, SIZE_LOOKUP }
	private volatile Phase phase = Phase.IDLE;
	/** Total MFT records on the target volume, computed once via FSCTL_GET_NTFS_VOLUME_DATA
	 *  at scan start. {@code 0} = couldn't determine, in which case phase 1 falls back to
	 *  the indeterminate spinner. */
	private volatile long totalMftRecords;
	/** Cursor returned by the previous FSCTL_ENUM_USN_DATA chunk; numerator for the phase 1
	 *  arc fraction. Monotonically increasing. */
	private volatile long currentNextFrn;
	/** Number of FSCTL_ENUM_USN_DATA chunks processed so far this scan; the {@code N} in the
	 *  phase 1 hub subtitle "Chunk N". We deliberately don't show a total — the obvious
	 *  estimate ({@code MftValidDataLength / OUTPUT_BUFFER_SIZE}) is wildly wrong because
	 *  each chunk returns *parsed* USN records (~80–120 B each), not raw MFT bytes. */
	private volatile long currentChunk;
	/** {@code (totalClusters - freeClusters) * bytesPerCluster} captured at scan start.
	 *  Same denominator the parallel scanner's hub arc uses, so phase 2 visually matches. */
	private volatile long usedBytesForArc;
	/** Live state during a scan; null when {@link #phase} is IDLE. {@link Scanner#hubState()}
	 *  reads {@code state.runningBytes} from here for phase 2 progress. */
	private volatile State currentState;
	/** Phase 1 occupies the first {@value} of the progress arc; phase 2 fills the rest.
	 *  Empirically phase 1 is ~35% of wall time on a populated SSD, but biasing slightly
	 *  low keeps the arc moving forward instead of stalling near the boundary. */
	private static final double PHASE_1_ARC_BUDGET = 0.30;

	// ── Capability checks ──────────────────────────────────────────────────

	/** Coarse availability check. Windows-only; native-image builds excluded because
	 *  GraalVM 21 Substrate VM still rejects JNA's {@code jnidispatch.dll} even with the
	 *  resource config bundling it — touching {@code com.sun.jna.Native} in a native build
	 *  throws {@code NoClassDefFoundError: Could not initialize class}. Until we either
	 *  ship a custom JNI helper DLL (no JNA) or move to Project Panama (Java 22+), MFT
	 *  is JVM-mode only. */
	public static boolean isAvailable() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return false;
		return true;
	}

	/** True iff the volume can be scanned via MFT: Windows + NTFS + drive-letter root +
	 *  raw volume open succeeds. Result cached per mount path. */
	public static boolean canScan(Volume v) {
		if (!isAvailable()) return false;
		if (v == null || v.fsType() == null || !"NTFS".equalsIgnoreCase(v.fsType())) return false;
		String key = v.root().toAbsolutePath().toString();
		String drive = driveLetterFromRoot(key);
		if (drive == null) return false;
		Boolean cached = CAN_SCAN_CACHE.get(key);
		if (cached != null) return cached;
		boolean ok = probeVolumeOpen(drive);
		CAN_SCAN_CACHE.put(key, ok);
		LOG.fine(() -> "canScan: " + key + " (drive=" + drive + ") -> " + ok);
		return ok;
	}

	/** Probes whether {@code FSCTL_ENUM_USN_DATA} actually works on this volume. Just opening
	 *  {@code C:\} succeeds even without admin (you have normal read access to the root), so
	 *  we have to issue the real IOCTL to find out — opening alone gives a false positive and
	 *  the scan would later fail with {@code ERROR_ACCESS_DENIED} mid-flight. */
	private static boolean probeVolumeOpen(String drive) {
		ensurePrivilegesEnabled();
		HANDLE h = openVolume(drive);
		if (h == null || h.equals(WinBase.INVALID_HANDLE_VALUE)) return false;
		try {
			Memory inBuf = new Memory(24);
			inBuf.clear();
			inBuf.setLong(0, 0L);              // StartFRN
			inBuf.setLong(8, 0L);              // LowUsn
			inBuf.setLong(16, Long.MAX_VALUE); // HighUsn
			Memory outBuf = new Memory(1024);
			IntByReference bytesReturned = new IntByReference();
			boolean ok = Kernel32.INSTANCE.DeviceIoControl(
					h, FSCTL_ENUM_USN_DATA,
					inBuf, (int) inBuf.size(),
					outBuf, (int) outBuf.size(),
					bytesReturned, null);
			if (ok) return true;
			int err = Kernel32.INSTANCE.GetLastError();
			// ERROR_HANDLE_EOF (38) means MFT exhausted — IOCTL was accepted, just no records
			// (would only happen on a brand-new empty volume). Treat as "yes, MFT works here."
			if (err == ERROR_HANDLE_EOF) return true;
			LOG.fine(() -> "probeVolumeOpen: " + drive + ": FSCTL_ENUM_USN_DATA err=" + err
					+ (err == 5 ? " (access denied — needs admin / SeBackupPrivilege)" : ""));
			return false;
		} finally {
			Kernel32.INSTANCE.CloseHandle(h);
		}
	}

	private static String driveLetterFromRoot(String rootPath) {
		if (rootPath == null || rootPath.length() < 2) return null;
		if (rootPath.startsWith("\\\\")) return null;
		char c = rootPath.charAt(0);
		if (rootPath.charAt(1) != ':') return null;
		if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return String.valueOf(Character.toUpperCase(c));
		return null;
	}

	// ── Privilege enablement ──────────────────────────────────────────────

	private static volatile boolean privilegesEnabled = false;
	private static final Object PRIVILEGES_LOCK = new Object();

	/** Idempotent: enables {@code SeBackupPrivilege} + {@code SeManageVolumePrivilege} in
	 *  the process token. Both are normally held by an admin token but with
	 *  {@code SE_PRIVILEGE_ENABLED} cleared until explicitly turned on. */
	private static void ensurePrivilegesEnabled() {
		if (privilegesEnabled) return;
		synchronized (PRIVILEGES_LOCK) {
			if (privilegesEnabled) return;
			boolean a = enableProcessPrivilege("SeBackupPrivilege");
			boolean b = enableProcessPrivilege("SeManageVolumePrivilege");
			LOG.fine(() -> "privileges: SeBackupPrivilege=" + a + " SeManageVolumePrivilege=" + b);
			privilegesEnabled = true;
		}
	}

	private static boolean enableProcessPrivilege(String privilegeName) {
		HANDLEByReference tokenRef = new HANDLEByReference();
		boolean opened = Advapi32.INSTANCE.OpenProcessToken(
				Kernel32.INSTANCE.GetCurrentProcess(),
				WinNT.TOKEN_ADJUST_PRIVILEGES | WinNT.TOKEN_QUERY,
				tokenRef);
		if (!opened) return false;
		HANDLE token = tokenRef.getValue();
		try {
			LUID luid = new LUID();
			if (!Advapi32.INSTANCE.LookupPrivilegeValue(null, privilegeName, luid)) return false;
			TOKEN_PRIVILEGES tp = new TOKEN_PRIVILEGES(1);
			tp.Privileges[0] = new LUID_AND_ATTRIBUTES(luid, new DWORD(WinNT.SE_PRIVILEGE_ENABLED));
			if (!Advapi32.INSTANCE.AdjustTokenPrivileges(token, false, tp, 0, null, null)) return false;
			return Kernel32.INSTANCE.GetLastError() == 0;
		} finally {
			Kernel32.INSTANCE.CloseHandle(token);
		}
	}

	// ── Volume open ───────────────────────────────────────────────────────

	/** Opens the volume's root directory (e.g. {@code "C:\"}) with {@code FILE_FLAG_BACKUP_SEMANTICS}
	 *  so the handle routes through NTFS and is suitable both for {@code FSCTL_ENUM_USN_DATA}
	 *  and as a {@code hVolumeHint} for {@code OpenFileById}. */
	private static HANDLE openVolume(String driveLetter) {
		String path = driveLetter + ":\\";
		HANDLE h = Kernel32.INSTANCE.CreateFile(
				path,
				GENERIC_READ,
				FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
				null,
				OPEN_EXISTING,
				FILE_FLAG_BACKUP_SEMANTICS,
				null);
		if (h == null || h.equals(WinBase.INVALID_HANDLE_VALUE)) {
			int err = Kernel32.INSTANCE.GetLastError();
			LOG.fine(() -> "openVolume: CreateFile failed for " + path + " err=" + err);
			return null;
		}
		return h;
	}

	/**
	 * Issues {@code FSCTL_GET_NTFS_VOLUME_DATA} to populate {@link #totalMftRecords} and
	 * {@link #usedBytesForArc}, both used by {@link #hubState()} to render an honest
	 * progress arc. Failures are logged at FINE and leave the fields at 0 — the hub falls
	 * back to its indeterminate spinner for phase 1 in that case.
	 *
	 * <pre>
	 *   NTFS_VOLUME_DATA_BUFFER (64 bytes; newer kernels return 96 with extended fields):
	 *     +0  LONGLONG VolumeSerialNumber
	 *     +8  LONGLONG NumberSectors
	 *     +16 LONGLONG TotalClusters
	 *     +24 LONGLONG FreeClusters
	 *     +32 LONGLONG TotalReserved
	 *     +40 ULONG    BytesPerSector
	 *     +44 ULONG    BytesPerCluster
	 *     +48 ULONG    BytesPerFileRecordSegment   ← phase 1 denominator part 1
	 *     +52 ULONG    ClustersPerFileRecordSegment
	 *     +56 LONGLONG MftValidDataLength          ← phase 1 denominator part 2
	 * </pre>
	 */
	private void queryNtfsVolumeData(HANDLE h) {
		Memory out = new Memory(128); // oversize so newer kernels don't ERROR_MORE_DATA
		IntByReference bytesReturned = new IntByReference();
		boolean ok = Kernel32.INSTANCE.DeviceIoControl(
				h, FSCTL_GET_NTFS_VOLUME_DATA,
				null, 0, out, (int) out.size(),
				bytesReturned, null);
		if (!ok) {
			int err = Kernel32.INSTANCE.GetLastError();
			LOG.fine(() -> "queryNtfsVolumeData: FSCTL_GET_NTFS_VOLUME_DATA failed err=" + err);
			return;
		}
		long totalClusters = out.getLong(16);
		long freeClusters = out.getLong(24);
		int bytesPerCluster = out.getInt(44);
		int bytesPerFrs = out.getInt(48);
		long mftValidDataLength = out.getLong(56);
		if (bytesPerFrs > 0) totalMftRecords = mftValidDataLength / bytesPerFrs;
		if (bytesPerCluster > 0) usedBytesForArc = (totalClusters - freeClusters) * (long) bytesPerCluster;
		LOG.fine(() -> "queryNtfsVolumeData: totalMftRecords=" + totalMftRecords
				+ " usedBytesForArc=" + usedBytesForArc);
	}

	// ── Scanner interface ─────────────────────────────────────────────────

	@Override
	public void scan(Path rootPath, ScanListener listener) {
		cancelled = false;
		DirectoryNode root = new DirectoryNode(null, displayName(rootPath), rootPath);
		listener.onStart(root);
		LOG.info(() -> "Scan start: " + rootPath + " (strategy=mft)");
		long startNanos = System.nanoTime();

		coordinator = new Thread(() -> {
			try {
				enumerateAndBuild(rootPath, root, listener);
				if (cancelled) return;
				root.sortBySizeRecursive();
				ScanTiming.log(LOG, rootPath, root, startNanos, "mft");
				listener.onComplete(root);
			} catch (Throwable t) {
				LOG.log(Level.SEVERE, "MftScanner failed for " + rootPath, t);
				if (!cancelled) listener.onError(t);
			} finally {
				closeOpenHandle();
				ExecutorService w = sizeWorkers;
				if (w != null) w.shutdownNow();
			}
		}, "DiskSpace-mft-" + rootPath);
		coordinator.setDaemon(true);
		coordinator.start();
	}

	@Override
	public Scanner.HubState hubState() {
		Phase p = phase;
		if (p == Phase.ENUMERATING) {
			// Phase 1 has no bytes/files yet, so the default hub text would show "0 B / 0 files".
			// Override the title with something honest, and feed the arc a real fraction
			// derived from the NTFS-supplied total MFT record count.
			long total = totalMftRecords;
			double arc = total > 0
					? PHASE_1_ARC_BUDGET * Math.min(1.0, currentNextFrn / (double) total)
					: -1.0;
			return new Scanner.HubState("Reading MFT…", "Chunk " + currentChunk, arc);
		}
		if (p == Phase.SIZE_LOOKUP) {
			// Phase 2 accumulates real bytes — let the default title/subtitle (humanSize +
			// "X files") render so the hub matches ParallelDirectoryScanner. Only the arc
			// is overridden, mapping the bytes-vs-usedBytes ratio into the remaining 70%
			// so it picks up where phase 1 left off instead of resetting to 0.
			State s = currentState;
			long used = usedBytesForArc;
			if (s == null || used <= 0) return Scanner.HubState.DEFAULT;
			double bytesFrac = Math.min(1.0, s.runningBytes.sum() / (double) used);
			double arc = PHASE_1_ARC_BUDGET + (1.0 - PHASE_1_ARC_BUDGET) * bytesFrac;
			return new Scanner.HubState(null, null, arc);
		}
		return Scanner.HubState.DEFAULT;
	}

	@Override
	public void cancel() {
		cancelled = true;
		phase = Phase.IDLE;
		currentState = null;
		closeOpenHandle();
		ExecutorService w = sizeWorkers;
		if (w != null) w.shutdownNow();
		Thread c = coordinator;
		if (c != null) c.interrupt();
	}

	private void closeOpenHandle() {
		HANDLE h = volumeHandle;
		if (h != null && !h.equals(WinBase.INVALID_HANDLE_VALUE)) {
			volumeHandle = null;
			Kernel32.INSTANCE.CloseHandle(h);
		}
	}

	// ── Enumeration + streaming tree build ───────────────────────────────

	/** Per-scan state. Created fresh in {@link #enumerateAndBuild}. */
	private static final class State {
		final DirectoryNode root;
		final HANDLE volumeHandle;
		final ExecutorService sizeWorkers;
		/** All directory nodes seen so far, keyed by NTFS file ID. Concurrent because size
		 *  workers may need to look up the parent path during their lookups. */
		final ConcurrentMap<Long, DirectoryNode> nodesByFileId = new ConcurrentHashMap<>();
		/** File records (NOT directories) grouped by parent directory file ID. Filled during
		 *  enumeration; consumed once per directory by the size-lookup phase, which opens the
		 *  directory and bulk-enumerates child sizes via FileIdBothDirectoryInfo. Only the
		 *  enumeration thread mutates this. */
		final Map<Long, List<MftRecord>> filesByParentId = new HashMap<>();
		/** Records (directories or files) waiting for their parent's DirectoryNode to be
		 *  created. Only the enumeration thread mutates this; no concurrent access. */
		final Map<Long, List<MftRecord>> orphansByParentId = new HashMap<>();
		/** Running counters for UI progress. {@link LongAdder} because size workers update them. */
		final LongAdder runningFiles = new LongAdder();
		final LongAdder runningBytes = new LongAdder();
		final AtomicReference<String> latestName = new AtomicReference<>();

		State(DirectoryNode root, HANDLE volumeHandle, ExecutorService sizeWorkers) {
			this.root = root;
			this.volumeHandle = volumeHandle;
			this.sizeWorkers = sizeWorkers;
		}
	}

	private void enumerateAndBuild(Path rootPath, DirectoryNode root, ScanListener listener) {
		String drive = driveLetterFromRoot(rootPath.toAbsolutePath().toString());
		if (drive == null) {
			throw new IllegalStateException("MftScanner only supports drive-letter roots; got " + rootPath);
		}
		LOG.fine(() -> "enumerateMft: opening " + drive + ":\\ (NTFS root directory)");
		HANDLE h = openVolume(drive);
		if (h == null) {
			throw new RuntimeException("Could not open volume " + drive
					+ ": for raw read. Run DiskSpace as administrator (or with SeBackupPrivilege) to use the MFT scanner.");
		}
		volumeHandle = h;
		ExecutorService workers = Executors.newFixedThreadPool(SIZE_WORKER_THREADS, r -> {
			Thread t = new Thread(r, "DiskSpace-mft-size");
			t.setDaemon(true);
			return t;
		});
		sizeWorkers = workers;
		State state = new State(root, h, workers);
		currentState = state;

		// Pre-register the scan-root DirectoryNode under the NTFS volume root file ID so
		// children of the volume root link to it during streaming tree build.
		root.setScanning();
		state.nodesByFileId.put(ROOT_FILE_ID, root);

		// Query NTFS layout up front so the hub arc has a real denominator during phase 1
		// (USN enumeration is bytes-blind — we won't know any file sizes until phase 2).
		// Failures are non-fatal; we just fall back to the indeterminate spinner for phase 1.
		queryNtfsVolumeData(h);
		currentNextFrn = 0L;
		currentChunk = 0L;
		phase = Phase.ENUMERATING;

		LOG.fine(() -> "enumerateMft: volume handle opened, ioctl=0x" + Integer.toHexString(FSCTL_ENUM_USN_DATA));

		// MFT_ENUM_DATA_V0: 24 bytes. Initial: StartFRN=0, LowUsn=0, HighUsn=Long.MAX_VALUE.
		Memory inBuf = new Memory(24);
		inBuf.clear();
		inBuf.setLong(0, 0L);                  // StartFileReferenceNumber
		inBuf.setLong(8, 0L);                  // LowUsn
		inBuf.setLong(16, Long.MAX_VALUE);     // HighUsn (covers all current records)

		Memory outBuf = new Memory(OUTPUT_BUFFER_SIZE);
		IntByReference bytesReturned = new IntByReference();

		LOG.fine(() -> "enumerateMft: buffers allocated (in=24 out=" + OUTPUT_BUFFER_SIZE + "), entering DeviceIoControl loop");

		long lastProgressNanos = 0L;
		long chunkCount = 0;
		long enumStart = System.nanoTime();

		while (!cancelled) {
			long ioctlStart = System.nanoTime();
			boolean ok = Kernel32.INSTANCE.DeviceIoControl(
					h, FSCTL_ENUM_USN_DATA,
					inBuf, (int) inBuf.size(),
					outBuf, (int) outBuf.size(),
					bytesReturned, null);
			long ioctlMs = (System.nanoTime() - ioctlStart) / 1_000_000L;
			if (!ok) {
				int err = Kernel32.INSTANCE.GetLastError();
				if (err == ERROR_HANDLE_EOF) {
					LOG.fine(() -> "enumerateMft: end of journal");
					break;
				}
				throw new RuntimeException("DeviceIoControl(FSCTL_ENUM_USN_DATA) failed, err=" + err);
			}
			int returned = bytesReturned.getValue();
			if (returned <= 8) {
				// Only the next-FRN header was returned; no more records.
				LOG.fine("enumerateMft: empty chunk, end of MFT");
				break;
			}
			chunkCount++;
			currentChunk = chunkCount;
			long parseStart = System.nanoTime();
			int dirCount = parseUsnChunk(outBuf, returned, state);
			long parseMs = (System.nanoTime() - parseStart) / 1_000_000L;

			// Output begins with 8-byte next-StartFRN. Feed it back into the input. Also
			// publish to currentNextFrn so hubState() can drive the phase 1 progress arc.
			long nextStartFrn = outBuf.getLong(0);
			inBuf.setLong(0, nextStartFrn);
			currentNextFrn = nextStartFrn;

			final long chunkNo = chunkCount;
			final long imap = state.nodesByFileId.size();
			LOG.fine(() -> String.format(
					"enumerateMft: chunk=%d bytes=%d dirs=%d nodes=%d ioctl=%dms parse=%dms nextFRN=0x%x",
					chunkNo, returned, dirCount, imap, ioctlMs, parseMs, nextStartFrn));

			long now = System.nanoTime();
			if (now - lastProgressNanos > PROGRESS_INTERVAL_NANOS) {
				lastProgressNanos = now;
				listener.onProgress(state.runningFiles.sum(), state.runningBytes.sum(), state.latestName.get());
			}
		}
		long enumMs = (System.nanoTime() - enumStart) / 1_000_000L;
		LOG.fine("enumerateMft: enumeration complete in " + enumMs + "ms across " + chunkCount + " chunks; "
				+ state.nodesByFileId.size() + " directory nodes; "
				+ state.filesByParentId.size() + " directories with file children; "
				+ state.orphansByParentId.size() + " unresolved parent buckets");

		// Phase transition: enumeration complete, switch to size-lookup phase. The hub now
		// falls back to the default bytes-driven arc + text — same look as ParallelDirectoryScanner.
		phase = Phase.SIZE_LOOKUP;

		// Dispatch one bulk-size-lookup task per directory.
		long dispatchStart = System.nanoTime();
		dispatchDirectorySizeLookups(state);
		long dispatchMs = (System.nanoTime() - dispatchStart) / 1_000_000L;
		LOG.fine("enumerateMft: dispatched " + state.filesByParentId.size() + " directory size-lookup tasks in " + dispatchMs + "ms");

		// Wait for size workers to finish. They use the volume handle as OpenFileById hint,
		// so we keep it open until they're done. Poll in short slices so we can emit
		// onProgress periodically — without this the hub's "X B / Y files" text would freeze
		// at 0 throughout phase 2 even though state.runningBytes is climbing.
		LOG.fine("enumerateMft: waiting for size workers to drain");
		long drainStart = System.nanoTime();
		long drainTimeoutNanos = TimeUnit.MINUTES.toNanos(10);
		long lastEmitNanos = 0L;
		workers.shutdown();
		try {
			while (!workers.awaitTermination(100, TimeUnit.MILLISECONDS)) {
				if (cancelled) {
					workers.shutdownNow();
					break;
				}
				if (System.nanoTime() - drainStart > drainTimeoutNanos) {
					LOG.warning("enumerateMft: size workers did not terminate within 10 minutes");
					workers.shutdownNow();
					break;
				}
				long now = System.nanoTime();
				if (now - lastEmitNanos > PROGRESS_INTERVAL_NANOS) {
					lastEmitNanos = now;
					listener.onProgress(state.runningFiles.sum(), state.runningBytes.sum(), state.latestName.get());
				}
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
		LOG.fine("enumerateMft: size workers done in " + drainMs + "ms; final files=" + state.runningFiles.sum()
				+ " bytes=" + state.runningBytes.sum());

		// Final progress emission so UI sees the last numbers.
		listener.onProgress(state.runningFiles.sum(), state.runningBytes.sum(), state.latestName.get());

		// Scan complete; release hub-state references so DiskView falls back to its
		// default rendering for any post-scan paint.
		phase = Phase.IDLE;
		currentState = null;

		closeOpenHandle();

		// Sanity: any node still QUEUED/SCANNING gets marked DONE.
		finishUnscanned(root);
	}

	/** Parses one FSCTL_ENUM_USN_DATA chunk. Returns the number of directory records seen
	 *  (handy for FINE diagnostics). */
	private int parseUsnChunk(Memory out, int returned, State state) {
		int dirCount = 0;
		// First 8 bytes are the next-FRN; records start at offset 8.
		int offset = 8;
		while (offset < returned) {
			int recordLength = out.getInt(offset);
			if (recordLength <= 0 || offset + recordLength > returned) break;
			MftRecord rec = parseUsnRecord(out, offset);
			if (rec != null) {
				if (rec.isDirectory) dirCount++;
				handleRecord(rec, state);
			}
			offset += recordLength;
		}
		return dirCount;
	}

	/**
	 * Parses one USN_RECORD_V2 entry at {@code offset} within the output buffer.
	 * <pre>
	 *   USN_RECORD_V2 (60-byte header + UTF-16LE name):
	 *     +0  DWORD RecordLength
	 *     +4  WORD  MajorVersion (2)
	 *     +6  WORD  MinorVersion (0)
	 *     +8  DWORDLONG FileReferenceNumber
	 *     +16 DWORDLONG ParentFileReferenceNumber
	 *     +24 USN Usn (LONGLONG)
	 *     +32 LARGE_INTEGER TimeStamp
	 *     +40 DWORD Reason
	 *     +44 DWORD SourceInfo
	 *     +48 DWORD SecurityId
	 *     +52 DWORD FileAttributes
	 *     +56 WORD FileNameLength (bytes)
	 *     +58 WORD FileNameOffset (relative to record start)
	 *     +60 WCHAR FileName[FileNameLength/2]
	 * </pre>
	 */
	private MftRecord parseUsnRecord(Memory out, int offset) {
		short majorVersion = out.getShort(offset + 4);
		if (majorVersion != 2) {
			// V3+ widens fileId to 128 bits — different layout, skip for now.
			return null;
		}
		long fileId = out.getLong(offset + 8);
		long parentId = out.getLong(offset + 16);
		int attrs = out.getInt(offset + 52);
		int nameLenBytes = out.getShort(offset + 56) & 0xFFFF;
		int nameOffset = out.getShort(offset + 58) & 0xFFFF;

		String name = null;
		if (nameLenBytes > 0 && nameOffset >= 60) {
			byte[] nameBytes = out.getByteArray(offset + nameOffset, nameLenBytes);
			name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_16LE);
		}

		boolean isDirectory = (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
		boolean isReparse = (attrs & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
		return new MftRecord(fileId, parentId, name, isDirectory, isReparse);
	}

	private void handleRecord(MftRecord rec, State state) {
		if (rec.name == null || rec.name.isEmpty()) return;
		// Skip reparse points (junctions, symlinks) to avoid double-counting under multiple paths.
		if (rec.isReparse) return;
		if (rec.fileId == ROOT_FILE_ID) return; // root pre-registered

		DirectoryNode parent = state.nodesByFileId.get(rec.parentId);
		if (parent == null) {
			// Park this record until its parent appears.
			state.orphansByParentId.computeIfAbsent(rec.parentId, k -> new ArrayList<>()).add(rec);
			return;
		}
		if (rec.isDirectory) {
			Path childPath = parent.path() != null ? parent.path().resolve(rec.name) : null;
			DirectoryNode node = parent.addChild(rec.name, childPath);
			state.nodesByFileId.put(rec.fileId, node);
			drainOrphans(rec.fileId, state);
			node.markDone(); // tree-structurally complete; size aggregation continues via addFile walks
		} else {
			// Group files by parent — we'll dispatch one bulk-size-lookup task per directory
			// once enumeration is done, which is dramatically cheaper than 3 syscalls per file.
			state.filesByParentId.computeIfAbsent(rec.parentId, k -> new ArrayList<>()).add(rec);
		}
	}

	private void drainOrphans(long parentId, State state) {
		List<MftRecord> orphans = state.orphansByParentId.remove(parentId);
		if (orphans == null) return;
		for (MftRecord o : orphans) handleRecord(o, state);
	}

	/**
	 * Dispatches one size-lookup task per directory. Each task opens the directory by file ID
	 * and bulk-enumerates child entries via {@code GetFileInformationByHandleEx} with
	 * {@code FileIdBothDirectoryInfo}, which returns FileId + EndOfFile for every child in
	 * one syscall — a dramatic win over the previous one-OpenFileById-per-file pattern (3
	 * syscalls per file × 3.7 M files vs ~3 syscalls per directory × 564 k directories).
	 */
	private void dispatchDirectorySizeLookups(State state) {
		for (Map.Entry<Long, List<MftRecord>> entry : state.filesByParentId.entrySet()) {
			long parentId = entry.getKey();
			List<MftRecord> files = entry.getValue();
			DirectoryNode parentNode = state.nodesByFileId.get(parentId);
			if (parentNode == null) continue; // parent never resolved (rare)
			state.sizeWorkers.execute(() -> {
				if (cancelled) return;
				lookupDirectorySizes(state.volumeHandle, parentId, parentNode, files, state);
			});
		}
	}

	/**
	 * Opens directory {@code parentId}, enumerates its entries via {@code FileIdBothDirectoryInfo},
	 * and populates sizes on the parent node for every child file we recorded during USN enum.
	 * Uses the per-thread scratch buffers to avoid allocations per directory.
	 */
	private static void lookupDirectorySizes(HANDLE volumeHint, long dirId, DirectoryNode parent,
			List<MftRecord> files, State state) {
		// Build a fast lookup of file IDs we care about. NtQueryDirectoryFile returns ALL
		// entries (including subdirectories which we already processed during USN enum),
		// so we filter by membership in this map.
		Map<Long, MftRecord> wantedById = new HashMap<>(files.size() * 2);
		for (MftRecord r : files) wantedById.put(r.fileId, r);

		Memory desc = SCRATCH_DESCRIPTOR.get();
		desc.setInt(0, 24);
		desc.setInt(4, FILE_ID_TYPE);
		desc.setLong(8, dirId);
		// FILE_LIST_DIRECTORY is required for GetFileInformationByHandleEx to enumerate
		// the directory's children — opening with 0 access succeeds but the subsequent
		// directory-info query silently returns false.
		HANDLE dirHandle = Kernel32Ex.INSTANCE.OpenFileById(
				volumeHint, desc,
				FILE_LIST_DIRECTORY,
				FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
				null,
				FILE_FLAG_BACKUP_SEMANTICS);
		if (dirHandle == null || dirHandle.equals(WinBase.INVALID_HANDLE_VALUE)) return;
		try {
			Memory buf = SCRATCH_DIR_BUFFER.get();
			boolean restart = true;
			while (true) {
				boolean ok = Kernel32Ex.INSTANCE.GetFileInformationByHandleEx(
						dirHandle,
						restart ? FILE_ID_BOTH_DIR_RESTART_INFO : FILE_ID_BOTH_DIR_INFO,
						buf, (int) buf.size());
				restart = false;
				if (!ok) break; // ERROR_NO_MORE_FILES is the normal exit
				consumeDirEntries(buf, wantedById, parent, state);
			}
		} finally {
			Kernel32.INSTANCE.CloseHandle(dirHandle);
		}
	}

	/**
	 * Walks a chained sequence of {@code FILE_ID_BOTH_DIR_INFO} records in {@code buf}, and
	 * for any entry whose FileId is in {@code wantedById} populates the parent counters with
	 * the entry's EndOfFile size.
	 *
	 * <pre>
	 *   FILE_ID_BOTH_DIR_INFO layout (chained via NextEntryOffset):
	 *     +0   DWORD NextEntryOffset
	 *     +4   DWORD FileIndex
	 *     +8   LARGE_INTEGER CreationTime
	 *     +16  LARGE_INTEGER LastAccessTime
	 *     +24  LARGE_INTEGER LastWriteTime
	 *     +32  LARGE_INTEGER ChangeTime
	 *     +40  LARGE_INTEGER EndOfFile          ← what we want
	 *     +48  LARGE_INTEGER AllocationSize
	 *     +56  DWORD FileAttributes
	 *     +60  DWORD FileNameLength
	 *     +64  DWORD EaSize
	 *     +68  CCHAR ShortNameLength (1 byte) + 1-byte pad
	 *     +70  WCHAR ShortName[12]              (24 bytes, ends at +93)
	 *     +94  (2 bytes pad to 8-byte align FileId)
	 *     +96  LARGE_INTEGER FileId             ← match against USN-derived map
	 *     +104 WCHAR FileName[FileNameLength/2]
	 * </pre>
	 */
	private static void consumeDirEntries(Memory buf, Map<Long, MftRecord> wantedById,
			DirectoryNode parent, State state) {
		int offset = 0;
		while (true) {
			int nextOffset = buf.getInt(offset);
			int attrs = buf.getInt(offset + 56);
			// Skip subdirectories and reparse points — we only want files here.
			if ((attrs & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) == 0) {
				long fileId = buf.getLong(offset + 96);
				MftRecord rec = wantedById.get(fileId);
				if (rec != null) {
					long size = buf.getLong(offset + 40);
					parent.addFile(size);
					if (size >= LARGE_FILE_THRESHOLD_BYTES) {
						parent.addLargeFile(rec.name, size);
					} else {
						parent.addSmallerFileBytes(size);
					}
					state.runningFiles.increment();
					state.runningBytes.add(size);
					state.latestName.set(rec.name);
				}
			}
			if (nextOffset == 0) break;
			offset += nextOffset;
		}
	}

	/**
	 * Extra Win32 functions JNA's stock Kernel32 binding doesn't expose. Loaded once at
	 * class load via {@link Native#load}; Unicode (W) variants where applicable.
	 */
	private interface Kernel32Ex extends StdCallLibrary {
		Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class, W32APIOptions.UNICODE_OPTIONS);

		HANDLE OpenFileById(HANDLE hVolumeHint, Pointer lpFileId,
				int dwDesiredAccess, int dwShareMode,
				Pointer lpSecurityAttributes, int dwFlagsAndAttributes);

		boolean GetFileInformationByHandleEx(HANDLE hFile, int FileInformationClass,
				Pointer lpFileInformation, int dwBufferSize);
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private static int ctlCode(int deviceType, int function, int method, int access) {
		return (deviceType << 16) | (access << 14) | (function << 2) | method;
	}

	private static String displayName(Path p) {
		Path name = p.getFileName();
		return name == null ? p.toString() : name.toString();
	}

	private static void finishUnscanned(DirectoryNode node) {
		if (node.state() != DirectoryNode.ScanState.DONE) node.markDone();
		for (DirectoryNode child : node.children()) {
			finishUnscanned(child);
		}
	}

	/** Flat MFT record: one row in the streamed output of FSCTL_ENUM_USN_DATA. */
	private static final class MftRecord {
		final long fileId;
		final long parentId;
		final String name;
		final boolean isDirectory;
		final boolean isReparse;

		MftRecord(long fileId, long parentId, String name, boolean isDirectory, boolean isReparse) {
			this.fileId = fileId;
			this.parentId = parentId;
			this.name = name;
			this.isDirectory = isDirectory;
			this.isReparse = isReparse;
		}
	}
}
