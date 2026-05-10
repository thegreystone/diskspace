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

/**
 * User preference for scan strategy. {@link Scanner#forVolume(se.hirt.diskspace.model.Volume)} resolves the actual
 * scanner to instantiate based on this preference and the volume's capabilities.
 * <p>S in the picker cycles through these values in declared order
 * ({@link #AUTO} → {@link #BULK} → {@link #MFT} → {@link #PARALLEL} → {@link #SEQUENTIAL} → {@link #AUTO}),
 * skipping any whose primary provider isn't registered on the current platform — so on macOS the cycle reads
 * AUTO → BULK → PARALLEL → SEQUENTIAL (MFT skipped) and on Windows it reads AUTO → MFT → PARALLEL → SEQUENTIAL
 * (BULK skipped).
 */
public enum ScanStrategy {
	/**
	 * Pick the fastest implementation available for each volume. NTFS on Windows with admin privilege gets the MFT
	 * scanner; local volumes on macOS get the getattrlistbulk-based bulk scanner; everything else gets parallel
	 * walking sized to the storage profile.
	 */
	AUTO,
	/**
	 * Force the macOS bulk scanner ({@code getattrlistbulk(2)} per directory). Falls back to PARALLEL if the volume
	 * isn't eligible (non-Mac, JVM dev mode, or a network mount where {@code getattrlistbulk}'s per-syscall
	 * amortisation loses to the parallel walker's latency-hiding fan-out).
	 */
	BULK,
	/**
	 * Force the MFT scanner. Falls back to PARALLEL if the volume isn't eligible (non-NTFS, non-Windows, no
	 * privilege).
	 */
	MFT,
	/**
	 * Force parallel directory walking with the per-profile pool size (HDD=1, SSD=8, NETWORK=16). Skips MFT / BULK
	 * even when available — useful for A/B comparison.
	 */
	PARALLEL,
	/**
	 * Force single-threaded directory walking (parallelism=1). Mostly a debugging knob — useful for measuring the
	 * speedup parallelism is actually giving us, or for spinning HDDs where extra readers just trade kernel readahead
	 * for head seeks.
	 */
	SEQUENTIAL;

	/** Short label suitable for the picker's status indicator. */
	public String label() {
		return switch (this) {
			case AUTO -> "Auto";
			case BULK -> "Bulk";
			case MFT -> "MFT";
			case PARALLEL -> "Parallel";
			case SEQUENTIAL -> "Sequential";
		};
	}

	/** Returns the next strategy in cycle order — used by the picker's S-key handler. */
	public ScanStrategy next() {
		ScanStrategy[] all = values();
		return all[(ordinal() + 1) % all.length];
	}
}
