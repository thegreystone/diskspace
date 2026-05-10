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
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import se.hirt.diskspace.platform.Capabilities;
import se.hirt.diskspace.ui.MainWindow;
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.*;

public final class App extends Application {

	/**
	 * Captured as the first <clinit> action of this class, so for native-image binaries it represents process entry, and for JVM runs the
	 * JVM-startup-to-class-load delta is the only thing in front of it. Used as the t=0 baseline in startup-timing log lines.
	 */
	private static final long MAIN_START_NANOS = System.nanoTime();

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

		// On Windows, offer to relaunch elevated so the MFT scanner becomes available.
		// Defer until after the main window is on screen so the dialog parents to it
		// rather than appearing standalone before anything has rendered.
		Platform.runLater(this::maybeOfferElevation);
	}

	/**
	 * If we're on Windows and not already elevated, ask the user once per launch whether they'd like to restart as administrator. Yes →
	 * {@code ShellExecute("runas")} relaunches the current command line elevated and we exit. No → continue with the parallel scanner.
	 * <p>Skipped silently when {@link Capabilities#ELEVATION} reports unavailable
	 * (non-Windows native-image, or JVM dev mode where the {@code @CFunction} bindings aren't linked). Skipped when
	 * {@code -Ddiskspace.skipElevationPrompt=true} is set (handy for unattended runs).
	 */
	private void maybeOfferElevation() {
		Capabilities.Elevation elev = Capabilities.ELEVATION;
		if (!elev.isAvailable())
			return;
		if (elev.isElevated())
			return;
		if (Boolean.getBoolean("diskspace.skipElevationPrompt"))
			return;
		Alert alert = new Alert(AlertType.CONFIRMATION,
				"DiskSpace can scan NTFS volumes much faster (via the MFT scanner) when run as " + "administrator. Without elevation it falls back to the directory-walking " + "scanner.\n\nRestart as administrator now?",
				ButtonType.YES, ButtonType.NO);
		alert.setHeaderText("Run as administrator for faster scanning?");
		alert.setTitle("DiskSpace");
		alert.showAndWait().ifPresent(choice -> {
			if (choice == ButtonType.YES) {
				if (elev.relaunchElevated()) {
					requestQuit();
				} else {
					// User declined UAC, or the spawn failed for some other reason. Stay open.
					Alert err = new Alert(AlertType.WARNING,
							"Could not restart as administrator. Continuing without elevation.\n" + "You can launch DiskSpace yourself from an elevated shell to enable MFT scanning.",
							ButtonType.OK);
					err.setHeaderText("Elevation declined");
					err.setTitle("DiskSpace");
					err.showAndWait();
				}
			}
		});
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

	/**
	 * Resolved at app startup so crash logs and the default log file have a known-writable destination. See {@link #resolveTempDir()} for
	 * the fallback chain.
	 */
	private static final Path TEMP_DIR = resolveTempDir();

	public static void main(String[] args) {
		applyDebugFlagIfPresent(args);
		installCrashHandler();
		configureLoggingFromSystemProperty();
		Logger.getLogger("se.hirt.diskspace").info(() -> String.format("DiskSpace main entered (%d ms since process start)",
				(System.nanoTime() - MAIN_START_NANOS) / 1_000_000));
		installShutdownNoiseFilter();
		maybeStartJfrRecording();
		launch(args);
	}

	/**
	 * Recognises {@code -debug} (or {@code --debug}) as a one-flag shorthand for the two properties our diagnostic flow normally needs:
	 * <ul>
	 *   <li>{@code diskspace.log.level=FINE} — turns on FINE logging to {@code <TEMP>/diskspace.log}</li>
	 *   <li>{@code diskspace.jfr.file=…} — auto-starts a JFR recording, dumped on exit</li>
	 * </ul>
	 * <p>Either property already set by the user wins (we don't clobber explicit choices).
	 * The flag itself is left in {@code args} so {@code launch(args)} sees it; JavaFX
	 * ignores unknown CLI arguments.
	 * <p>Use:
	 * <pre>
	 *   DiskSpace.exe -debug                    (native binary)
	 *   mvn javafx:run -Dexec.args="-debug"     (JVM dev)
	 * </pre>
	 */
	private static void applyDebugFlagIfPresent(String[] args) {
		boolean debug = false;
		for (String a : args) {
			if ("-debug".equals(a) || "--debug".equals(a)) {
				debug = true;
				break;
			}
		}
		if (!debug)
			return;
		if (System.getProperty("diskspace.log.level") == null) {
			System.setProperty("diskspace.log.level", "FINE");
		}
		if (System.getProperty("diskspace.jfr.file") == null) {
			boolean inNativeImage = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
			System.setProperty("diskspace.jfr.file", inNativeImage ? "diskspace-native.jfr" : "diskspace.jfr");
		}
	}

	/**
	 * Starts a JFR recording programmatically when {@code -Ddiskspace.jfr.file=PATH} is set on the command line — typically from the
	 * {@code -debug} shorthand wired up in {@link #applyDebugFlagIfPresent(String[])}, but also usable directly.
	 * <p>We drive the JFR API instead of {@code -XX:StartFlightRecording=...} because
	 * Substrate VM (GraalVM 21 LTS) doesn't honour that command-line flag in native-image mode — both the {@code -XX:} runtime form and the
	 * {@code -R:} build-time baked-in form are silently no-ops.
	 * <p>On HotSpot the API path works as expected. On Gluon Substrate native images the
	 * API is also broken right now: {@code new Recording().start()} throws "Flight Recorder is not supported on this VM" even when
	 * {@code --enable-monitoring=jfr} was passed at build time (our {@code native-jfr} profile). Tracked at
	 * <a href="https://github.com/gluonhq/substrate/issues/1354">gluonhq/substrate#1354</a>;
	 * minimal reproducer at
	 * <a href="https://github.com/thegreystone/jfr-gluonfx-repro">thegreystone/jfr-gluonfx-repro</a>.
	 * The catch block below swallows that exception, so once Gluon picks up the upstream fix this code path will Just Work in native
	 * binaries too.
	 * <p>If JFR isn't compiled into the binary (default {@code native} build), the
	 * {@code Recording} call throws and we swallow it — JFR is strictly opt-in. Dump-on-exit is enabled so the file appears when the user
	 * closes the app normally.
	 */
	private static void maybeStartJfrRecording() {
		String filename = System.getProperty("diskspace.jfr.file");
		if (filename == null)
			return;
		try {
			jdk.jfr.Recording r = new jdk.jfr.Recording();
			r.setName("diskspace");
			r.setDestination(java.nio.file.Paths.get(filename).toAbsolutePath());
			r.setDumpOnExit(true);
			r.start();
			Logger.getLogger("se.hirt.diskspace").info(() -> "JFR recording started -> " + r.getDestination());
		} catch (Throwable t) {
			// JFR not compiled into this binary (default native build does not include
			// --enable-monitoring=jfr) or the runtime rejected the request. Either way,
			// skip silently — JFR is opt-in.
			Logger.getLogger("se.hirt.diskspace").fine(() -> "JFR auto-start skipped: " + t);
		}
	}

	/**
	 * Walks a list of candidate temp directories, returning the first one that exists, is a directory, and lets us create + delete a probe
	 * file. Order:
	 * <ol>
	 *   <li>{@code java.io.tmpdir} — Java's already-resolved value for the platform.</li>
	 *   <li>Env vars {@code TEMP}, {@code TMP}, {@code TMPDIR}.</li>
	 *   <li>Windows fallbacks: {@code %LOCALAPPDATA%\Temp}, {@code %USERPROFILE%\AppData\Local\Temp},
	 *       {@code C:\Windows\Temp}.</li>
	 *   <li>POSIX fallbacks: {@code /tmp}, {@code /var/tmp}.</li>
	 *   <li>Current working directory as last resort.</li>
	 * </ol>
	 * Probing rather than just checking existence catches read-only or quota-exhausted cases.
	 */
	private static Path resolveTempDir() {
		List<String> candidates = new ArrayList<>();
		candidates.add(System.getProperty("java.io.tmpdir"));
		candidates.add(System.getenv("TEMP"));
		candidates.add(System.getenv("TMP"));
		candidates.add(System.getenv("TMPDIR"));
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null)
			candidates.add(localAppData + "\\Temp");
		String userProfile = System.getenv("USERPROFILE");
		if (userProfile != null)
			candidates.add(userProfile + "\\AppData\\Local\\Temp");
		candidates.add("C:\\Windows\\Temp");
		candidates.add("/tmp");
		candidates.add("/var/tmp");
		candidates.add(".");

		for (String c : candidates) {
			if (c == null || c.isBlank())
				continue;
			try {
				Path p = Paths.get(c);
				if (!Files.isDirectory(p))
					continue;
				Path probe = Files.createTempFile(p, "diskspace-tmp-", ".probe");
				Files.deleteIfExists(probe);
				return p.toAbsolutePath();
			} catch (Exception ignore) {
				// not writable or otherwise inaccessible — try the next
			}
		}
		return Paths.get(".").toAbsolutePath();
	}

	/**
	 * Installs an uncaught-exception handler that always writes a stack trace to {@code <TEMP_DIR>/diskspace-crash.log}. This is the safety
	 * net for native-image builds where the binary is a Windows GUI-subsystem .exe with no attached console — without it, a crash on
	 * startup or a swallowed exception deep in a scan thread leaves the user staring at a frozen UI with nothing to share.
	 */
	private static void installCrashHandler() {
		Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
			try {
				Path p = TEMP_DIR.resolve("diskspace-crash.log");
				StringWriter sw = new StringWriter();
				try (PrintWriter pw = new PrintWriter(sw)) {
					pw.println("=== " + Instant.now() + " thread=" + thread.getName() + " ===");
					ex.printStackTrace(pw);
				}
				Files.writeString(p, sw.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException ignore) {
				// best-effort — there's nowhere else to put it
			}
		});
	}

	/**
	 * JavaFX 21 has a known Glass shutdown race on macOS where {@code Toolkit.checkFxUserThread} can throw an {@code IllegalStateException}
	 * from a window-destroy notification while we're already exiting. The exception is harmless — the JVM is on its way out — but the stack
	 * trace looks alarming in the console. Silence it once we've started shutting down.
	 * <p>Wraps whatever handler {@link #installCrashHandler()} installed previously, so genuine crashes still land in
	 * {@code <TEMP_DIR>/diskspace-crash.log} — only the macOS shutdown noise is dropped.
	 */
	private static void installShutdownNoiseFilter() {
		Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
			if (shuttingDown && ex instanceof IllegalStateException && ex.getMessage() != null && ex.getMessage()
					.contains("Not on FX application thread")) {
				return;
			}
			if (prev != null)
				prev.uncaughtException(t, ex);
			else
				ex.printStackTrace();
		});
	}

	/**
	 * When {@code -Ddiskspace.log.level=FINE} (or any valid {@link Level}) is passed, route logs from {@code se.hirt.diskspace.*} to
	 * handlers at that level. Two handlers are installed:
	 * <ul>
	 *   <li>A {@link ConsoleHandler} — visible during {@code mvn javafx:run}; silently dropped
	 *       for native-image GUI-subsystem builds where stdout has nowhere to go.</li>
	 *   <li>A {@link FileHandler} — appends to {@code <TEMP_DIR>/diskspace.log} by default,
	 *       overridable with {@code -Ddiskspace.log.file=PATH}. Required for native-image
	 *       builds; harmless otherwise.</li>
	 * </ul>
	 * Inert when the level property is unset, so this stays safely shipped in release builds.
	 */
	private static void configureLoggingFromSystemProperty() {
		String levelStr = System.getProperty("diskspace.log.level");
		if (levelStr == null || levelStr.isBlank())
			return;
		Level level;
		try {
			level = Level.parse(levelStr.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			System.err.println("Invalid diskspace.log.level: '" + levelStr + "'");
			return;
		}
		// Set a compact format before constructing handlers so SimpleFormatter picks it up.
		if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
			System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tT.%1$tL] %4$-6s %3$s: %5$s%6$s%n");
		}
		SimpleFormatter fmt = new SimpleFormatter();

		Logger pkg = Logger.getLogger("se.hirt.diskspace");
		pkg.setLevel(level);
		pkg.setUseParentHandlers(false);

		ConsoleHandler ch = new ConsoleHandler();
		ch.setLevel(level);
		ch.setFormatter(fmt);
		pkg.addHandler(ch);

		String fileStr = System.getProperty("diskspace.log.file");
		if (fileStr == null || fileStr.isBlank()) {
			fileStr = TEMP_DIR.resolve("diskspace.log").toString();
		}
		try {
			FileHandler fh = new FileHandler(fileStr, true);
			fh.setLevel(level);
			fh.setFormatter(fmt);
			pkg.addHandler(fh);
			System.err.println("DiskSpace logging at " + level + " to " + fileStr);
		} catch (IOException e) {
			System.err.println("Failed to open log file " + fileStr + ": " + e);
		}
	}
}
