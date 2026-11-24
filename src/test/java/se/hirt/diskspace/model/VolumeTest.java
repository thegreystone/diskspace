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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeTest {

	@Test
	void filesystemRootIsAlwaysSeparate() {
		for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
			assertTrue(Volume.isSeparateMount(root), root.toString());
		}
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void mountOnAnotherDeviceIsSeparateOnLinux() {
		assertTrue(Volume.isSeparateMount(Path.of("/proc")));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void mountOnAnotherDeviceIsSeparateOnMac() {
		assertTrue(Volume.isSeparateMount(Path.of("/dev")));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void plainDirectoryIsNotSeparate(@TempDir Path dir) throws IOException {
		Path sub = Files.createDirectory(dir.resolve("not-a-mount"));
		assertFalse(Volume.isSeparateMount(sub));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void symlinkIsNotSeparate(@TempDir Path dir) throws IOException {
		// Like macOS's /Volumes/Macintosh HD -> /, which must not list the boot disk twice.
		Path link = Files.createSymbolicLink(dir.resolve("boot-volume"), Path.of("/"));
		assertFalse(Volume.isSeparateMount(link));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void fileIsNotSeparate(@TempDir Path dir) throws IOException {
		// Like a container's bind-mounted /etc/hosts, which sits on another device than /etc.
		assertFalse(Volume.isSeparateMount(Files.createFile(dir.resolve("hosts"))));
	}

	@Test
	void missingPathIsLeftToResolve(@TempDir Path dir) {
		assertTrue(Volume.isSeparateMount(dir.resolve("gone")));
	}

	@Test
	void volumeUnderVolumesIsNamedByItsMountPointWithoutTouchingIt() {
		// No such volume exists here: the name must come from the path alone.
		assertEquals("USB Stick", Volume.resolveDisplayName(Path.of("/Volumes/USB Stick"), "/dev/disk9s1"));
	}

	@Test
	void otherFoldersKeepTheFallbackName(@TempDir Path dir) {
		assertEquals("fallback", Volume.resolveDisplayName(dir, "fallback"));
	}
}
