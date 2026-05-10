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

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.Platform;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cross-platform registry for platform-specific capabilities. Three small interfaces — one per concern (MFT-style fast scanner, UAC
 * elevation, native bulk storage classifier) — each with default-method "not available" no-ops, and a static initializer that picks either
 * the Windows-specific implementations or the {@code NOOP} singletons. Cross-platform code (e.g. {@code Scanner.forVolume},
 * {@code App.maybeOfferElevation}, {@code StorageProfileProbe.probeMany}) consumes capabilities through this class only, never by
 * referencing the underlying Windows-only classes ({@code MftScanner}, {@code WindowsElevation}, {@code Win32StorageProbe}) directly.
 *
 * <h3>Why a registry instead of inline platform checks</h3>
 * Each of the three Windows-only classes is annotated {@code @Platforms(Platform.WINDOWS.class)}, so it physically does not exist in a
 * non-Windows native-image. Routing all access through this class means: (1) the platform check happens in exactly one place, (2) callers
 * cannot accidentally re-introduce a direct unguarded reference — that's a build-time error, and (3) future Linux/macOS native fast paths
 * (e.g. an NTFS-3G backed {@code Mft} on Linux) plug in by adding another impl class and another branch in the static initializer below;
 * the cross-platform call sites stay platform-blind.
 *
 * <h3>Operand order is load-bearing</h3>
 * {@link Platform#includedIn(Class)} delegates to {@code ImageSingletons.lookup}, which throws on a plain JVM ({@code mvn javafx:run}). The
 * {@link ImageInfo#inImageRuntimeCode()} half of the gate must be evaluated first to short-circuit before {@code Platform.includedIn} is
 * touched — reversing the {@code &&} would crash dev mode at startup. On a native-image build both halves fold to constants during
 * Substrate's analysis, so the dead branch (and its reference to {@link WindowsCapabilities}) is dead-stripped before the points-to
 * analysis tries to resolve any {@code @Platforms(WINDOWS)}-gated type on a non-matching platform.
 */
public final class Capabilities {

	private Capabilities() {
	}

	/**
	 * MFT-style fast directory scanner. Today only the Windows NTFS implementation exists ({@code FSCTL_ENUM_USN_DATA} +
	 * {@code GetFileInformationByHandleEx}); the interface is platform-neutral so a future Linux NTFS-3G fast path or APFS catalog-tree
	 * implementation can plug in without touching cross-platform callers.
	 */
	public interface Mft {
		Mft NOOP = new Mft() {
		};

		default boolean isAvailable() {
			return false;
		}

		default boolean canScan(Volume volume) {
			return false;
		}

		default Scanner createScanner() {
			throw new UnsupportedOperationException("MFT scanner is not available on this platform");
		}
	}

	/** UAC-style admin elevation. Currently Windows-only via {@code ShellExecuteW("runas")}. */
	public interface Elevation {
		Elevation NOOP = new Elevation() {
		};

		default boolean isAvailable() {
			return false;
		}

		default boolean isElevated() {
			return false;
		}

		default boolean relaunchElevated() {
			return false;
		}
	}

	/**
	 * Fast OS-native bulk classifier for SSD/HDD/NETWORK. The slow cross-platform paths (PowerShell on Windows JVM mode, {@code diskutil} on
	 * macOS, {@code findmnt}/{@code lsblk} on Linux) live in {@code StorageProfileProbe} and stay there; this interface only covers the
	 * native-image fast shortcut.
	 */
	public interface StorageProbe {
		StorageProbe NOOP = new StorageProbe() {
		};

		default boolean isAvailable() {
			return false;
		}

		default Map<Path, StorageProfile> probeAll(List<Volume> volumes) {
			return Collections.emptyMap();
		}
	}

	public static final Mft MFT;
	public static final Elevation ELEVATION;
	public static final StorageProbe STORAGE_PROBE;

	static {
		// inImageRuntimeCode FIRST: Platform.includedIn → ImageSingletons.lookup, which
		// throws on a plain JVM. Short-circuit keeps mvn javafx:run alive.
		if (ImageInfo.inImageRuntimeCode() && Platform.includedIn(Platform.WINDOWS.class)) {
			MFT = WindowsCapabilities.mft();
			ELEVATION = WindowsCapabilities.elevation();
			STORAGE_PROBE = WindowsCapabilities.storageProbe();
		} else {
			MFT = Mft.NOOP;
			ELEVATION = Elevation.NOOP;
			STORAGE_PROBE = StorageProbe.NOOP;
		}
	}
}
