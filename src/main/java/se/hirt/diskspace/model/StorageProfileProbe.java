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

import se.hirt.diskspace.platform.Capabilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Probes the underlying physical-storage type of a mounted volume by shelling out to a platform-native tool. Each probe
 * completes in under a second on healthy hardware; the caller is expected to run probes concurrently when classifying
 * multiple volumes.
 */
public final class StorageProfileProbe {

	private static final Logger LOG = Logger.getLogger(StorageProfileProbe.class.getName());
	/**
	 * Single-probe timeout. Sufficient for one diskutil/findmnt/lsblk call or a single PowerShell launch with light
	 * work.
	 */
	private static final long TIMEOUT_MS = 3000;
	/**
	 * Batch-probe timeout. Generously sized: one PowerShell process classifies all drives sequentially, so cold-start
	 * overhead is paid once but total work scales with N.
	 */
	private static final long BATCH_TIMEOUT_MS = 15000;

	/**
	 * Process-lifetime cache. Storage type for a given mount almost never changes during a session — physical media
	 * doesn't morph from spinning to solid mid-run — so probing on every picker open is pure waste. Keyed by the
	 * absolute mount path string. Failures (UNKNOWN) are cached too: if the probe couldn't classify once, it almost
	 * certainly can't on repeat attempts, and re-trying just slows the picker down.
	 */
	private static final ConcurrentMap<String, StorageProfile> CACHE = new ConcurrentHashMap<>();

	/**
	 * Batch PowerShell script. {@code %s} is substituted with a comma-separated list of quoted drive letters (e.g.
	 * {@code 'C','D','E'}). Emits one {@code result: X=…} line per drive plus zero or more {@code diag: X …} lines for
	 * troubleshooting. Avoids the N×PowerShell-cold-start cost of per-drive probes — one .NET startup classifies every
	 * drive in sequence.
	 */
	private static final String WINDOWS_BATCH_SCRIPT = "$ErrorActionPreference='Continue';" + "$drives = @(%s);" + "foreach ($drive in $drives) {" + "  try {" + "    $psd = Get-PSDrive -Name $drive -PSProvider FileSystem -ErrorAction Stop;" + "    if ($psd.DisplayRoot) {" + "      \"result: $drive=NETWORK\";" + "      \"diag: $drive DisplayRoot=$($psd.DisplayRoot)\"" + "    } else {" + "      $p = Get-Partition -DriveLetter $drive -ErrorAction Stop;" + "      $disks = @($p | Get-Disk | Get-PhysicalDisk);" + "      $types = @($disks | Select-Object -ExpandProperty MediaType -Unique);" + "      $r = if ($types.Count -gt 1) { 'MIXED' }" + "           elseif ($types.Count -eq 0) { 'UNKNOWN' }" + "           else { [string]$types[0] };" + "      \"result: $drive=$r\";" + "      foreach ($d in $disks) {" + "        \"diag: $drive FriendlyName='$($d.FriendlyName)' MediaType=$($d.MediaType) BusType=$($d.BusType) SpindleSpeed=$($d.SpindleSpeed)\"" + "      }" + "    }" + "  } catch {" + "    \"result: $drive=UNKNOWN\";" + "    \"diag: $drive Exception=$_\"" + "  }" + "}";

	/**
	 * Single-drive PowerShell script. Used by {@link #probe(Path, String)} for the directory-chooser path; {@code %s}
	 * is substituted twice (PSDrive name + DriveLetter) with the drive letter. Output shape mirrors
	 * {@link #WINDOWS_BATCH_SCRIPT} for a single drive: first line is the result, subsequent {@code diag:} lines are
	 * diagnostics.
	 */
	private static final String WINDOWS_SCRIPT = "$ErrorActionPreference='Stop';" + "try {" + "  $psd = Get-PSDrive -Name '%s' -PSProvider FileSystem -ErrorAction Stop;" + "  if ($psd.DisplayRoot) {" + "    'NETWORK';" + "    \"diag: DisplayRoot=$($psd.DisplayRoot)\"" + "  } else {" + "    $p = Get-Partition -DriveLetter '%s' -ErrorAction Stop;" + "    $disks = @($p | Get-Disk | Get-PhysicalDisk);" + "    $types = @($disks | Select-Object -ExpandProperty MediaType -Unique);" + "    if ($types.Count -gt 1) { 'MIXED' }" + "    elseif ($types.Count -eq 0) { 'UNKNOWN' }" + "    else { [string]$types[0] };" + "    foreach ($d in $disks) {" + "      \"diag: FriendlyName='$($d.FriendlyName)' MediaType=$($d.MediaType) BusType=$($d.BusType) SpindleSpeed=$($d.SpindleSpeed)\"" + "    }" + "  }" + "} catch { 'UNKNOWN'; \"diag: Exception=$_\" }";

	private StorageProfileProbe() {
	}

	/**
	 * Returns the storage profile for {@code mountPath}, or {@link StorageProfile#UNKNOWN} if classification fails.
	 * {@code fsType} is the {@code FileStore.type()} string, used to short-circuit network filesystems without shelling
	 * out. Results are memoised for the lifetime of the JVM (see {@link #CACHE}).
	 */
	public static StorageProfile probe(Path mountPath, String fsType) {
		String key = mountPath.toAbsolutePath().toString();
		StorageProfile cached = CACHE.get(key);
		if (cached != null) {
			LOG.fine(() -> "probe: cache hit for " + key + " -> " + cached);
			return cached;
		}
		StorageProfile result = doProbe(mountPath, fsType);
		CACHE.put(key, result);
		return result;
	}

	/**
	 * Classifies many volumes at once, preserving input order in the returned map. On Windows this issues a single
	 * PowerShell invocation that probes every uncached drive — paying the .NET cold-start cost once instead of per
	 * drive — which avoids the concurrent-process contention that timed out individual probes when many volumes were
	 * enumerated together. macOS and Linux fall back to per-volume parallel probes, since {@code diskutil} /
	 * {@code findmnt} don't have a comparable startup tax.
	 * <p>Results for cache hits and network-filesystem short-circuits are returned without
	 * any subprocess spawn.
	 */
	public static Map<Path, StorageProfile> probeMany(List<Volume> volumes) {
		Map<Path, StorageProfile> results = new LinkedHashMap<>();
		if (volumes == null || volumes.isEmpty())
			return results;

		List<Volume> needsProbe = new ArrayList<>();
		for (Volume v : volumes) {
			Path root = v.root();
			String key = root.toAbsolutePath().toString();
			StorageProfile cached = CACHE.get(key);
			if (cached != null) {
				LOG.fine(() -> "probeMany: cache hit for " + key + " -> " + cached);
				results.put(root, cached);
				continue;
			}
			if (isNetworkFsType(v.fsType())) {
				LOG.fine(() -> "probeMany: " + key + " fsType=" + v.fsType() + " -> NETWORK");
				results.put(root, StorageProfile.NETWORK);
				CACHE.put(key, StorageProfile.NETWORK);
				continue;
			}
			needsProbe.add(v);
		}
		if (needsProbe.isEmpty())
			return results;

		// Native-image fast path: Win32 ioctls (Windows) or Disk Arbitration (macOS).
		// Either way, pure platform API, no subprocess spawn — single-digit ms per volume
		// vs ~200–500 ms for diskutil and ~2.4 s for PowerShell. Falls through to the
		// per-OS JVM-mode fallback when no native probe is registered for this build
		// (mvn javafx:run, or platforms without a Capabilities.STORAGE_PROBE wired up).
		if (Capabilities.STORAGE_PROBE.isAvailable()) {
			long startNanos = System.nanoTime();
			Map<Path, StorageProfile> nativeResults = Capabilities.STORAGE_PROBE.probeAll(needsProbe);
			long ms = (System.nanoTime() - startNanos) / 1_000_000L;
			LOG.fine(() -> "probeMany: native classified " + nativeResults.size() + " volumes in " + ms + "ms");
			for (Map.Entry<Path, StorageProfile> e : nativeResults.entrySet()) {
				CACHE.put(e.getKey().toAbsolutePath().toString(), e.getValue());
				results.put(e.getKey(), e.getValue());
			}
			return results;
		}

		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			results.putAll(probeWindowsBatch(needsProbe));
		} else {
			// macOS / Linux: per-volume parallel probes via the cached single-volume entry.
			try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
				List<Future<?>> futures = new ArrayList<>(needsProbe.size());
				for (Volume v : needsProbe) {
					futures.add(exec.submit(() -> {
						StorageProfile p = probe(v.root(), v.fsType());
						synchronized (results) {
							results.put(v.root(), p);
						}
					}));
				}
				for (Future<?> f : futures) {
					try {
						f.get();
					} catch (Exception ignore) { /* missing key -> caller defaults to UNKNOWN */ }
				}
			}
		}
		return results;
	}

	private static StorageProfile doProbe(Path mountPath, String fsType) {
		String os = System.getProperty("os.name", "");
		LOG.fine(() -> "probe: mountPath=" + mountPath + " fsType=" + fsType + " os=" + os);
		if (isNetworkFsType(fsType)) {
			LOG.fine(() -> "  -> NETWORK (matched network fsType)");
			return StorageProfile.NETWORK;
		}
		StorageProfile result = StorageProfile.UNKNOWN;
		try {
			String osLower = os.toLowerCase();
			if (osLower.contains("mac") || osLower.contains("darwin"))
				result = probeMac(mountPath);
			else if (osLower.contains("win"))
				result = probeWindows(mountPath);
			else if (osLower.contains("linux"))
				result = probeLinux(mountPath);
			else
				LOG.fine(() -> "  no platform probe for os.name=" + os);
		} catch (Exception e) {
			LOG.log(Level.FINE, "Storage probe failed for " + mountPath, e);
		}
		final StorageProfile r = result;
		LOG.fine(() -> "  -> " + r + " for " + mountPath);
		return result;
	}

	private static boolean isNetworkFsType(String fsType) {
		if (fsType == null)
			return false;
		String t = fsType.toLowerCase();
		return t.contains("nfs") || t.contains("cifs") || t.contains("smb") || t.contains("afp") || t.contains(
				"sshfs") || t.contains("webdav") || t.contains("davfs") || t.contains("ftp");
	}

	/** macOS: parse {@code diskutil info -plist <mount>} for {@code <key>SolidState</key>}. */
	private static StorageProfile probeMac(Path mountPath) throws Exception {
		LOG.fine(() -> "  mac: running diskutil info -plist " + mountPath);
		String out = runCommand(TIMEOUT_MS, "diskutil", "info", "-plist", mountPath.toString());
		if (out == null) {
			LOG.fine("  mac: diskutil returned null (timeout / non-zero exit)");
			return StorageProfile.UNKNOWN;
		}
		Boolean solid = readBoolKey(out, "SolidState");
		LOG.fine(() -> "  mac: SolidState=" + solid);
		if (solid == null)
			return StorageProfile.UNKNOWN;
		return solid ? StorageProfile.SSD : StorageProfile.HDD;
	}

	/**
	 * Locates {@code <key>name</key>} in a plist string and returns the boolean of the next sibling element, or
	 * {@code null} if the key is absent or has a non-boolean value. Tolerant of whitespace; not a real XML parser.
	 */
	private static Boolean readBoolKey(String plist, String name) {
		String needle = "<key>" + name + "</key>";
		int i = plist.indexOf(needle);
		if (i < 0)
			return null;
		int j = plist.indexOf('<', i + needle.length());
		if (j < 0)
			return null;
		int end = plist.indexOf('>', j);
		if (end < 0)
			return null;
		String tag = plist.substring(j, end + 1).toLowerCase().trim();
		if (tag.startsWith("<true"))
			return Boolean.TRUE;
		if (tag.startsWith("<false"))
			return Boolean.FALSE;
		return null;
	}

	/**
	 * Windows: a single PowerShell pipeline that handles network mappings, single-disk volumes, and Storage Spaces /
	 * spanned volumes (multiple physical disks). The first output line is the parsed result; subsequent {@code diag:}
	 * lines are emitted for troubleshooting and logged at FINE.
	 * <p>The script is delivered via {@code -EncodedCommand} (base64-encoded UTF-16LE) to
	 * bypass Windows command-line quoting — embedded double-quotes in the script otherwise get stripped by the spawn
	 * pathway and break parsing.
	 */
	private static StorageProfile probeWindows(Path mountPath) throws Exception {
		String mountStr = mountPath.toString();
		if (mountStr.startsWith("\\\\")) {
			LOG.fine("  windows: UNC path -> NETWORK");
			return StorageProfile.NETWORK;
		}
		if (mountStr.length() < 2 || mountStr.charAt(1) != ':') {
			LOG.fine(() -> "  windows: cannot extract drive letter from " + mountStr);
			return StorageProfile.UNKNOWN;
		}
		String drive = mountStr.substring(0, 1).toUpperCase();
		String script = String.format(WINDOWS_SCRIPT, drive, drive);
		String encoded = encodePowerShellScript(script);
		LOG.fine(() -> "  windows: probing drive " + drive + ":");
		String out = runCommand(TIMEOUT_MS, "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
				encoded);
		if (out == null) {
			LOG.fine("  windows: PowerShell returned null (timeout / non-zero exit)");
			return StorageProfile.UNKNOWN;
		}
		String[] lines = out.split("\\R");
		String firstLine = lines.length > 0 ? lines[0].trim() : "";
		for (int i = 1; i < lines.length; i++) {
			String diag = lines[i];
			if (!diag.isBlank())
				LOG.fine("  windows: " + diag.trim());
		}
		StorageProfile result = switch (firstLine.toUpperCase()) {
			case "SSD" -> StorageProfile.SSD;
			case "HDD" -> StorageProfile.HDD;
			case "MIXED" -> StorageProfile.MIXED;
			case "NETWORK" -> StorageProfile.NETWORK;
			default -> StorageProfile.UNKNOWN;
		};
		LOG.fine(() -> "  windows: raw='" + firstLine + "' -> " + result);
		return result;
	}

	/** PowerShell's {@code -EncodedCommand} expects base64-encoded UTF-16LE bytes. */
	private static String encodePowerShellScript(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}

	/**
	 * Windows batch probe: one PowerShell process classifies every drive in the list. Pre-filters UNC paths and
	 * non-letter mounts in Java; everything else goes into a single {@code -EncodedCommand} call driven by
	 * {@link #WINDOWS_BATCH_SCRIPT}. Output is parsed line by line: {@code result: X=…} sets the per-drive
	 * classification, {@code diag: X …} lines are logged at FINE for troubleshooting.
	 */
	private static Map<Path, StorageProfile> probeWindowsBatch(List<Volume> volumes) {
		// JVM-dev-mode-only path. The native-image fast path (Win32 ioctls) is dispatched
		// from probeMany via Capabilities.STORAGE_PROBE before this method is reached, so
		// by the time we get here we know we're on a HotSpot run that can't bind
		// @CFunction symbols and have to fall back to PowerShell + WMI.
		Map<Path, StorageProfile> results = new LinkedHashMap<>();
		Map<String, Path> driveToPath = new LinkedHashMap<>();
		for (Volume v : volumes) {
			Path root = v.root();
			String mountStr = root.toString();
			String key = root.toAbsolutePath().toString();
			if (mountStr.startsWith("\\\\")) {
				LOG.fine(() -> "  windows batch: " + mountStr + " UNC -> NETWORK");
				results.put(root, StorageProfile.NETWORK);
				CACHE.put(key, StorageProfile.NETWORK);
				continue;
			}
			if (mountStr.length() < 2 || mountStr.charAt(1) != ':') {
				LOG.fine(() -> "  windows batch: cannot extract drive letter from " + mountStr);
				results.put(root, StorageProfile.UNKNOWN);
				CACHE.put(key, StorageProfile.UNKNOWN);
				continue;
			}
			driveToPath.put(mountStr.substring(0, 1).toUpperCase(), root);
		}
		if (driveToPath.isEmpty())
			return results;

		StringBuilder driveList = new StringBuilder();
		for (String drive : driveToPath.keySet()) {
			if (driveList.length() > 0)
				driveList.append(',');
			driveList.append('\'').append(drive).append('\'');
		}
		String script = String.format(WINDOWS_BATCH_SCRIPT, driveList);
		String encoded = encodePowerShellScript(script);
		LOG.fine(() -> "  windows batch: probing drives " + driveToPath.keySet());
		String out = runCommand(BATCH_TIMEOUT_MS, "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
				encoded);

		Map<String, StorageProfile> driveResults = new HashMap<>();
		if (out != null) {
			for (String line : out.split("\\R")) {
				String t = line.trim();
				if (t.startsWith("result:")) {
					String body = t.substring("result:".length()).trim();
					int eq = body.indexOf('=');
					if (eq <= 0)
						continue;
					String drive = body.substring(0, eq).trim().toUpperCase();
					String value = body.substring(eq + 1).trim().toUpperCase();
					StorageProfile prof = switch (value) {
						case "SSD" -> StorageProfile.SSD;
						case "HDD" -> StorageProfile.HDD;
						case "MIXED" -> StorageProfile.MIXED;
						case "NETWORK" -> StorageProfile.NETWORK;
						default -> StorageProfile.UNKNOWN;
					};
					driveResults.put(drive, prof);
				} else if (t.startsWith("diag:")) {
					LOG.fine("  windows batch: " + t);
				}
			}
		} else {
			LOG.fine("  windows batch: PowerShell returned null (timeout / non-zero exit)");
		}

		for (Map.Entry<String, Path> e : driveToPath.entrySet()) {
			StorageProfile p = driveResults.getOrDefault(e.getKey(), StorageProfile.UNKNOWN);
			Path root = e.getValue();
			results.put(root, p);
			CACHE.put(root.toAbsolutePath().toString(), p);
			LOG.fine(() -> "  windows batch: " + e.getKey() + ": -> " + p);
		}
		return results;
	}

	/**
	 * Linux: {@code findmnt -T <path> -no SOURCE} to resolve the backing device, then {@code lsblk -no ROTA <device>}
	 * which walks LVM/dm/RAID stacks down to physical disks and emits one row per physical leaf.
	 */
	private static StorageProfile probeLinux(Path mountPath) throws Exception {
		LOG.fine(() -> "  linux: findmnt -T " + mountPath);
		String src = runCommand(TIMEOUT_MS, "findmnt", "-T", mountPath.toString(), "-no", "SOURCE");
		if (src == null) {
			LOG.fine("  linux: findmnt returned null");
			return StorageProfile.UNKNOWN;
		}
		src = src.trim();
		LOG.fine("  linux: source device = '" + src + "'");
		if (src.isEmpty() || !src.startsWith("/dev/"))
			return StorageProfile.UNKNOWN;
		String rota = runCommand(TIMEOUT_MS, "lsblk", "-no", "ROTA", src);
		if (rota == null) {
			LOG.fine("  linux: lsblk returned null");
			return StorageProfile.UNKNOWN;
		}
		LOG.fine("  linux: lsblk ROTA output = '" + rota.replace("\n", " | ").trim() + "'");
		boolean any0 = false, any1 = false;
		for (String line : rota.split("\\R")) {
			String t = line.trim();
			if (t.equals("0"))
				any0 = true;
			else if (t.equals("1"))
				any1 = true;
		}
		if (any0 && any1)
			return StorageProfile.MIXED;
		if (any0)
			return StorageProfile.SSD;
		if (any1)
			return StorageProfile.HDD;
		return StorageProfile.UNKNOWN;
	}

	/**
	 * Spawns {@code command}, drains stdout on a background reader thread (so a full pipe buffer can't deadlock the
	 * wait), and returns the captured stdout. Returns {@code null} on timeout, non-zero exit, or any I/O failure.
	 */
	private static String runCommand(long timeoutMs, String... command) {
		Process p = null;
		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			p = pb.start();
			final Process proc = p;
			StringBuilder sb = new StringBuilder();
			Thread reader = new Thread(() -> {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = br.readLine()) != null) {
						synchronized (sb) {
							sb.append(line).append('\n');
						}
					}
				} catch (Exception ignore) { /* drained best-effort */ }
			}, "StorageProbe-stdout");
			reader.setDaemon(true);
			reader.start();
			if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
				LOG.fine(() -> "runCommand: timeout after " + timeoutMs + "ms: " + commandSummary(command));
				p.destroyForcibly();
				return null;
			}
			reader.join(500);
			int exit = p.exitValue();
			if (exit != 0) {
				LOG.fine(() -> "runCommand: non-zero exit " + exit + ": " + commandSummary(command));
				return null;
			}
			synchronized (sb) {
				return sb.toString();
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "runCommand: exception running " + commandSummary(command), e);
			if (p != null)
				p.destroyForcibly();
			return null;
		}
	}

	/**
	 * Truncates the long base64 blob in {@code -EncodedCommand} payloads for log output — otherwise every probe failure
	 * dumps a few KB of base64 into the log.
	 */
	private static String commandSummary(String[] command) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < command.length; i++) {
			if (i > 0)
				sb.append(' ');
			String arg = command[i];
			if (arg != null && arg.length() > 80) {
				sb.append(arg, 0, 40).append("...[").append(arg.length()).append(" chars]");
			} else {
				sb.append(arg);
			}
		}
		return sb.toString();
	}
}
