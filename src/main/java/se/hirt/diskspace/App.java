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
package se.hirt.diskspace;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import se.hirt.diskspace.ui.MainWindow;
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class App extends Application {

	private static volatile MainWindow mainWindow;
	private static volatile boolean shuttingDown;

	@Override
	public void start(Stage stage) {
		ColorScheme scheme = ColorScheme.DARK;

		MainWindow main = new MainWindow(scheme);
		mainWindow = main;
		Scene scene = new Scene(main.getRoot(), 1100, 700);
		scene.setFill(scheme.background());
		if (scheme.stylesheet() != null) {
			var url = App.class.getResource(scheme.stylesheet());
			if (url != null) {
				scene.getStylesheets().add(url.toExternalForm());
			}
		}

		stage.setTitle("DiskSpace");
		stage.getIcons().addAll(loadAppIcons());
		stage.setScene(scene);
		stage.setOnCloseRequest(ev -> requestQuit());
		stage.show();
	}

	/**
	 * Centralized graceful quit. Cancels in-flight scans and stops per-view animation timers, then defers {@link Platform#exit()} to the
	 * next pulse so the current event finishes before the toolkit starts tearing down. Avoids the JavaFX 21 macOS Glass shutdown race where
	 * {@code Toolkit.checkFxUserThread} throws while {@code Window.hide} runs during destroy notifications.
	 */
	public static void requestQuit() {
		if (shuttingDown)
			return;
		shuttingDown = true;
		MainWindow w = mainWindow;
		if (w != null) {
			try {
				w.shutdown();
			} catch (RuntimeException ignored) {
				// Swallow shutdown-path exceptions — we're tearing down anyway.
			}
		}
		Platform.runLater(Platform::exit);
	}

	/**
	 * Loads the runtime window icons in all available pre-rendered sizes. JavaFX picks the closest match for each display context (titlebar
	 * at 16, taskbar at 32, alt-tab at 256). The bundle icon (.exe / .app / .desktop) is a separate, build-time artifact.
	 */
	private static java.util.List<Image> loadAppIcons() {
		java.util.List<Image> icons = new java.util.ArrayList<>();
		for (int size : new int[] {16, 32, 64, 128, 256}) {
			var url = App.class.getResource("/se/hirt/diskspace/icon-" + size + ".png");
			if (url != null) {
				icons.add(new Image(url.toExternalForm()));
			}
		}
		return icons;
	}

	public static void main(String[] args) {
		configureLoggingFromSystemProperty();
		installShutdownNoiseFilter();
		launch(args);
	}

	/**
	 * JavaFX 21 has a known Glass shutdown race on macOS where {@code Toolkit.checkFxUserThread} can throw an
	 * {@code IllegalStateException} from a window-destroy notification while we're already exiting. The exception is harmless — the JVM
	 * is on its way out — but the stack trace looks alarming in the console. Silence it once we've started shutting down.
	 */
	private static void installShutdownNoiseFilter() {
		Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
			if (shuttingDown && ex instanceof IllegalStateException && ex.getMessage() != null && ex.getMessage().contains("Not on FX application thread")) {
				return;
			}
			if (prev != null)
				prev.uncaughtException(t, ex);
			else
				ex.printStackTrace();
		});
	}

	/**
	 * When {@code -Ddiskspace.log.level=FINE} (or any valid {@link Level}) is passed, route logs from {@code se.hirt.diskspace.*} to a
	 * console handler at that level. Inert when the property is unset, so this stays safely shipped in release builds.
	 */
	private static void configureLoggingFromSystemProperty() {
		String levelStr = System.getProperty("diskspace.log.level");
		if (levelStr == null || levelStr.isBlank())
			return;
		try {
			Level level = Level.parse(levelStr.trim().toUpperCase());
			// Set a compact format before constructing the handler so SimpleFormatter picks it up.
			if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
				System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tT.%1$tL] %4$-6s %3$s: %5$s%6$s%n");
			}
			ConsoleHandler handler = new ConsoleHandler();
			handler.setLevel(level);
			handler.setFormatter(new SimpleFormatter());
			Logger pkg = Logger.getLogger("se.hirt.diskspace");
			pkg.setLevel(level);
			pkg.setUseParentHandlers(false);
			pkg.addHandler(handler);
		} catch (IllegalArgumentException e) {
			System.err.println("Invalid diskspace.log.level: '" + levelStr + "'");
		}
	}
}
