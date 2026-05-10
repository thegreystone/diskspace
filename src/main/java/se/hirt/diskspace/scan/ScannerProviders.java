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

import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.platform.Capabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * Static registry of {@link ScannerProvider}s in priority order — fast paths first, generic fallbacks last.
 * Cross-platform code uses {@link #providerFor(Volume, ScanStrategy)} to pick a provider for a given volume + user
 * preference; the picker UI uses {@link #isStrategyAvailable(ScanStrategy)} to decide which entries the {@code S} key
 * should cycle through.
 * <h3>Registration</h3>
 * The list is built once at class init. Today: the MFT provider is registered first when
 * {@link Capabilities#MFT_PROVIDER} is non-null, then the always-available {@link ParallelScannerProvider}. Adding a
 * Linux NTFS-3G fast path or an APFS catalog-tree provider means one more conditional registration here — no caller
 * changes.
 * <h3>Selection semantics</h3>
 * {@link #providerFor} first looks for a provider that {@linkplain ScannerProvider#matchesPreference primarily matches}
 * the user's preference and {@linkplain ScannerProvider#canScan can scan} the volume. If none is eligible (e.g. user
 * forced MFT but the volume isn't NTFS), it retries against {@link ScanStrategy#AUTO} so the request still produces a
 * working scanner — that's where the existing "MFT not available — falling back to parallel" semantics live, now
 * expressed declaratively rather than as branchy code in {@code Scanner.forVolume}.
 */
public final class ScannerProviders {

	private ScannerProviders() {
	}

	private static final List<ScannerProvider> ALL = build();

	private static List<ScannerProvider> build() {
		List<ScannerProvider> list = new ArrayList<>();
		// Capabilities.MFT_PROVIDER is non-null only on a Windows native-image binary; the field
		// is populated by Capabilities's static initializer, which is the one place in the code
		// base that does the Platform.includedIn check. Critically: WindowsCapabilities is NOT
		// referenced from this file. An earlier draft did exactly that, behind a Capabilities.IS_WINDOWS_NATIVE
		// flag, and Substrate's analyzer reached the @Platforms(WINDOWS)-gated WindowsCapabilities
		// class on macOS / Linux because static-field reads don't fold the same way as inline
		// Platform.includedIn calls. Reading a nullable interface-typed field, by contrast, lets
		// the analyzer dead-strip the implementation type cleanly.
		if (Capabilities.MFT_PROVIDER != null) {
			list.add(Capabilities.MFT_PROVIDER);
		}
		list.add(new ParallelScannerProvider());
		return List.copyOf(list);
	}

	/** All registered providers, in priority order — fastest first. */
	public static List<ScannerProvider> all() {
		return ALL;
	}

	/**
	 * Picks the provider that should run for {@code volume} under {@code preference}. First tries strict-preference
	 * matches; if none can scan, falls back to AUTO matches so a forced-but-ineligible preference (e.g. MFT on a
	 * non-NTFS volume) still produces a working scanner via the fallback provider.
	 */
	public static ScannerProvider providerFor(Volume volume, ScanStrategy preference) {
		for (ScannerProvider p : ALL) {
			if (p.matchesPreference(preference) && p.canScan(volume))
				return p;
		}
		for (ScannerProvider p : ALL) {
			if (p.matchesPreference(ScanStrategy.AUTO) && p.canScan(volume))
				return p;
		}
		throw new IllegalStateException("No scanner provider for " + volume + " (preference=" + preference + ")");
	}

	/**
	 * True if any registered provider primarily matches {@code strategy}. Used by
	 * {@link Scanner#nextAvailable(ScanStrategy)} to skip strategies whose primary provider isn't on this platform —
	 * e.g. MFT on macOS and Linux.
	 */
	public static boolean isStrategyAvailable(ScanStrategy strategy) {
		for (ScannerProvider p : ALL) {
			if (p.matchesPreference(strategy))
				return true;
		}
		return false;
	}
}
