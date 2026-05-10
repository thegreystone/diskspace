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
package se.hirt.diskspace.platform;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.MacBulkScanner;
import se.hirt.diskspace.scan.MacStorageProbe;
import se.hirt.diskspace.scan.ParallelScannerProvider;
import se.hirt.diskspace.scan.ScanStrategy;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.scan.ScannerProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * macOS-only implementations of the platform abstractions. The whole class is annotated {@code @Platforms(DARWIN)} so
 * it physically does not exist in non-Darwin native-image builds; {@link Capabilities}'s static initializer references
 * {@link #storageProbe()} / {@link #bulkScannerProvider()} only behind a build-time-foldable Darwin guard, so on
 * Windows / Linux the reference is dead-stripped before the analyzer tries to load this class or anything it imports
 * (notably the {@code @Platforms(DARWIN)}-gated {@link MacStorageProbe}, {@link MacBulkScanner}, and
 * {@link se.hirt.diskspace.scan.Darwin} classes).
 * <p>Mac doesn't need a UAC-style elevation prompt, so
 * {@link Capabilities#ELEVATION} stays NOOP on this platform. {@link Capabilities#STORAGE_PROBE} is wired to the
 * Disk-Arbitration / IOKit classifier; {@link Capabilities#NATIVE_SCANNER_PROVIDER} is wired to the
 * {@code getattrlistbulk(2)}-based scanner.
 */
@Platforms(Platform.DARWIN.class)
public final class MacCapabilities {

	private MacCapabilities() {
	}

	static Capabilities.StorageProbe storageProbe() {
		return new Capabilities.StorageProbe() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public Map<Path, StorageProfile> probeAll(List<Volume> volumes) {
				return MacStorageProbe.probeAll(volumes);
			}
		};
	}

	/**
	 * Bulk-syscall scanner provider — wraps {@link MacBulkScanner} as a {@link ScannerProvider} so cross-platform
	 * code in {@code Scanner.forVolume} selects it generically. Matches {@link ScanStrategy#AUTO} only; we don't add a
	 * dedicated "BULK" strategy because the scanner is strictly faster than {@link ParallelScannerProvider} on local
	 * Mac volumes and there's no real reason for the user to manually pick it. {@code canScan} excludes
	 * {@link StorageProfile#NETWORK} so latency-bound network filesystems fall through to the parallel walker's
	 * high-fan-out path (parallelism=16) — getattrlistbulk over NFS/SMB still amortises calls but doesn't hide
	 * round-trip the way many concurrent in-flight requests do.
	 * <p>Parallelism for each scan is derived from the volume's storage profile via
	 * {@link ParallelScannerProvider#parallelismFor(StorageProfile)} so the bulk scanner respects the same HDD=1,
	 * SSD=8 mapping the cross-platform parallel scanner uses.
	 */
	public static ScannerProvider bulkScannerProvider() {
		return new ScannerProvider() {
			@Override
			public boolean matchesPreference(ScanStrategy preference) {
				return preference == ScanStrategy.AUTO || preference == ScanStrategy.BULK;
			}

			@Override
			public boolean canScan(Volume volume) {
				if (!MacBulkScanner.isAvailable())
					return false;
				// Network filesystems benefit from the latency-hiding fan-out of
				// ParallelScannerProvider (parallelism=16) more than from getattrlistbulk's
				// per-syscall amortisation, so let them fall through to the parallel walker.
				return volume.storageProfile() != StorageProfile.NETWORK;
			}

			@Override
			public Scanner createScanner(Volume volume, ScanStrategy preference) {
				int p = ParallelScannerProvider.parallelismFor(volume.storageProfile());
				return new MacBulkScanner(p);
			}

			@Override
			public String label(Volume volume, ScanStrategy preference) {
				int p = ParallelScannerProvider.parallelismFor(volume.storageProfile());
				return "Bulk (" + p + ")";
			}

			@Override
			public String description(Volume volume, ScanStrategy preference) {
				int p = ParallelScannerProvider.parallelismFor(volume.storageProfile());
				return "Reads directory metadata in bulk via getattrlistbulk(2) — ~500–800 entries per syscall instead of one stat per file — and recurses on a ForkJoinPool sized to the storage profile ("
						+ p + " readers).";
			}
		};
	}
}
