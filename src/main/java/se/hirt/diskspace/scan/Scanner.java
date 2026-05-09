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
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;

import java.nio.file.Path;

public interface Scanner {

	/**
	 * Process-lifetime preference for which scanner family to use. {@link ScanStrategy#AUTO} picks the fastest available implementation per
	 * volume; {@link ScanStrategy#PARALLEL} forces the FS-walking scanner. Toggleable from the picker UI (S key).
	 */
	java.util.concurrent.atomic.AtomicReference<ScanStrategy> PREFERENCE = new java.util.concurrent.atomic.AtomicReference<>(
			ScanStrategy.AUTO);

	/**
	 * Starts scanning {@code root} on a background thread. The listener's {@code onStart} fires synchronously with the live, mutating tree
	 * before the thread is started — UI code can begin observing the root immediately. Other callbacks fire on the scan thread; UI code is
	 * responsible for marshalling to the JavaFX Application Thread.
	 */
	void scan(Path root, ScanListener listener);

	void cancel();

	/**
	 * Optional per-frame overrides for what the {@code DiskView} hub displays during a scan. Polled by the UI on every redraw, so
	 * implementations must be safe to call from the JavaFX thread while the scan is in flight.
	 * <p>Any field can be left as a "no override" sentinel — {@code null} for the strings,
	 * {@code -1} for {@code arcFraction} — and the hub falls back to its default rendering (running {@code humanSize(bytes)} title,
	 * {@code "X files"} subtitle, and a {@code bytes / usedBytes} progress arc) driven by {@link ScanListener#onProgress}.
	 * <p>The default — {@link #DEFAULT} — is no-overrides. {@link ParallelDirectoryScanner}
	 * uses it; the bytes-driven hub is exactly right for an FS walk that's discovering files in real time. {@link MftScanner} overrides
	 * during phase 1 (USN enumeration) because no bytes are known yet, then defers to defaults during phase 2 (size lookup) so the visible
	 * hub matches the parallel scanner once real bytes start flowing.
	 */
	record HubState(String title, String subtitle, double arcFraction) {
		public static final HubState DEFAULT = new HubState(null, null, -1.0);
	}

	/**
	 * Returns the scanner's current hub overrides, or {@link HubState#DEFAULT} for "no override". Polled by the UI on every redraw, so it
	 * must be cheap and thread-safe.
	 */
	default HubState hubState() {
		return HubState.DEFAULT;
	}

	/**
	 * Returns the next strategy in cycle order, skipping {@link ScanStrategy#MFT} when {@link MftScanner#isAvailable()} is false
	 * (non-Windows or native-image build) — on those platforms MFT is functionally identical to PARALLEL because every volume falls through
	 * to parallel walking, so cycling through it is just a dead step.
	 */
	static ScanStrategy nextAvailable(ScanStrategy current) {
		ScanStrategy n = current.next();
		if (n == ScanStrategy.MFT && !MftScanner.isAvailable()) {
			n = n.next();
		}
		return n;
	}

	/**
	 * Returns the scanner used for a volume, respecting {@link #PREFERENCE}:
	 * <ul>
	 *   <li>{@link ScanStrategy#AUTO}: pick the fastest available — MFT on NTFS/Windows/admin,
	 *       else parallel sized per storage profile.</li>
	 *   <li>{@link ScanStrategy#MFT}: force {@link MftScanner} if eligible; fall back to
	 *       parallel(profile) otherwise.</li>
	 *   <li>{@link ScanStrategy#PARALLEL}: always parallel walking, parallelism per storage
	 *       profile (HDD=1, SSD=8, NETWORK=16).</li>
	 *   <li>{@link ScanStrategy#SEQUENTIAL}: parallel walking with parallelism=1 (single
	 *       thread).</li>
	 * </ul>
	 */
	static Scanner forVolume(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		if ((pref == ScanStrategy.AUTO || pref == ScanStrategy.MFT) && MftScanner.canScan(volume)) {
			return new MftScanner();
		}
		int parallelism = pref == ScanStrategy.SEQUENTIAL ? 1 : parallelismFor(volume.storageProfile());
		return new ParallelDirectoryScanner(parallelism);
	}

	/**
	 * Returns the strategy label that {@link #forVolume(Volume)} would use for {@code volume} under the current {@link #PREFERENCE}. Used
	 * by the picker UI to render per-row tooltips and the global indicator.
	 */
	static String strategyLabelFor(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		if ((pref == ScanStrategy.AUTO || pref == ScanStrategy.MFT) && MftScanner.canScan(volume)) {
			return "MFT";
		}
		int p = pref == ScanStrategy.SEQUENTIAL ? 1 : parallelismFor(volume.storageProfile());
		return p == 1 ? "Sequential" : "Parallel (" + p + ")";
	}

	/**
	 * Longer prose explanation of why {@link #strategyLabelFor(Volume)} chose what it did, for the picker's row tooltip. Always reflects
	 * the strategy that would actually run, not a generic profile claim — so toggling {@link #PREFERENCE} changes this text.
	 */
	static String strategyDescriptionFor(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		if ((pref == ScanStrategy.AUTO || pref == ScanStrategy.MFT) && MftScanner.canScan(volume)) {
			return "Reads the NTFS Master File Table via FSCTL_ENUM_USN_DATA, then bulk-enumerates " + "child sizes per directory. Requires admin / SeBackupPrivilege.";
		}
		// User asked for MFT but volume isn't eligible — call out the fallback.
		if (pref == ScanStrategy.MFT) {
			int p = parallelismFor(volume.storageProfile());
			return p == 1 ? "MFT not available for this volume — falling back to single-threaded directory walking."
					: "MFT not available for this volume — falling back to parallel walking (" + p + " readers, sized to the storage profile).";
		}
		int p = pref == ScanStrategy.SEQUENTIAL ? 1 : parallelismFor(volume.storageProfile());
		return switch (p) {
			case 1 -> "Single-threaded directory walking. Right for spinning media; useful as a debug baseline elsewhere.";
			case 16 -> "Latency-bound network filesystem — many in-flight requests hide round-trip time.";
			default -> "SSD metadata throughput peaks around 4–8 concurrent readers; the ForkJoinPool is sized to match.";
		};
	}

	/**
	 * Maps a storage profile to a ForkJoinPool size. {@link StorageProfile#HDD HDD} is sequential because two concurrent readers on a
	 * spinning disk only trade kernel readahead for head seeks. {@link StorageProfile#SSD SSD} stops scaling around 4–8 concurrent metadata
	 * readers. {@link StorageProfile#NETWORK NETWORK} is latency-bound, so concurrency hides RTT. {@link StorageProfile#MIXED MIXED} and
	 * {@link StorageProfile#UNKNOWN UNKNOWN} fall back to the SSD value — the common case is solid-state, and the only profile that loses
	 * badly to parallelism (HDD) is the one we explicitly identify.
	 */
	static int parallelismFor(StorageProfile profile) {
		if (profile == null)
			return 8;
		return switch (profile) {
			case HDD -> 1;
			case SSD -> 8;
			case NETWORK -> 16;
			case MIXED, UNKNOWN -> 8;
		};
	}

	interface ScanListener {
		/** Called once, before scanning begins, with the live tree root. */
		void onStart(DirectoryNode liveRoot);

		/** Periodic progress, throttled by the scanner. {@code currentPath} may be null. */
		void onProgress(long files, long bytes, String currentPath);

		/** Called before {@link #onComplete} if any entries were inaccessible (permission denied). */
		default void onPermissionsDenied(long count) {
		}

		/** Final tree (children sorted by size desc, recursively). */
		void onComplete(DirectoryNode root);

		void onError(Throwable t);
	}
}
