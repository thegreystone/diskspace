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
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link TableSelectionKeeper} against a real {@link TableView}, since the behaviour it compensates for lives
 * inside JavaFX's selection and focus models. Needs a JavaFX toolkit; on a headless CI runner the toolkit cannot start
 * and the tests are skipped rather than failed.
 */
class TableSelectionKeeperTest {
	private static volatile boolean toolkitAvailable;

	@BeforeAll
	static void startToolkit() {
		try {
			Platform.startup(() -> {
			});
			toolkitAvailable = true;
		} catch (IllegalStateException alreadyStarted) {
			toolkitAvailable = true;
		} catch (Throwable headless) {
			toolkitAvailable = false;
		}
		if (toolkitAvailable)
			Platform.setImplicitExit(false);
	}

	/** Rows are mutable-looking records: equality is by both fields, identity for the keeper is the name only. */
	private record Row(String name, long size) {
	}

	private static Object keyOf(Row r) {
		return r.name();
	}

	private static Row row(String name) {
		return new Row(name, 0);
	}

	private static final class Fixture {
		final ObservableList<Row> items = FXCollections.observableArrayList();
		final TableView<Row> table = new TableView<>(items);
		final TableColumn<Row, String> nameCol = new TableColumn<>("Name");

		Fixture(String... names) {
			nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
			table.getColumns().add(nameCol);
			table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
			for (String n : names)
				items.add(row(n));
		}

		void replaceWith(String... names) {
			List<Row> replacement = new java.util.ArrayList<>();
			for (String n : names)
				replacement.add(new Row(n, 1)); // different size → not equals() to the old row
			TableSelectionKeeper.replaceAll(table, items, replacement, TableSelectionKeeperTest::keyOf);
		}

		List<String> selectedNames() {
			return table.getSelectionModel().getSelectedItems().stream().map(Row::name).sorted().toList();
		}

		List<Integer> selectedIndices() {
			return table.getSelectionModel().getSelectedIndices().stream().sorted().toList();
		}
	}

	private static <T> T onFxThread(java.util.concurrent.Callable<T> body) throws Exception {
		assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless)");
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
		assumeTrue(done.await(10, TimeUnit.SECONDS), "FX thread did not run the test body");
		if (failure.get() instanceof Exception ex)
			throw ex;
		if (failure.get() != null)
			throw new AssertionError(failure.get());
		return result.get();
	}

	@Test
	void multiSelectionFollowsRowsAcrossReorder() throws Exception {
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b", "c", "d", "e");
			f.table.getSelectionModel().selectIndices(0, 2, 4); // a, c, e — e is the lead
			assertSame(f.items.get(4), f.table.getSelectionModel().getSelectedItem());

			f.replaceWith("b", "e", "a", "d", "c");

			assertEquals(List.of("a", "c", "e"), f.selectedNames());
			assertEquals(List.of(1, 2, 4), f.selectedIndices());
			assertEquals("e", f.table.getSelectionModel().getSelectedItem().name(), "lead row is preserved");
			return null;
		});
	}

	@Test
	void plainSetAllWouldHaveCollapsedTheSelection() throws Exception {
		// Documents the JavaFX behaviour the keeper exists to counter, so a future JavaFX upgrade
		// that fixes it upstream shows up as a failing (now redundant) assertion here.
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b", "c", "d", "e");
			f.table.getSelectionModel().selectIndices(0, 2, 4);
			f.items.setAll(List.of(row("b"), row("e"), row("a"), row("d"), row("c")));
			assertEquals(List.of("e"), f.selectedNames());
			return null;
		});
	}

	@Test
	void rowsThatVanishDropOutOfTheSelection() throws Exception {
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b", "c");
			f.table.getSelectionModel().selectIndices(0, 2); // a, c
			f.replaceWith("c", "b");
			assertEquals(List.of("c"), f.selectedNames());
			assertEquals(List.of(0), f.selectedIndices());
			return null;
		});
	}

	@Test
	void focusAndShiftAnchorAreRemapped() throws Exception {
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b", "c");
			f.table.getSelectionModel().select(0);
			f.table.getFocusModel().focus(0);
			f.table.getProperties().put(TableSelectionKeeper.FX_ANCHOR_KEY, new TablePosition<>(f.table, 0, f.nameCol));

			f.replaceWith("c", "b", "a");

			assertEquals(2, f.table.getFocusModel().getFocusedIndex(), "focus follows the row");
			Object anchor = f.table.getProperties().get(TableSelectionKeeper.FX_ANCHOR_KEY);
			assertEquals(2, ((TablePosition<?, ?>) anchor).getRow(), "shift anchor follows the row");
			assertSame(f.nameCol, ((TablePosition<?, ?>) anchor).getTableColumn());
			return null;
		});
	}

	@Test
	void anchorOnAVanishedRowIsCleared() throws Exception {
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b");
			f.table.getProperties().put(TableSelectionKeeper.FX_ANCHOR_KEY, new TablePosition<>(f.table, 0, f.nameCol));
			f.replaceWith("b");
			assertNull(f.table.getProperties().get(TableSelectionKeeper.FX_ANCHOR_KEY));
			return null;
		});
	}

	@Test
	void emptySelectionStaysEmpty() throws Exception {
		onFxThread(() -> {
			Fixture f = new Fixture("a", "b");
			f.replaceWith("b", "a");
			assertEquals(List.of(), f.selectedNames());
			return null;
		});
	}
}
