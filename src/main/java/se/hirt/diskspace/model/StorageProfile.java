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
 * Physical-storage classification used by the scanner to choose between sequential and parallel walk strategies. {@link #HDD} is the only
 * profile that should force sequential scanning — everything else (including {@link #UNKNOWN}) benefits from, or is neutral to, parallel
 * I/O.
 */
public enum StorageProfile {
	/** Solid-state local storage (SATA SSD, NVMe, eMMC). Parallel-friendly. */
	SSD,
	/** Spinning magnetic disk. Parallel reads cause seek thrashing — scan sequentially. */
	HDD,
	/** Remote filesystem (SMB, NFS, sshfs). Latency-bound; parallelism helps a lot. */
	NETWORK,
	/**
	 * Composite volume spanning physical disks of different types (RAID, Storage Spaces, ZFS/btrfs pools). Treated as parallel-friendly:
	 * the underlying media may include an HDD, but the logical-volume layer typically reorders requests well enough that parallel readdir
	 * wins on net.
	 */
	MIXED,
	/**
	 * Probe failed, was unsupported on this platform, or returned an ambiguous result. Treated as parallel-friendly because most ambiguous
	 * cases (network mounts, virtual disks) benefit from parallelism.
	 */
	UNKNOWN;

	/** Whether the scanner should prefer parallel walking on this profile. */
	public boolean parallelFriendly() {
		return this != HDD;
	}

	/**
	 * Short label for inline UI display. {@link #UNKNOWN} renders as empty string so the picker doesn't show a meaningless tag for fuzzy
	 * cases.
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

	/** Longer explanation for tooltip display. */
	public String tooltipDescription() {
		return switch (this) {
			case SSD -> "Solid-state storage. DiskSpace will scan in parallel for speed.";
			case HDD -> "Spinning hard disk. DiskSpace will scan sequentially to avoid head thrashing.";
			case NETWORK -> "Network filesystem. DiskSpace will scan in parallel — round-trip latency dominates.";
			case MIXED -> "Composite volume (RAID / pool). DiskSpace will scan in parallel.";
			case UNKNOWN -> "Storage type couldn't be determined. DiskSpace will scan in parallel.";
		};
	}
}
