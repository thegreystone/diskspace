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

/**
 * Pluggable strategy for producing a {@link Scanner} for a {@link Volume} under a given user-facing
 * {@link ScanStrategy}. Each provider encapsulates one scanner family (MFT, parallel directory walk, ...): which
 * preferences it primarily answers to, which volumes it can actually handle, the scanner instance it builds, and the
 * label/description text the picker UI shows for that combination.
 * <p>Cross-platform code never branches on "is this MFT?" or "is this parallel?". It walks
 * {@link ScannerProviders} in priority order and picks the first provider that {@link #matchesPreference matches} the
 * user's preference and {@link #canScan can scan} the volume. Adding a new fast path (e.g. a Linux NTFS-3G
 * implementation, or an APFS catalog-tree scanner) is just another implementation registered alongside the existing
 * ones — no caller changes.
 * <p>{@link #matchesPreference} is for "primary" matches — providers should not claim to
 * match preferences they handle only as a fallback. {@link ScannerProviders#providerFor} falls back to
 * {@link ScanStrategy#AUTO}-matching candidates automatically when the strict-preference match isn't eligible, so e.g.
 * forcing MFT on a non-NTFS volume still produces a working scanner via the parallel provider.
 */
public interface ScannerProvider {

	/**
	 * True if this provider is one of the primary candidates for the given user preference. {@link ScanStrategy#AUTO}
	 * is matched by every registered provider. Other strategies are matched only by providers that primarily implement
	 * that strategy — providers used as fallbacks should NOT claim to match.
	 */
	boolean matchesPreference(ScanStrategy preference);

	/**
	 * True if this provider can scan {@code volume} right now (volume eligibility check — e.g. MFT requires NTFS +
	 * drive-letter root + raw volume open succeeds). The result may be cached by the implementation; callers should not
	 * assume idempotence is free.
	 */
	boolean canScan(Volume volume);

	/**
	 * Constructs a fresh {@link Scanner} for {@code volume} under {@code preference}. Only valid when {@link #canScan}
	 * just returned true, either as a primary match or as the AUTO fallback. {@code preference} is passed in so
	 * providers that serve multiple strategies (e.g. {@link ParallelScannerProvider} handling AUTO / PARALLEL /
	 * SEQUENTIAL) can configure themselves accordingly.
	 */
	Scanner createScanner(Volume volume, ScanStrategy preference);

	/**
	 * Short label for the picker UI's per-row badge — e.g. {@code "MFT"}, {@code "Parallel (8)"}, {@code "Sequential"}.
	 * Must reflect what {@link #createScanner} would actually do for this {@code volume} + {@code preference}.
	 */
	String label(Volume volume, ScanStrategy preference);

	/**
	 * Tooltip-friendly prose describing what the scanner would do, why, and any caveats. Single sentence or two; no
	 * leading/trailing whitespace.
	 */
	String description(Volume volume, ScanStrategy preference);
}
