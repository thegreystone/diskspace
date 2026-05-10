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

	public static List<Volume> enumerate() {
		List<Volume> volumes = new ArrayList<>();
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
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
					continue;
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
				volumes.add(new Volume(displayName, deviceName, scanRoot, total, usable, used, store.type(),
						StorageProfile.UNKNOWN));
			} catch (Exception ignore) {
				// Volume not accessible (offline drive, permission denied) — skip silently.
			}
		}
		return enrichWithStorageProfiles(volumes);
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
			StorageProfile profile = StorageProfileProbe.probe(target, store.type());
			return new Volume(displayName, deviceName, target, total, usable, used, store.type(), profile);
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
