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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link ParallelDirectoryScanner#deviceOf}. {@code sun.nio.fs.UnixFileKey#toString()} renders the
 * device as bare lowercase hex with no {@code 0x} prefix (e.g. {@code "(dev=fd00,ino=2)"}); the device guard that keeps a
 * scan on the root filesystem depends on parsing it as base 16. Parsing it with {@code Long.decode} instead threw for
 * letter-containing values (typical of btrfs/LVM/overlay roots), collapsing the root device to -1 and silently
 * descending into {@code /proc}, {@code /sys} and other virtual filesystems (issue #21).
 * <p>
 * {@code deviceOf} reads {@code fileKey.toString()}, so these tests pass the formatted strings directly.
 */
class ParallelDirectoryScannerTest {

	@Test
	void parsesLetterContainingHexInsteadOfThrowing() {
		// The exact failure from issue #21: a btrfs/LVM root whose st_dev hex contains a–f.
		// Long.decode("fd00") throws -> -1; base-16 parsing must yield the real device.
		assertEquals(0xfd00L, ParallelDirectoryScanner.deviceOf("(dev=fd00,ino=2)"));
	}

	@Test
	void parsesDigitOnlyHexAsBase16() {
		// macOS-style digit-only device. Long.decode parsed it as decimal (wrong number, but the
		// equality-only guard tolerated it); base-16 now gives the correct value.
		assertEquals(0x1000004L, ParallelDirectoryScanner.deviceOf("(dev=1000004,ino=12)"));
	}

	@Test
	void distinguishesRootFromVirtualFilesystem() {
		// What the guard actually relies on: the root device and a different mount compare unequal,
		// and two paths on the same device compare equal.
		long root = ParallelDirectoryScanner.deviceOf("(dev=fd00,ino=256)");
		long proc = ParallelDirectoryScanner.deviceOf("(dev=16,ino=1)");
		long sameAsRoot = ParallelDirectoryScanner.deviceOf("(dev=fd00,ino=999)");

		assertTrue(root >= 0, "root device must parse to a non-negative value to keep the guard enabled");
		assertNotEquals(root, proc, "a separately-mounted filesystem must not match the root device");
		assertEquals(root, sameAsRoot, "same device, different inode must match");
	}

	@Test
	void returnsMinusOneForUnparseableKeys() {
		assertEquals(-1L, ParallelDirectoryScanner.deviceOf(null), "null fileKey (Windows)");
		assertEquals(-1L, ParallelDirectoryScanner.deviceOf("no device here"), "missing dev= token");
		assertEquals(-1L, ParallelDirectoryScanner.deviceOf("(dev=,ino=2)"), "empty device field");
		assertEquals(-1L, ParallelDirectoryScanner.deviceOf("(dev=xyz,ino=2)"), "non-hex device field");
	}
}
