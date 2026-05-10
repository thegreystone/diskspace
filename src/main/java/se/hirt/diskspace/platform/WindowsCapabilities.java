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
import se.hirt.diskspace.scan.MftScanner;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.scan.Win32StorageProbe;
import se.hirt.diskspace.scan.WindowsElevation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Windows-only implementations of the {@link Capabilities} interfaces. The whole class is annotated {@code @Platforms(WINDOWS)} so it
 * physically does not exist in non-Windows native-image builds; {@link Capabilities}' static initializer references {@link #mft()} /
 * {@link #elevation()} / {@link #storageProbe()} only inside a build-time-foldable Windows guard, so on non-Windows the references are
 * dead-stripped before the analyzer tries to load this class or anything it imports.
 */
@Platforms(Platform.WINDOWS.class)
final class WindowsCapabilities {

	private WindowsCapabilities() {
	}

	static Capabilities.Mft mft() {
		return new Capabilities.Mft() {
			@Override
			public boolean isAvailable() {
				return MftScanner.isAvailable();
			}

			@Override
			public boolean canScan(Volume volume) {
				return MftScanner.canScan(volume);
			}

			@Override
			public Scanner createScanner() {
				return new MftScanner();
			}
		};
	}

	static Capabilities.Elevation elevation() {
		return new Capabilities.Elevation() {
			@Override
			public boolean isAvailable() {
				return WindowsElevation.isAvailable();
			}

			@Override
			public boolean isElevated() {
				return WindowsElevation.isElevated();
			}

			@Override
			public boolean relaunchElevated() {
				return WindowsElevation.relaunchElevated();
			}
		};
	}

	static Capabilities.StorageProbe storageProbe() {
		return new Capabilities.StorageProbe() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public Map<Path, StorageProfile> probeAll(List<Volume> volumes) {
				return Win32StorageProbe.probeAll(volumes);
			}
		};
	}
}
