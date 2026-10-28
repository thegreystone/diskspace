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

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real JavaFX toolkit and propagates failures from the application thread to JUnit.
 * Requires a display; on headless Linux, run {@code xvfb-run --auto-servernum mvn test}.
 */
final class FxTestSupport {
	private static boolean toolkitStarted;

	private FxTestSupport() {
	}

	static synchronized void startToolkit() throws InterruptedException {
		if (toolkitStarted)
			return;
		CountDownLatch ready = new CountDownLatch(1);
		try {
			Platform.startup(ready::countDown);
		} catch (IllegalStateException alreadyStarted) {
			// Another test may have started the toolkit; verify that its application thread still responds.
			Platform.runLater(ready::countDown);
		}
		assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start within 10 seconds");
		Platform.setImplicitExit(false);
		toolkitStarted = true;
	}

	static <T> T onFxThread(Callable<T> body) throws Exception {
		if (Platform.isFxApplicationThread())
			return body.call();
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				result.set(body.call());
			} catch (Throwable t) {
				failure.set(t);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS), "FX thread did not finish the test body within 10 seconds");
		if (failure.get() instanceof Exception ex)
			throw ex;
		if (failure.get() instanceof Error error)
			throw error;
		if (failure.get() != null)
			throw new AssertionError(failure.get());
		return result.get();
	}
}
