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

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import se.hirt.diskspace.App;
import se.hirt.diskspace.AppInfo;
import se.hirt.diskspace.Launcher;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.ui.DiskView;
import se.hirt.diskspace.ui.PreferencesDialog;
import se.hirt.diskspace.ui.theme.Theme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Drives the real DiskSpace UI so GraalVM's tracing agent observes every screen, control,
 * visualization, overlay, theme, and JFR path.
 *
 * <p>Run with a throwaway {@code user.home} and {@code -Ddiskspace.settings.file=...} so the traced
 * session never touches user preferences. Building this class as a second native image and running
 * with {@code -XX:MissingRegistrationReportingMode=Exit} verifies that every UI path works inside a
 * real Native Image executable without missing reachability registrations.
 */
public final class TraceDriver {
	private static final long FX_TIMEOUT_SECONDS = 30;
	private static volatile boolean quitByClosingWindow = true;
	private static volatile boolean passDebugFlag = false;

	private TraceDriver() {
	}

	public static void main(String[] args) throws Exception {
		for (String arg : args) {
			if (arg.equals("--quit=exit")) {
				quitByClosingWindow = false;
			} else if (arg.equals("--quit=close")) {
				quitByClosingWindow = true;
			} else if (arg.equals("-debug") || arg.equals("--debug")) {
				passDebugFlag = true;
			} else {
				throw new IllegalArgumentException("Unknown option: " + arg);
			}
		}
		System.setProperty("diskspace.skipElevationPrompt", "true");
		Thread driver = new Thread(TraceDriver::drive, "trace-driver");
		driver.setDaemon(true);
		driver.start();
		String[] appArgs = passDebugFlag ? new String[]{"-debug"} : new String[0];
		Launcher.main(appArgs);
	}

	private static void drive() {
		Path fixtureRoot = null;
		boolean failed = false;
		try {
			fixtureRoot = createFixtureDirectory();
			awaitStage();
			settle(1500);

			// Verify bundled resources (app.properties + LICENSE.txt) are reachable.
			step("check bundled AppInfo resources", () -> {
				String version = AppInfo.version();
				String license = AppInfo.licenseText();
				if ("(unknown)".equals(version)) {
					throw new IllegalStateException("app.properties not bundled");
				}
				if (license.startsWith("(")) {
					throw new IllegalStateException("LICENSE.txt not bundled: " + license);
				}
				System.out.println("[trace] AppInfo version=" + version + ", runtime=" + AppInfo.runtimeDescription());
			});

			// --- PickerView interactions ---
			step("picker help overlay", () -> keyOnFx(KeyCode.ESCAPE));
			settle(400);
			step("picker about overlay", () -> keyOnFx(KeyCode.A));
			settle(400);
			step("picker license overlay", () -> keyOnFx(KeyCode.L));
			settle(400);
			step("close picker overlays", () -> {
				keyOnFx(KeyCode.ESCAPE);
				keyOnFx(KeyCode.ESCAPE);
				keyOnFx(KeyCode.ESCAPE);
			});
			settle(300);

			step("picker toggles (U/S/H/T)", () -> {
				keyOnFx(KeyCode.U);
				keyOnFx(KeyCode.S);
				keyOnFx(KeyCode.H);
				keyOnFx(KeyCode.T); // switch to light theme (loads light.css)
			});
			settle(400);
			step("picker switch back to dark", () -> {
				if (primaryScene().getStylesheets().stream().noneMatch(s -> s.contains("light.css"))) {
					throw new IllegalStateException("Expected light.css stylesheet after pressing T: "
							+ primaryScene().getStylesheets());
				}
				keyOnFx(KeyCode.T);
			});
			settle(300);

			step("picker context menu", () -> {
				TabPane tabs = find(TabPane.class);
				Tab selected = tabs.getSelectionModel().getSelectedItem();
				Node target = selected != null && selected.getContent() != null
						? selected.getContent()
						: primaryScene().getRoot();
				Event.fireEvent(target, new ContextMenuEvent(
						ContextMenuEvent.CONTEXT_MENU_REQUESTED, 200, 200, 300, 300, false, null));
			});
			settle(300);
			step("dismiss context menu", () -> {
				Scene scene = primaryScene();
				Event.fireEvent(scene.getRoot(), new MouseEvent(MouseEvent.MOUSE_PRESSED, 100, 100, 100, 100,
						MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null));
			});
			settle(200);

			// Open PreferencesDialog asynchronously (it uses showAndWait) and click OK from a helper pulse.
			System.out.println("[trace] preferences dialog");
			Platform.runLater(PreferencesDialog::show);
			settle(800);
			step("accept preferences dialog", () -> {
				for (Window w : Window.getWindows()) {
					if (w.isShowing() && w != primaryScene().getWindow() && w.getScene() != null
							&& w.getScene().getRoot() instanceof DialogPane pane) {
						Node ok = pane.lookupButton(ButtonType.OK);
						if (ok instanceof Button btn) {
							btn.fire();
							return;
						}
					}
				}
				throw new IllegalStateException("Preferences dialog did not appear");
			});
			settle(400);

			// --- DiskView interactions (scan a small fixture folder so it completes quickly) ---
			final Path scanDir = fixtureRoot;
			step("open DiskView tab on fixture folder", () -> {
				TabPane tabs = find(TabPane.class);
				Volume vol = Volume.from(scanDir);
				DiskView dv = new DiskView(vol, Theme.current());
				Tab tab = new Tab(vol.displayName());
				tab.setUserData(dv);
				tab.setContent(dv.getRoot());
				tab.setOnClosed(e -> dv.shutdown());
				int insertAt = Math.max(0, tabs.getTabs().size() - 1);
				tabs.getTabs().add(insertAt, tab);
				tabs.getSelectionModel().select(tab);
				dv.getRoot().requestFocus();
			});
			settle(1500);

			step("sunburst hover and click", () -> {
				Canvas c = find(Canvas.class);
				double cx = c.getWidth() * 0.5;
				double cy = c.getHeight() * 0.5;
				Event.fireEvent(c, new MouseEvent(MouseEvent.MOUSE_MOVED, cx + 80, cy, cx + 80, cy,
						MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
			});
			settle(300);

			step("switch to heatmap (V)", () -> keyOnFx(KeyCode.V));
			settle(600);
			step("switch to voronoi (V)", () -> keyOnFx(KeyCode.V));
			settle(1000);
			step("switch back to sunburst (V)", () -> keyOnFx(KeyCode.V));
			settle(500);

			step("disk view coloring, free-space, units, theme (C/H/U/T)", () -> {
				keyOnFx(KeyCode.C);
				keyOnFx(KeyCode.H);
				keyOnFx(KeyCode.U);
				keyOnFx(KeyCode.T);
			});
			settle(500);
			step("restore disk view theme & free-space", () -> {
				keyOnFx(KeyCode.T);
				keyOnFx(KeyCode.H);
				keyOnFx(KeyCode.C);
			});
			settle(400);

			step("table select, stage delete, and cancel staging", () -> {
				TableView<?> table = find(TableView.class);
				if (!table.getItems().isEmpty()) {
					table.requestFocus();
					table.getSelectionModel().select(0);
					keyOnFx(KeyCode.DELETE);
				}
			});
			settle(500);
			step("cancel staging pane", () -> {
				for (Node n : visibleNodes()) {
					if (n instanceof Button b && "Cancel".equals(b.getText())) {
						b.fire();
						return;
					}
				}
				throw new IllegalStateException("Delete staging pane Cancel button did not appear");
			});
			settle(300);

			step("copy path header to clipboard", () -> {
				for (Node n : visibleNodes()) {
					if (n instanceof Label l && l.getText() != null && l.getText().contains(scanDir.getFileName().toString())) {
						Event.fireEvent(l, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5,
								MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null));
						return;
					}
				}
			});
			settle(300);

			step("disk view overlays (Esc/A/L)", () -> {
				keyOnFx(KeyCode.ESCAPE);
				keyOnFx(KeyCode.A);
				keyOnFx(KeyCode.L);
			});
			settle(400);
			step("close disk view overlays", () -> {
				keyOnFx(KeyCode.ESCAPE);
				keyOnFx(KeyCode.ESCAPE);
				keyOnFx(KeyCode.ESCAPE);
			});
			settle(300);

			step("open + tab", () -> {
				TabPane tabs = find(TabPane.class);
				tabs.getSelectionModel().select(tabs.getTabs().size() - 1);
			});
			settle(400);

			System.out.println("[trace] finished walking DiskSpace UI, exiting");
		} catch (Throwable t) {
			failed = true;
			System.err.println("[trace] driver failed: " + t);
			t.printStackTrace();
		} finally {
			cleanupDirectory(fixtureRoot);
			final boolean hadFailure = failed;
			Platform.runLater(() -> quit(hadFailure));
		}
	}

	private static void quit(boolean hadFailure) {
		if (hadFailure) {
			System.exit(1);
			return;
		}
		if (!quitByClosingWindow) {
			App.requestQuit();
			return;
		}
		Window window = primaryScene().getWindow();
		Event.fireEvent(window, new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
		window.hide();
	}

	private static Path createFixtureDirectory() throws Exception {
		Path root = Files.createTempDirectory("diskspace-trace-");
		Path alpha = Files.createDirectory(root.resolve("alpha"));
		Path beta = Files.createDirectory(root.resolve("beta"));
		Path nested = Files.createDirectory(alpha.resolve("nested"));
		Files.write(alpha.resolve("a.bin"), new byte[64 * 1024]);
		Files.write(nested.resolve("n.bin"), new byte[32 * 1024]);
		Files.write(beta.resolve("b.bin"), new byte[48 * 1024]);
		Files.write(root.resolve("readme.txt"), "hello diskspace trace\n".getBytes());
		return root;
	}

	private static void cleanupDirectory(Path root) {
		if (root == null) return;
		try (var stream = Files.walk(root)) {
			stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try { Files.deleteIfExists(p); } catch (Exception ignored) {}
			});
		} catch (Exception ignored) {}
	}

	private static void awaitStage() throws Exception {
		for (int attempt = 0; attempt < 240; attempt++) {
			Thread.sleep(250);
			try {
				Boolean ready = onFx(() -> {
					for (Window w : Window.getWindows()) {
						if (w.isShowing() && w.getScene() != null) return true;
					}
					return false;
				});
				if (Boolean.TRUE.equals(ready)) return;
			} catch (IllegalStateException toolkitNotUpYet) {
				// Platform.runLater throws until the FX toolkit has started.
			}
		}
		throw new IllegalStateException("DiskSpace window never appeared");
	}

	private static void step(String name, FxAction action) throws Exception {
		System.out.println("[trace] " + name);
		onFx(() -> {
			action.run();
			return null;
		});
	}

	private static void keyOnFx(KeyCode code) {
		Scene scene = primaryScene();
		// Fire at the focused node (or scene root) so events traverse MainWindow / view handlers;
		// firing directly on Scene skips the scene graph and never reaches node-level handlers.
		EventTarget target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
		String ch = code.getChar();
		Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, ch, code.getName(),
				code, false, false, false, false));
		Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, ch, code.getName(),
				code, false, false, false, false));
	}

	private static <T> T find(Class<T> type) {
		for (Node n : visibleNodes()) {
			if (type.isInstance(n)) return type.cast(n);
		}
		throw new IllegalStateException("No " + type.getSimpleName() + " in the scene");
	}

	private static List<Node> visibleNodes() {
		List<Node> nodes = new ArrayList<>();
		for (Window w : Window.getWindows()) {
			Scene s = w.getScene();
			if (s != null) collect(s.getRoot(), nodes);
		}
		return nodes;
	}

	private static void collect(Node node, List<Node> nodes) {
		if (node == null || !node.isVisible()) return;
		nodes.add(node);
		if (node instanceof Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) collect(child, nodes);
		}
	}

	private static Scene primaryScene() {
		for (Window w : Window.getWindows()) {
			if (w.isShowing() && w.getScene() != null) return w.getScene();
		}
		throw new IllegalStateException("No showing window");
	}

	private static void settle(long millis) throws InterruptedException {
		Thread.sleep(millis);
	}

	private static <T> T onFx(Callable<T> work) throws Exception {
		FutureTask<T> task = new FutureTask<>(work);
		Platform.runLater(task);
		return task.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private interface FxAction {
		void run();
	}
}
