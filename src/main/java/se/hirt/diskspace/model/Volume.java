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
package se.hirt.diskspace.model;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record Volume(String displayName, String deviceName, Path root, long totalBytes, long usableBytes,
                     long usedBytes, String fsType, StorageProfile storageProfile) {

	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(Volume.class.getName());

	public double usedFraction() {
		return totalBytes == 0 ? 0.0 : (double) usedBytes / (double) totalBytes;
	}

	public Volume withStorageProfile(StorageProfile profile) {
		return new Volume(displayName, deviceName, root, totalBytes, usableBytes, usedBytes, fsType, profile);
	}

	/**
	 * On macOS APFS, Java NIO's {@code totalSpace − usableSpace} is the *container* used (system snapshot + Data +
	 * Preboot + VM + snapshots). We want the per-volume Used, which only {@code df}'s {@code Used} column reports
	 * correctly. Falls back to the Java NIO calculation when {@code df} is unavailable or this isn't macOS.
	 */
	private static long computeUsedBytes(Path scanRoot, long totalBytes, long usableBytes) {
		long mac = MacVolumeInfo.spaceUsed(scanRoot);
		if (mac >= 0)
			return mac;
		return Math.max(0L, totalBytes - usableBytes);
	}

	/**
	 * The candidate volume roots, in the platform's natural order (drive letters on Windows, mount points elsewhere).
	 * Cheap and non-blocking: this reads the live filesystem's root list (a {@code GetLogicalDrives} bitmask on
	 * Windows) without touching any medium. The blocking, hang-prone work — {@link Files#getFileStore} and the
	 * free/total-space queries — is deferred to {@link #resolve}, so callers can fan {@code resolve} out across threads
	 * and time out a dead disk without wedging the rest.
	 */
	public static List<Path> rootDirectories() {
		List<Path> roots = new ArrayList<>();
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			roots.add(root);
		}
		return roots;
	}

	/**
	 * Resolves a single root into a {@link Volume} with an {@link StorageProfile#UNKNOWN} profile (call
	 * {@link #probeStorageProfile} separately to classify the medium), or {@code null} when the root is a pseudo
	 * filesystem or inaccessible (offline drive, permission denied, dead media).
	 * <p><b>May block for a long time on unresponsive media</b> — the free/total-space queries on a failing SD card or
	 * a stalled USB reader can hang for tens of seconds. Call this off the FX thread, one root per worker, so a single
	 * bad disk can't hold up the others or the UI.
	 */
	public static Volume resolve(Path root) {
		try {
			// On macOS, "/" is a sealed APFS system snapshot. User data lives on
			// "/System/Volumes/Data". Scanning from "/" crosses firmlinks into that
			// volume and double-counts everything. Use Data as the scan root instead.
			Path scanRoot = apfsDataVolumeFor(root);
			// Query the FileStore at scanRoot, not root — APFS volumes inside one
			// container share a free-space pool, but each volume has its own block
			// count. Querying at "/" returns container-wide used space (System + Data
			// + Preboot + VM + snapshots), which makes the scanner's "Unaccounted"
			// comparison apples-to-oranges since the scanner only walks Data.
			FileStore store = Files.getFileStore(scanRoot);
			if (isPseudoFs(store.type())) {
				return null;
			}
			String deviceName = store.name();
			if (deviceName == null || deviceName.isBlank()) {
				deviceName = root.toString();
			}
			String displayName = resolveDisplayName(root, deviceName);
			long total = store.getTotalSpace();
			long usable = store.getUsableSpace();
			long used = computeUsedBytes(scanRoot, total, usable);
			LOG.info(String.format("Volume: root=%s scanRoot=%s device=%s display=%s type=%s", root, scanRoot,
					deviceName, displayName, store.type()));
			return new Volume(displayName, deviceName, scanRoot, total, usable, used, store.type(),
					StorageProfile.UNKNOWN);
		} catch (Exception ignore) {
			// Volume not accessible (offline drive, permission denied) — skip silently.
			return null;
		}
	}

	/**
	 * Classifies the physical medium behind a single {@code volume}, reusing the same cache and native fast path (Win32
	 * ioctls / Disk Arbitration) that {@link #enrichWithStorageProfiles} uses in bulk. Returns
	 * {@link StorageProfile#UNKNOWN} when classification fails. Like {@link #resolve}, this can block — run it off the
	 * FX thread.
	 * <p><b>Per-disk by design.</b> The picker classifies one volume at a time, as each resolves, so this hands a
	 * singleton to {@link StorageProfileProbe#probeMany}. In the native image — the shipped binary — that's exactly the
	 * intended path: the probe is a per-drive Win32 ioctl costing a few ms, so there's nothing to batch. The only case
	 * that loses out is JVM dev mode ({@code mvn javafx:run}), where {@code probeMany}'s reason for existing is to fold
	 * a PowerShell launch per drive into one process; calling it per-disk gives that back up (N parallel PowerShell
	 * starts). That's a dev-only inefficiency — mitigated by the per-mount cache and the streaming UI — not a regression
	 * in what users run, so the simpler per-disk streaming is the deliberate trade.
	 */
	public static StorageProfile probeStorageProfile(Volume volume) {
		return StorageProfileProbe.probeMany(List.of(volume)).getOrDefault(volume.root(), StorageProfile.UNKNOWN);
	}

	/**
	 * Resolves storage profiles via {@link StorageProfileProbe#probeMany} (one PowerShell invocation on Windows,
	 * parallel single probes elsewhere) and rebuilds each Volume with the classified profile. Volumes missing from the
	 * result map default to {@link StorageProfile#UNKNOWN}.
	 */
	private static List<Volume> enrichWithStorageProfiles(List<Volume> volumes) {
		if (volumes.isEmpty())
			return volumes;
		Map<Path, StorageProfile> profiles = StorageProfileProbe.probeMany(volumes);
		List<Volume> result = new ArrayList<>(volumes.size());
		for (Volume v : volumes) {
			StorageProfile p = profiles.getOrDefault(v.root(), StorageProfile.UNKNOWN);
			result.add(v.withStorageProfile(p));
		}
		return result;
	}

	/**
	 * Looks in /Volumes/ for a Finder-visible label whose inode matches {@code scanRoot}. Falls back to
	 * {@code fallback} when not on macOS or nothing matches.
	 */
	public static Volume from(Path target) {
		try {
			var store = Files.getFileStore(target);
			String deviceName = store.name();
			if (deviceName == null || deviceName.isBlank())
				deviceName = target.toString();
			String displayName = resolveDisplayName(target, deviceName);
			long total = store.getTotalSpace();
			long usable = store.getUsableSpace();
			long used = computeUsedBytes(target, total, usable);
			// Build with UNKNOWN, then route through enrichWithStorageProfiles so the
			// native fast path used by enumerate() (Capabilities.STORAGE_PROBE on
			// native-image builds) classifies us. Without this we'd fall through to
			// the per-volume StorageProfileProbe.probe(...) which still shells out to
			// diskutil / PowerShell even in native builds. MacStorageProbe in turn
			// uses the BSD device name straight from FileStore, so this works for
			// both volume mount points and arbitrary subdirectories.
			Volume v = new Volume(displayName, deviceName, target, total, usable, used, store.type(),
					StorageProfile.UNKNOWN);
			return enrichWithStorageProfiles(java.util.List.of(v)).get(0);
		} catch (Exception e) {
			return new Volume(target.toString(), target.toString(), target, 0L, 0L, 0L, "", StorageProfile.UNKNOWN);
		}
	}

	public static String resolveDisplayName(Path scanRoot, String fallback) {
		Path volumes = Path.of("/Volumes");
		if (!Files.isDirectory(volumes))
			return fallback;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(volumes)) {
			for (Path entry : ds) {
				try {
					LOG.info(String.format("  /Volumes entry: %s  isSameFile(%s)=%b", entry, scanRoot,
							Files.isSameFile(entry, scanRoot)));
					if (Files.isSameFile(entry, scanRoot)) {
						Path name = entry.getFileName();
						if (name != null)
							return name.toString();
					}
				} catch (IOException e) {
					LOG.info("  /Volumes entry " + entry + " isSameFile failed: " + e);
				}
			}
		} catch (IOException e) {
			LOG.info("resolveDisplayName failed to list /Volumes: " + e);
		}
		return fallback;
	}

	private static Path apfsDataVolumeFor(Path root) {
		if (!"/".equals(root.toString())) {
			return root;
		}
		Path dataVol = Path.of("/System/Volumes/Data");
		return Files.isDirectory(dataVol) ? dataVol : root;
	}

	private static boolean isPseudoFs(String type) {
		if (type == null)
			return false;
		return switch (type.toLowerCase()) {
			case "proc", "sysfs", "tmpfs", "devtmpfs", "cgroup", "cgroup2", "devpts", "securityfs", "pstore", "autofs",
			     "overlay", "squashfs", "fuse.gvfsd-fuse", "fuse.portal", "tracefs", "debugfs", "configfs", "bpf",
			     "binfmt_misc", "mqueue", "hugetlbfs", "rpc_pipefs", "fusectl" -> true;
			default -> false;
		};
	}
}
