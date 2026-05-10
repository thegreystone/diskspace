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
import se.hirt.diskspace.scan.MacStorageProbe;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * macOS-only implementations of the platform abstractions. The whole class is annotated {@code @Platforms(DARWIN)} so
 * it physically does not exist in non-Darwin native-image builds; {@link Capabilities}'s static initializer references
 * {@link #storageProbe()} only behind a build-time-foldable Darwin guard, so on Windows / Linux the reference is
 * dead-stripped before the analyzer tries to load this class or anything it imports (notably the
 * {@code @Platforms(DARWIN)}-gated {@link MacStorageProbe} and {@link se.hirt.diskspace.scan.Darwin} classes).
 * <p>Today this only wires up the storage probe — Mac doesn't have an MFT
 * equivalent and doesn't need a UAC-style elevation prompt — so {@link Capabilities#ELEVATION} stays NOOP and
 * {@link Capabilities#MFT_PROVIDER} stays {@code null} on this platform. A future macOS bulk-scanner provider would
 * register through this same factory.
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
}
