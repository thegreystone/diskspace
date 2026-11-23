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
package se.hirt.diskspace.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import se.hirt.diskspace.ui.theme.ColorScheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.diskspace.ui.FxTestSupport.onFxThread;

@DisabledOnOs(OS.WINDOWS) // relies on the Unix device check in Volume.isSeparateMount
class PickerViewTest {

	@BeforeAll
	static void startFx() throws InterruptedException {
		FxTestSupport.startToolkit();
	}

	@Test
	void rootThatIsNotItsOwnFilesystemIsDroppedAndTheListSettles(@TempDir Path dir) throws Exception {
		Path notAMount = Files.createDirectory(dir.resolve("not-a-mount"));
		Path fsRoot = dir.getRoot();
		PickerView picker = onFxThread(() -> new PickerView(ColorScheme.DARK, v -> {
		}, () -> List.of(fsRoot, notAMount)));
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (!onFxThread(picker::isEnumerationSettled)) {
				assertTrue(System.nanoTime() < deadline, "picker rows did not settle within 20 seconds");
				Thread.sleep(50);
			}
			assertEquals(1, onFxThread(picker::diskRowCount), "only the filesystem root should remain");
		} finally {
			onFxThread(() -> {
				picker.dispose();
				return null;
			});
		}
	}
}
