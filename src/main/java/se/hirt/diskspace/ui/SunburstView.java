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

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.scan.WalkFileTreeScanner;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.SectorPalette;

public final class SunburstView {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SunburstView.class.getName());

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(SunburstView.class);
    private static final String PREF_FDA_SKIP = "fda.prompt.skip";

    private static final int MAX_DEPTH = 6;
    private static final double HUB_RADIUS = 78;
    private static final double MIN_VISIBLE_SWEEP_DEG = 0.6;
    private static final long LIVE_REFRESH_INTERVAL_NANOS = 100_000_000L; // 10 Hz
    private static final long ANIM_DURATION_NANOS = 350_000_000L;        // 350 ms

    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;
    private static final long TIB = GIB * 1024L;

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
    private BorderPane stagingPane;
    private Button cancelStagingButton;
    private Button deleteStagedButton;
    private volatile boolean deleting;

    private final List<SectorRect> sectors = new ArrayList<>();
    private DirectoryNode scanRoot;
    private DirectoryNode viewRoot;
    private DirectoryNode hoverNode;
    private boolean hoveringHub;
    private boolean hoveringFreeSpace;
    private volatile boolean scanning = true;

    private long progressFiles;
    private long progressBytes;
    private String progressPath;

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
        canvas.setOnMouseExited(e -> { hoverNode = null; hoveringHub = false; hoveringFreeSpace = false; redraw(); });
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
        rightHeader.setStyle(
                "-fx-text-fill: " + css(scheme.textMuted()) + ";"
                        + "-fx-font-size: 11px; -fx-padding: 8 12 8 12;");
        BorderPane right = new BorderPane(rightSplit);
        right.setTop(rightHeader);
        right.setStyle(bg(scheme.background()));

        root = new SplitPane(leftStack, right);
        root.setStyle(bg(scheme.background()));
        root.setDividerPositions(0.70);
        SplitPane.setResizableWithParent((Region) right, false);

        liveTicker = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastTickNanos < LIVE_REFRESH_INTERVAL_NANOS) return;
                lastTickNanos = now;
                refreshTable();
                if (!stagedItems.isEmpty()) {
                    stagingTable.refresh();
                    updateStagingFooter();
                }
                if (!animating) redraw();
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
            if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown()) return;
            switch (e.getCode()) {
                case E -> {
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
                    if (!deleting) rescan();
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
        table.setStyle(
                "-fx-background-color: " + css(scheme.background()) + ";"
                        + "-fx-control-inner-background: " + css(scheme.background()) + ";"
                        + "-fx-text-fill: " + css(scheme.textPrimary()) + ";"
                        + "-fx-table-cell-border-color: transparent;");

        TableColumn<Entry, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        nameCol.setPrefWidth(200);
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(9, 9);
            { swatch.setArcWidth(3); swatch.setArcHeight(3); }

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
                if (e != null && e.isDirectory() && e.dirNode() != null) {
                    DirectoryNode node = e.dirNode();
                    swatch.setFill(SectorPalette.forName(node.name(), 0));
                    setGraphic(swatch);
                    switch (node.state()) {
                        case QUEUED -> {
                            setText(item + "  <queued>");
                            setStyle("-fx-font-weight: bold; -fx-text-fill: "
                                    + css(scheme.textMuted().darker()) + ";");
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

        // Subtle row tint for folders so they read as a section even before the file rows below.
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || !item.isDirectory()) {
                    setStyle("");
                } else {
                    setStyle("-fx-background-color: rgba(255, 255, 255, 0.045);");
                }
            }
        });
    }

    // ---- staging UI ------------------------------------------------------

    private void configureStagingTable() {
        stagingTable.setItems(stagedItems);
        stagingTable.setPlaceholder(new Label(""));
        stagingTable.setStyle(
                "-fx-background-color: " + css(scheme.background()) + ";"
                        + "-fx-control-inner-background: " + css(scheme.background()) + ";"
                        + "-fx-text-fill: " + css(scheme.textPrimary()) + ";"
                        + "-fx-table-cell-border-color: transparent;");
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
        header.setStyle(
                "-fx-text-fill: " + css(scheme.textMuted()) + ";"
                        + "-fx-font-size: 11px; -fx-padding: 8 12 8 12;"
                        + "-fx-border-color: " + css(scheme.surface()) + " transparent transparent transparent;"
                        + "-fx-border-width: 1 0 0 0;");

        stagingFooterLabel.setStyle(
                "-fx-text-fill: " + css(scheme.textMuted()) + ";"
                        + "-fx-font-size: 11px;");

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
            return java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
        } catch (Throwable t) {
            return false;
        }
    }

    private void confirmAndDelete() {
        if (stagedItems.isEmpty() || deleting) return;
        boolean trash = canMoveToTrash();
        long total = 0;
        for (StagedItem si : stagedItems) total += si.currentSize();

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
        alert.setHeaderText((trash ? "Move " : "Permanently delete ")
                + stagedItems.size() + " item" + (stagedItems.size() == 1 ? "" : "s")
                + " (" + humanSize(total) + ")?");
        alert.setContentText(body.toString());
        ButtonType go = new ButtonType(trash ? "Move to Trash" : "Delete",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(go, ButtonType.CANCEL);

        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() != go) return;

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
                    if (!ok) throw new java.io.IOException("moveToTrash returned false");
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
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir,
                        java.io.IOException exc) throws java.io.IOException {
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
            for (int i = 0; i < shown; i++) body.append(result.failures.get(i)).append('\n');
            if (result.failures.size() > shown) {
                body.append("\n… and ").append(result.failures.size() - shown).append(" more");
            }
            a.setContentText(body.toString());
            a.show();
        }
    }

    private void updateAfterDelete(List<StagedItem> deleted) {
        if (deleted.isEmpty()) return;

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
                    if (parent == viewRoot) filesListChanged = true;
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
            if (n == candidate) return true;
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
        forwardStack.clear();
        progressFiles = 0;
        progressBytes = 0;
        progressPath = null;
        scanning = true;
        lastListedRoot = null;
        currentFiles = List.of();
        tableItems.clear();
        sectors.clear();
        rebuildBreadcrumb();
        redraw();
        startScan();
    }

    private record DeleteResult(List<StagedItem> deleted, List<String> failures) {
        int succeeded() { return deleted.size(); }
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
        if (candidate == null || candidate.path() == null) return;
        // If an ancestor is already staged, the candidate is already covered.
        for (StagedItem existing : stagedItems) {
            if (candidate.path().equals(existing.path())) return;
            if (candidate.path().startsWith(existing.path())) return;
        }
        // Remove any existing item that the candidate covers (descendants of candidate).
        stagedItems.removeIf(existing ->
                !existing.path().equals(candidate.path())
                        && existing.path().startsWith(candidate.path()));
        stagedItems.add(candidate);
    }

    private StagedItem entryToStaged(Entry e) {
        if (e.isDirectory()) {
            return dirToStaged(e.dirNode());
        }
        java.nio.file.Path filePath = (viewRoot != null && viewRoot.path() != null)
                ? viewRoot.path().resolve(e.name())
                : null;
        // For a file row, the parent node is whatever directory we're currently viewing.
        return new StagedItem(false, filePath, e.staticSize(), null, viewRoot);
    }

    private StagedItem dirToStaged(DirectoryNode n) {
        if (n == null) return null;
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
        for (StagedItem si : stagedItems) total += si.currentSize();
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
        sb.append(String.format("  Scanned      : %s  (%d files)%n",
                humanSize(root.totalBytes()), root.totalFileCount()));
        sb.append(String.format("  Unaccounted  : %s%n",
                humanSize(Math.abs(target.usedBytes() - root.totalBytes()))));
        sb.append("  Root breakdown (by size):\n");
        for (DirectoryNode child : children) {
            double pct = root.totalBytes() > 0
                    ? 100.0 * child.totalBytes() / root.totalBytes() : 0;
            sb.append(String.format("    %-32s %10s  (%4.1f%%)%n",
                    child.name(), humanSize(child.totalBytes()), pct));
        }
        LOG.info(sb.toString());
    }

    private void showPermissionDeniedDialog(long count) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Limited Access");
        alert.setHeaderText(count + " location" + (count == 1 ? "" : "s") + " couldn't be read");
        alert.setContentText(
                "Grant DiskSpace Full Disk Access in System Settings\n"
                + "to include protected directories in the scan.");
        ButtonType openSettings = new ButtonType("Open System Settings");
        alert.getButtonTypes().setAll(openSettings, ButtonType.CANCEL);
        alert.showAndWait()
             .filter(b -> b == openSettings)
             .ifPresent(b -> {
                 try {
                     new ProcessBuilder("open",
                             "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")
                             .start();
                 } catch (java.io.IOException ignore) {}
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
        if (!Files.exists(tcc)) return true;
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
                "Without Full Disk Access, protected folders like Mail, Messages,\n"
                + "and system directories won't be included in the scan.\n\n"
                + "DiskSpace only reads names, sizes, and folder structure.\n"
                + "It never reads file contents, and never modifies files\n"
                + "without your explicit action.");
        ButtonType openSettings = new ButtonType("Open System Settings…");
        ButtonType scanAnyway = new ButtonType("Scan without Full Access");
        alert.getButtonTypes().setAll(openSettings, scanAnyway);
        alert.showAndWait().ifPresent(b -> {
            if (b == openSettings) {
                try {
                    new ProcessBuilder("open",
                            "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")
                            .start();
                } catch (java.io.IOException ignore) {}
            } else {
                PREFS.putBoolean(PREF_FDA_SKIP, true);
            }
        });
    }

    private void doStartScan() {
        scanner.scan(target.root(), new Scanner.ScanListener() {
            @Override
            public void onStart(DirectoryNode liveRoot) {
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
                Platform.runLater(() -> showPermissionDeniedDialog(count));
            }

            @Override
            public void onComplete(DirectoryNode result) {
                logScanSummary(result);
                Platform.runLater(() -> {
                    scanning = false;
                    liveTicker.stop();
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

    private void refreshTable() {
        if (viewRoot == null) {
            tableItems.clear();
            rightHeader.setText("");
            return;
        }
        rightHeader.setText("  " + viewRoot.path() + "  —  " + humanSize(viewRoot.totalBytes())
                + "   " + viewRoot.totalFileCount() + " files");

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
        // Folders first (sorted by size desc), then files (sorted by size desc).
        entries.sort((a, b) -> {
            int byKind = Boolean.compare(b.isDirectory(), a.isDirectory());
            if (byKind != 0) return byKind;
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
        if (dir == null) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
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

    private static boolean sameOrder(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Entry ea = a.get(i), eb = b.get(i);
            if (ea.isDirectory() != eb.isDirectory()) return false;
            if (ea.isDirectory()) {
                if (ea.dirNode() != eb.dirNode()) return false;
            } else {
                if (!ea.name().equals(eb.name())) return false;
            }
        }
        return true;
    }

    // ---- selection / animation ------------------------------------------

    private void select(DirectoryNode newViewRoot) {
        select(newViewRoot, true);
    }

    private void select(DirectoryNode newViewRoot, boolean clearForward) {
        if (newViewRoot == null || newViewRoot == viewRoot) return;
        if (clearForward) forwardStack.clear();

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
        // Make sure the root has focus so the 'E' shortcut works after a drill.
        root.requestFocus();
    }

    private Map<DirectoryNode, Layout> computeLayout(DirectoryNode rootForView) {
        Map<DirectoryNode, Layout> out = new HashMap<>();
        if (rootForView == null) return out;

        // When viewing the scan root, ring 1 is the root's children (no anchor ring).
        // When drilled into a sector, ring 1 is that sector at 360° anchoring the view,
        // and ring 2 onward shows its descendants.
        boolean rootHasRing = rootForView != scanRoot;
        if (rootHasRing) {
            out.put(rootForView, new Layout(1, 90.0, 360.0));
            layoutChildrenInto(rootForView, 2, 90.0, 360.0, out);
        } else {
            double usedSweep = target.totalBytes() > 0 ? target.usedFraction() * 360.0 : 360.0;
            double startAngle = 90.0 - usedSweep / 2.0;
            layoutChildrenInto(rootForView, 1, startAngle, usedSweep, out);
        }
        return out;
    }

    private void layoutChildrenInto(DirectoryNode parent, int depth,
                                    double startDeg, double sweepDeg,
                                    Map<DirectoryNode, Layout> out) {
        if (depth > MAX_DEPTH) return;
        long total = parent.totalBytes();
        if (total <= 0) return;

        List<DirectoryNode> ordered = new ArrayList<>(parent.children());
        ordered.sort(Comparator.comparingLong(DirectoryNode::totalBytes).reversed());

        double a = startDeg;
        for (DirectoryNode child : ordered) {
            double frac = child.totalBytes() / (double) total;
            double childSweep = sweepDeg * frac;
            if (childSweep < MIN_VISIBLE_SWEEP_DEG) {
                a += childSweep;
                continue;
            }
            out.put(child, new Layout(depth, a, childSweep));
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
        double ringWidth = (maxR - HUB_RADIUS) / MAX_DEPTH;
        if (ringWidth < 6) ringWidth = 6;

        // Draw sectors first, hub on top so anti-aliasing edges are clipped cleanly.
        if (animating) {
            drawAnimatedFrame(g, cx, cy, ringWidth);
        } else if (viewRoot != null) {
            drawLayout(g, cx, cy, ringWidth, computeLayout(viewRoot));
        }

        drawHub(g, cx, cy);
    }

    private void drawLayout(GraphicsContext g, double cx, double cy, double ringWidth,
                            Map<DirectoryNode, Layout> layout) {
        // Render outer rings first so that any anti-aliasing edges are overdrawn cleanly
        // by the inner rings.
        List<Map.Entry<DirectoryNode, Layout>> entries = new ArrayList<>(layout.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue().depth(), a.getValue().depth()));

        for (Map.Entry<DirectoryNode, Layout> entry : entries) {
            DirectoryNode node = entry.getKey();
            Layout l = entry.getValue();
            if (l.sweepDeg() < MIN_VISIBLE_SWEEP_DEG) continue;

            double r1 = HUB_RADIUS + (l.depth() - 1) * ringWidth;
            double r2 = HUB_RADIUS + l.depth() * ringWidth;

            Color base = SectorPalette.forName(node.name(), Math.max(0, (int) l.depth() - 1));
            double alpha = node.isDone() ? 1.0 : 0.45;
            if (hoverNode == node) {
                base = base.brighter();
                alpha = Math.min(1.0, alpha + 0.25);
            }
            Color fill = base.deriveColor(0, 1, 1, alpha);
            drawAnnularSector(g, cx, cy, r1, r2, l.startDeg(), l.sweepDeg(), fill);
            sectors.add(new SectorRect(node, (int) l.depth(), l.startDeg(), l.sweepDeg(), r1, r2));
        }

        if (viewRoot == scanRoot && target.totalBytes() > 0) {
            double usedFraction = target.usedFraction();
            double freeSweep = (1.0 - usedFraction) * 360.0;
            if (freeSweep > MIN_VISIBLE_SWEEP_DEG) {
                double freeStart = 90.0 + usedFraction * 180.0;
                double r1 = HUB_RADIUS;
                double r2 = HUB_RADIUS + ringWidth;
                Color freeColor = hoveringFreeSpace
                        ? scheme.capacityTrack().brighter()
                        : scheme.capacityTrack();
                drawAnnularSector(g, cx, cy, r1, r2, freeStart, freeSweep, freeColor);
                sectors.add(new SectorRect(null, 1, freeStart, freeSweep, r1, r2));
            }
        }
    }

    private void drawAnimatedFrame(GraphicsContext g, double cx, double cy, double ringWidth) {
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
            if (o != null && w != null) {
                from = o; to = w;
            } else if (o != null) {
                if (n == animOldViewRoot) {
                    // Outgoing inner ring (drill-in): stay in place, fade out as the new
                    // viewRoot grows over it.
                    from = o; to = o;
                    alphaScale = 1 - e;
                } else {
                    // Sibling/cousin not in new view: shrink in place.
                    from = o;
                    to = new Layout(o.depth(), o.startDeg() + o.sweepDeg() / 2, 0);
                }
            } else {
                if (n == animNewViewRoot) {
                    // Incoming inner ring (drill-out): fade in at destination.
                    from = w; to = w;
                    alphaScale = e;
                } else {
                    // Newly visible deep node: grow from a point.
                    from = new Layout(w.depth(), w.startDeg() + w.sweepDeg() / 2, 0);
                    to = w;
                }
            }
            double depth = lerp(from.depth(), to.depth(), e);
            double start = lerp(from.startDeg(), to.startDeg(), e);
            double sweep = lerp(from.sweepDeg(), to.sweepDeg(), e);
            if (sweep < 0.05) continue;
            frame.add(new FrameEntry(n, depth, start, sweep, alphaScale));
        }

        // Render outer rings first so inner rings overdraw on radial overlap regions.
        // For ties on depth (e.g., growing clicked sector overlapping shrinking siblings
        // in the same ring), draw smaller sweeps first so the larger sweep overdraws.
        frame.sort(Comparator.<FrameEntry>comparingDouble(FrameEntry::depth).reversed()
                .thenComparingDouble(FrameEntry::sweep));

        for (FrameEntry fe : frame) {
            double r1 = Math.max(1, HUB_RADIUS + (fe.depth - 1) * ringWidth);
            double r2 = Math.max(r1 + 1, HUB_RADIUS + fe.depth * ringWidth);
            int colorDepth = Math.max(0, (int) Math.round(fe.depth) - 1);
            Color base = SectorPalette.forName(fe.node.name(), colorDepth);
            double alpha = (fe.node.isDone() ? 1.0 : 0.45) * fe.alphaScale;
            if (alpha <= 0.001) continue;
            Color fill = base.deriveColor(0, 1, 1, alpha);
            drawAnnularSector(g, cx, cy, r1, r2, fe.start, fe.sweep, fill);
        }
    }

    private void drawAnnularSector(GraphicsContext g, double cx, double cy,
                                   double r1, double r2,
                                   double startDeg, double sweepDeg, Color fill) {
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
        } else {
            DirectoryNode focus;
            if (hoveringHub) {
                focus = scanRoot;
            } else if (hoverNode != null) {
                focus = hoverNode;
            } else {
                focus = viewRoot;
            }
            if (focus == null) return;
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
        if (scanRoot == null || animating) return;
        double cx = canvas.getWidth() / 2.0;
        double cy = canvas.getHeight() / 2.0;
        double dx = mx - cx;
        double dy = my - cy;
        double r = Math.hypot(dx, dy);

        boolean wasHub = hoveringHub;
        boolean wasFree = hoveringFreeSpace;
        DirectoryNode wasNode = hoverNode;

        if (r < HUB_RADIUS) {
            hoveringHub = true;
            hoverNode = null;
            hoveringFreeSpace = false;
        } else {
            hoveringHub = false;
            hoverNode = null;
            hoveringFreeSpace = false;
            double theta = Math.toDegrees(Math.atan2(-dy, dx));
            if (theta < 0) theta += 360;
            for (SectorRect s : sectors) {
                if (r >= s.r1 && r <= s.r2 && angleInSweep(theta, s.startDeg, s.sweepDeg)) {
                    if (s.node() == null) {
                        hoveringFreeSpace = true;
                    } else {
                        hoverNode = s.node();
                    }
                    break;
                }
            }
        }

        if (wasHub != hoveringHub || wasFree != hoveringFreeSpace || wasNode != hoverNode) {
            redraw();
        }
    }

    private void handleClick(double mx, double my) {
        if (scanRoot == null || animating) return;
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
        if (theta < 0) theta += 360;
        for (SectorRect s : sectors) {
            if (r >= s.r1 && r <= s.r2 && angleInSweep(theta, s.startDeg, s.sweepDeg)) {
                if (s.node() != null) select(s.node());
                return;
            }
        }
    }

    /** Walk from current viewRoot up to {@code targetAncestor}, pushing each intermediate
     *  node onto the forward stack so Right arrow can replay the path. {@code targetAncestor}
     *  must be an ancestor of viewRoot (or equal to it). */
    private void navigateUpTo(DirectoryNode targetAncestor) {
        if (targetAncestor == viewRoot) return;
        for (DirectoryNode cur = viewRoot; cur != null && cur != targetAncestor; cur = cur.parent()) {
            forwardStack.push(cur);
        }
        select(targetAncestor, false);
    }

    private void rebuildBreadcrumb() {
        breadcrumb.getChildren().clear();
        if (scanRoot == null || viewRoot == null) return;

        // Walk parent chain from viewRoot up to scanRoot.
        List<DirectoryNode> chain = new ArrayList<>();
        for (DirectoryNode n = viewRoot; n != null; n = n.parent()) {
            chain.add(0, n);
            if (n == scanRoot) break;
        }
        if (chain.isEmpty() || chain.get(0) != scanRoot) {
            // viewRoot got disconnected somehow — render just the current node.
            chain.clear();
            chain.add(viewRoot);
        }

        final int max = 5;
        if (chain.size() <= max) {
            for (int i = 0; i < chain.size(); i++) {
                if (i > 0) breadcrumb.getChildren().add(separatorLabel());
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
                if (!animating) navigateUpTo(node);
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
        return "-fx-text-fill: " + css(color) + ";"
                + "-fx-font-size: 11.5px; -fx-font-weight: " + weight + ";"
                + (active ? "" : "-fx-cursor: hand;");
    }

    private Label separatorLabel() {
        // Use ❯ (U+276F) — a slightly heavier chevron than › so it reads at small sizes
        // even at reduced opacity against the black background.
        Label l = new Label("❯");
        l.setStyle("-fx-text-fill: " + css(scheme.textMuted()) + ";"
                + "-fx-font-size: 10px; -fx-padding: 0 2 0 2;");
        return l;
    }

    private Label ellipsisLabel(List<DirectoryNode> hidden) {
        Label l = new Label("…");
        l.setStyle("-fx-text-fill: " + css(scheme.textMuted()) + ";-fx-font-size: 12px;");
        if (!hidden.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (DirectoryNode n : hidden) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(n.name());
            }
            l.setTooltip(new javafx.scene.control.Tooltip(sb.toString()));
        }
        return l;
    }

    private void openInExplorer() {
        DirectoryNode target = (hoverNode != null) ? hoverNode : viewRoot;
        if (target == null || target.path() == null) return;
        try {
            java.awt.Desktop.getDesktop().open(target.path().toFile());
        } catch (Exception ignored) {
            // No fatal handling; if Desktop isn't supported on this platform, do nothing.
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
        if (end <= 360) return theta >= start && theta <= end;
        return theta >= start || theta <= (end - 360);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String tailPath(String p) {
        if (p == null) return "";
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash < 0 ? p : p.substring(slash + 1);
    }

    static String humanSize(long bytes) {
        if (bytes >= TIB) return String.format("%.1f TB", bytes / (double) TIB);
        if (bytes >= GIB) return String.format("%.1f GB", bytes / (double) GIB);
        if (bytes >= MIB) return String.format("%.0f MB", bytes / (double) MIB);
        if (bytes >= KIB) return String.format("%.0f KB", bytes / (double) KIB);
        return bytes + " B";
    }

    private static String bg(Color c) {
        return "-fx-background-color: " + css(c) + ";";
    }

    private static String css(Color c) {
        return String.format("rgba(%d,%d,%d,%.3f)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255),
                c.getOpacity());
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double easeOutCubic(double t) {
        double inv = 1 - t;
        return 1 - inv * inv * inv;
    }

    private record SectorRect(DirectoryNode node, int depth,
                              double startDeg, double sweepDeg,
                              double r1, double r2) {}

    private record Layout(double depth, double startDeg, double sweepDeg) {}

    private record FrameEntry(DirectoryNode node, double depth, double start, double sweep, double alphaScale) {}

    /** Row in the staging (delete-tray) table: a folder or file the user has marked for deletion.
     *  {@code parentNode} is captured at staging time so a successful delete can apply size/count
     *  deltas to the in-memory tree without re-walking. */
    private record StagedItem(boolean isDirectory, java.nio.file.Path path, long sizeAtStaging,
                              DirectoryNode dirNode, DirectoryNode parentNode) {
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
