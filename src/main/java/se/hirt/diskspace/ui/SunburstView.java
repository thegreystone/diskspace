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

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.MacHiddenSpace;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.scan.WalkFileTreeScanner;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.SectorPalette;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class SunburstView {

	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(SunburstView.class.getName());

	private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(SunburstView.class);
	private static final String PREF_FDA_SKIP = "fda.prompt.skip";

	/**
	 * First {@value} rings render at full thickness ({@code normalW}); after that, up to {@link #THIN_RINGS} additional rings are squeezed
	 * in at {@link #THIN_RING_FACTOR} of the normal width so deep file structure stays visible without dominating the layout.
	 */
	private static final int NORMAL_RINGS = 5;
	private static final int THIN_RINGS = 4;
	private static final int MAX_DEPTH = NORMAL_RINGS + THIN_RINGS;
	private static final double THIN_RING_FACTOR = 0.2;
	private static final double HUB_RADIUS = 78;
	private static final double MIN_VISIBLE_SWEEP_DEG = 0.6;
	private static final long LIVE_REFRESH_INTERVAL_NANOS = 100_000_000L; // 10 Hz
	private static final long ANIM_DURATION_NANOS = 350_000_000L;        // 350 ms

	private final SplitPane root;
	private final Canvas canvas;
	private final ColorScheme scheme;
	private final Volume target;

	private final Label rightHeader;
	private final HBox breadcrumb;
	private final TableView<Entry> table = new TableView<>();
	private final ObservableList<Entry> tableItems = FXCollections.observableArrayList();
	private DirectoryNode lastListedRoot;
	private List<Entry> currentFiles = List.of();

	// ---- staging (delete-tray) state ------------------------------------
	private final TableView<StagedItem> stagingTable = new TableView<>();
	private final ObservableList<StagedItem> stagedItems = FXCollections.observableArrayList();
	private final Label stagingFooterLabel = new Label();
	private final SplitPane rightSplit = new SplitPane();
	private final BorderPane stagingPane;
	private Button cancelStagingButton;
	private Button deleteStagedButton;
	private volatile boolean deleting;

	private final List<SectorRect> sectors = new ArrayList<>();
	private DirectoryNode scanRoot;
	private DirectoryNode viewRoot;
	private DirectoryNode hoverNode;
	private boolean hoveringHub;
	private boolean hoveringFreeSpace;
	private boolean hoveringUnaccounted;
	private volatile boolean scanning = true;

	private long progressFiles;
	private long progressBytes;
	private String progressPath;
	private long lastPermDeniedCount;
	private volatile MacHiddenSpace.HiddenSpace cachedHidden;
	/**
	 * The synthetic "Hidden" node attached as a child of scanRoot. Held so layout and table sorters can pin it to the end regardless of
	 * size. Null until injected.
	 */
	private volatile DirectoryNode hiddenNode;

	/**
	 * Memoized sunburst color per node. Family root (immediate child of scanRoot) gets a palette pick by name; deeper descendants inherit
	 * the parent's color, lightened and hue-shifted by sibling rank + depth so the largest-child trunk reads as one ribbon while side
	 * branches fade outward. Cleared on every (re)scan.
	 */
	private final java.util.Map<DirectoryNode, Color> colorCache = new java.util.IdentityHashMap<>();

	/**
	 * Palette index claimed by each top-level family (immediate child of scanRoot). Allocated lazily with collision avoidance so two
	 * top-level siblings can't end up on the same color even when their names hash to the same bucket.
	 */
	private final java.util.Map<DirectoryNode, Integer> topLevelPaletteIdx = new java.util.IdentityHashMap<>();

	/**
	 * Top-level folders whose scan has completed and whose descendant colors have been invalidated against final ranks. Walked on every
	 * live tick so colors stabilize per-folder as each finishes, instead of all flipping at the end of the scan.
	 */
	private final java.util.Set<DirectoryNode> finalizedTopLevels = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private final Scanner scanner = new WalkFileTreeScanner();
	private final AnimationTimer liveTicker;
	private long lastTickNanos;

	private final Deque<DirectoryNode> forwardStack = new ArrayDeque<>();

	private boolean animating;
	private long animStartNanos;
	private DirectoryNode animOldViewRoot;
	private DirectoryNode animNewViewRoot;
	private Map<DirectoryNode, Layout> animOld;
	private Map<DirectoryNode, Layout> animNew;
	private final AnimationTimer animTimer;

	public SunburstView(Volume target, ColorScheme scheme) {
		this.target = target;
		this.scheme = scheme;

		canvas = new Canvas();
		Pane canvasHolder = new Pane(canvas);
		canvasHolder.setStyle(bg(scheme.background()));
		canvas.widthProperty().bind(canvasHolder.widthProperty());
		canvas.heightProperty().bind(canvasHolder.heightProperty());
		canvas.widthProperty().addListener((o, a, b) -> redraw());
		canvas.heightProperty().addListener((o, a, b) -> redraw());
		canvas.setOnMouseMoved(e -> handleMouseMove(e.getX(), e.getY()));
		canvas.setOnMouseExited(e -> {
			hoverNode = null;
			hoveringHub = false;
			hoveringFreeSpace = false;
			hoveringUnaccounted = false;
			redraw();
		});
		canvas.setOnMouseClicked(e -> handleClick(e.getX(), e.getY()));

		breadcrumb = new HBox(4);
		breadcrumb.setPadding(new Insets(10, 14, 10, 14));
		breadcrumb.setAlignment(Pos.CENTER_LEFT);
		breadcrumb.setPickOnBounds(false);
		// Important: cap to content size so StackPane respects TOP_LEFT alignment.
		// Without this, HBox stretches to fill, and Pos.CENTER_LEFT plants the labels
		// at the vertical centre of the canvas instead of pinned at the top.
		breadcrumb.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane leftStack = new StackPane(canvasHolder, breadcrumb);
		StackPane.setAlignment(breadcrumb, Pos.TOP_LEFT);
		leftStack.setStyle(bg(scheme.background()));

		configureTable();
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		configureStagingTable();
		stagingPane = buildStagingPane();
		rightSplit.setOrientation(Orientation.VERTICAL);
		rightSplit.setStyle(bg(scheme.background()));
		rightSplit.getItems().add(table);
		stagedItems.addListener((ListChangeListener<StagedItem>) c -> updateStagingVisibility());

		rightHeader = new Label("  " + target.displayName() + "  —  scanning…");
		rightHeader.setStyle("-fx-text-fill: " + css(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 8 12 8 12;");
		BorderPane right = new BorderPane(rightSplit);
		right.setTop(rightHeader);
		right.setStyle(bg(scheme.background()));

		root = new SplitPane(leftStack, right);
		root.setStyle(bg(scheme.background()));
		root.setDividerPositions(0.70);
		SplitPane.setResizableWithParent(right, false);

		liveTicker = new AnimationTimer() {
			@Override
			public void handle(long now) {
				if (now - lastTickNanos < LIVE_REFRESH_INTERVAL_NANOS)
					return;
				lastTickNanos = now;
				stabilizeFinalizedTopLevels();
				refreshTable();
				if (!stagedItems.isEmpty()) {
					stagingTable.refresh();
					updateStagingFooter();
				}
				if (!animating)
					redraw();
			}
		};

		animTimer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				if (now - animStartNanos >= ANIM_DURATION_NANOS) {
					animating = false;
					stop();
					redraw();
					return;
				}
				redraw();
			}
		};

		// Keyboard shortcuts.
		root.setFocusTraversable(true);
		root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
			if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown())
				return;
			switch (e.getCode()) {
			case E, F -> {
				openInExplorer();
				e.consume();
			}
			case LEFT, UP -> {
				// Up one level. Push the current viewRoot onto the forward stack so
				// Right arrow can replay the path back down. Always consume — at
				// scanRoot we no-op, but unconsumed arrow keys bubble to the TabPane
				// and would step to the "+" tab, opening a new picker.
				if (viewRoot != null && viewRoot != scanRoot && viewRoot.parent() != null) {
					forwardStack.push(viewRoot);
					select(viewRoot.parent(), false);
				}
				e.consume();
			}
			case RIGHT, DOWN -> {
				// Forward: pop the most recently traversed-up node and drill back into it.
				// Right pairs with Left; Down pairs with Up — both bound here.
				// Always consume so an empty-stack press doesn't fall through to the TabPane.
				if (!forwardStack.isEmpty()) {
					DirectoryNode next = forwardStack.pop();
					select(next, false);
				}
				e.consume();
			}
			case DELETE -> {
				handleDeleteKey();
				e.consume();
			}
			case R -> {
				// Full rescan. Useful after external changes (e.g. something deleted outside diskspace).
				if (!deleting)
					rescan();
				e.consume();
			}
			case U -> {
				SizeFormat.toggle();
				refreshAfterUnitChange();
				e.consume();
			}
			default -> { /* let it bubble */ }
			}
		});

		startScan();
	}

	public Region getRoot() {
		return root;
	}

	private void configureTable() {
		table.setItems(tableItems);
		table.setPlaceholder(new Label(""));
		table.setStyle("-fx-background-color: " + css(scheme.background()) + ";" + "-fx-control-inner-background: " + css(
				scheme.background()) + ";" + "-fx-text-fill: " + css(
				scheme.textPrimary()) + ";" + "-fx-table-cell-border-color: transparent;");

		TableColumn<Entry, String> nameCol = new TableColumn<>("Name");
		nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
		nameCol.setPrefWidth(200);
		nameCol.setCellFactory(col -> new TableCell<>() {
			private final javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(9, 9);

			{
				swatch.setArcWidth(3);
				swatch.setArcHeight(3);
			}

			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setGraphic(null);
					setStyle("");
					return;
				}
				Entry e = (getTableRow() == null) ? null : getTableRow().getItem();
				DirectoryNode node = (e != null && e.isDirectory()) ? e.dirNode() : null;
				if (node != null && node.isFileSector()) {
					// Large file or "Smaller files" aggregate — grey swatch (matches the
					// sunburst sector) and italic muted text so users know it isn't a
					// drillable folder.
					swatch.setFill(getNodeColor(node));
					setGraphic(swatch);
					setText(item);
					setStyle("-fx-font-style: italic; -fx-text-fill: " + css(scheme.textMuted()) + ";");
				} else if (node != null && node.path() == null) {
					// Synthetic Hidden node — keep the color swatch so the row maps visually
					// to its sunburst sector, but render the text italic muted to signal it
					// isn't a real on-disk folder.
					swatch.setFill(getNodeColor(node));
					setGraphic(swatch);
					setText(item);
					setStyle("-fx-font-style: italic; -fx-text-fill: " + css(scheme.textMuted()) + ";");
				} else if (node != null) {
					swatch.setFill(getNodeColor(node));
					setGraphic(swatch);
					switch (node.state()) {
					case QUEUED -> {
						setText(item + "  <queued>");
						setStyle("-fx-font-weight: bold; -fx-text-fill: " + css(scheme.textMuted().darker()) + ";");
					}
					case SCANNING -> {
						setText(item + "  <scanning>");
						setStyle("-fx-font-weight: bold; -fx-opacity: 0.75;");
					}
					default -> {
						setText(item);
						setStyle("-fx-font-weight: bold;");
					}
					}
				} else {
					setGraphic(null);
					setText(item);
					setStyle("");
				}
			}
		});

		TableColumn<Entry, String> sizeCol = new TableColumn<>("Size");
		sizeCol.setCellValueFactory(d -> new SimpleStringProperty(humanSize(d.getValue().currentSize())));
		sizeCol.setPrefWidth(80);
		sizeCol.setStyle("-fx-alignment: CENTER-RIGHT;");
		sizeCol.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setStyle("");
				} else {
					setText(item);
					setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-alignment: CENTER-RIGHT;");
				}
			}
		});

		table.getColumns().setAll(List.of(nameCol, sizeCol));
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

		// Row factory: alternates row tint (so populated rows match the empty alternation
		// below them), highlights the selected row, gives directories a stronger tint so
		// they read as a section, and drills into a directory when its row is clicked.
		table.setRowFactory(tv -> new javafx.scene.control.TableRow<Entry>() {
			{
				selectedProperty().addListener((o, a, b) -> applyRowStyle());
				hoverProperty().addListener((o, a, b) -> applyRowStyle());
				setOnMouseClicked(e -> {
					if (e.getButton() == MouseButton.PRIMARY && !isEmpty()) {
						Entry it = getItem();
						if (it != null && it.isDirectory() && it.dirNode() != null) {
							select(it.dirNode());
						}
					}
				});
			}

			@Override
			protected void updateItem(Entry item, boolean empty) {
				super.updateItem(item, empty);
				applyRowStyle();
			}

			@Override
			public void updateIndex(int i) {
				super.updateIndex(i);
				applyRowStyle();
			}

			private void applyRowStyle() {
				int idx = getIndex();
				boolean odd = (idx & 1) == 1;
				Entry item = getItem();
				String bg;
				if (isSelected()) {
					bg = css(scheme.accent().deriveColor(0, 1.0, 1.0, 0.30));
				} else if (isHover() && item != null) {
					// Hover lands between selection and alternation in visual weight, so
					// the user can see exactly which row a click would target without it
					// being mistaken for a selected row.
					bg = "rgba(255,255,255,0.12)";
				} else if (item != null && item.isDirectory()) {
					bg = odd ? "rgba(255,255,255,0.075)" : "rgba(255,255,255,0.045)";
				} else {
					bg = odd ? "rgba(255,255,255,0.025)" : "transparent";
				}
				setStyle("-fx-background-color: " + bg + ";");
			}
		});
	}

	// ---- staging UI ------------------------------------------------------

	private void configureStagingTable() {
		stagingTable.setItems(stagedItems);
		stagingTable.setPlaceholder(new Label(""));
		stagingTable.setStyle("-fx-background-color: " + css(scheme.background()) + ";" + "-fx-control-inner-background: " + css(
				scheme.background()) + ";" + "-fx-text-fill: " + css(
				scheme.textPrimary()) + ";" + "-fx-table-cell-border-color: transparent;");
		stagingTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

		TableColumn<StagedItem, String> nameCol = new TableColumn<>("Path");
		nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().displayPath()));
		nameCol.setPrefWidth(180);
		nameCol.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setStyle("");
					return;
				}
				setText(item);
				StagedItem si = (getTableRow() == null) ? null : getTableRow().getItem();
				setStyle(si != null && si.isDirectory() ? "-fx-font-weight: bold;" : "");
			}
		});

		TableColumn<StagedItem, String> sizeCol = new TableColumn<>("Size");
		sizeCol.setCellValueFactory(d -> new SimpleStringProperty(humanSize(d.getValue().currentSize())));
		sizeCol.setPrefWidth(80);
		sizeCol.setStyle("-fx-alignment: CENTER-RIGHT;");
		sizeCol.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setStyle("");
				} else {
					setText(item);
					setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-alignment: CENTER-RIGHT;");
				}
			}
		});

		stagingTable.getColumns().setAll(List.of(nameCol, sizeCol));
		stagingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

		stagingTable.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
			@Override
			protected void updateItem(StagedItem item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null || !item.isDirectory()) {
					setStyle("");
				} else {
					setStyle("-fx-background-color: rgba(255, 255, 255, 0.045);");
				}
			}
		});
	}

	private BorderPane buildStagingPane() {
		Label header = new Label("  Scheduled for deletion");
		header.setStyle("-fx-text-fill: " + css(
				scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 8 12 8 12;" + "-fx-border-color: " + css(
				scheme.surface()) + " transparent transparent transparent;" + "-fx-border-width: 1 0 0 0;");

		stagingFooterLabel.setStyle("-fx-text-fill: " + css(scheme.textMuted()) + ";" + "-fx-font-size: 11px;");

		cancelStagingButton = new Button("Cancel");
		cancelStagingButton.setOnAction(e -> stagedItems.clear());

		deleteStagedButton = new Button(canMoveToTrash() ? "Move to Trash…" : "Delete…");
		deleteStagedButton.setOnAction(e -> confirmAndDelete());

		Region grow = new Region();
		HBox.setHgrow(grow, javafx.scene.layout.Priority.ALWAYS);
		HBox footer = new HBox(8, stagingFooterLabel, grow, cancelStagingButton, deleteStagedButton);
		footer.setAlignment(Pos.CENTER_LEFT);
		footer.setPadding(new Insets(8, 12, 8, 12));
		footer.setStyle(bg(scheme.background()));

		BorderPane pane = new BorderPane(stagingTable);
		pane.setTop(header);
		pane.setBottom(footer);
		pane.setStyle(bg(scheme.background()));
		return pane;
	}

	// ---- deletion --------------------------------------------------------

	private static boolean canMoveToTrash() {
		try {
			return java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop()
					.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
		} catch (Throwable t) {
			return false;
		}
	}

	private void confirmAndDelete() {
		if (stagedItems.isEmpty() || deleting)
			return;
		boolean trash = canMoveToTrash();
		long total = 0;
		for (StagedItem si : stagedItems)
			total += si.currentSize();

		StringBuilder body = new StringBuilder();
		int shown = Math.min(stagedItems.size(), 10);
		for (int i = 0; i < shown; i++) {
			body.append(stagedItems.get(i).displayPath()).append('\n');
		}
		if (stagedItems.size() > shown) {
			body.append("\n… and ").append(stagedItems.size() - shown).append(" more");
		}

		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle(trash ? "Move to Trash" : "Delete permanently");
		alert.setHeaderText((trash ? "Move " : "Permanently delete ") + stagedItems.size() + " item" + (stagedItems.size() == 1 ? ""
				: "s") + " (" + humanSize(total) + ")?");
		alert.setContentText(body.toString());
		ButtonType go = new ButtonType(trash ? "Move to Trash" : "Delete", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
		alert.getButtonTypes().setAll(go, ButtonType.CANCEL);

		var result = alert.showAndWait();
		if (result.isEmpty() || result.get() != go)
			return;

		performDelete(new ArrayList<>(stagedItems));
	}

	private void performDelete(List<StagedItem> items) {
		deleting = true;
		cancelStagingButton.setDisable(true);
		deleteStagedButton.setDisable(true);
		stagingFooterLabel.setText("Deleting " + items.size() + " items…");

		Thread t = new Thread(() -> {
			DeleteResult r = doDeleteWork(items);
			Platform.runLater(() -> onDeleteComplete(r));
		}, "DiskSpace-delete");
		t.setDaemon(true);
		t.start();
	}

	private DeleteResult doDeleteWork(List<StagedItem> items) {
		boolean trash = canMoveToTrash();
		List<StagedItem> deleted = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		for (StagedItem si : items) {
			try {
				if (si.path() == null || !Files.exists(si.path(), LinkOption.NOFOLLOW_LINKS)) {
					failures.add(si.displayPath() + ": no longer exists");
					continue;
				}
				if (trash) {
					boolean ok = java.awt.Desktop.getDesktop().moveToTrash(si.path().toFile());
					if (!ok)
						throw new java.io.IOException("moveToTrash returned false");
				} else {
					deleteRecursive(si.path());
				}
				deleted.add(si);
			} catch (Throwable ex) {
				failures.add(si.displayPath() + ": " + ex.getMessage());
			}
		}
		return new DeleteResult(deleted, failures);
	}

	private static void deleteRecursive(Path path) throws java.io.IOException {
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<Path>() {
				@Override
				public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
						throws java.io.IOException {
					Files.delete(file);
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
					Files.delete(dir);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		} else {
			Files.delete(path);
		}
	}

	private void onDeleteComplete(DeleteResult result) {
		deleting = false;
		cancelStagingButton.setDisable(false);
		deleteStagedButton.setDisable(false);

		// Apply the deltas to the in-memory tree before clearing the staging list, so we
		// know which items were actually deleted (we only patch the tree for those).
		updateAfterDelete(result.deleted);
		stagedItems.clear();

		if (!result.failures.isEmpty()) {
			Alert a = new Alert(Alert.AlertType.WARNING);
			a.setTitle("Some items could not be deleted");
			a.setHeaderText(result.succeeded() + " deleted, " + result.failures.size() + " failed");
			int shown = Math.min(result.failures.size(), 10);
			StringBuilder body = new StringBuilder();
			for (int i = 0; i < shown; i++)
				body.append(result.failures.get(i)).append('\n');
			if (result.failures.size() > shown) {
				body.append("\n… and ").append(result.failures.size() - shown).append(" more");
			}
			a.setContentText(body.toString());
			a.show();
		}
	}

	private void updateAfterDelete(List<StagedItem> deleted) {
		if (deleted.isEmpty())
			return;

		// Snapshot the pre-delete layout so we can lerp from it to the post-delete layout.
		Map<DirectoryNode, Layout> beforeLayout = computeLayout(viewRoot);
		DirectoryNode previousViewRoot = viewRoot;

		boolean filesListChanged = false;
		boolean viewRootInvalidated = false;

		for (StagedItem si : deleted) {
			if (si.isDirectory()) {
				DirectoryNode node = si.dirNode();
				DirectoryNode parent = si.parentNode();
				if (parent != null && node != null) {
					parent.removeChild(node);
				}
				if (node != null && isAncestorOrSame(node, viewRoot)) {
					viewRootInvalidated = true;
				}
			} else {
				DirectoryNode parent = si.parentNode();
				if (parent != null) {
					parent.removeFile(si.sizeAtStaging());
					if (parent == viewRoot)
						filesListChanged = true;
				}
			}
		}

		if (viewRootInvalidated) {
			// Walk up until we find a node still attached to the tree.
			DirectoryNode v = viewRoot;
			while (v != null && v != scanRoot && isOrphaned(v)) {
				v = v.parent();
			}
			viewRoot = (v != null) ? v : scanRoot;
			forwardStack.clear();
		}

		// Hover state can be stale (e.g., hovering a sector that was just deleted).
		hoverNode = null;
		hoveringHub = false;

		if (filesListChanged) {
			// Force file-list re-read for current viewRoot (its files moved to trash on disk).
			lastListedRoot = null;
			currentFiles = List.of();
		}

		refreshTable();
		rebuildBreadcrumb();

		// Snapshot the post-delete layout and run the standard old→new tween. Deleted
		// sectors are "in old only" (shrink in place); surviving siblings whose sweeps
		// grew (less weight in the parent) tween into their new wider positions.
		Map<DirectoryNode, Layout> afterLayout = computeLayout(viewRoot);
		animOldViewRoot = previousViewRoot;
		animNewViewRoot = viewRoot;
		animOld = beforeLayout;
		animNew = afterLayout;
		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
	}

	private static boolean isAncestorOrSame(DirectoryNode candidate, DirectoryNode target) {
		for (DirectoryNode n = target; n != null; n = n.parent()) {
			if (n == candidate)
				return true;
		}
		return false;
	}

	private static boolean isOrphaned(DirectoryNode node) {
		DirectoryNode parent = node.parent();
		return parent != null && !parent.children().contains(node);
	}

	private void rescan() {
		scanner.cancel();
		liveTicker.stop();
		scanRoot = null;
		viewRoot = null;
		hoverNode = null;
		hoveringHub = false;
		hoveringFreeSpace = false;
		hoveringUnaccounted = false;
		forwardStack.clear();
		progressFiles = 0;
		progressBytes = 0;
		progressPath = null;
		scanning = true;
		lastListedRoot = null;
		lastPermDeniedCount = 0;
		cachedHidden = null;
		hiddenNode = null;
		colorCache.clear();
		topLevelPaletteIdx.clear();
		finalizedTopLevels.clear();
		currentFiles = List.of();
		tableItems.clear();
		sectors.clear();
		rebuildBreadcrumb();
		redraw();
		startScan();
	}

	private record DeleteResult(List<StagedItem> deleted, List<String> failures) {
		int succeeded() {
			return deleted.size();
		}
	}

	private void handleDeleteKey() {
		// Priority:
		//  1. If staging table has rows selected → unstage those.
		//  2. Else if contents table has rows selected → stage those.
		//  3. Else if drilled in → stage the current viewRoot section.
		ObservableList<StagedItem> stagingSel = stagingTable.getSelectionModel().getSelectedItems();
		if (stagingTable.isFocused() && !stagingSel.isEmpty()) {
			stagedItems.removeAll(new ArrayList<>(stagingSel));
			return;
		}

		ObservableList<Entry> contentsSel = table.getSelectionModel().getSelectedItems();
		if (!contentsSel.isEmpty()) {
			for (Entry entry : new ArrayList<>(contentsSel)) {
				stage(entryToStaged(entry));
			}
			return;
		}

		if (viewRoot != null && viewRoot != scanRoot) {
			stage(dirToStaged(viewRoot));
		}
	}

	private void stage(StagedItem candidate) {
		if (candidate == null || candidate.path() == null)
			return;
		// If an ancestor is already staged, the candidate is already covered.
		for (StagedItem existing : stagedItems) {
			if (candidate.path().equals(existing.path()))
				return;
			if (candidate.path().startsWith(existing.path()))
				return;
		}
		// Remove any existing item that the candidate covers (descendants of candidate).
		stagedItems.removeIf(existing -> !existing.path().equals(candidate.path()) && existing.path().startsWith(candidate.path()));
		stagedItems.add(candidate);
	}

	private StagedItem entryToStaged(Entry e) {
		if (e.isDirectory()) {
			return dirToStaged(e.dirNode());
		}
		java.nio.file.Path filePath = (viewRoot != null && viewRoot.path() != null) ? viewRoot.path().resolve(e.name()) : null;
		// For a file row, the parent node is whatever directory we're currently viewing.
		return new StagedItem(false, filePath, e.staticSize(), null, viewRoot);
	}

	private StagedItem dirToStaged(DirectoryNode n) {
		if (n == null)
			return null;
		return new StagedItem(true, n.path(), n.totalBytes(), n, n.parent());
	}

	private void updateStagingVisibility() {
		boolean show = !stagedItems.isEmpty();
		boolean alreadyShown = rightSplit.getItems().contains(stagingPane);
		if (show && !alreadyShown) {
			rightSplit.getItems().add(stagingPane);
			rightSplit.setDividerPositions(0.65);
		} else if (!show && alreadyShown) {
			rightSplit.getItems().remove(stagingPane);
		}
		if (show) {
			updateStagingFooter();
		}
	}

	private void updateStagingFooter() {
		long total = 0;
		for (StagedItem si : stagedItems)
			total += si.currentSize();
		stagingFooterLabel.setText(stagedItems.size() + " items · " + humanSize(total));
	}

	private void logScanSummary(DirectoryNode root) {
		List<DirectoryNode> children = new ArrayList<>(root.children());
		children.sort(Comparator.comparingLong(DirectoryNode::totalBytes).reversed());

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("Scan complete: %s%n", target.displayName()));
		sb.append(String.format("  Volume total : %s%n", humanSize(target.totalBytes())));
		sb.append(String.format("  OS used      : %s%n", humanSize(target.usedBytes())));
		sb.append(String.format("  OS free      : %s%n", humanSize(target.usableBytes())));
		sb.append(String.format("  Scanned      : %s  (%d files)%n", humanSize(root.totalBytes()), root.totalFileCount()));
		long delta = root.totalBytes() - target.usedBytes();
		if (delta > 0) {
			// Scanner sum exceeds OS-reported used space; APFS clones are the usual cause.
			sb.append(String.format("  Overcounted  : %s%n", humanSize(delta)));
		} else {
			sb.append(String.format("  Unaccounted  : %s%n", humanSize(-delta)));
		}

		MacHiddenSpace.HiddenSpace hidden = cachedHidden;
		long hiddenTotal = hidden == null ? 0L : hidden.totalBytes();
		if (hidden != null && (hiddenTotal > 0 || hidden.localSnapshotCount() > 0 || lastPermDeniedCount > 0)) {
			sb.append(String.format("  Hidden       : %s%n", humanSize(hiddenTotal)));
			sb.append(String.format("    Other volumes  : %s  (%d volume%s)%n", humanSize(hidden.otherVolumesBytes()),
					hidden.otherVolumesCount(), hidden.otherVolumesCount() == 1 ? "" : "s"));
			sb.append(String.format("    Snapshots      : %s%n", hidden.localSnapshotCount() == 0 ? "no local snapshots"
					: hidden.localSnapshotCount() + " local snapshot" + (hidden.localSnapshotCount() == 1 ? "" : "s")));
			sb.append(String.format("    Other          : %s%n", humanSize(hidden.residualBytes())));
			sb.append(String.format("    Not accessible : %d path%s%n", lastPermDeniedCount, lastPermDeniedCount == 1 ? "" : "s"));
		}
		sb.append("  Root breakdown (by size):\n");
		for (DirectoryNode child : children) {
			double pct = root.totalBytes() > 0 ? 100.0 * child.totalBytes() / root.totalBytes() : 0;
			sb.append(String.format("    %-32s %10s  (%4.1f%%)%n", child.name(), humanSize(child.totalBytes()), pct));
		}
		LOG.info(sb.toString());
	}

	private void showPermissionDeniedDialog(long count) {
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("Limited Access");
		alert.setHeaderText(count + " location" + (count == 1 ? "" : "s") + " couldn't be read");
		alert.setContentText("Grant DiskSpace Full Disk Access in System Settings\n" + "to include protected directories in the scan.");
		ButtonType openSettings = new ButtonType("Open System Settings");
		alert.getButtonTypes().setAll(openSettings, ButtonType.CANCEL);
		alert.showAndWait().filter(b -> b == openSettings).ifPresent(b -> {
			try {
				new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles").start();
			} catch (java.io.IOException ignore) {
			}
		});
	}

	private void startScan() {
		if (isMac() && !isFdaGranted() && !PREFS.getBoolean(PREF_FDA_SKIP, false)) {
			promptForFda();
		}
		doStartScan();
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static boolean isFdaGranted() {
		Path tcc = Path.of("/Library/Application Support/com.apple.TCC");
		if (!Files.exists(tcc))
			return true;
		try (var ignored = Files.newDirectoryStream(tcc)) {
			return true;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	private void promptForFda() {
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("Full Disk Access");
		alert.setHeaderText("Full Disk Access needed for a complete scan");
		alert.setContentText(
				"Without Full Disk Access, protected folders like Mail, Messages,\n" + "and system directories won't be included in the scan.\n\n" + "DiskSpace only reads names, sizes, and folder structure.\n" + "It never reads file contents, and never modifies files\n" + "without your explicit action.");
		ButtonType openSettings = new ButtonType("Open System Settings…");
		ButtonType scanAnyway = new ButtonType("Scan without Full Access");
		alert.getButtonTypes().setAll(openSettings, scanAnyway);
		alert.showAndWait().ifPresent(b -> {
			if (b == openSettings) {
				try {
					new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles").start();
				} catch (java.io.IOException ignore) {
				}
			} else {
				PREFS.putBoolean(PREF_FDA_SKIP, true);
			}
		});
	}

	private void doStartScan() {
		scanner.scan(target.root(), new Scanner.ScanListener() {
			@Override
			public void onStart(DirectoryNode liveRoot) {
				// Compute Hidden up front and inject it now — its bytes (df / diskutil)
				// don't depend on the scan walk, and pre-injecting avoids the visual jump
				// where the rest of the chart would shrink at scan completion to make
				// room for a sector that was always going to be there.
				long containerUsed = Math.max(0L, target.totalBytes() - target.usableBytes());
				cachedHidden = MacHiddenSpace.gather(target.root(), containerUsed, target.usedBytes());
				injectHiddenInto(liveRoot);
				Platform.runLater(() -> {
					scanRoot = liveRoot;
					viewRoot = liveRoot;
					scanning = true;
					refreshTable();
					rebuildBreadcrumb();
					redraw();
					liveTicker.start();
				});
			}

			@Override
			public void onProgress(long files, long bytes, String currentPath) {
				progressFiles = files;
				progressBytes = bytes;
				progressPath = currentPath;
			}

			@Override
			public void onPermissionsDenied(long count) {
				lastPermDeniedCount = count;
				Platform.runLater(() -> showPermissionDeniedDialog(count));
			}

			@Override
			public void onComplete(DirectoryNode result) {
				logScanSummary(result);
				injectFileChildrenInto(result);
				// Hidden was injected at scan start; nothing more to do for it here.
				Platform.runLater(() -> {
					scanning = false;
					liveTicker.stop();
					// Drop colors that were memoized during the scan against stale child
					// sort orders — a node briefly cached as rank-0 stays cached as rank-0
					// until invalidated, even if siblings overtook it. Same for the
					// top-level palette allocation: redo it in final-size order so the
					// largest top-level family gets its hashed index first.
					colorCache.clear();
					topLevelPaletteIdx.clear();
					refreshTable();
					redraw();
				});
			}

			@Override
			public void onError(Throwable t) {
				Platform.runLater(() -> {
					scanning = false;
					liveTicker.stop();
					progressPath = "Scan failed: " + t.getMessage();
					redraw();
				});
			}
		});
	}

	// ---- table refresh ---------------------------------------------------

	private void refreshAfterUnitChange() {
		refreshTable();
		table.refresh();
		stagingTable.refresh();
		if (!stagedItems.isEmpty())
			updateStagingFooter();
		redraw();
	}

	private void refreshTable() {
		if (viewRoot == null) {
			tableItems.clear();
			rightHeader.setText("");
			return;
		}
		// Synthetic Hidden nodes have no on-disk path; show the name instead and skip the
		// file count (it's always 0 for synthetic).
		String headerLeft = viewRoot.path() != null ? viewRoot.path().toString() : viewRoot.name();
		String headerRight = viewRoot.path() != null ? "   " + viewRoot.totalFileCount() + " files" : "";
		rightHeader.setText("  " + headerLeft + "  —  " + humanSize(viewRoot.totalBytes()) + headerRight);

		// Re-list immediate files only when the viewRoot itself changes; files of a fixed
		// directory don't move during a scan.
		if (viewRoot != lastListedRoot) {
			currentFiles = listFiles(viewRoot.path());
			lastListedRoot = viewRoot;
		}

		// Combine current child directories with the cached file list, sort by size desc.
		List<Entry> entries = new ArrayList<>(viewRoot.children().size() + currentFiles.size());
		for (DirectoryNode c : viewRoot.children()) {
			entries.add(Entry.forDir(c));
		}
		entries.addAll(currentFiles);
		// Folders first (sorted by size desc with Hidden pinned last), then files
		// (sorted by size desc).
		entries.sort((a, b) -> {
			int byKind = Boolean.compare(b.isDirectory(), a.isDirectory());
			if (byKind != 0)
				return byKind;
			boolean aHidden = (a.dirNode() == hiddenNode);
			boolean bHidden = (b.dirNode() == hiddenNode);
			if (aHidden != bHidden)
				return aHidden ? 1 : -1;
			return Long.compare(b.currentSize(), a.currentSize());
		});

		if (!sameOrder(tableItems, entries)) {
			tableItems.setAll(entries);
		} else if (scanning) {
			// Same items in same positions; live size values still need to repaint.
			table.refresh();
		}
	}

	private static List<Entry> listFiles(Path dir) {
		List<Entry> out = new ArrayList<>();
		if (dir == null)
			return out;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				try {
					BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					if (attrs.isRegularFile()) {
						Path fileName = p.getFileName();
						String name = (fileName == null) ? p.toString() : fileName.toString();
						out.add(Entry.forFile(name, attrs.size()));
					}
				} catch (Exception ignore) {
					// Per-entry attribute read failure (permission, broken link, etc) — skip.
				}
			}
		} catch (Exception e) {
			// Directory not readable — return what we have.
		}
		return out;
	}

	/**
	 * Files at or above this size become their own sunburst sector. Smaller files are summed per directory and surface as a single "Smaller
	 * files" sector when the sum itself crosses the same threshold. 1 GB decimal — must match
	 * {@code WalkFileTreeScanner.LARGE_FILE_THRESHOLD_BYTES}.
	 */
	private static final long FILE_SECTOR_THRESHOLD = 1_000_000_000L;

	/**
	 * Walks {@code dir} and appends synthetic children for any large files (≥ threshold) the scanner recorded, plus a single "Smaller
	 * files" aggregate when the smaller-files sum on this directory also crosses the threshold. The synthetic children's bytes are already
	 * counted in {@code dir.totalBytes()} via the scanner's normal propagation, so no totals are bumped here.
	 */
	private static void injectFileChildrenInto(DirectoryNode dir) {
		// Snapshot real children before we add synthetic ones so the recursion doesn't
		// re-visit our own injections.
		List<DirectoryNode> realChildren = new ArrayList<>(dir.children());
		for (DirectoryNode c : realChildren) {
			injectFileChildrenInto(c);
		}
		for (DirectoryNode.FileRecord f : dir.largeFiles()) {
			DirectoryNode fileNode = new DirectoryNode(dir, f.name(), dir.path() != null ? dir.path().resolve(f.name()) : null);
			fileNode.addSyntheticBytes(f.size());
			fileNode.markDone();
			fileNode.markFileSector();
			dir.children().add(fileNode);
		}
		long smaller = dir.smallerFilesBytes();
		if (smaller >= FILE_SECTOR_THRESHOLD) {
			DirectoryNode smallNode = new DirectoryNode(dir, "Smaller files", null);
			smallNode.addSyntheticBytes(smaller);
			smallNode.markDone();
			smallNode.markFileSector();
			dir.children().add(smallNode);
		}
	}

	/**
	 * Builds the synthetic "Hidden" subtree from {@link #cachedHidden} and attaches it as a child of {@code scanRootNode}. The Hidden node
	 * itself and its children carry zero scanned bytes and no on-disk path, but their {@code totalBytes} is set so the sunburst renders
	 * them like any other sector. {@code scanRootNode}'s {@code totalBytes} is bumped by Hidden's bytes so children fractions sum to 1.
	 */
	private void injectHiddenInto(DirectoryNode scanRootNode) {
		MacHiddenSpace.HiddenSpace h = cachedHidden;
		if (h == null)
			return;
		if (h.totalBytes() <= 0 && h.localSnapshotCount() == 0 && lastPermDeniedCount == 0)
			return;
		if (hiddenNode != null)
			return;  // already injected (e.g. at scan start)

		DirectoryNode hidden = new DirectoryNode(scanRootNode, "Hidden", null);
		hidden.markDone();
		hiddenNode = hidden;

		DirectoryNode otherVols = new DirectoryNode(hidden, "Other volumes", null);
		otherVols.addSyntheticBytes(h.otherVolumesBytes());
		otherVols.markDone();
		hidden.children().add(otherVols);

		DirectoryNode snapshots = new DirectoryNode(hidden, "Snapshots", null);
		snapshots.markDone();
		hidden.children().add(snapshots);

		DirectoryNode other = new DirectoryNode(hidden, "Other", null);
		other.addSyntheticBytes(h.residualBytes());
		other.markDone();
		hidden.children().add(other);

		if (lastPermDeniedCount > 0) {
			DirectoryNode notAccess = new DirectoryNode(hidden, "Not accessible", null);
			notAccess.markDone();
			hidden.children().add(notAccess);
		}

		long hiddenTotal = h.totalBytes();
		hidden.addSyntheticBytes(hiddenTotal);
		scanRootNode.children().add(hidden);
		scanRootNode.addSyntheticBytes(hiddenTotal);
	}

	private static boolean sameOrder(List<Entry> a, List<Entry> b) {
		if (a.size() != b.size())
			return false;
		for (int i = 0; i < a.size(); i++) {
			Entry ea = a.get(i), eb = b.get(i);
			if (ea.isDirectory() != eb.isDirectory())
				return false;
			if (ea.isDirectory()) {
				if (ea.dirNode() != eb.dirNode())
					return false;
			} else {
				if (!ea.name().equals(eb.name()))
					return false;
			}
		}
		return true;
	}

	// ---- selection / animation ------------------------------------------

	private void select(DirectoryNode newViewRoot) {
		select(newViewRoot, true);
	}

	private void select(DirectoryNode newViewRoot, boolean clearForward) {
		if (newViewRoot == null)
			return;
		// File sectors (large-file leaves and "Smaller files" aggregates) aren't drillable
		// themselves — clicking them navigates to their containing directory so the table
		// lists everything inside it.
		if (newViewRoot.isFileSector() && newViewRoot.parent() != null) {
			newViewRoot = newViewRoot.parent();
		}
		if (newViewRoot == viewRoot)
			return;
		// Don't drill into synthetic terminals (Hidden's leaf rows like Snapshots / Other /
		// Not accessible). Drilling into the Hidden parent is fine — it has children.
		if (newViewRoot.path() == null && newViewRoot.children().isEmpty())
			return;
		if (clearForward)
			forwardStack.clear();

		Map<DirectoryNode, Layout> oldL = computeLayout(viewRoot);
		Map<DirectoryNode, Layout> newL = computeLayout(newViewRoot);

		animOldViewRoot = viewRoot;
		animNewViewRoot = newViewRoot;
		animOld = oldL;
		animNew = newL;

		viewRoot = newViewRoot;
		hoverNode = null;
		hoveringHub = false;
		refreshTable();
		rebuildBreadcrumb();

		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
		// Make sure the root has focus so keyboard shortcuts work after a drill.
		root.requestFocus();
	}

	/**
	 * Color for {@code node}'s sunburst sector. Used by both the canvas drawing path and the right-pane table swatch so they stay in sync.
	 * <p>Algorithm — DaisyDisk-style family inheritance:
	 * <ul>
	 *  <li>{@code scanRoot} → hub fill (no sector color).</li>
	 *  <li>File sectors (large files, "Smaller files") → grey via
	 *      {@link SectorPalette#forFileSector}, regardless of family.</li>
	 *  <li>Family root (immediate child of {@code scanRoot}) → palette pick by name.</li>
	 *  <li>Deeper descendants → parent's color, with a small hue shift, mild saturation
	 *      pull-back, and lightening proportional to sibling rank + depth. The rank-0 child
	 *      stays nearly identical to its parent (the "trunk"); higher-rank siblings drift
	 *      lighter and slightly hue-shifted (visible as the soft halo at the rim).</li>
	 * </ul>
	 */
	Color getNodeColor(DirectoryNode node) {
		if (node == null || node == scanRoot)
			return scheme.surface();
		Color cached = colorCache.get(node);
		if (cached != null)
			return cached;

		Color computed;
		if (node.isFileSector()) {
			int d = depthFromScanRoot(node);
			computed = SectorPalette.forFileSector(node.name(), Math.max(0, d - 1));
		} else if (node.parent() == scanRoot || node.parent() == null) {
			// Family root — pick from the palette by name, with collision avoidance so two
			// top-level siblings whose names hash to the same bucket don't render in the
			// same color (e.g. "System" and "Applications" both hash to idx 11 on JDK 25).
			if ("Hidden".equals(node.name())) {
				// Hidden has its own reserved grey via SectorPalette.forName.
				computed = SectorPalette.forName("Hidden", 0);
			} else {
				computed = SectorPalette.atIndex(allocateTopLevelIdx(node), 0);
			}
		} else {
			// Drive lightness off how much the child shrinks relative to its parent, not off
			// depth. A child that takes ~100% of its parent (a true "trunk" continuation)
			// keeps the parent's color exactly — without this the color washes toward white
			// in deep single-folder chains. Side branches with low fraction lighten and
			// hue-shift more, which is what creates the soft halo at the rim.
			Color parentColor = getNodeColor(node.parent());
			int rank = sortedRank(node);
			DirectoryNode p = node.parent();
			long parentBytes = p.totalBytes();
			double frac = parentBytes > 0 ? Math.min(1.0, (double) node.totalBytes() / parentBytes) : 1.0;
			double shrink = Math.max(0.0, 1.0 - frac);
			// Trunk vs branch — two different regimes:
			//
			//  * Rank 0 (trunk): the largest-descendant chain. Apply a small baseline
			//    darkening (5%) plus shrink-proportional darkening, so deep trunks visibly
			//    deepen ring-by-ring like roots into ground, even when each step takes
			//    ~100% of its parent. Saturation stays close to the parent's so warm
			//    colors don't go muddy.
			//
			//  * Rank ≥ 1 (side branches): brightness has hard clipping at 1.0, so once
			//    the brightFactor pushes past that, additional lightening does nothing.
			//    Push saturation DOWN aggressively instead — that's what makes a branch
			//    read as "pale / washed-out" relative to its parent rather than just
			//    "still saturated yellow." Combined with the brightness lift, the result
			//    is the cream/pastel halo at the rim.
			double brightFactor;
			double satFactor;
			if (rank == 0) {
				brightFactor = Math.max(0.65, 0.95 - shrink * 0.20);
				satFactor = Math.max(0.88, 1.0 - shrink * 0.04);
			} else {
				brightFactor = Math.min(1.35, 1.0 + shrink * 0.20 + rank * 0.05);
				satFactor = Math.max(0.30, 1.0 - shrink * 0.40 - rank * 0.04);
			}
			double hueShift = Math.min(20.0, rank * 5.0);
			if (rank == 0) {
				// Yellow-family trunks read as muddy/olive when darkened straight down. Pull
				// the hue toward red (-8° at full shrink) so darker yellows go amber/orange
				// — what the eye expects from "shaded yellow" in nature.
				double pHue = parentColor.getHue();
				if (pHue >= 30 && pHue <= 90) {
					hueShift -= shrink * 8.0;
				}
			}
			computed = parentColor.deriveColor(hueShift, satFactor, brightFactor, 1.0);
		}
		colorCache.put(node, computed);
		return computed;
	}

	/**
	 * Returns the palette index this top-level family will use, allocating on first access. Starts from
	 * {@code name.hashCode() % paletteSize} and walks forward to the first index not already claimed by a previously-allocated sibling — so
	 * two top-level siblings whose names happen to hash to the same bucket can't render identical.
	 */
	private int allocateTopLevelIdx(DirectoryNode node) {
		Integer cached = topLevelPaletteIdx.get(node);
		if (cached != null)
			return cached;
		int n = SectorPalette.paletteSize();
		java.util.Set<Integer> used = new java.util.HashSet<>(topLevelPaletteIdx.values());
		int idx = Math.floorMod(node.name().hashCode(), n);
		int tries = 0;
		while (used.contains(idx) && tries < n) {
			idx = (idx + 1) % n;
			tries++;
		}
		topLevelPaletteIdx.put(node, idx);
		return idx;
	}

	/**
	 * Per-tick: detect any top-level folders that have just transitioned to {@code DONE} and drop their descendants' cached colors. The
	 * next render re-derives those colors against the now-final sort order, so per-folder colors stabilize *as that folder finishes* rather
	 * than all flipping at the very end of the scan.
	 * <p>The top-level node itself is left in the cache because its color is hash-based via
	 * {@link #allocateTopLevelIdx}, not rank-based — it doesn't shift during the scan.
	 */
	private void stabilizeFinalizedTopLevels() {
		if (scanRoot == null)
			return;
		for (DirectoryNode c : scanRoot.children()) {
			if (c == hiddenNode)
				continue;
			if (c.isDone() && finalizedTopLevels.add(c)) {
				clearDescendantColors(c);
			}
		}
	}

	private void clearDescendantColors(DirectoryNode root) {
		java.util.Deque<DirectoryNode> stack = new java.util.ArrayDeque<>();
		for (DirectoryNode c : root.children())
			stack.push(c);
		while (!stack.isEmpty()) {
			DirectoryNode n = stack.pop();
			colorCache.remove(n);
			for (DirectoryNode c : n.children())
				stack.push(c);
		}
	}

	/**
	 * Sort comparator that puts {@link #hiddenNode} last and otherwise sorts by size desc. Used wherever scanRoot's children are ordered so
	 * Hidden never moves position as the scan progresses or as users navigate.
	 */
	private Comparator<DirectoryNode> hiddenLastSizeDesc() {
		return (a, b) -> {
			boolean aHidden = (a == hiddenNode);
			boolean bHidden = (b == hiddenNode);
			if (aHidden != bHidden)
				return aHidden ? 1 : -1;
			return Long.compare(b.totalBytes(), a.totalBytes());
		};
	}

	private int depthFromScanRoot(DirectoryNode node) {
		int d = 0;
		for (DirectoryNode n = node; n != null && n != scanRoot; n = n.parent())
			d++;
		return d;
	}

	private static int sortedRank(DirectoryNode node) {
		DirectoryNode parent = node.parent();
		if (parent == null)
			return 0;
		List<DirectoryNode> sorted = new ArrayList<>(parent.children());
		sorted.sort(Comparator.comparingLong(DirectoryNode::totalBytes).reversed());
		return Math.max(0, sorted.indexOf(node));
	}

	/**
	 * Inner radius of the ring at layout depth {@code depth}. Depths {@code <= NORMAL_RINGS} use full-width rings; deeper depths use thin
	 * rings. Accepts fractional depths so the drill-in animation can interpolate radii smoothly.
	 */
	private static double ringInnerR(double depth, double normalW, double thinW) {
		if (depth <= 1)
			return HUB_RADIUS;
		double normalRings = Math.min(depth - 1, NORMAL_RINGS);
		double thinRings = Math.max(0, depth - 1 - NORMAL_RINGS);
		return HUB_RADIUS + normalRings * normalW + thinRings * thinW;
	}

	private Map<DirectoryNode, Layout> computeLayout(DirectoryNode rootForView) {
		Map<DirectoryNode, Layout> out = new HashMap<>();
		if (rootForView == null)
			return out;

		// When viewing the scan root, ring 1 is the root's children (no anchor ring).
		// When drilled into a sector, ring 1 is that sector at 360° anchoring the view,
		// and ring 2 onward shows its descendants.
		boolean rootHasRing = rootForView != scanRoot;
		if (rootHasRing) {
			out.put(rootForView, new Layout(1, 90.0, 360.0, getNodeColor(rootForView)));
			layoutChildrenInto(rootForView, 2, 90.0, 360.0, out);
		} else {
			double usedSweep = target.totalBytes() > 0 ? target.usedFraction() * 360.0 : 360.0;
			double startAngle = 90.0 - usedSweep / 2.0;
			double scannedSweep =
					target.usedBytes() > 0 ? Math.min(usedSweep, usedSweep * rootForView.totalBytes() / (double) target.usedBytes())
							: usedSweep;
			layoutChildrenInto(rootForView, 1, startAngle, scannedSweep, out);
		}
		return out;
	}

	private void layoutChildrenInto(DirectoryNode parent, int depth, double startDeg, double sweepDeg, Map<DirectoryNode, Layout> out) {
		if (depth > MAX_DEPTH)
			return;
		long total = parent.totalBytes();
		if (total <= 0)
			return;

		List<DirectoryNode> ordered = new ArrayList<>(parent.children());
		// Hidden always lands at the end of scanRoot's children regardless of size — it's
		// less actionable than real folders and a stable position is more useful than a
		// size-correct one.
		ordered.sort(hiddenLastSizeDesc());

		double a = startDeg;
		for (DirectoryNode child : ordered) {
			double frac = child.totalBytes() / (double) total;
			double childSweep = sweepDeg * frac;
			if (childSweep < MIN_VISIBLE_SWEEP_DEG) {
				a += childSweep;
				continue;
			}
			out.put(child, new Layout(depth, a, childSweep, getNodeColor(child)));
			layoutChildrenInto(child, depth + 1, a, childSweep, out);
			a += childSweep;
		}
	}

	// ---- rendering -------------------------------------------------------

	private void redraw() {
		GraphicsContext g = canvas.getGraphicsContext2D();
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		g.setFill(scheme.background());
		g.fillRect(0, 0, w, h);

		sectors.clear();

		if (scanRoot == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}

		double cx = w / 2.0;
		double cy = h / 2.0;
		double maxR = Math.max(40, Math.min(w, h) * 0.46);
		// Pack the rings: NORMAL_RINGS at full thickness + THIN_RINGS at THIN_RING_FACTOR.
		double normalW = (maxR - HUB_RADIUS) / (NORMAL_RINGS + THIN_RINGS * THIN_RING_FACTOR);
		if (normalW < 6)
			normalW = 6;
		double thinW = normalW * THIN_RING_FACTOR;

		// Draw sectors first, hub on top so anti-aliasing edges are clipped cleanly.
		if (animating) {
			drawAnimatedFrame(g, cx, cy, normalW, thinW);
		} else if (viewRoot != null) {
			drawLayout(g, cx, cy, normalW, thinW, computeLayout(viewRoot));
		}

		drawHub(g, cx, cy);
	}

	private void drawLayout(GraphicsContext g, double cx, double cy, double normalW, double thinW, Map<DirectoryNode, Layout> layout) {
		// Render outer rings first so that any anti-aliasing edges are overdrawn cleanly
		// by the inner rings.
		List<Map.Entry<DirectoryNode, Layout>> entries = new ArrayList<>(layout.entrySet());
		entries.sort((a, b) -> Double.compare(b.getValue().depth(), a.getValue().depth()));

		for (Map.Entry<DirectoryNode, Layout> entry : entries) {
			DirectoryNode node = entry.getKey();
			Layout l = entry.getValue();
			if (l.sweepDeg() < MIN_VISIBLE_SWEEP_DEG)
				continue;

			double r1 = ringInnerR(l.depth(), normalW, thinW);
			double r2 = ringInnerR(l.depth() + 1, normalW, thinW);

			Color base = l.color();
			double alpha = node.isDone() ? 1.0 : 0.45;
			if (hoverNode == node) {
				// Universal hover: slight darkening + saturation boost. JavaFX brightness
				// clamps at 1.0, so .brighter() on already-light rim sectors is a no-op —
				// darken-plus-saturate guarantees visible change for both vivid and grey.
				base = base.deriveColor(0, 1.20, 0.85, 1.0);
				alpha = Math.min(1.0, alpha + 0.10);
			}
			Color fill = base.deriveColor(0, 1, 1, alpha);
			drawAnnularSector(g, cx, cy, r1, r2, l.startDeg(), l.sweepDeg(), fill);
			sectors.add(new SectorRect(node, (int) l.depth(), l.startDeg(), l.sweepDeg(), r1, r2, false));
		}

		if (viewRoot == scanRoot && target.totalBytes() > 0) {
			double usedFraction = target.usedFraction();
			double usedSweep = usedFraction * 360.0;
			double startAngle = 90.0 - usedSweep / 2.0;
			double r1 = HUB_RADIUS;
			double r2 = ringInnerR(2, normalW, thinW);

			long unaccountedBytes = target.usedBytes() - viewRoot.totalBytes();
			if (unaccountedBytes > 0 && target.usedBytes() > 0) {
				double scannedSweep = Math.min(usedSweep, usedSweep * viewRoot.totalBytes() / (double) target.usedBytes());
				double unaccountedSweep = usedSweep - scannedSweep;
				if (unaccountedSweep > MIN_VISIBLE_SWEEP_DEG) {
					double unaccountedStart = startAngle + scannedSweep;
					Color unaccountedColor = hoveringUnaccounted ? scheme.surface().brighter().brighter() : scheme.surface().brighter();
					drawAnnularSector(g, cx, cy, r1, r2, unaccountedStart, unaccountedSweep, unaccountedColor);
					sectors.add(new SectorRect(null, 1, unaccountedStart, unaccountedSweep, r1, r2, true));
				}
			}

			double freeSweep = (1.0 - usedFraction) * 360.0;
			if (freeSweep > MIN_VISIBLE_SWEEP_DEG) {
				double freeStart = 90.0 + usedSweep / 2.0;
				Color freeColor = hoveringFreeSpace ? scheme.capacityTrack().brighter() : scheme.capacityTrack();
				drawAnnularSector(g, cx, cy, r1, r2, freeStart, freeSweep, freeColor);
				sectors.add(new SectorRect(null, 1, freeStart, freeSweep, r1, r2, false));
			}
		}
	}

	private void drawAnimatedFrame(GraphicsContext g, double cx, double cy, double normalW, double thinW) {
		long elapsed = System.nanoTime() - animStartNanos;
		double t = Math.min(1.0, elapsed / (double) ANIM_DURATION_NANOS);
		double e = easeOutCubic(t);

		Set<DirectoryNode> all = new HashSet<>(animOld.keySet());
		all.addAll(animNew.keySet());

		List<FrameEntry> frame = new ArrayList<>(all.size());
		for (DirectoryNode n : all) {
			Layout o = animOld.get(n);
			Layout w = animNew.get(n);
			Layout from, to;
			double alphaScale = 1.0;
			Color color = getNodeColor(n);
			if (o != null && w != null) {
				from = o;
				to = w;
			} else if (o != null) {
				if (n == animOldViewRoot) {
					// Outgoing inner ring (drill-in): stay in place, fade out as the new
					// viewRoot grows over it.
					from = o;
					to = o;
					alphaScale = 1 - e;
				} else {
					// Sibling/cousin not in new view: shrink in place.
					from = o;
					to = new Layout(o.depth(), o.startDeg() + o.sweepDeg() / 2, 0, color);
				}
			} else {
				if (n == animNewViewRoot) {
					// Incoming inner ring (drill-out): fade in at destination.
					from = w;
					to = w;
					alphaScale = e;
				} else {
					// Newly visible deep node: grow from a point.
					from = new Layout(w.depth(), w.startDeg() + w.sweepDeg() / 2, 0, color);
					to = w;
				}
			}
			double depth = lerp(from.depth(), to.depth(), e);
			double start = lerp(from.startDeg(), to.startDeg(), e);
			double sweep = lerp(from.sweepDeg(), to.sweepDeg(), e);
			if (sweep < 0.05)
				continue;
			frame.add(new FrameEntry(n, depth, start, sweep, alphaScale, color));
		}

		// Render outer rings first so inner rings overdraw on radial overlap regions.
		// For ties on depth (e.g., growing clicked sector overlapping shrinking siblings
		// in the same ring), draw smaller sweeps first so the larger sweep overdraws.
		frame.sort(Comparator.comparingDouble(FrameEntry::depth).reversed().thenComparingDouble(FrameEntry::sweep));

		for (FrameEntry fe : frame) {
			double r1 = Math.max(1, ringInnerR(fe.depth, normalW, thinW));
			double r2 = Math.max(r1 + 1, ringInnerR(fe.depth + 1, normalW, thinW));
			Color base = fe.color;
			double alpha = (fe.node.isDone() ? 1.0 : 0.45) * fe.alphaScale;
			if (alpha <= 0.001)
				continue;
			Color fill = base.deriveColor(0, 1, 1, alpha);
			drawAnnularSector(g, cx, cy, r1, r2, fe.start, fe.sweep, fill);
		}
	}

	private void drawAnnularSector(
			GraphicsContext g, double cx, double cy, double r1, double r2, double startDeg, double sweepDeg,
			Color fill) {
		double a1 = Math.toRadians(startDeg);
		double a2 = Math.toRadians(startDeg + sweepDeg);
		g.setFill(fill);
		g.beginPath();
		g.moveTo(cx + r2 * Math.cos(a1), cy - r2 * Math.sin(a1));
		g.arc(cx, cy, r2, r2, startDeg, sweepDeg);
		g.lineTo(cx + r1 * Math.cos(a2), cy - r1 * Math.sin(a2));
		g.arc(cx, cy, r1, r1, startDeg + sweepDeg, -sweepDeg);
		g.closePath();
		g.fill();

		g.setStroke(scheme.background());
		g.setLineWidth(0.8);
		g.stroke();
	}

	private void drawHub(GraphicsContext g, double cx, double cy) {
		Color hubFill = hoveringHub ? scheme.surface().brighter() : scheme.surface();
		g.setFill(hubFill);
		g.fillOval(cx - HUB_RADIUS, cy - HUB_RADIUS, HUB_RADIUS * 2, HUB_RADIUS * 2);

		// Decide what the hub displays right now.
		String title;
		String subtitle;
		if (hoveringFreeSpace) {
			title = "Free";
			subtitle = humanSize(target.usableBytes());
		} else if (hoveringUnaccounted) {
			title = "Other";
			subtitle = humanSize(Math.max(0, target.usedBytes() - (scanRoot != null ? scanRoot.totalBytes() : 0)));
		} else {
			DirectoryNode focus;
			if (hoveringHub) {
				focus = scanRoot;
			} else if (hoverNode != null) {
				focus = hoverNode;
			} else {
				focus = viewRoot;
			}
			if (focus == null)
				return;
			if (scanning && focus == scanRoot && !hoveringHub && hoverNode == null) {
				title = humanSize(progressBytes);
				subtitle = progressFiles + " files";
			} else {
				title = (focus == scanRoot) ? target.displayName() : focus.name();
				subtitle = humanSize(focus.totalBytes());
			}
		}

		g.setFill(scheme.textPrimary());
		g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.fillText(truncate(title, 18), cx, cy - 10, HUB_RADIUS * 1.7);

		g.setFill(scheme.textMuted());
		g.setFont(Font.font("Segoe UI", 11));
		g.fillText(subtitle, cx, cy + 8, HUB_RADIUS * 1.7);

		// Third line: only during scan, current path tail.
		if (scanning && hoverNode == null && !hoveringHub && progressPath != null) {
			g.setFill(scheme.textMuted().deriveColor(0, 1, 1, 0.6));
			g.setFont(Font.font("Segoe UI", 10));
			g.fillText(truncate(tailPath(progressPath), 22), cx, cy + 24, HUB_RADIUS * 1.85);
		}

		if (scanning) {
			drawHubProgress(g, cx, cy);
		}
	}

	private void drawHubProgress(GraphicsContext g, double cx, double cy) {
		double r = HUB_RADIUS - 4;
		double thickness = 2.5;
		long usedBytes = target.totalBytes() - target.usableBytes();

		g.setStroke(scheme.accent());
		g.setLineWidth(thickness);
		g.setLineCap(StrokeLineCap.ROUND);

		if (usedBytes > 0 && progressBytes > 0) {
			double frac = Math.min(1.0, progressBytes / (double) usedBytes);
			// Draw a faint full track first.
			g.setStroke(scheme.accent().deriveColor(0, 1, 1, 0.18));
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90, 360, ArcType.OPEN);
			// Then the filled portion clockwise from 12 o'clock.
			g.setStroke(scheme.accent());
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90, -360 * frac, ArcType.OPEN);
		} else {
			// Indeterminate: a 60° segment that rotates clockwise once every ~1.6s.
			double offset = (System.nanoTime() / 1_000_000.0 / 4.5) % 360.0; // deg/ms-ish
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90 - offset, -60, ArcType.OPEN);
		}
	}

	private void drawCenterText(GraphicsContext g, double cx, double cy, String text) {
		g.setFill(scheme.textMuted());
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.setFont(Font.font("Segoe UI", 13));
		g.fillText(text, cx, cy);
	}

	// ---- interaction -----------------------------------------------------

	private void handleMouseMove(double mx, double my) {
		if (scanRoot == null || animating)
			return;
		double cx = canvas.getWidth() / 2.0;
		double cy = canvas.getHeight() / 2.0;
		double dx = mx - cx;
		double dy = my - cy;
		double r = Math.hypot(dx, dy);

		boolean wasHub = hoveringHub;
		boolean wasFree = hoveringFreeSpace;
		boolean wasUnaccounted = hoveringUnaccounted;
		DirectoryNode wasNode = hoverNode;

		if (r < HUB_RADIUS) {
			hoveringHub = true;
			hoverNode = null;
			hoveringFreeSpace = false;
			hoveringUnaccounted = false;
		} else {
			hoveringHub = false;
			hoverNode = null;
			hoveringFreeSpace = false;
			hoveringUnaccounted = false;
			double theta = Math.toDegrees(Math.atan2(-dy, dx));
			if (theta < 0)
				theta += 360;
			for (SectorRect s : sectors) {
				if (r >= s.r1 && r <= s.r2 && angleInSweep(theta, s.startDeg, s.sweepDeg)) {
					if (s.node() == null) {
						if (s.unaccounted())
							hoveringUnaccounted = true;
						else
							hoveringFreeSpace = true;
					} else {
						hoverNode = s.node();
					}
					break;
				}
			}
		}

		if (wasHub != hoveringHub || wasFree != hoveringFreeSpace || wasUnaccounted != hoveringUnaccounted || wasNode != hoverNode) {
			redraw();
		}
	}

	private void handleClick(double mx, double my) {
		if (scanRoot == null || animating)
			return;
		root.requestFocus();
		double cx = canvas.getWidth() / 2.0;
		double cy = canvas.getHeight() / 2.0;
		double dx = mx - cx;
		double dy = my - cy;
		double r = Math.hypot(dx, dy);

		if (r < HUB_RADIUS) {
			// Reset to scan root, but record intermediates so Right can replay.
			navigateUpTo(scanRoot);
			return;
		}

		double theta = Math.toDegrees(Math.atan2(-dy, dx));
		if (theta < 0)
			theta += 360;
		for (SectorRect s : sectors) {
			if (r >= s.r1 && r <= s.r2 && angleInSweep(theta, s.startDeg, s.sweepDeg)) {
				if (s.node() != null)
					select(s.node());
				return;
			}
		}
	}

	/**
	 * Walk from current viewRoot up to {@code targetAncestor}, pushing each intermediate node onto the forward stack so Right arrow can
	 * replay the path. {@code targetAncestor} must be an ancestor of viewRoot (or equal to it).
	 */
	private void navigateUpTo(DirectoryNode targetAncestor) {
		if (targetAncestor == viewRoot)
			return;
		for (DirectoryNode cur = viewRoot; cur != null && cur != targetAncestor; cur = cur.parent()) {
			forwardStack.push(cur);
		}
		select(targetAncestor, false);
	}

	private void rebuildBreadcrumb() {
		breadcrumb.getChildren().clear();
		if (scanRoot == null || viewRoot == null)
			return;

		// Walk parent chain from viewRoot up to scanRoot.
		List<DirectoryNode> chain = new ArrayList<>();
		for (DirectoryNode n = viewRoot; n != null; n = n.parent()) {
			chain.add(0, n);
			if (n == scanRoot)
				break;
		}
		if (chain.isEmpty() || chain.get(0) != scanRoot) {
			// viewRoot got disconnected somehow — render just the current node.
			chain.clear();
			chain.add(viewRoot);
		}

		final int max = 5;
		if (chain.size() <= max) {
			for (int i = 0; i < chain.size(); i++) {
				if (i > 0)
					breadcrumb.getChildren().add(separatorLabel());
				breadcrumb.getChildren().add(crumbLabel(chain.get(i), i == chain.size() - 1));
			}
		} else {
			// Root  ›  …  ›  grandparent  ›  parent  ›  current
			int n = chain.size();
			breadcrumb.getChildren().add(crumbLabel(chain.get(0), false));
			breadcrumb.getChildren().add(separatorLabel());
			breadcrumb.getChildren().add(ellipsisLabel(chain.subList(1, n - 3)));
			breadcrumb.getChildren().add(separatorLabel());
			breadcrumb.getChildren().add(crumbLabel(chain.get(n - 3), false));
			breadcrumb.getChildren().add(separatorLabel());
			breadcrumb.getChildren().add(crumbLabel(chain.get(n - 2), false));
			breadcrumb.getChildren().add(separatorLabel());
			breadcrumb.getChildren().add(crumbLabel(chain.get(n - 1), true));
		}
	}

	private Label crumbLabel(DirectoryNode node, boolean active) {
		String text = (node == scanRoot) ? target.displayName() : node.name();
		Label l = new Label(text);
		l.setMaxWidth(160);
		l.setStyle(crumbStyle(active, false));
		if (!active) {
			l.setOnMouseEntered(e -> l.setStyle(crumbStyle(false, true)));
			l.setOnMouseExited(e -> l.setStyle(crumbStyle(false, false)));
			l.setOnMouseClicked(e -> {
				if (!animating)
					navigateUpTo(node);
			});
		}
		return l;
	}

	private String crumbStyle(boolean active, boolean hovered) {
		Color color;
		String weight;
		if (active) {
			color = scheme.textPrimary();
			weight = "600";
		} else if (hovered) {
			color = scheme.textPrimary();
			weight = "400";
		} else {
			color = scheme.textMuted();
			weight = "400";
		}
		return "-fx-text-fill: " + css(color) + ";" + "-fx-font-size: 11.5px; -fx-font-weight: " + weight + ";" + (active ? ""
				: "-fx-cursor: hand;");
	}

	private Label separatorLabel() {
		// Use › (U+203A) — Latin punctuation, present in default fonts on all platforms.
		// Avoid ❯ (U+276F, Dingbats) — JavaFX falls back to a font on macOS that
		// renders it as horizontal bars rather than a chevron. Bump size + weight to
		// recover visual heft against the muted color.
		Label l = new Label("›");
		l.setStyle("-fx-text-fill: " + css(
				scheme.textMuted()) + ";" + "-fx-font-size: 14px; -fx-font-weight: bold;" + "-fx-padding: 0 2 0 2;");
		return l;
	}

	private Label ellipsisLabel(List<DirectoryNode> hidden) {
		Label l = new Label("…");
		l.setStyle("-fx-text-fill: " + css(scheme.textMuted()) + ";-fx-font-size: 12px;");
		if (!hidden.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (DirectoryNode n : hidden) {
				if (sb.length() > 0)
					sb.append("\n");
				sb.append(n.name());
			}
			l.setTooltip(new javafx.scene.control.Tooltip(sb.toString()));
		}
		return l;
	}

	private void openInExplorer() {
		DirectoryNode target = (hoverNode != null) ? hoverNode : viewRoot;
		if (target == null || target.path() == null)
			return;
		try {
			if (isMac()) {
				// Desktop.open() from JavaFX on macOS silently fails — AWT and JavaFX
				// contend for the AppKit main thread. Shell out to `open` instead.
				new ProcessBuilder("open", target.path().toString()).start();
			} else {
				java.awt.Desktop.getDesktop().open(target.path().toFile());
			}
		} catch (Exception ignored) {
			// No fatal handling; if the platform doesn't support it, do nothing.
		}
	}

	// ---- helpers ---------------------------------------------------------

	private static boolean angleInSweep(double theta, double start, double sweep) {
		// Normalize start to [0, 360). Layout angles can grow past 360° as the iterator
		// walks counterclockwise around the full circle (sectors in the upper-right
		// quadrant end up with start > 360°). Without normalization the wrap branch
		// below mis-classifies their range.
		start = ((start % 360) + 360) % 360;
		double end = start + sweep;
		if (end <= 360)
			return theta >= start && theta <= end;
		return theta >= start || theta <= (end - 360);
	}

	private static String truncate(String s, int max) {
		if (s == null)
			return "";
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static String tailPath(String p) {
		if (p == null)
			return "";
		int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
		return slash < 0 ? p : p.substring(slash + 1);
	}

	static String humanSize(long bytes) {
		return SizeFormat.format(bytes);
	}

	private static String bg(Color c) {
		return "-fx-background-color: " + css(c) + ";";
	}

	private static String css(Color c) {
		return String.format("rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
				(int) Math.round(c.getBlue() * 255), c.getOpacity());
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double easeOutCubic(double t) {
		double inv = 1 - t;
		return 1 - inv * inv * inv;
	}

	private record SectorRect(DirectoryNode node, int depth, double startDeg, double sweepDeg, double r1, double r2, boolean unaccounted) {
	}

	private record Layout(double depth, double startDeg, double sweepDeg, Color color) {
	}

	private record FrameEntry(DirectoryNode node, double depth, double start, double sweep, double alphaScale, Color color) {
	}

	/**
	 * Row in the staging (delete-tray) table: a folder or file the user has marked for deletion. {@code parentNode} is captured at staging
	 * time so a successful delete can apply size/count deltas to the in-memory tree without re-walking.
	 */
	private record StagedItem(boolean isDirectory, java.nio.file.Path path, long sizeAtStaging, DirectoryNode dirNode,
							  DirectoryNode parentNode) {
		long currentSize() {
			return isDirectory && dirNode != null ? dirNode.totalBytes() : sizeAtStaging;
		}

		String displayPath() {
			return path == null ? "" : path.toString();
		}
	}

	/** Row in the right-side table: either a child directory or an immediate file of the viewRoot. */
	private record Entry(boolean isDirectory, String name, long staticSize, DirectoryNode dirNode) {
		long currentSize() {
			return isDirectory ? dirNode.totalBytes() : staticSize;
		}

		static Entry forDir(DirectoryNode n) {
			return new Entry(true, n.name(), 0L, n);
		}

		static Entry forFile(String name, long size) {
			return new Entry(false, name, size, null);
		}
	}
}
