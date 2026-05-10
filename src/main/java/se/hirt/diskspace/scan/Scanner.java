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
import se.hirt.diskspace.model.Volume;

import java.nio.file.Path;

public interface Scanner {

	/**
	 * Process-lifetime preference for which scanner family to use. {@link ScanStrategy#AUTO} picks the fastest
	 * available implementation per volume; {@link ScanStrategy#PARALLEL} forces the FS-walking scanner. Toggleable from
	 * the picker UI (S key).
	 */
	java.util.concurrent.atomic.AtomicReference<ScanStrategy> PREFERENCE = new java.util.concurrent.atomic.AtomicReference<>(
			ScanStrategy.AUTO);

	/**
	 * Starts scanning {@code root} on a background thread. The listener's {@code onStart} fires synchronously with the
	 * live, mutating tree before the thread is started — UI code can begin observing the root immediately. Other
	 * callbacks fire on the scan thread; UI code is responsible for marshalling to the JavaFX Application Thread.
	 */
	void scan(Path root, ScanListener listener);

	void cancel();

	/**
	 * Optional per-frame overrides for what the {@code DiskView} hub displays during a scan. Polled by the UI on every
	 * redraw, so implementations must be safe to call from the JavaFX thread while the scan is in flight.
	 * <p>Any field can be left as a "no override" sentinel — {@code null} for the strings,
	 * {@code -1} for {@code arcFraction} — and the hub falls back to its default rendering (running
	 * {@code humanSize(bytes)} title, {@code "X files"} subtitle, and a {@code bytes / usedBytes} progress arc) driven
	 * by {@link ScanListener#onProgress}.
	 * <p>The default — {@link #DEFAULT} — is no-overrides. {@link ParallelDirectoryScanner}
	 * uses it; the bytes-driven hub is exactly right for an FS walk that's discovering files in real time.
	 * {@link MftScanner} overrides during phase 1 (USN enumeration) because no bytes are known yet, then defers to
	 * defaults during phase 2 (size lookup) so the visible hub matches the parallel scanner once real bytes start
	 * flowing.
	 */
	record HubState(String title, String subtitle, double arcFraction) {
		public static final HubState DEFAULT = new HubState(null, null, -1.0);
	}

	/**
	 * Returns the scanner's current hub overrides, or {@link HubState#DEFAULT} for "no override". Polled by the UI on
	 * every redraw, so it must be cheap and thread-safe.
	 */
	default HubState hubState() {
		return HubState.DEFAULT;
	}

	/**
	 * Returns the next strategy in cycle order, skipping any whose primary {@link ScannerProvider} isn't registered on
	 * this platform — e.g. {@link ScanStrategy#MFT} on macOS / Linux, where cycling through it is a dead step because
	 * every volume falls through to parallel walking anyway.
	 */
	static ScanStrategy nextAvailable(ScanStrategy current) {
		ScanStrategy n = current.next();
		while (!ScannerProviders.isStrategyAvailable(n)) {
			n = n.next();
		}
		return n;
	}

	/**
	 * Returns the scanner used for {@code volume} under the current {@link #PREFERENCE}. Selection (including the "user
	 * forced MFT but volume isn't NTFS, fall back to parallel" semantics) is expressed declaratively by the
	 * {@link ScannerProviders} list rather than as branchy code here.
	 */
	static Scanner forVolume(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		return ScannerProviders.providerFor(volume, pref).createScanner(volume, pref);
	}

	/**
	 * Short label that {@link #forVolume(Volume)} would produce for {@code volume} under the current
	 * {@link #PREFERENCE}. Used by the picker UI for per-row badges and the global indicator.
	 */
	static String strategyLabelFor(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		return ScannerProviders.providerFor(volume, pref).label(volume, pref);
	}

	/**
	 * Tooltip-friendly explanation of what {@link #forVolume(Volume)} would actually do for {@code volume} — reflects
	 * the actually-chosen provider, including the "MFT not available — falling back" wording when the parallel provider
	 * is selected as the AUTO fallback for a forced-MFT pref.
	 */
	static String strategyDescriptionFor(Volume volume) {
		ScanStrategy pref = PREFERENCE.get();
		return ScannerProviders.providerFor(volume, pref).description(volume, pref);
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
