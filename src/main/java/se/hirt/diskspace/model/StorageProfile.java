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

/**
 * Physical-storage classification surfaced in the picker so users can see at a glance what kind of media each volume
 * sits on.
 */
public enum StorageProfile {
	/** Solid-state local storage (SATA SSD, NVMe, eMMC). */
	SSD,
	/** Spinning magnetic disk. */
	HDD,
	/** Remote filesystem (SMB, NFS, sshfs). */
	NETWORK,
	/** Composite volume spanning physical disks of different types (RAID, Storage Spaces, ZFS/btrfs pools). */
	MIXED,
	/** Probe failed, was unsupported on this platform, or returned an ambiguous result. */
	UNKNOWN;

	/**
	 * Short label for inline UI display. {@link #UNKNOWN} renders as empty string so the picker doesn't show a
	 * meaningless tag for fuzzy cases.
	 */
	public String shortLabel() {
		return switch (this) {
			case SSD -> "SSD";
			case HDD -> "HDD";
			case NETWORK -> "Network";
			case MIXED -> "Mixed";
			case UNKNOWN -> "";
		};
	}

	/**
	 * Scan-strategy explanation for tooltip display. Stays focused on how DiskSpace walks this profile — the storage
	 * type itself is already shown as its own key/value line.
	 */
	public String tooltipDescription() {
		return switch (this) {
			case SSD -> "Parallel (8 readers) — SSD metadata throughput peaks around 4–8 concurrent readers.";
			case HDD -> "Sequential — concurrent readers on spinning media trade kernel readahead for head seeks.";
			case NETWORK -> "Parallel (16 readers) — round-trip latency dominates, so concurrency hides it.";
			case MIXED ->
					"Parallel (8 readers) — the logical-volume layer reorders requests across the underlying disks.";
			case UNKNOWN ->
					"Parallel (8 readers) by default — the only profile that loses to parallelism is a confirmed spinning disk.";
		};
	}
}
