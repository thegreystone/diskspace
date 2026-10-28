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

import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.diskspace.ui.FxTestSupport.onFxThread;

/** Exercises the view's navigation and file-cache refresh paths with real JavaFX selection models. */
class DiskViewTableTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void startToolkit() throws InterruptedException {
		FxTestSupport.startToolkit();
	}

	@Test
	void navigationDoesNotSelectMatchingFilesInTheDestination() throws Exception {
		Path from = Files.createDirectory(tempDir.resolve("from"));
		Path to = Files.createDirectory(tempDir.resolve("to"));
		Files.write(from.resolve("shared.txt"), new byte[5]);
		Files.write(from.resolve("old.txt"), new byte[1]);
		Files.write(to.resolve("shared.txt"), new byte[9]);
		Files.write(to.resolve("new.txt"), new byte[1]);

		onFxThread(() -> {
			try (Fixture f = new Fixture(tempDir)) {
				DirectoryNode source = f.root.addChild("from", from);
				DirectoryNode destination = f.root.addChild("to", to);
				f.navigate(source);
				f.select("old.txt", "shared.txt");
				f.navigate(destination);

				f.assertSelectionCleared();
				assertEquals(9L, invoke(f.table.getItems().get(0), "currentSize"));
			}
			return null;
		});
	}

	@Test
	void navigationReplacesIdenticalFileNamesAndOrder() throws Exception {
		Path from = Files.createDirectory(tempDir.resolve("from"));
		Path to = Files.createDirectory(tempDir.resolve("to"));
		Files.write(from.resolve("shared.txt"), new byte[5]);
		Files.write(to.resolve("shared.txt"), new byte[20]);

		onFxThread(() -> {
			try (Fixture f = new Fixture(tempDir)) {
				DirectoryNode source = f.root.addChild("from", from);
				DirectoryNode destination = f.root.addChild("to", to);
				f.navigate(source);
				f.select("shared.txt");
				f.navigate(destination);

				f.assertSelectionCleared();
				assertEquals(20L, invoke(f.table.getItems().get(0), "currentSize"));
			}
			return null;
		});
	}

	@Test
	void relistingTheSameDirectoryPreservesSurvivingSelection() throws Exception {
		Files.write(tempDir.resolve("keep.txt"), new byte[5]);
		Path removed = Files.write(tempDir.resolve("removed.txt"), new byte[1]);

		onFxThread(() -> {
			try (Fixture f = new Fixture(tempDir)) {
				f.select("removed.txt", "keep.txt");
				Files.delete(removed);
				// The delete-completion path invalidates the file cache without navigating.
				setField(f.view, "lastListedRoot", null);
				invoke(f.view, "refreshTable");

				assertEquals(1, f.table.getSelectionModel().getSelectedItems().size());
				assertEquals("keep.txt", invoke(f.table.getSelectionModel().getSelectedItem(), "name"));
				assertEquals(0, f.table.getFocusModel().getFocusedIndex());
				TablePosition<?, ?> anchor = (TablePosition<?, ?>) f.table.getProperties().get(
						TableSelectionKeeper.FX_ANCHOR_KEY);
				assertEquals(0, anchor.getRow());
			}
			return null;
		});
	}

	@Test
	void clearingTheViewDropsSelectionAndReloadsItsFiles() throws Exception {
		Path file = Files.write(tempDir.resolve("shared.txt"), new byte[5]);

		onFxThread(() -> {
			try (Fixture f = new Fixture(tempDir)) {
				f.select("shared.txt");
				setField(f.view, "viewRoot", null);
				invoke(f.view, "refreshTable");
				f.assertSelectionCleared();
				assertEquals(0, f.table.getItems().size());

				Files.write(file, new byte[12]);
				f.navigate(f.root);
				f.assertSelectionCleared();
				assertEquals(12L, invoke(f.table.getItems().get(0), "currentSize"));
			}
			return null;
		});
	}

	@Test
	void mixedSelectionCountsPathlessRowsButOnlyStagesRealPaths() throws Exception {
		Path file = Files.write(tempDir.resolve("file.txt"), new byte[5]);

		onFxThread(() -> {
			try (Fixture f = new Fixture(tempDir)) {
				f.root.addChild("Hidden", null);
				invoke(f.view, "refreshTable");
				f.select("Hidden", "file.txt");
				Object target = invoke(f.view, "selectionTarget", new Class<?>[] {List.class},
						List.copyOf(f.table.getSelectionModel().getSelectedItems()));

				assertEquals("2 items selected", invoke(target, "selectionLabel"));
				assertEquals(List.of(file), invoke(target, "allPaths"));
				((Runnable) invoke(target, "stageAction")).run();
				assertEquals(1, ((List<?>) getField(f.view, "stagedItems")).size());
			}
			return null;
		});
	}

	private static final class Fixture implements AutoCloseable {
		final DiskView view;
		final DirectoryNode root;
		final TableView<Object> table;

		@SuppressWarnings("unchecked")
		Fixture(Path path) throws Exception {
			Volume volume = new Volume("Test", "test", path, 1000, 900, 100, "test", StorageProfile.UNKNOWN);
			Scanner scanner = new Scanner() {
				@Override
				public void scan(Path scanPath, ScanListener listener) {
					throw new AssertionError("Table tests must not start a background scan");
				}

				@Override
				public void cancel() {
				}
			};
			view = new DiskView(volume, ColorScheme.DARK, scanner);
			table = (TableView<Object>) getField(view, "table");
			root = new DirectoryNode(null, "Test", path);
			setField(view, "scanRoot", root);
			setField(view, "viewRoot", root);
			setField(view, "scanning", false);
			invoke(view, "refreshTable");
		}

		void navigate(DirectoryNode destination) throws Exception {
			invoke(view, "select", new Class<?>[] {DirectoryNode.class}, destination);
		}

		void select(String... names) throws Exception {
			table.getSelectionModel().clearSelection();
			for (String name : names) {
				int index = -1;
				for (int i = 0; i < table.getItems().size(); i++) {
					if (name.equals(invoke(table.getItems().get(i), "name"))) {
						index = i;
						break;
					}
				}
				assertTrue(index >= 0, "Missing row: " + name);
				table.getSelectionModel().select(index);
			}
			int lead = table.getSelectionModel().getSelectedIndex();
			table.getFocusModel().focus(lead);
			table.getProperties().put(TableSelectionKeeper.FX_ANCHOR_KEY,
					new TablePosition<>(table, lead, table.getColumns().get(0)));
		}

		void assertSelectionCleared() {
			assertEquals(List.of(), table.getSelectionModel().getSelectedItems());
			assertEquals(-1, table.getFocusModel().getFocusedIndex());
			assertNull(table.getFocusModel().getFocusedItem());
			assertNull(table.getProperties().get(TableSelectionKeeper.FX_ANCHOR_KEY));
		}

		@Override
		public void close() {
			view.shutdown();
		}
	}

	private static Object getField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object invoke(Object target, String name) throws Exception {
		return invoke(target, name, new Class<?>[0]);
	}

	private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception exception)
				throw exception;
			if (e.getCause() instanceof Error error)
				throw error;
			throw e;
		}
	}
}
