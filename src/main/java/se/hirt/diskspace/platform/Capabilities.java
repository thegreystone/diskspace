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
import se.hirt.diskspace.scan.ScannerProvider;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cross-platform registry for the platform-specific bits the rest of the codebase consumes generically: UAC elevation,
 * the native bulk storage classifier, and the optional MFT-style fast scanner. Each Windows-only implementation is
 * loaded only on a Windows native-image binary; otherwise the field holds either a "not available" no-op singleton (for
 * the interface-typed slots) or {@code null} (for {@link #NATIVE_SCANNER_PROVIDER}, which the scanner registry treats
 * as "no platform-native scanner here, use the parallel fallback"). Cross-platform code never references the underlying
 * {@code WindowsElevation} / {@code Win32StorageProbe} / {@code MftScanner} classes directly; those are annotated
 * {@code @Platforms(WINDOWS)} and a direct cross-platform reference is a build-time error.
 * <h3>One platform check, in one place</h3>
 * The static initializer below contains the only {@link Platform#includedIn(Class)} call in the cross-platform code
 * path. It selects between {@link WindowsCapabilities}'s factory methods on a Windows native-image and the no-op
 * fallbacks everywhere else.
 * <h3>Operand order is load-bearing</h3>
 * {@link Platform#includedIn(Class)} delegates to {@code ImageSingletons.lookup}, which throws on a plain JVM
 * ({@code mvn javafx:run}). The {@link ImageInfo#inImageRuntimeCode()} half of the gate must be evaluated first to
 * short-circuit before {@code Platform.includedIn} is touched; reversing the {@code &&} crashes dev mode at startup. On
 * a native-image build both halves fold to build-time constants during Substrate's analysis, so the dead branch (and
 * every reference inside it to {@link WindowsCapabilities} and its imports) is dead-stripped before the points-to
 * analysis attempts to resolve any {@code @Platforms(WINDOWS)}-gated type on a non-matching platform.
 * <h3>Why the gate must be inline, not via a static-final flag</h3>
 * Substrate folds {@code Platform.includedIn(WINDOWS)} when it appears textually inside a method body; it does NOT fold
 * cross-class field reads of a {@code static final boolean} derived from it (that would require build-time class
 * initialization, which we don't request). An earlier draft of this code routed the platform check through a public
 * {@code IS_WINDOWS_NATIVE} field, and the analyzer reached {@link WindowsCapabilities} on macOS / Linux. Reverting to
 * inline {@code Platform.includedIn} fixed it; do not extract this check into a constant.
 */
public final class Capabilities {

	private Capabilities() {
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

		/**
		 * Defense-in-depth: when running elevated, irreversibly drop every privilege except the backup-read ones the
		 * MFT scanner needs, so no dormant admin privilege can be enabled later. No-op on platforms without UAC
		 * elevation and when not already elevated.
		 */
		default void dropToBackupPrivileges() {
		}
	}

	/**
	 * Fast OS-native bulk classifier for SSD/HDD/NETWORK. The slow cross-platform paths (PowerShell on Windows JVM
	 * mode, {@code diskutil} on macOS, {@code findmnt}/{@code lsblk} on Linux) live in {@code StorageProfileProbe} and
	 * stay there; this interface only covers the native-image fast shortcut.
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

	public static final Elevation ELEVATION;
	public static final StorageProbe STORAGE_PROBE;

	/**
	 * Platform-native fast scanner provider, or {@code null} when no native fast path is available on this binary
	 * ({@code mvn javafx:run}, or a platform we haven't ported a scanner to yet). On Windows native-image this is the
	 * MFT scanner ({@code FSCTL_ENUM_USN_DATA} + bulk dir info); on macOS native-image it's the bulk scanner
	 * ({@code getattrlistbulk(2)}). {@link se.hirt.diskspace.scan.ScannerProviders} treats null as "skip this entry";
	 * never construct a sentinel non-null instance, because that would force the {@link ScannerProvider}'s
	 * implementation type to be reachable on platforms where it doesn't belong.
	 */
	public static final ScannerProvider NATIVE_SCANNER_PROVIDER;

	static {
		// Inline Platform.includedIn so Substrate folds it during analysis. Do NOT extract to a static-final flag;
		// see the class javadoc for why that breaks dead-stripping. inImageRuntimeCode FIRST so a plain JVM doesn't
		// trigger ImageSingletons.lookup (which throws when not running inside Substrate). Each branch references
		// exactly one platform-gated capabilities class — substrate dead-strips the others, so MacCapabilities's
		// imports of the @Platforms(DARWIN) Darwin/MacStorageProbe/MacBulkScanner classes never resolve on a Windows
		// build (and vice versa for WindowsCapabilities's Win32*/MftScanner imports on a Mac build).
		if (ImageInfo.inImageRuntimeCode() && Platform.includedIn(Platform.WINDOWS.class)) {
			ELEVATION = WindowsCapabilities.elevation();
			STORAGE_PROBE = WindowsCapabilities.storageProbe();
			NATIVE_SCANNER_PROVIDER = WindowsCapabilities.mftScannerProvider();
		} else if (ImageInfo.inImageRuntimeCode() && Platform.includedIn(Platform.DARWIN.class)) {
			// Mac has no UAC-style elevation; the native scanner is getattrlistbulk(2)-based.
			ELEVATION = Elevation.NOOP;
			STORAGE_PROBE = MacCapabilities.storageProbe();
			NATIVE_SCANNER_PROVIDER = MacCapabilities.bulkScannerProvider();
		} else {
			ELEVATION = Elevation.NOOP;
			STORAGE_PROBE = StorageProbe.NOOP;
			NATIVE_SCANNER_PROVIDER = null;
		}
	}
}
