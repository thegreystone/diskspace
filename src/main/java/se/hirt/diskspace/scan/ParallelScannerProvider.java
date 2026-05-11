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

import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;

/**
 * Cross-platform {@link ScannerProvider} backed by {@link ParallelDirectoryScanner}. Always eligible — directory
 * walking works on every volume — and serves as the AUTO fallback when no faster provider (currently only
 * {@link MftScanner}) handles the volume.
 * <p>Owns the storage-profile → parallelism mapping ({@link #parallelismFor}). HDD is sequential
 * (parallel readers on a spinning disk just trade kernel readahead for head seeks), SSD peaks around 4–8 concurrent
 * metadata readers, NETWORK is latency-bound and benefits from many in-flight requests. {@link ScanStrategy#SEQUENTIAL}
 * forces parallelism=1 regardless of profile — a debug knob useful for measuring the parallel speedup or for spinning
 * drives we haven't classified.
 * <p>{@link #matchesPreference} returns true for AUTO / PARALLEL / SEQUENTIAL but NOT for MFT or
 * BULK — those are "primarily" the platform-native scanners' job, and {@link ScannerProviders#providerFor} falls back
 * to AUTO automatically when the user forced one on a volume that can't actually serve it, at which point this provider
 * is selected. {@link #description} branches on the preference so the tooltip honestly says "X not available — falling
 * back" in that case.
 */
public final class ParallelScannerProvider implements ScannerProvider {

	@Override
	public boolean matchesPreference(ScanStrategy preference) {
		return preference == ScanStrategy.AUTO || preference == ScanStrategy.PARALLEL || preference == ScanStrategy.SEQUENTIAL;
	}

	@Override
	public boolean canScan(Volume volume) {
		return true;
	}

	@Override
	public Scanner createScanner(Volume volume, ScanStrategy preference) {
		return new ParallelDirectoryScanner(parallelismFor(volume, preference));
	}

	@Override
	public String label(Volume volume, ScanStrategy preference) {
		int p = parallelismFor(volume, preference);
		return p == 1 ? "Sequential" : "Parallel (" + p + ")";
	}

	@Override
	public String description(Volume volume, ScanStrategy preference) {
		// User asked for MFT or BULK but ended up here via AUTO fallback in ScannerProviders.providerFor.
		// Call out the fallback explicitly so the tooltip doesn't lie about what's running.
		if (preference == ScanStrategy.MFT || preference == ScanStrategy.BULK) {
			String forced = preference == ScanStrategy.MFT ? "MFT" : "Bulk";
			int p = parallelismFor(volume.storageProfile());
			return p == 1
					? forced + " not available for this volume — falling back to single-threaded directory walking."
					: forced + " not available for this volume — falling back to parallel walking (" + p + " readers, sized to the storage profile).";
		}
		int p = parallelismFor(volume, preference);
		return switch (p) {
			case 1 ->
					"Single-threaded directory walking. Right for spinning media; useful as a debug baseline elsewhere.";
			case 16 -> "Latency-bound network filesystem — many in-flight requests hide round-trip time.";
			default ->
					"SSD metadata throughput peaks around 4–8 concurrent readers; the ForkJoinPool is sized to match.";
		};
	}

	/** SEQUENTIAL pins parallelism to 1 regardless of profile; everything else uses the storage-profile mapping. */
	private static int parallelismFor(Volume volume, ScanStrategy preference) {
		return preference == ScanStrategy.SEQUENTIAL ? 1 : parallelismFor(volume.storageProfile());
	}

	/**
	 * Maps a storage profile to a ForkJoinPool size. {@link StorageProfile#HDD HDD} is sequential because two
	 * concurrent readers on a spinning disk only trade kernel readahead for head seeks. {@link StorageProfile#SSD SSD}
	 * stops scaling around 4–8 concurrent metadata readers. {@link StorageProfile#NETWORK NETWORK} is latency-bound, so
	 * concurrency hides RTT. {@link StorageProfile#MIXED MIXED} and {@link StorageProfile#UNKNOWN UNKNOWN} fall back to
	 * the SSD value — the common case is solid-state, and the only profile that loses badly to parallelism (HDD) is the
	 * one we explicitly identify.
	 */
	public static int parallelismFor(StorageProfile profile) {
		if (profile == null)
			return 8;
		return switch (profile) {
			case HDD -> 1;
			case SSD -> 8;
			case NETWORK -> 16;
			case MIXED, UNKNOWN -> 8;
		};
	}
}
