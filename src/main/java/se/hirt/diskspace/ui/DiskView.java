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
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.MacHiddenSpace;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.SectorPalette;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class DiskView {

	public enum RenderMode {SUNBURST, HEATMAP}

	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DiskView.class.getName());

	/**
	 * Session-only "don't ask again" flag for the FDA prompt. Deliberately not persisted: an occasional user who clicks
	 * "skip" today and upgrades macOS in six months would otherwise get silently incomplete scans, with no way to
	 * recover the prompt short of editing preferences. Forgetting on quit means the worst case is one extra dialog per
	 * launch — fine for an "occasionally used" tool.
	 */
	private static volatile boolean fdaPromptSkippedThisSession = false;

	/**
	 * First {@value} rings render at full thickness ({@code normalW}); after that, up to {@link #THIN_RINGS} additional
	 * rings are squeezed in at {@link #THIN_RING_FACTOR} of the normal width so deep file structure stays visible
	 * without dominating the layout.
	 */
	private static final int NORMAL_RINGS = 5;
	private static final int THIN_RINGS = 4;
	private static final int MAX_DEPTH = NORMAL_RINGS + THIN_RINGS;
	private static final double THIN_RING_FACTOR = 0.2;
	private static final double HUB_RADIUS = 78;
	private static final double MIN_VISIBLE_SWEEP_DEG = 0.6;
	private static final long LIVE_REFRESH_INTERVAL_NANOS = 100_000_000L; // 10 Hz
	private static final long ANIM_DURATION_NANOS = 350_000_000L;        // 350 ms

	/**
	 * Inline background reset prepended to every table cell's {@code setStyle}. Modena's default
	 * {@code .table-cell} paints a two-layer background ({@code -fx-table-cell-border-color},
	 * {@code -fx-background}) with matching insets {@code 0, 0 0 1 0}. We replace the colors with
	 * a single transparent layer AND reset insets so the layer count matches — without that
	 * insets are stale and modena's selection variant ends up still painting through. With both
	 * cleared, the row tint set by the row factory shows at full width.
	 */
	private static final String CELL_TRANSPARENT_BG = "-fx-background-color: transparent; -fx-background-insets: 0; ";

	private final SplitPane root;
	private final StackPane outerRoot;
	private final StackPane helpOverlay;
	private final Canvas canvas;
	private final ColorScheme scheme;
	private final Volume target;

	private final Label rightHeader;
	private final Label rightHeaderInfo;
	private final Rectangle headerFlash;
	private Path currentHeaderPath;
	/**
	 * Single context menu shared by every "thing that represents a path" — table rows, breadcrumb crumbs, the path
	 * label above the table, and visualization sectors. Each call site installs itself with a resolver that turns the
	 * right-click event into the underlying {@link PathTarget}; the menu hides when the resolver yields {@code null}.
	 */
	private final PathContextMenu pathContextMenu = new PathContextMenu();
	private final HBox breadcrumb;
	/**
	 * Top-right badge in the canvas pane showing which scanner was used (MFT / Parallel(8) / Sequential / etc). Sits on
	 * the same horizontal band as the breadcrumb but pinned to the opposite corner so it doesn't fight breadcrumb
	 * labels for space. Hidden when no scan is in flight; pulsed via {@link #strategyPulse} while scanning.
	 */
	private final Label strategyBadge;
	/**
	 * Opacity pulse on {@link #strategyBadge} while scanning — ramps 1.0 → 0.5 → 1.0 every ~1.2 s, autoreverse,
	 * indefinite. Played on scan start, stopped on completion / error / cancel; opacity is reset to 1.0 before hiding
	 * so the next show isn't mid-fade.
	 */
	private final Timeline strategyPulse;
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
	private final List<RectHit> rects = new ArrayList<>();
	private RenderMode currentMode = RenderMode.SUNBURST;
	/**
	 * JFR event spanning the duration the user spends in a single visualization mode. Begun in the constructor,
	 * committed + replaced on every {@link #toggleRenderMode}, finally committed in {@link #shutdown}. Lets JMC's flame
	 * graph filter samples to "while sunburst was on screen" or "while heatmap was on screen".
	 */
	private VisualizationEvent currentVizEvent;
	/**
	 * Per-{@link #currentVizEvent} repaint counter. Incremented inside {@link #redraw()}, reset by
	 * {@link #startVizEvent()}, captured by {@link #endVizEvent()}.
	 */
	private int vizEventRenderCount;
	/**
	 * Correlation ID stamped on every JFR event from this scan run ({@link ScanEvent}, {@link RenderEvent},
	 * {@link VisualizationEvent}, {@link UserActionEvent}). Generated fresh in {@link #startScan()} and
	 * {@link #rescan()}. {@code 0} = no scan yet.
	 */
	private long currentScanId;
	/**
	 * Trigger label for the next {@link #redraw()}. {@link #redrawWith(String)} sets it for the call sites that know
	 * "why" they're redrawing; falls back to {@code "auto"} when {@code redraw()} is called directly. Read + reset
	 * inside {@code redraw()}.
	 */
	private String pendingRedrawTrigger = "auto";
	/**
	 * In-flight {@link ScanEvent}, begun in {@link #doStartScan()} and committed by {@code onComplete} /
	 * {@code onError}.
	 */
	private ScanEvent currentScanEvent;
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
	 * The synthetic "Hidden" node attached as a child of scanRoot. Held so layout and table sorters can pin it to the
	 * end regardless of size. Null until injected.
	 */
	private volatile DirectoryNode hiddenNode;

	/**
	 * Memoized sunburst color per node. Family root (immediate child of scanRoot) gets a palette pick by name; deeper
	 * descendants inherit the parent's color, lightened and hue-shifted by sibling rank + depth so the largest-child
	 * trunk reads as one ribbon while side branches fade outward. Cleared on every (re)scan.
	 */
	private final java.util.Map<DirectoryNode, Color> colorCache = new java.util.IdentityHashMap<>();

	/**
	 * Per-render cache of {@code parent → (child → rank)}. {@link #sortedRank(DirectoryNode)} fills the inner map by
	 * sorting {@code parent.children()} by size once; subsequent calls for siblings of the same parent become O(1)
	 * lookups instead of an O(K log K) sort each.
	 * <p>Without this, drawHeatmap pegs the FX thread to 100% on TimSort during a scan: each
	 * cell calls getNodeColor → sortedRank, which builds and sorts a fresh ArrayList every time. With K children per
	 * parent that's K calls × K log K work = K² log K per parent; jstack caught the FX thread spending 122 s of CPU in
	 * TimSort that way.
	 * <p>Cleared at the top of {@link #redraw()} so successive renders see a fresh snapshot.
	 */
	private final java.util.Map<DirectoryNode, java.util.IdentityHashMap<DirectoryNode, Integer>> rankCache = new java.util.IdentityHashMap<>();

	/**
	 * Persistent rank map for parents whose children list is sort-stable
	 * (post-{@link DirectoryNode#sortBySizeRecursive}, no subsequent mutation). Survives across renders — once computed
	 * for a parent, lookups are O(1) forever until that parent's children change. Cleared on scan start; lazy-evicted
	 * in {@link #sortedRank} when a parent transitions back to unstable.
	 * <p>JFR follow-up to the {@link #rankCache} fix: the per-render cache eliminated the in-render re-sort cost, but
	 * the renderer was still rebuilding the map for every visible parent on every render. For finalised subtrees (the
	 * steady state after a scan completes) the map can be reused indefinitely.
	 */
	private final java.util.Map<DirectoryNode, java.util.IdentityHashMap<DirectoryNode, Integer>> stableRankCache = new java.util.IdentityHashMap<>();

	/**
	 * Palette index claimed by each top-level family (immediate child of scanRoot). Allocated lazily with collision
	 * avoidance so two top-level siblings can't end up on the same color even when their names hash to the same
	 * bucket.
	 */
	private final java.util.Map<DirectoryNode, Integer> topLevelPaletteIdx = new java.util.IdentityHashMap<>();

	/**
	 * Top-level folders whose scan has completed and whose descendant colors have been invalidated against final ranks.
	 * Walked on every live tick so colors stabilize per-folder as each finishes, instead of all flipping at the end of
	 * the scan.
	 */
	private final java.util.Set<DirectoryNode> finalizedTopLevels = java.util.Collections.newSetFromMap(
			new java.util.IdentityHashMap<>());

	private final Scanner scanner;
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

	public DiskView(Volume target, ColorScheme scheme) {
		this.target = target;
		this.scheme = scheme;
		this.scanner = Scanner.forVolume(target);

		canvas = new Canvas();
		Pane canvasHolder = new Pane(canvas);
		canvasHolder.setStyle(bg(scheme.background()));
		canvas.widthProperty().bind(canvasHolder.widthProperty());
		canvas.heightProperty().bind(canvasHolder.heightProperty());
		canvas.widthProperty().addListener((o, a, b) -> redrawWith("resize"));
		canvas.heightProperty().addListener((o, a, b) -> redrawWith("resize"));
		canvas.setOnMouseMoved(e -> handleMouseMove(e.getX(), e.getY()));
		canvas.setOnMouseExited(e -> {
			// While the context menu is up, keep the right-clicked sector visually pinned —
			// the popup window's bounds steal mouse-exit events from the canvas as the cursor
			// crosses onto the menu, and clearing hover state here would make the highlight
			// disappear the moment the user moves toward an action.
			if (pathContextMenu.isShowing())
				return;
			hoverNode = null;
			hoveringHub = false;
			hoveringFreeSpace = false;
			hoveringUnaccounted = false;
			redraw();
		});
		canvas.setOnMouseClicked(e -> {
			if (e.getButton() == MouseButton.PRIMARY)
				handleClick(e.getX(), e.getY());
		});
		// Right-click on a sector / cell offers the same Open / Copy / Stage actions as the table
		// and breadcrumb, plus view-level actions (Help, Re-scan, …) so mouse-only users can reach
		// them without memorizing keyboard shortcuts. Path actions are conditional on hovering a
		// real sector — over the hub / free-space / unaccounted regions only the view actions show.
		pathContextMenu.install(canvas, e -> {
			if (hoveringHub || hoveringFreeSpace || hoveringUnaccounted)
				return null;
			return targetFor(hoverNode);
		}, true);

		breadcrumb = new HBox(4);
		breadcrumb.setPadding(new Insets(10, 14, 10, 14));
		breadcrumb.setAlignment(Pos.CENTER_LEFT);
		breadcrumb.setPickOnBounds(false);
		// Important: cap to content size so StackPane respects TOP_LEFT alignment.
		// Without this, HBox stretches to fill, and Pos.CENTER_LEFT plants the labels
		// at the vertical centre of the canvas instead of pinned at the top.
		breadcrumb.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

		// Compute the strategy label up-front (Scanner.forVolume above already used the
		// same PREFERENCE+capability check) so the badge text accurately reflects which
		// scanner is actually running for this view, not whatever PREFERENCE later flips to.
		strategyBadge = new Label(Scanner.strategyLabelFor(target));
		strategyBadge.setStyle("-fx-text-fill: " + css(
				scheme.textMuted()) + ";" + "-fx-font-size: 10px; -fx-font-weight: 600;" + "-fx-padding: 12 14 0 0;");
		strategyBadge.setPickOnBounds(false);
		strategyBadge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		// Hidden by default — onStart turns it on for the duration of the scan.
		strategyBadge.setVisible(false);
		Tooltip badgeTip = new Tooltip("Scanner used for this view: " + Scanner.strategyLabelFor(target));
		badgeTip.setShowDelay(Duration.millis(300));
		Tooltip.install(strategyBadge, badgeTip);

		// Pulse: opacity drifts 1.0 → 0.5 → 1.0 over ~1.2 s. autoReverse with one
		// 600 ms keyframe gives the round trip; INDEFINITE keeps it running until stopped.
		strategyPulse = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(strategyBadge.opacityProperty(), 1.0)),
				new KeyFrame(Duration.millis(600), new KeyValue(strategyBadge.opacityProperty(), 0.5)));
		strategyPulse.setAutoReverse(true);
		strategyPulse.setCycleCount(Timeline.INDEFINITE);

		StackPane leftStack = new StackPane(canvasHolder, breadcrumb, strategyBadge);
		StackPane.setAlignment(breadcrumb, Pos.TOP_LEFT);
		StackPane.setAlignment(strategyBadge, Pos.TOP_RIGHT);
		leftStack.setStyle(bg(scheme.background()));

		configureTable();
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		configureStagingTable();
		stagingPane = buildStagingPane();
		rightSplit.setOrientation(Orientation.VERTICAL);
		rightSplit.setStyle(bg(scheme.background()));
		rightSplit.getItems().add(table);
		stagedItems.addListener((ListChangeListener<StagedItem>) c -> updateStagingVisibility());

		rightHeader = new Label("  " + target.displayName());
		rightHeader.setStyle(
				"-fx-text-fill: " + css(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 8 0 8 12;");
		// Keep the size/file-count tail visible when the path is long; ellipsize the head instead.
		rightHeader.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
		rightHeader.setMaxWidth(Double.MAX_VALUE);
		rightHeader.setCursor(Cursor.HAND);
		Tooltip.install(rightHeader, new Tooltip("Click to copy path"));
		rightHeader.setOnMouseClicked(e -> {
			if (e.getButton() == MouseButton.PRIMARY)
				copyHeaderPath();
		});
		pathContextMenu.install(rightHeader, e -> targetFor(viewRoot));

		rightHeaderInfo = new Label("  —  scanning…");
		rightHeaderInfo.setStyle(
				"-fx-text-fill: " + css(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 8 12 8 0;");

		// Flash overlay sits behind the path label and pulses on copy. Mouse-transparent so it
		// doesn't intercept the click that triggered it.
		headerFlash = new Rectangle();
		headerFlash.setFill(scheme.accent());
		headerFlash.setOpacity(0);
		headerFlash.setMouseTransparent(true);
		headerFlash.setArcWidth(4);
		headerFlash.setArcHeight(4);
		headerFlash.widthProperty().bind(rightHeader.widthProperty());
		headerFlash.heightProperty().bind(rightHeader.heightProperty());

		StackPane pathStack = new StackPane(headerFlash, rightHeader);
		StackPane.setAlignment(rightHeader, Pos.CENTER_LEFT);
		StackPane.setAlignment(headerFlash, Pos.CENTER_LEFT);
		// Allow the stack (and the leading-ellipsized label inside) to shrink below its preferred width.
		pathStack.setMinWidth(0);

		// BorderPane gives `rightHeaderInfo` its preferred width unconditionally and lets `pathStack`
		// fill the rest. The info label is therefore exactly content-sized, never squeezed when the
		// path is long, never stretched when the path is short.
		BorderPane headerBar = new BorderPane();
		headerBar.setCenter(pathStack);
		headerBar.setRight(rightHeaderInfo);
		BorderPane.setAlignment(rightHeaderInfo, Pos.CENTER_RIGHT);

		BorderPane right = new BorderPane(rightSplit);
		right.setTop(headerBar);
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
					redrawWith("scan-update");
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

		// Keyboard shortcuts. The same dispatch is also installed at the MainWindow
		// level so the keys work even when focus is on the TabPane header — see
		// dispatchTopLevelKey.
		root.setFocusTraversable(true);
		root.addEventHandler(KeyEvent.KEY_PRESSED, this::dispatchTopLevelKey);
		// Manual auto-hide for the path context menu. PopupWindow's built-in autoHide reliably
		// fires when the click target lands on a recycled node (e.g. a TableRow that gets
		// rebuilt as the selection model changes), but stays put when the click is on the same
		// persistent node that owns the menu — the canvas is the textbook case. We mirror the
		// auto-hide behaviour here so any press inside the DiskView root closes the menu, and
		// consume the press so the underlying handler (drill, navigate) doesn't also fire — the
		// click was clearly meant as "dismiss," not "do something else."
		root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
			if (pathContextMenu.isShowing()) {
				pathContextMenu.hide();
				e.consume();
			}
		});

		// Help overlay floats on top of the live visualization. The split pane keeps
		// running underneath (scan progress, redraws, etc.); the overlay is just a
		// semi-transparent layer toggled with Esc.
		this.helpOverlay = buildHelpOverlay();
		helpOverlay.setVisible(false);
		helpOverlay.setManaged(false);
		this.outerRoot = new StackPane(root, helpOverlay);
		outerRoot.setStyle(bg(scheme.background()));

		startVizEvent();
		startScan();
	}

	public Region getRoot() {
		return outerRoot;
	}

	/**
	 * Cancel the running scan and stop animation timers. Called during app quit so the scanner's
	 * {@link Platform#runLater} callbacks don't fire into a half-torn-down toolkit.
	 */
	public void shutdown() {
		try {
			scanner.cancel();
		} catch (RuntimeException ignored) {
		}
		try {
			liveTicker.stop();
		} catch (RuntimeException ignored) {
		}
		try {
			stopStrategyPulse();
		} catch (RuntimeException ignored) {
		}
		try {
			animTimer.stop();
		} catch (RuntimeException ignored) {
		}
		try {
			endVizEvent();
		} catch (RuntimeException ignored) {
		}
		try {
			// If a scan was in flight when the user closed the tab / quit, mark it cancelled
			// rather than leaving a dangling in-flight Scan event.
			commitScanEvent(scanRoot, "cancelled");
		} catch (RuntimeException ignored) {
		}
	}

	/**
	 * Stops the strategy-badge pulse, snaps opacity back to 1.0 (so the badge isn't frozen mid-fade if it gets shown
	 * again), and hides the badge. Idempotent.
	 */
	private void stopStrategyPulse() {
		strategyPulse.stop();
		strategyBadge.setOpacity(1.0);
		strategyBadge.setVisible(false);
	}

	/**
	 * Top-level command dispatch. Invoked both by this view's own key handler and by MainWindow's BorderPane-level
	 * handler so single-key shortcuts fire whether focus is inside the view or on the TabPane / picker. Plain
	 * modifier-free keys only — chords belong to focused controls.
	 */
	public void dispatchTopLevelKey(KeyEvent e) {
		// Esc toggles the help overlay even with modifiers off-path; check it first so
		// users always have a way out.
		if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
			emitUserAction("Esc", "toggle-help");
			toggleHelp();
			e.consume();
			return;
		}
		// Modifier-held keys (Cmd-Q, etc.) belong to native handlers — get out of the way.
		if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown())
			return;
		// Q quits unconditionally — must work even when the help overlay is up. Route
		// through App.requestQuit so scanners are cancelled before the toolkit tears down.
		if (e.getCode() == javafx.scene.input.KeyCode.Q) {
			emitUserAction("Q", "quit");
			se.hirt.diskspace.App.requestQuit();
			e.consume();
			return;
		}
		// While help is visible swallow other keys so they don't trigger silently behind
		// the overlay.
		if (helpOverlay != null && helpOverlay.isVisible()) {
			e.consume();
			return;
		}
		switch (e.getCode()) {
		case E, F -> {
			emitUserAction(e.getCode().name(), "open-in-explorer");
			openInExplorer();
			e.consume();
		}
		case LEFT, UP -> {
			// Up one level. Push the current viewRoot onto the forward stack so
			// Right arrow can replay the path back down. Always consume — at
			// scanRoot we no-op, but unconsumed arrow keys bubble to the TabPane
			// and would step to the "+" tab, opening a new picker.
			if (viewRoot != null && viewRoot != scanRoot && viewRoot.parent() != null) {
				emitUserAction(e.getCode().name(), "drill-out");
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
				emitUserAction(e.getCode().name(), "drill-in-forward");
				DirectoryNode next = forwardStack.pop();
				select(next, false);
			}
			e.consume();
		}
		case DELETE -> {
			emitUserAction("Del", "stage-delete");
			handleDeleteKey();
			e.consume();
		}
		case R -> {
			// Full rescan. Useful after external changes (e.g. something deleted outside diskspace).
			if (!deleting) {
				emitUserAction("R", "rescan");
				rescan();
			}
			e.consume();
		}
		case U -> {
			emitUserAction("U", "toggle-units");
			SizeFormat.toggle();
			refreshAfterUnitChange();
			e.consume();
		}
		case V -> {
			emitUserAction("V", "toggle-mode");
			toggleRenderMode();
			e.consume();
		}
		default -> { /* let it bubble */ }
		}
	}

	private void configureTable() {
		table.setItems(tableItems);
		table.setPlaceholder(new Label(""));
		table.setStyle(
				"-fx-background-color: " + css(scheme.background()) + ";" + "-fx-control-inner-background: " + css(
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
					setStyle(CELL_TRANSPARENT_BG);
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
					setStyle(CELL_TRANSPARENT_BG + "-fx-font-style: italic; -fx-text-fill: " + css(scheme.textMuted())
							+ ";");
				} else if (node != null && node.path() == null) {
					// Synthetic Hidden node — keep the color swatch so the row maps visually
					// to its sunburst sector, but render the text italic muted to signal it
					// isn't a real on-disk folder.
					swatch.setFill(getNodeColor(node));
					setGraphic(swatch);
					setText(item);
					setStyle(CELL_TRANSPARENT_BG + "-fx-font-style: italic; -fx-text-fill: " + css(scheme.textMuted())
							+ ";");
				} else if (node != null) {
					swatch.setFill(getNodeColor(node));
					setGraphic(swatch);
					switch (node.state()) {
					case QUEUED -> {
						setText(item + "  <queued>");
						setStyle(CELL_TRANSPARENT_BG + "-fx-font-weight: bold; -fx-text-fill: " + css(
								scheme.textMuted().darker()) + ";");
					}
					case SCANNING -> {
						setText(item + "  <scanning>");
						setStyle(CELL_TRANSPARENT_BG + "-fx-font-weight: bold; -fx-opacity: 0.75;");
					}
					default -> {
						setText(item);
						setStyle(CELL_TRANSPARENT_BG + "-fx-font-weight: bold;");
					}
					}
				} else {
					setGraphic(null);
					setText(item);
					setStyle(CELL_TRANSPARENT_BG);
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
					setStyle(CELL_TRANSPARENT_BG);
				} else {
					setText(item);
					setStyle(CELL_TRANSPARENT_BG
							+ "-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-alignment: CENTER-RIGHT;");
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
				// Right-click should act on the row under the cursor, not the prior selection. If
				// that row isn't already selected, snap selection to it before the menu shows so the
				// highlight matches what the menu will operate on. Already-selected (incl. multi-
				// select) rows are left alone, matching common file-manager behaviour.
				addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
					if (!isEmpty() && !isSelected())
						getTableView().getSelectionModel().clearAndSelect(getIndex());
				});
				pathContextMenu.install(this, e -> targetFor(getItem()));
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
					bg = css(scheme.accent().deriveColor(0, 1.0, 1.0, 0.55));
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
		stagingTable.setStyle(
				"-fx-background-color: " + css(scheme.background()) + ";" + "-fx-control-inner-background: " + css(
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
		// TODO(awt-free): replace AWT Desktop probe with per-platform checks (Finder/osascript on macOS,
		// gio trash on Linux, SHFileOperation on Windows) so we don't initialize AWT on macOS at all.
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
		alert.setHeaderText(
				(trash ? "Move " : "Permanently delete ") + stagedItems.size() + " item" + (stagedItems.size() == 1 ? ""
						: "s") + " (" + humanSize(total) + ")?");
		alert.setContentText(body.toString());
		ButtonType go = new ButtonType(trash ? "Move to Trash" : "Delete",
				javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
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
					// TODO(awt-free): swap Desktop.moveToTrash for a per-platform implementation to avoid AWT init.
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
				public java.nio.file.FileVisitResult visitFile(
						Path file,
						java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
					Files.delete(file);
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc)
						throws java.io.IOException {
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
		// Only meaningful in sunburst mode — treemap layouts shuffle every rectangle.
		boolean animateRemoval = currentMode == RenderMode.SUNBURST;
		Map<DirectoryNode, Layout> beforeLayout = animateRemoval ? computeLayout(viewRoot) : null;
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

		if (!animateRemoval) {
			redraw();
			return;
		}
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
		stopStrategyPulse();
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
		stableRankCache.clear();
		currentFiles = List.of();
		tableItems.clear();
		sectors.clear();
		rebuildBreadcrumb();
		redrawWith("rescan");
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
		stagedItems.removeIf(
				existing -> !existing.path().equals(candidate.path()) && existing.path().startsWith(candidate.path()));
		stagedItems.add(candidate);
	}

	private StagedItem entryToStaged(Entry e) {
		if (e.isDirectory()) {
			return dirToStaged(e.dirNode());
		}
		java.nio.file.Path filePath =
				(viewRoot != null && viewRoot.path() != null) ? viewRoot.path().resolve(e.name()) : null;
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
		sb.append(String.format("  Scanned      : %s  (%d files)%n", humanSize(root.totalBytes()),
				root.totalFileCount()));
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
			sb.append(String.format("    Not accessible : %d path%s%n", lastPermDeniedCount,
					lastPermDeniedCount == 1 ? "" : "s"));
		}
		sb.append("  Root breakdown (by size):\n");
		for (DirectoryNode child : children) {
			double pct = root.totalBytes() > 0 ? 100.0 * child.totalBytes() / root.totalBytes() : 0;
			sb.append(String.format("    %-32s %10s  (%4.1f%%)%n", child.name(), humanSize(child.totalBytes()), pct));
		}
		LOG.info(sb.toString());
	}

	private void startScan() {
		if (isMac()) {
			boolean granted = isFdaGranted();
			boolean skip = fdaPromptSkippedThisSession;
			LOG.fine(() -> "FDA gate: granted=" + granted + " sessionSkip=" + skip + " → " + (!granted && !skip
					? "show prompt" : "no prompt"));
			if (!granted && !skip) {
				promptForFda();
			}
		}
		doStartScan();
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static boolean isFdaGranted() {
		// Open TCC.db directly: without FDA the syscall fails immediately with EPERM.
		// The directory listing this used to do can succeed in edge cases even without
		// FDA, leading to spurious prompts; opening the file is the unambiguous probe.
		Path tccDb = Path.of("/Library/Application Support/com.apple.TCC/TCC.db");
		try (var ignored = Files.newInputStream(tccDb)) {
			LOG.fine(() -> "FDA probe: opened " + tccDb + " — granted");
			return true;
		} catch (java.nio.file.NoSuchFileException e) {
			LOG.fine(() -> "FDA probe: " + tccDb + " not found — assuming pre-TCC macOS, treating as granted");
			return true;
		} catch (java.io.IOException e) {
			LOG.fine(() -> "FDA probe: " + tccDb + " open failed (" + e.getClass()
					.getSimpleName() + ": " + e.getMessage() + ") — not granted");
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
		ButtonType scanAnyway = new ButtonType("Skip for this session");
		alert.getButtonTypes().setAll(openSettings, scanAnyway);
		alert.showAndWait().ifPresent(b -> {
			if (b == openSettings) {
				try {
					new ProcessBuilder("open",
							"x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles").start();
				} catch (java.io.IOException ignore) {
				}
			} else {
				fdaPromptSkippedThisSession = true;
			}
		});
	}

	private void doStartScan() {
		// Begin a JFR Scan event spanning this scan run. ScanID is generated here so any
		// Render / VisualizationActive / UserAction event committed after this point can
		// be correlated to the scan in JMC. Generated even when JFR isn't recording —
		// the cost is one ThreadLocalRandom.nextLong + a couple of field assignments.
		currentScanId = java.util.concurrent.ThreadLocalRandom.current().nextLong();
		ScanEvent se = new ScanEvent();
		se.scanId = currentScanId;
		se.root = target.root().toString();
		se.strategy = Scanner.strategyLabelFor(target);
		se.begin();
		currentScanEvent = se;

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
					redrawWith("scan-start");
					liveTicker.start();
					strategyBadge.setVisible(true);
					strategyPulse.playFromStart();
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
			}

			@Override
			public void onComplete(DirectoryNode result) {
				logScanSummary(result);
				injectFileChildrenInto(result);
				// addChild() flips sortStableByTotalBytes back to false on every parent we injected
				// into. Re-sort once so the synthetic sectors land in size-order alongside real
				// children AND the renderer's stable-rank fast path engages on post-scan renders.
				result.sortBySizeRecursive();
				// Hidden was injected at scan start; nothing more to do for it here.
				Platform.runLater(() -> {
					scanning = false;
					liveTicker.stop();
					stopStrategyPulse();
					commitScanEvent(result, "complete");
					// Drop colors that were memoized during the scan against stale child
					// sort orders — a node briefly cached as rank-0 stays cached as rank-0
					// until invalidated, even if siblings overtook it. Same for the
					// top-level palette allocation: redo it in final-size order so the
					// largest top-level family gets its hashed index first.
					colorCache.clear();
					topLevelPaletteIdx.clear();
					refreshTable();
					redrawWith("scan-complete");
				});
			}

			@Override
			public void onError(Throwable t) {
				Platform.runLater(() -> {
					scanning = false;
					liveTicker.stop();
					stopStrategyPulse();
					commitScanEvent(null, "error");
					progressPath = "Scan failed: " + t.getMessage();
					redrawWith("scan-error");
				});
			}
		});
	}

	/**
	 * Finalises {@link #currentScanEvent} and commits it. {@code outcome} is one of {@code "complete"},
	 * {@code "error"}, or {@code "cancelled"}. Idempotent.
	 */
	private void commitScanEvent(DirectoryNode result, String outcome) {
		ScanEvent se = currentScanEvent;
		if (se == null)
			return;
		currentScanEvent = null;
		se.fileCount = result != null ? result.totalFileCount() : progressFiles;
		se.totalBytes = result != null ? result.totalBytes() : progressBytes;
		se.permissionDeniedCount = lastPermDeniedCount;
		se.outcome = outcome;
		se.end();
		se.commit();
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
			rightHeaderInfo.setText("");
			currentHeaderPath = null;
			rightHeader.setCursor(Cursor.DEFAULT);
			return;
		}
		// Synthetic Hidden nodes have no on-disk path; show the name instead and skip the
		// file count (it's always 0 for synthetic).
		String headerLeft = viewRoot.path() != null ? viewRoot.path().toString() : viewRoot.name();
		String headerRight = viewRoot.path() != null ? "   " + viewRoot.totalFileCount() + " files" : "";
		// Inaccessible count is a scan-level concept; surface it only at the scan root
		// so it doesn't read as "this directory has N inaccessible" while drilled in.
		if (viewRoot == scanRoot && lastPermDeniedCount > 0) {
			headerRight += "   ·   " + lastPermDeniedCount + " inaccessible";
		}
		rightHeader.setText("  " + headerLeft);
		rightHeaderInfo.setText("  —  " + humanSize(viewRoot.totalBytes()) + headerRight);
		currentHeaderPath = viewRoot.path();
		rightHeader.setCursor(currentHeaderPath != null ? Cursor.HAND : Cursor.DEFAULT);

		// Re-list immediate files only when the viewRoot itself changes; files of a fixed
		// directory don't move during a scan.
		if (viewRoot != lastListedRoot) {
			currentFiles = listFiles(viewRoot.path());
			lastListedRoot = viewRoot;
		}

		// Combine current child directories with the cached file list, sort by size desc.
		// Synthetic file-sector nodes (large files ≥ 1 GB, "Smaller files" aggregate) are skipped:
		// they exist solely to give the sunburst / heatmap / Voronoi visualizations grey leaves
		// for big files, but {@code listFiles(viewRoot.path())} already returns those same files
		// as real file rows. Including them here would double-represent every large file in the
		// table — and worse, the delete-completion path (line 887ff) treats any directory-style
		// row as a real directory, so staging a synthetic node would route through
		// {@code parent.removeChild} (which adjusts file counts) instead of
		// {@code parent.removeFile}, leaving the actual file row stale in the table.
		List<Entry> entries = new ArrayList<>(viewRoot.children().size() + currentFiles.size());
		for (DirectoryNode c : viewRoot.children()) {
			if (c.isFileSector())
				continue;
			entries.add(Entry.forDir(c));
		}
		entries.addAll(currentFiles);
		// Folders first (sorted by size desc with Hidden pinned last), then files
		// (sorted by size desc). Snapshot sizes so the comparator stays consistent
		// under the parallel scanner's concurrent writers — currentSize() reads
		// totalBytes() on directory entries, which can drift mid-sort.
		java.util.IdentityHashMap<Entry, Long> entrySizes = new java.util.IdentityHashMap<>(entries.size());
		for (Entry e : entries)
			entrySizes.put(e, e.currentSize());
		entries.sort((a, b) -> {
			int byKind = Boolean.compare(b.isDirectory(), a.isDirectory());
			if (byKind != 0)
				return byKind;
			boolean aHidden = (a.dirNode() == hiddenNode);
			boolean bHidden = (b.dirNode() == hiddenNode);
			if (aHidden != bHidden)
				return aHidden ? 1 : -1;
			return Long.compare(entrySizes.get(b), entrySizes.get(a));
		});

		if (!sameOrder(tableItems, entries)) {
			tableItems.setAll(entries);
		} else {
			// Same items in same positions; force a cell repaint so live size and state
			// changes (e.g. SCANNING → DONE between live ticks) become visible. The
			// `scanning` guard that used to be here missed the last-frame race in the
			// parallel scanner: a node finishing between the liveTicker stopping and
			// onComplete running would otherwise stay rendered as "<scanning>" forever.
			table.refresh();
		}
	}

	private void copyHeaderPath() {
		if (currentHeaderPath == null)
			return;
		copyPathToClipboard(currentHeaderPath);
		Timeline flash = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(headerFlash.opacityProperty(), 0.0)),
				new KeyFrame(Duration.millis(90), new KeyValue(headerFlash.opacityProperty(), 0.45)),
				new KeyFrame(Duration.millis(550), new KeyValue(headerFlash.opacityProperty(), 0.0)));
		flash.play();
	}

	private void copyPathToClipboard(Path p) {
		ClipboardContent content = new ClipboardContent();
		content.putString(p.toString());
		Clipboard.getSystemClipboard().setContent(content);
	}

	/**
	 * Open the OS file browser at {@code target}. Directories open in place via JavaFX's
	 * {@link javafx.application.HostServices} (which routes through the platform-native launcher
	 * without initialising AWT). Files are revealed in their containing folder where the platform
	 * supports it (macOS {@code open -R}, Windows {@code explorer /select}), and fall back to
	 * opening the parent directory on Linux.
	 */
	private void revealPath(PathTarget target) {
		Path p = target.path();
		if (p == null)
			return;
		try {
			switch (target.kind()) {
			case DIRECTORY, AGGREGATE -> openDirectory(p);
			case FILE -> {
				if (isMac()) {
					new ProcessBuilder("open", "-R", p.toString()).start();
				} else if (isWindows()) {
					// Pass /select, and the path as separate args. ProcessBuilder joins them with
					// a space when building the Windows command line, and explorer.exe accepts
					// that form reliably. The concatenated single-arg form (/select,<path>) is
					// quirkier — when explorer can't parse it cleanly it silently falls back to
					// the user's Documents folder, which looks like the feature is broken.
					new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
				} else {
					Path parent = p.getParent();
					if (parent != null)
						openDirectory(parent);
				}
			}
			}
		} catch (Exception ignored) {
			// No fatal handling; if the platform doesn't support it, do nothing.
		}
	}

	/**
	 * Open a folder in the system file manager. On Windows we shell out to {@code explorer.exe}
	 * with the raw path: {@link javafx.application.HostServices#showDocument} sends a
	 * {@code file:///…/} URI through {@code ShellExecute}, which doesn't reliably navigate to a
	 * folder and tends to fall back to Documents. On macOS/Linux {@code HostServices} works
	 * fine (LSOpen / xdg-open).
	 */
	private static void openDirectory(Path p) {
		if (isWindows()) {
			try {
				new ProcessBuilder("explorer.exe", p.toString()).start();
			} catch (Exception ignored) {
				// Best-effort; if the platform doesn't support it, do nothing.
			}
			return;
		}
		var hs = se.hirt.diskspace.App.hostServices();
		if (hs != null)
			hs.showDocument(p.toUri().toString());
	}

	/**
	 * "Open" semantics: hand the path to the OS's default handler — the registered app for a file,
	 * the file manager for a directory. Distinct from {@link #revealPath} which always lands the
	 * user in a folder view (revealing files in their parent).
	 */
	private static void openPath(PathTarget target) {
		if (target == null || target.path() == null)
			return;
		Path p = target.path();
		if (target.kind() == TargetKind.DIRECTORY) {
			openDirectory(p);
			return;
		}
		// Files: route through HostServices so the registered app handles the file type.
		var hs = se.hirt.diskspace.App.hostServices();
		if (hs != null)
			hs.showDocument(p.toUri().toString());
	}

	/** Resolves the {@link PathTarget} for a table {@link Entry}, or {@code null} when the row has no on-disk path. */
	private PathTarget targetFor(Entry entry) {
		if (entry == null)
			return null;
		if (entry.isDirectory())
			return targetFor(entry.dirNode());
		Path base = (viewRoot != null) ? viewRoot.path() : null;
		if (base == null)
			return null;
		Path filePath = base.resolve(entry.name());
		return new PathTarget(filePath, TargetKind.FILE, () -> stage(entryToStaged(entry)));
	}

	/**
	 * Resolves the {@link PathTarget} for a {@link DirectoryNode}; synthetic Hidden nodes yield null. Real directories
	 * get a stage action unless they are the scan root (refusing to let a single right-click queue the entire disk for
	 * deletion). File-sector nodes (large files / "Smaller files" aggregate) are surfaced as non-directory targets, so
	 * right-clicking a big file in the sunburst behaves the same as right-clicking it in the table.
	 *
	 * <p>The "Smaller files" aggregate is special: it carries no on-disk path of its own (synthesised post-scan), so
	 * the natural target for "Open Location" is the parent directory where those small files actually live. Staging
	 * is disabled because removing an aggregate has no concrete meaning — there's no single file or folder to delete.
	 */
	private PathTarget targetFor(DirectoryNode node) {
		if (node == null)
			return null;
		if (node.path() == null) {
			if (node.isFileSector() && node.parent() != null && node.parent().path() != null) {
				return new PathTarget(node.parent().path(), TargetKind.AGGREGATE, null);
			}
			return null;
		}
		if (node.isFileSector()) {
			return new PathTarget(node.path(), TargetKind.FILE,
					() -> stage(new StagedItem(false, node.path(), node.totalBytes(), null, node.parent())));
		}
		Runnable stageAction = (node == scanRoot) ? null : () -> stage(dirToStaged(node));
		return new PathTarget(node.path(), TargetKind.DIRECTORY, stageAction);
	}

	private static List<Entry> listFiles(Path dir) {
		List<Entry> out = new ArrayList<>();
		if (dir == null)
			return out;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				try {
					BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS);
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
	 * Files at or above this size become their own sunburst sector. Smaller files are summed per directory and surface
	 * as a single "Smaller files" sector when the sum itself crosses the same threshold. 1 GB decimal — must match
	 * {@code ParallelDirectoryScanner.LARGE_FILE_THRESHOLD_BYTES}.
	 */
	private static final long FILE_SECTOR_THRESHOLD = 1_000_000_000L;

	/**
	 * Walks {@code dir} and appends synthetic children for any large files (≥ threshold) the scanner recorded, plus a
	 * single "Smaller files" aggregate when the smaller-files sum on this directory also crosses the threshold. The
	 * synthetic children's bytes are already counted in {@code dir.totalBytes()} via the scanner's normal propagation,
	 * so no totals are bumped here.
	 */
	private static void injectFileChildrenInto(DirectoryNode dir) {
		// Snapshot real children before we add synthetic ones so the recursion doesn't
		// re-visit our own injections.
		List<DirectoryNode> realChildren = new ArrayList<>(dir.children());
		for (DirectoryNode c : realChildren) {
			injectFileChildrenInto(c);
		}
		// addChild adds under the children-list lock and returns the new node so we can stamp
		// synthetic flags on it. (The previous code constructed the node manually and called
		// dir.children().add(...) — but children() returns a defensive snapshot now, so that
		// add went into a throw-away list and the synthetics never appeared.)
		for (DirectoryNode.FileRecord f : dir.largeFiles()) {
			DirectoryNode fileNode = dir.addChild(f.name(), dir.path() != null ? dir.path().resolve(f.name()) : null);
			fileNode.addSyntheticBytes(f.size());
			fileNode.markDone();
			fileNode.markFileSector();
		}
		long smaller = dir.smallerFilesBytes();
		if (smaller >= FILE_SECTOR_THRESHOLD) {
			DirectoryNode smallNode = dir.addChild("Smaller files", null);
			smallNode.addSyntheticBytes(smaller);
			smallNode.markDone();
			smallNode.markFileSector();
		}
	}

	/**
	 * Builds the synthetic "Hidden" subtree from {@link #cachedHidden} and attaches it as a child of
	 * {@code scanRootNode}. The Hidden node itself and its children carry zero scanned bytes and no on-disk path, but
	 * their {@code totalBytes} is set so the sunburst renders them like any other sector. {@code scanRootNode}'s
	 * {@code totalBytes} is bumped by Hidden's bytes so children fractions sum to 1.
	 */
	private void injectHiddenInto(DirectoryNode scanRootNode) {
		MacHiddenSpace.HiddenSpace h = cachedHidden;
		if (h == null)
			return;
		if (h.totalBytes() <= 0 && h.localSnapshotCount() == 0 && lastPermDeniedCount == 0)
			return;
		if (hiddenNode != null)
			return;  // already injected (e.g. at scan start)

		// Use addChild() throughout so each synthetic node attaches to the parent's actual children
		// list under lock. (children() returns a defensive snapshot, so .children().add(...) silently
		// dropped the node into a throw-away list — same bug as injectFileChildrenInto.)
		DirectoryNode hidden = scanRootNode.addChild("Hidden", null);
		hidden.markDone();
		hiddenNode = hidden;

		DirectoryNode otherVols = hidden.addChild("Other volumes", null);
		otherVols.addSyntheticBytes(h.otherVolumesBytes());
		otherVols.markDone();

		DirectoryNode snapshots = hidden.addChild("Snapshots", null);
		snapshots.markDone();

		DirectoryNode other = hidden.addChild("Other", null);
		other.addSyntheticBytes(h.residualBytes());
		other.markDone();

		if (lastPermDeniedCount > 0) {
			DirectoryNode notAccess = hidden.addChild("Not accessible", null);
			notAccess.markDone();
		}

		long hiddenTotal = h.totalBytes();
		hidden.addSyntheticBytes(hiddenTotal);
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

		if (currentMode == RenderMode.HEATMAP) {
			// Treemap layouts shuffle every rectangle on drill, so a polar lerp doesn't apply.
			// Swap the view root and repaint statically.
			viewRoot = newViewRoot;
			hoverNode = null;
			hoveringHub = false;
			refreshTable();
			rebuildBreadcrumb();
			redraw();
			root.requestFocus();
			return;
		}

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

	private void toggleHelp() {
		boolean show = !helpOverlay.isVisible();
		helpOverlay.setVisible(show);
		helpOverlay.setManaged(show);
		// Keep keyboard focus inside the view so Esc/Q keep working in either state.
		root.requestFocus();
	}

	private StackPane buildHelpOverlay() {
		javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
		grid.setHgap(20);
		grid.setVgap(8);

		int row = 0;
		addHelpRow(grid, row++, "Esc", "Show / hide this help");
		addHelpRow(grid, row++, "←  ↑", "Go up one level");
		addHelpRow(grid, row++, "→  ↓", "Go forward (replay an up step)");
		addHelpRow(grid, row++, "E  F", "Open in system file explorer");
		addHelpRow(grid, row++, "Del", "Stage / unstage selection for deletion");
		addHelpRow(grid, row++, "R", "Re-scan the current disk");
		addHelpRow(grid, row++, "U", "Toggle size units (GB / GiB)");
		addHelpRow(grid, row++, "V", "Toggle visualization (sunburst / heatmap)");
		addHelpRow(grid, row++, "Q", "Quit DiskSpace");

		Label title = new Label("Keyboard Shortcuts");
		title.setStyle("-fx-text-fill: " + css(
				scheme.textPrimary()) + ";" + "-fx-font-size: 18px; -fx-font-weight: 600; -fx-padding: 0 0 14 0;");

		Label hint = new Label("Press Esc to close");
		hint.setStyle(
				"-fx-text-fill: " + css(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 14 0 0 0;");

		VBox card = new VBox(title, grid, hint);
		card.setAlignment(Pos.TOP_LEFT);
		card.setPadding(new Insets(24, 28, 20, 28));
		card.setMaxWidth(460);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		card.setStyle("-fx-background-color: " + css(
				scheme.surface()) + ";" + "-fx-background-radius: 12;" + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0.25, 0, 4);");

		StackPane overlay = new StackPane(card);
		overlay.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
		// Backdrop click dismisses; the card consumes its own clicks before they reach here.
		overlay.setOnMouseClicked(e -> {
			if (e.getTarget() == overlay)
				toggleHelp();
		});
		return overlay;
	}

	private void addHelpRow(javafx.scene.layout.GridPane grid, int row, String key, String desc) {
		Label k = new Label(key);
		k.setStyle("-fx-text-fill: " + css(
				scheme.accent()) + ";" + "-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;" + "-fx-font-size: 13px; -fx-font-weight: 600;");
		k.setMinWidth(64);
		Label d = new Label(desc);
		d.setStyle("-fx-text-fill: " + css(scheme.textPrimary()) + ";" + "-fx-font-size: 13px;");
		grid.add(k, 0, row);
		grid.add(d, 1, row);
	}

	private void toggleRenderMode() {
		endVizEvent();
		currentMode = (currentMode == RenderMode.SUNBURST) ? RenderMode.HEATMAP : RenderMode.SUNBURST;
		startVizEvent();
		hoverNode = null;
		hoveringHub = false;
		hoveringFreeSpace = false;
		hoveringUnaccounted = false;
		// Cancel any in-flight sunburst drill animation; the new mode draws statically.
		if (animating) {
			animating = false;
			animTimer.stop();
		}
		// Reclaim focus so subsequent keypresses route through this view's handler.
		root.requestFocus();
		redrawWith("mode-change");
	}

	/**
	 * Color for {@code node}'s sunburst sector. Used by both the canvas drawing path and the right-pane table swatch so
	 * they stay in sync.
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
	 * {@code name.hashCode() % paletteSize} and walks forward to the first index not already claimed by a
	 * previously-allocated sibling — so two top-level siblings whose names happen to hash to the same bucket can't
	 * render identical.
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
	 * Per-tick: detect any top-level folders that have just transitioned to {@code DONE} and drop their descendants'
	 * cached colors. The next render re-derives those colors against the now-final sort order, so per-folder colors
	 * stabilize *as that folder finishes* rather than all flipping at the very end of the scan.
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
	 * Single-pass snapshot pairing a node with its {@code totalBytes()} captured at the moment of capture. Replaces the
	 * older {@code IdentityHashMap<DirectoryNode, Long>} pattern that JFR flagged as the #1 render-CPU hotspot: TimSort
	 * calls the comparator O(N log N) times per render, the old pattern did two map lookups + two unboxings per
	 * comparison plus N {@code Long.valueOf} boxings up front. With this record, comparisons read primitive
	 * {@code long} fields directly.
	 * <p>The snapshot is still required: reading {@link DirectoryNode#totalBytes()} inside the comparator would cause
	 * TimSort to throw "Comparison method violates its general contract" during a parallel scan, because concurrent
	 * writers can shift values between the comparator's repeated calls on the same pair and break transitivity.
	 */
	private record SizedNode(DirectoryNode node, long size) {
	}

	/**
	 * Sort comparator that puts {@link #hiddenNode} last and otherwise sorts by size desc. Used wherever scanRoot's
	 * children are ordered so Hidden never moves position as the scan progresses or as users navigate.
	 * <p>Held as a field (not a per-call factory) so the renderer reuses one instance across every
	 * {@code layoutChildrenInto} call instead of allocating a fresh capturing lambda on each parent. The method
	 * reference reads {@link #hiddenNode} at call time, so reassignments of the {@code hiddenNode} field (between
	 * scans, via the picker) are still seen correctly.
	 */
	private final Comparator<SizedNode> hiddenLastSizeDesc = this::compareHiddenLastSizeDesc;

	private int compareHiddenLastSizeDesc(SizedNode a, SizedNode b) {
		boolean aHidden = (a.node == hiddenNode);
		boolean bHidden = (b.node == hiddenNode);
		if (aHidden != bHidden)
			return aHidden ? 1 : -1;
		return Long.compare(b.size, a.size);
	}

	/** Frozen-size snapshot of {@code nodes}. See {@link SizedNode} for the why. */
	private static List<SizedNode> snapshotSized(java.util.Collection<DirectoryNode> nodes) {
		List<SizedNode> out = new ArrayList<>(nodes.size());
		for (DirectoryNode n : nodes)
			out.add(new SizedNode(n, n.totalBytes()));
		return out;
	}

	private int depthFromScanRoot(DirectoryNode node) {
		int d = 0;
		for (DirectoryNode n = node; n != null && n != scanRoot; n = n.parent())
			d++;
		return d;
	}

	/**
	 * Returns the index of {@code node} in its parent's size-descending sibling list, with results cached per-parent in
	 * {@link #rankCache} for the duration of the current render. Reads {@link #rankCache} so this can no longer be
	 * {@code static}.
	 */
	private int sortedRank(DirectoryNode node) {
		DirectoryNode parent = node.parent();
		if (parent == null)
			return 0;

		// Fast path: parent's children are already sorted by size desc and stable
		// (post-scan, no mutations since). The sorted-children indices ARE the ranks;
		// build the lookup map once and reuse across renders.
		if (parent.isSortStableByTotalBytes()) {
			java.util.IdentityHashMap<DirectoryNode, Integer> ranks = stableRankCache.get(parent);
			if (ranks == null) {
				List<DirectoryNode> kids = parent.children();
				ranks = new java.util.IdentityHashMap<>(kids.size());
				for (int i = 0; i < kids.size(); i++) {
					ranks.put(kids.get(i), i);
				}
				stableRankCache.put(parent, ranks);
			}
			Integer r = ranks.get(node);
			return r != null ? r : 0;
		}

		// Unstable (mid-scan, or post-scan but a mutation happened): evict any stale
		// long-term entry and fall back to per-render snapshot+sort.
		stableRankCache.remove(parent);
		java.util.IdentityHashMap<DirectoryNode, Integer> ranks = rankCache.get(parent);
		if (ranks == null) {
			List<SizedNode> sorted = snapshotSized(parent.children());
			sorted.sort((a, b) -> Long.compare(b.size, a.size));
			ranks = new java.util.IdentityHashMap<>(sorted.size());
			for (int i = 0; i < sorted.size(); i++) {
				ranks.put(sorted.get(i).node, i);
			}
			rankCache.put(parent, ranks);
		}
		Integer r = ranks.get(node);
		return r != null ? r : 0;
	}

	/**
	 * Inner radius of the ring at layout depth {@code depth}. Depths {@code <= NORMAL_RINGS} use full-width rings;
	 * deeper depths use thin rings. Accepts fractional depths so the drill-in animation can interpolate radii
	 * smoothly.
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

		// Compute per-render ring widths from the current canvas size, so the recursion
		// can do depth-aware pixel-arc culling (a sweep of N° at the inner ring is
		// fewer pixels of arc than the same N° at an outer ring; the angle threshold
		// alone over-culls deep rings and under-culls shallow ones).
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		double maxR = Math.max(40, Math.min(w, h) * 0.46);
		double normalW = (maxR - HUB_RADIUS) / (NORMAL_RINGS + THIN_RINGS * THIN_RING_FACTOR);
		if (normalW < 6)
			normalW = 6;
		double thinW = normalW * THIN_RING_FACTOR;

		// When viewing the scan root, ring 1 is the root's children (no anchor ring).
		// When drilled into a sector, ring 1 is that sector at 360° anchoring the view,
		// and ring 2 onward shows its descendants.
		boolean rootHasRing = rootForView != scanRoot;
		if (rootHasRing) {
			out.put(rootForView, new Layout(1, 90.0, 360.0, getNodeColor(rootForView)));
			layoutChildrenInto(rootForView, 2, 90.0, 360.0, out, normalW, thinW);
		} else {
			double usedSweep = target.totalBytes() > 0 ? target.usedFraction() * 360.0 : 360.0;
			double startAngle = 90.0 - usedSweep / 2.0;
			double scannedSweep = target.usedBytes() > 0 ? Math.min(usedSweep,
					usedSweep * rootForView.totalBytes() / (double) target.usedBytes()) : usedSweep;
			layoutChildrenInto(rootForView, 1, startAngle, scannedSweep, out, normalW, thinW);
		}
		return out;
	}

	/**
	 * Recursive sunburst layout. Two culling thresholds compose:
	 * <ul>
	 *   <li>{@link #MIN_VISIBLE_SWEEP_DEG} — a fixed angle floor, cheapest check; cuts most slivers.</li>
	 *   <li>Pixel-arc check — at the outer radius of this depth's ring, we require {@code >= 1 px} of arc length. Sectors below that
	 *       can't render visibly even at high DPI, and crucially we save the recursive descent into their subtrees.</li>
	 * </ul>
	 * Both checks let the sweep "consume" the angle (via {@code a += childSweep}) so non-culled siblings keep their proportional
	 * positions; we just skip the {@link Layout} allocation and the recursion for invisible sectors.
	 */
	private void layoutChildrenInto(
			DirectoryNode parent, int depth, double startDeg, double sweepDeg,
			Map<DirectoryNode, Layout> out, double normalW, double thinW) {
		if (depth > MAX_DEPTH)
			return;
		long total = parent.totalBytes();
		if (total <= 0)
			return;

		double outerR = ringInnerR(depth + 1, normalW, thinW);
		double minSweepFromPixels = Math.toDegrees(1.0 / outerR);

		// Fast path: parent's children are sort-stable (post-{@link DirectoryNode#sortBySizeRecursive},
		// no subsequent mutation) AND the parent isn't scanRoot (whose children may include hiddenNode,
		// which we always pin last regardless of size). For everyone else, parent.children() is already
		// in size-desc order — skip the snapshot+sort and iterate directly, reading totalBytes() inline.
		// This kills the per-render TimSort that JFR flagged as the dominant render-CPU cost on large
		// trees (1.38M-node SUNBURST snapshots).
		if (parent != scanRoot && parent.isSortStableByTotalBytes()) {
			double a = startDeg;
			for (DirectoryNode child : parent.children()) {
				long size = child.totalBytes();
				double frac = size / (double) total;
				double childSweep = sweepDeg * frac;
				if (childSweep < MIN_VISIBLE_SWEEP_DEG || childSweep < minSweepFromPixels) {
					a += childSweep;
					continue;
				}
				out.put(child, new Layout(depth, a, childSweep, getNodeColor(child)));
				layoutChildrenInto(child, depth + 1, a, childSweep, out, normalW, thinW);
				a += childSweep;
			}
			return;
		}

		// Slow path (mid-scan, OR scanRoot which needs Hidden pinned last): snapshot child sizes
		// alongside the nodes so the comparator can read primitive long fields (no boxing, no map
		// lookup per comparison). See SizedNode for context.
		List<SizedNode> ordered = snapshotSized(parent.children());
		// Hidden always lands at the end of scanRoot's children regardless of size — it's
		// less actionable than real folders and a stable position is more useful than a
		// size-correct one.
		ordered.sort(hiddenLastSizeDesc);

		double a = startDeg;
		for (SizedNode s : ordered) {
			double frac = s.size / (double) total;
			double childSweep = sweepDeg * frac;
			if (childSweep < MIN_VISIBLE_SWEEP_DEG || childSweep < minSweepFromPixels) {
				a += childSweep;
				continue;
			}
			out.put(s.node, new Layout(depth, a, childSweep, getNodeColor(s.node)));
			layoutChildrenInto(s.node, depth + 1, a, childSweep, out, normalW, thinW);
			a += childSweep;
		}
	}

	// ---- rendering -------------------------------------------------------

	/**
	 * Wrapper around {@link #redraw()} that stamps the JFR {@link RenderEvent} with a meaningful {@code trigger}.
	 * Direct {@code redraw()} calls record the trigger as {@code "auto"}.
	 */
	private void redrawWith(String trigger) {
		pendingRedrawTrigger = trigger;
		redraw();
	}

	private void redraw() {
		// JFR Render event spans the entire layout+draw work. Fields filled at commit time
		// since width/height/mode could in principle change across the body (defensive).
		RenderEvent renderEvent = new RenderEvent();
		String trigger = pendingRedrawTrigger;
		pendingRedrawTrigger = "auto";
		renderEvent.trigger = trigger;
		renderEvent.scanId = currentScanId;
		renderEvent.begin();
		try {
			doRedraw();
		} finally {
			renderEvent.mode = currentMode.name();
			renderEvent.widthPx = (int) canvas.getWidth();
			renderEvent.heightPx = (int) canvas.getHeight();
			renderEvent.nodeCount = progressFiles;
			renderEvent.end();
			renderEvent.commit();
			vizEventRenderCount++;
		}
	}

	private void doRedraw() {
		GraphicsContext g = canvas.getGraphicsContext2D();
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		g.setFill(scheme.background());
		g.fillRect(0, 0, w, h);

		// Reset per-render rank cache. {@link #sortedRank} fills it lazily during this draw,
		// so subsequent calls for siblings of the same parent become O(1) lookups. Across
		// renders the cache is invalid because sizes shift as the scan progresses.
		rankCache.clear();

		sectors.clear();
		rects.clear();

		if (scanRoot == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}

		if (currentMode == RenderMode.HEATMAP) {
			// Wrap to keep a paint-time exception from bricking the live ticker / animation
			// timer infrastructure. Logged so we still see what blew up.
			try {
				drawHeatmap(g, w, h);
				drawHeatmapHoverOverlay(g, w, h);
			} catch (RuntimeException ex) {
				LOG.log(java.util.logging.Level.WARNING, "Heatmap render failed", ex);
				drawCenterText(g, w / 2, h / 2, "Heatmap render error — see logs");
			}
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

	private void drawLayout(
			GraphicsContext g, double cx, double cy, double normalW, double thinW, Map<DirectoryNode, Layout> layout) {
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
			// See drawTreemapCell for why we avoid deriveColor for alpha-only mods.
			Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
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
				double scannedSweep = Math.min(usedSweep,
						usedSweep * viewRoot.totalBytes() / (double) target.usedBytes());
				double unaccountedSweep = usedSweep - scannedSweep;
				if (unaccountedSweep > MIN_VISIBLE_SWEEP_DEG) {
					double unaccountedStart = startAngle + scannedSweep;
					Color unaccountedColor =
							hoveringUnaccounted ? scheme.surface().brighter().brighter() : scheme.surface().brighter();
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
			// See drawTreemapCell for why we avoid deriveColor for alpha-only mods.
			Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
			drawAnnularSector(g, cx, cy, r1, r2, fe.start, fe.sweep, fill);
		}
	}

	private void drawAnnularSector(
			GraphicsContext g, double cx, double cy, double r1, double r2, double startDeg,
			double sweepDeg, Color fill) {
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
				// Scanner-driven overrides take precedence; null fields fall back to the
				// bytes/files text that the parallel scanner is happy with.
				Scanner.HubState hs = scanner.hubState();
				title = hs.title() != null ? hs.title() : humanSize(progressBytes);
				subtitle = hs.subtitle() != null ? hs.subtitle() : (progressFiles + " files");
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

	// ---- heatmap (squarified treemap) -----------------------------------

	private static final double HEATMAP_TOP_INSET = 36.0;
	private static final double HEATMAP_MIN_RECURSE_PX = 12.0;
	private static final double HEATMAP_LABEL_MIN_W = 100.0;
	private static final double HEATMAP_LABEL_MIN_H = 24.0;
	private static final double HEATMAP_INNER_PAD = 2.0;

	private void drawHeatmap(GraphicsContext g, double w, double h) {
		if (viewRoot == null)
			return;
		double x = 0;
		double y = HEATMAP_TOP_INSET;
		double availW = w;
		double availH = h - HEATMAP_TOP_INSET;
		if (availW < 4 || availH < 4)
			return;

		List<TreemapItem> items = buildTopLevelTreemapItems();
		long totalBytes = 0;
		for (TreemapItem it : items)
			totalBytes += Math.max(0, it.bytes());
		if (totalBytes <= 0 || items.isEmpty()) {
			drawCenterText(g, w / 2.0, y + availH / 2.0, scanning ? "Scanning…" : "Empty");
			return;
		}

		double scale = (availW * availH) / (double) totalBytes;

		// Free space is pinned to a fixed strip on the right so it doesn't shuffle with
		// the squarified layout — visual convention from capacity bars, and otherwise
		// reads as buggy when the largest item lands on the left.
		TreemapItem freeItem = null;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).freeSpace()) {
				freeItem = items.remove(i);
				break;
			}
		}
		if (freeItem != null && freeItem.bytes() > 0) {
			double freeArea = freeItem.bytes() * scale;
			double freeW = Math.min(availW, freeArea / availH);
			if (freeW >= 1) {
				double freeX = x + availW - freeW;
				drawTreemapCell(g, freeItem, freeX, y, freeW, availH, 0);
				availW -= freeW;
			}
		}

		if (availW < 4 || items.isEmpty())
			return;

		// Stable sort desc; ties keep declaration order so the unaccounted virtual entry
		// stays positioned predictably relative to its siblings.
		items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
		squarify(g, items, x, y, availW, availH, scale, 0);
	}

	private List<TreemapItem> buildTopLevelTreemapItems() {
		List<TreemapItem> items = new ArrayList<>();
		for (DirectoryNode child : viewRoot.children()) {
			items.add(new TreemapItem(child, child.totalBytes(), getNodeColor(child), false, false));
		}
		if (viewRoot == scanRoot && target.totalBytes() > 0) {
			long unaccounted = Math.max(0L, target.usedBytes() - viewRoot.totalBytes());
			if (unaccounted > 0) {
				items.add(new TreemapItem(null, unaccounted, scheme.surface().brighter(), true, false));
			}
			long free = Math.max(0L, target.usableBytes());
			if (free > 0) {
				items.add(new TreemapItem(null, free, scheme.capacityTrack(), false, true));
			}
		}
		return items;
	}

	/**
	 * Squarified treemap (Bruls/Huijgen/van Wijk). Items are pixel-area-scaled via {@code scale}. Walks items in
	 * size-desc order, packing them into rows along the rectangle's short side until adding the next item would worsen
	 * the row's worst aspect ratio, then commits the row and continues on the remaining strip.
	 */
	private void squarify(
			GraphicsContext g, List<TreemapItem> items, double x, double y, double w, double h,
			double scale, int depth) {
		if (items.isEmpty() || w < 1 || h < 1)
			return;
		List<TreemapItem> remaining = new ArrayList<>(items);
		while (!remaining.isEmpty() && w >= 1 && h >= 1) {
			double shortSide = Math.min(w, h);
			List<TreemapItem> row = new ArrayList<>();
			double rowSum = 0;
			double rowMin = Double.POSITIVE_INFINITY;
			double rowMax = 0;
			while (!remaining.isEmpty()) {
				TreemapItem next = remaining.get(0);
				double nextArea = Math.max(0, next.bytes()) * scale;
				if (nextArea <= 0) {
					remaining.remove(0);
					continue;
				}
				double trialSum = rowSum + nextArea;
				double trialMin = Math.min(rowMin, nextArea);
				double trialMax = Math.max(rowMax, nextArea);
				double currentWorst =
						row.isEmpty() ? Double.POSITIVE_INFINITY : worstAspect(rowSum, rowMin, rowMax, shortSide);
				double trialWorst = worstAspect(trialSum, trialMin, trialMax, shortSide);
				if (row.isEmpty() || trialWorst <= currentWorst) {
					row.add(next);
					rowSum = trialSum;
					rowMin = trialMin;
					rowMax = trialMax;
					remaining.remove(0);
				} else {
					break;
				}
			}
			if (row.isEmpty())
				break;
			double thickness = rowSum / shortSide;
			boolean rowAlongTop = w < h;
			if (rowAlongTop) {
				layoutHeatmapRow(g, row, x, y, w, thickness, scale, true, depth);
				y += thickness;
				h -= thickness;
			} else {
				layoutHeatmapRow(g, row, x, y, thickness, h, scale, false, depth);
				x += thickness;
				w -= thickness;
			}
		}
	}

	private static double worstAspect(double sum, double min, double max, double shortSide) {
		if (sum <= 0 || shortSide <= 0)
			return Double.POSITIVE_INFINITY;
		double s2 = sum * sum;
		double w2 = shortSide * shortSide;
		return Math.max((w2 * max) / s2, s2 / (w2 * min));
	}

	private void layoutHeatmapRow(
			GraphicsContext g, List<TreemapItem> row, double x, double y, double w, double h,
			double scale, boolean rowAlongTop, int depth) {
		double rowSum = 0;
		for (TreemapItem t : row)
			rowSum += Math.max(0, t.bytes()) * scale;
		if (rowSum <= 0)
			return;
		double offset = 0;
		for (TreemapItem t : row) {
			double area = Math.max(0, t.bytes()) * scale;
			double frac = area / rowSum;
			double rx, ry, rw, rh;
			if (rowAlongTop) {
				rx = x + offset;
				ry = y;
				rw = w * frac;
				rh = h;
				offset += rw;
			} else {
				rx = x;
				ry = y + offset;
				rw = w;
				rh = h * frac;
				offset += rh;
			}
			drawTreemapCell(g, t, rx, ry, rw, rh, depth);
		}
	}

	private void drawTreemapCell(
			GraphicsContext g, TreemapItem item, double x, double y, double w, double h, int depth) {
		// Sub-pixel cull. Anything thinner than 1 px on either axis can't render visibly
		// (Canvas's fillRect will antialias to nothing) and we'd still pay for getNodeColor,
		// hover-state derivation, fillRect, and a rects.add hit-test entry. JFR flagged the
		// recursive squarify/drawTreemapCell chain as the FX-thread CPU dominant on big
		// trees; this is the cheapest and biggest cut to its max-render time.
		if (w < 1.0 || h < 1.0)
			return;

		Color base = item.color();
		boolean hovered = false;
		if (item.node() != null && hoverNode == item.node()) {
			base = base.deriveColor(0, 1.20, 0.85, 1.0);
			hovered = true;
		} else if (item.freeSpace() && hoveringFreeSpace) {
			base = scheme.capacityTrack().brighter();
			hovered = true;
		} else if (item.unaccounted() && hoveringUnaccounted) {
			base = scheme.surface().brighter().brighter();
			hovered = true;
		}
		double alpha = (item.node() == null || item.node().isDone()) ? 1.0 : 0.45;
		if (hovered)
			alpha = Math.min(1.0, alpha + 0.10);
		// Avoid Color.deriveColor() here: it always does RGB→HSB→RGB even when hue/sat/bright
		// are no-ops (the 0,1,1 args), allocating a fresh Color per cell. JFR flagged this as
		// the dominant per-cell cost on million-cell heatmap renders. Two cheap fast paths:
		//   - alpha == 1.0 (post-scan, common case): base is already opaque, reuse it as-is.
		//   - alpha < 1.0: direct constructor does the same thing as deriveColor's alpha-only
		//     path without the HSB roundtrip.
		Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);

		g.setFill(fill);
		g.fillRect(x, y, w, h);

		if (w > 1.5 && h > 1.5) {
			g.setStroke(scheme.background());
			g.setLineWidth(1.0);
			g.strokeRect(x + 0.5, y + 0.5, Math.max(0, w - 1), Math.max(0, h - 1));
		}

		rects.add(new RectHit(item.node(), x, y, w, h, item.unaccounted(), item.freeSpace()));

		boolean recursable = item.node() != null && !item.node().isFileSector() && !item.node().children().isEmpty();
		double childX = x + HEATMAP_INNER_PAD;
		double childY = y + HEATMAP_INNER_PAD;
		double childW = w - 2 * HEATMAP_INNER_PAD;
		double childH = h - 2 * HEATMAP_INNER_PAD;

		if (w >= HEATMAP_LABEL_MIN_W && h >= HEATMAP_LABEL_MIN_H && (item.node() != null || item.freeSpace() || item.unaccounted())) {
			String name;
			long bytes;
			if (item.node() != null) {
				name = item.node().name();
				bytes = item.node().totalBytes();
			} else if (item.freeSpace()) {
				name = "Free";
				bytes = target.usableBytes();
			} else {
				name = "Other";
				bytes = item.bytes();
			}
			drawTreemapLabel(g, x, y, w, name, bytes, base);
			if (recursable) {
				double labelBand = 16;
				if (childH > labelBand + HEATMAP_MIN_RECURSE_PX) {
					childY += labelBand;
					childH -= labelBand;
				}
			}
		}

		if (recursable && childW >= HEATMAP_MIN_RECURSE_PX && childH >= HEATMAP_MIN_RECURSE_PX) {
			List<DirectoryNode> kids = item.node().children();
			List<TreemapItem> childItems = new ArrayList<>(kids.size());
			long childTotal = 0;
			for (DirectoryNode k : kids) {
				long b = k.totalBytes();
				if (b <= 0)
					continue;
				childItems.add(new TreemapItem(k, b, getNodeColor(k), false, false));
				childTotal += b;
			}
			if (childTotal > 0 && !childItems.isEmpty()) {
				childItems.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
				double childScale = (childW * childH) / (double) childTotal;
				squarify(g, childItems, childX, childY, childW, childH, childScale, depth + 1);
			}
		}
	}

	private void drawTreemapLabel(
			GraphicsContext g, double x, double y, double w, String name, long bytes, Color fillBase) {
		Color textColor = textOn(fillBase);
		g.setFill(textColor);
		g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
		g.setTextAlign(TextAlignment.LEFT);
		g.setTextBaseline(VPos.TOP);
		String size = humanSize(bytes);
		double padded = Math.max(0, w - 12);
		String sizeSuffix = "  " + size;
		int approxCharsForName = Math.max(2, (int) (padded / 6.5) - sizeSuffix.length());
		String shown = truncate(name, approxCharsForName) + sizeSuffix;
		g.fillText(shown, x + 6, y + 4, padded);
	}

	private void drawHeatmapHoverOverlay(GraphicsContext g, double w, double h) {
		String name;
		long bytes;
		if (hoverNode != null) {
			name = hoverNode.name();
			bytes = hoverNode.totalBytes();
		} else if (hoveringFreeSpace) {
			name = "Free";
			bytes = target.usableBytes();
		} else if (hoveringUnaccounted) {
			name = "Other";
			bytes = Math.max(0, target.usedBytes() - (scanRoot != null ? scanRoot.totalBytes() : 0));
		} else if (scanning && progressPath != null) {
			name = tailPath(progressPath);
			bytes = progressBytes;
		} else {
			return;
		}
		String text = truncate(name, 60) + "  —  " + humanSize(bytes);
		g.setFont(Font.font("Segoe UI", 11));
		double pad = 8;
		double textW = Math.min(w - 24, 6.5 * text.length() + 2 * pad);
		double boxH = 22;
		double boxX = 12;
		double boxY = h - boxH - 12;
		g.setFill(scheme.surface().deriveColor(0, 1, 1, 0.85));
		g.fillRoundRect(boxX, boxY, textW, boxH, 6, 6);
		g.setFill(scheme.textPrimary());
		g.setTextAlign(TextAlignment.LEFT);
		g.setTextBaseline(VPos.CENTER);
		g.fillText(text, boxX + pad, boxY + boxH / 2.0, textW - 2 * pad);
	}

	private static Color textOn(Color bg) {
		double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
		return lum > 0.55 ? Color.gray(0.10) : Color.gray(0.95);
	}

	private void drawHubProgress(GraphicsContext g, double cx, double cy) {
		double r = HUB_RADIUS - 4;
		double thickness = 2.5;

		g.setStroke(scheme.accent());
		g.setLineWidth(thickness);
		g.setLineCap(StrokeLineCap.ROUND);

		// Scanner override wins; otherwise compute bytes/usedBytes ourselves (the parallel
		// scanner relies on the fallback). Negative = "no override, use the default path."
		Scanner.HubState hs = scanner.hubState();
		double frac = hs.arcFraction();
		if (frac < 0) {
			long usedBytes = target.totalBytes() - target.usableBytes();
			if (usedBytes > 0 && progressBytes > 0) {
				frac = Math.min(1.0, progressBytes / (double) usedBytes);
			}
		}

		if (frac >= 0) {
			// Faint full track first.
			g.setStroke(scheme.accent().deriveColor(0, 1, 1, 0.18));
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90, 360, ArcType.OPEN);
			// Filled portion clockwise from 12 o'clock.
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
		// Freeze the highlight while the path context menu is open so the sector under the
		// right-click stays selected as the user moves the cursor over the menu items.
		if (pathContextMenu.isShowing())
			return;

		boolean wasHub = hoveringHub;
		boolean wasFree = hoveringFreeSpace;
		boolean wasUnaccounted = hoveringUnaccounted;
		DirectoryNode wasNode = hoverNode;

		hoveringHub = false;
		hoverNode = null;
		hoveringFreeSpace = false;
		hoveringUnaccounted = false;

		if (currentMode == RenderMode.HEATMAP) {
			// Iterate in reverse so deeper rects (added later by recursion) win the hit.
			for (int i = rects.size() - 1; i >= 0; i--) {
				RectHit r = rects.get(i);
				if (r.contains(mx, my)) {
					if (r.node() != null) {
						hoverNode = r.node();
					} else if (r.freeSpace()) {
						hoveringFreeSpace = true;
					} else if (r.unaccounted()) {
						hoveringUnaccounted = true;
					}
					break;
				}
			}
		} else {
			double cx = canvas.getWidth() / 2.0;
			double cy = canvas.getHeight() / 2.0;
			double dx = mx - cx;
			double dy = my - cy;
			double r = Math.hypot(dx, dy);
			if (r < HUB_RADIUS) {
				hoveringHub = true;
			} else {
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
		}

		if (wasHub != hoveringHub || wasFree != hoveringFreeSpace || wasUnaccounted != hoveringUnaccounted || wasNode != hoverNode) {
			redraw();
		}
	}

	private void handleClick(double mx, double my) {
		if (scanRoot == null || animating)
			return;
		root.requestFocus();

		if (currentMode == RenderMode.HEATMAP) {
			for (int i = rects.size() - 1; i >= 0; i--) {
				RectHit r = rects.get(i);
				if (r.contains(mx, my) && r.node() != null) {
					select(r.node());
					return;
				}
			}
			return;
		}

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
	 * Walk from current viewRoot up to {@code targetAncestor}, pushing each intermediate node onto the forward stack so
	 * Right arrow can replay the path. {@code targetAncestor} must be an ancestor of viewRoot (or equal to it).
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
				if (e.getButton() == MouseButton.PRIMARY && !animating)
					navigateUpTo(node);
			});
		}
		pathContextMenu.install(l, e -> targetFor(node));
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
		return "-fx-text-fill: " + css(color) + ";" + "-fx-font-size: 11.5px; -fx-font-weight: " + weight + ";" + (
				active ? "" : "-fx-cursor: hand;");
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
		PathTarget pt = targetFor((hoverNode != null) ? hoverNode : viewRoot);
		if (pt != null)
			revealPath(pt);
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
		// Locale.ROOT forces "." as the decimal separator. Without it, locales such as Swedish
		// or German render the alpha as "0,550", and the CSS string becomes
		// "rgba(122,211,217,0,550)" — five commas, which JavaFX parses by taking the first four
		// tokens, so alpha silently becomes 0 (transparent).
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
				(int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double easeOutCubic(double t) {
		double inv = 1 - t;
		return 1 - inv * inv * inv;
	}

	private record SectorRect(DirectoryNode node, int depth, double startDeg, double sweepDeg, double r1, double r2,
							  boolean unaccounted) {
	}

	/**
	 * Hit-test rect for the heatmap. {@code node == null} means the rectangle is the free-space or unaccounted virtual
	 * entry at scan root.
	 */
	private record RectHit(DirectoryNode node, double x, double y, double w, double h, boolean unaccounted,
						   boolean freeSpace) {
		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}

	/**
	 * Heatmap item — a directory child or a virtual entry (free / unaccounted) used while building the squarified
	 * layout. {@code bytes} drives the layout area; {@code color} is the resolved fill before alpha/hover modulation.
	 */
	private record TreemapItem(DirectoryNode node, long bytes, Color color, boolean unaccounted, boolean freeSpace) {
	}

	private record Layout(double depth, double startDeg, double sweepDeg, Color color) {
	}

	private record FrameEntry(DirectoryNode node, double depth, double start, double sweep, double alphaScale,
							  Color color) {
	}

	/**
	 * Row in the staging (delete-tray) table: a folder or file the user has marked for deletion. {@code parentNode} is
	 * captured at staging time so a successful delete can apply size/count deltas to the in-memory tree without
	 * re-walking.
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

	/**
	 * Kind of thing the menu is acting on. Determines which actions the menu shows and how
	 * "Open"-style actions interpret the path:
	 * <ul>
	 *   <li>{@link #DIRECTORY} — a real on-disk folder. Menu shows {@code Open} only.</li>
	 *   <li>{@link #FILE} — a real on-disk file. Menu shows {@code Open} (default app) and
	 *       {@code Open Location} (reveals in the parent folder).</li>
	 *   <li>{@link #AGGREGATE} — a synthetic node with no path of its own (currently the
	 *       "Smaller files" sector). The {@link PathTarget#path} is set to the parent directory
	 *       so {@code Open Location} can take the user there; {@code Open} is omitted because
	 *       there's no single thing to launch.</li>
	 * </ul>
	 */
	private enum TargetKind {DIRECTORY, FILE, AGGREGATE}

	/**
	 * What the context menu acts on: an on-disk path, the {@link TargetKind} (which controls menu shape and
	 * "Open"-style action semantics), and a {@link Runnable} that knows how to stage this specific target for
	 * deletion. The stage action is supplied by the resolver because it needs domain context the menu doesn't have
	 * (size, parent node, file vs directory). {@code null} stage action → menu item is disabled (e.g. the scan root,
	 * which we refuse to stage as a footgun guard, or aggregates which have no concrete delete target).
	 */
	private record PathTarget(Path path, TargetKind kind, Runnable stageAction) {
		boolean isDirectory() {
			return kind == TargetKind.DIRECTORY;
		}
	}

	/** Resolves the target for a context-menu request. Returning {@code null} suppresses the menu. */
	@FunctionalInterface
	private interface PathTargetResolver {
		PathTarget resolve(ContextMenuEvent event);
	}

	/**
	 * Reusable per-{@link DiskView} context menu for "things that represent a path." One instance is wired into every
	 * call site via {@link #install}; each site supplies a resolver that maps the right-click event to its current
	 * {@link PathTarget}. The resolved target is captured on show so the {@link MenuItem} actions, which fire later,
	 * still know what was clicked.
	 */
	private final class PathContextMenu {
		private final ContextMenu menu = new ContextMenu();
		/**
		 * Disabled header item naming the target of the menu. JavaFX renders disabled items in a muted style, which
		 * reads naturally as a "you are operating on…" label and answers the "what does this menu act on?" question
		 * even after the on-canvas highlight is gone.
		 */
		private final MenuItem headerItem = new MenuItem();
		// Path-section items. {@link #openItem} (Open) opens the path in its default handler — the
		// folder for directories, the registered app for files. {@link #openLocationItem} reveals a
		// file in its containing folder; redundant for directories, so only added to the menu when
		// the target is a file.
		private final MenuItem openItem = new MenuItem("Open");
		private final MenuItem openLocationItem = new MenuItem("Open Location");
		private final MenuItem copyItem = new MenuItem("Copy Path");
		private final MenuItem stageItem = new MenuItem("Stage for Removal");
		// View-section items (canvas only) — same semantics as the keyboard shortcuts so they
		// stay discoverable for users who don't memorize hotkeys.
		private final MenuItem helpItem = new MenuItem("Show Keyboard Shortcuts");
		private final MenuItem rescanItem = new MenuItem("Re-scan");
		private final MenuItem toggleUnitsItem = new MenuItem("Toggle Size Units");
		private final MenuItem toggleVizItem = new MenuItem("Toggle Visualization");
		private final MenuItem quitItem = new MenuItem("Quit");
		private PathTarget pending;
		/**
		 * Wall-clock nanos of the last hide, used to swallow the immediate re-open caused by right-clicking again to
		 * dismiss: ContextMenu auto-hides on the new mouse press, and the OS then fires a fresh CONTEXT_MENU_REQUESTED
		 * that would otherwise reopen the menu at the same spot. If a request lands within
		 * {@link #TOGGLE_DEBOUNCE_NANOS} of the hide, we treat it as the closing half of a toggle and ignore it.
		 */
		private long lastHideNanos;
		private static final long TOGGLE_DEBOUNCE_NANOS = 150_000_000L; // 150 ms

		/**
		 * Bubble-phase handler installed on the menu's own scene while the menu is shown. Listens
		 * for {@code MOUSE_RELEASED} (not {@code MOUSE_PRESSED}) so the MenuItem skin gets to fire
		 * the item's action on release first — by the time this handler runs, the menu is already
		 * hidden and the {@code isShowing()} guard makes it a no-op. For clicks that <em>didn't</em>
		 * land on an actionable item — right-clicks on items, left-clicks on the menu's padding /
		 * header / border — nothing dismisses the menu, so this handler does it explicitly.
		 */
		private final javafx.event.EventHandler<javafx.scene.input.MouseEvent> menuMissDismiss = e -> {
			if (menu.isShowing()) {
				menu.hide();
				e.consume();
			}
		};

		PathContextMenu() {
			headerItem.setDisable(true);
			menu.setOnShown(e -> {
				javafx.scene.Scene s = menu.getScene();
				if (s != null)
					s.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, menuMissDismiss);
			});
			menu.setOnHidden(e -> {
				javafx.scene.Scene s = menu.getScene();
				if (s != null)
					s.removeEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, menuMissDismiss);
				lastHideNanos = System.nanoTime();
			});
			openItem.setOnAction(e -> {
				if (pending != null)
					openPath(pending);
			});
			openLocationItem.setOnAction(e -> {
				if (pending != null)
					revealPath(pending);
			});
			copyItem.setOnAction(e -> {
				if (pending != null && pending.path() != null)
					copyPathToClipboard(pending.path());
			});
			stageItem.setOnAction(e -> {
				if (pending != null && pending.stageAction() != null)
					pending.stageAction().run();
			});
			helpItem.setOnAction(e -> {
				emitUserAction("ContextMenu", "toggle-help");
				toggleHelp();
			});
			rescanItem.setOnAction(e -> {
				if (!deleting) {
					emitUserAction("ContextMenu", "rescan");
					rescan();
				}
			});
			toggleUnitsItem.setOnAction(e -> {
				emitUserAction("ContextMenu", "toggle-units");
				SizeFormat.toggle();
				refreshAfterUnitChange();
			});
			toggleVizItem.setOnAction(e -> {
				emitUserAction("ContextMenu", "toggle-mode");
				toggleRenderMode();
			});
			quitItem.setOnAction(e -> {
				emitUserAction("ContextMenu", "quit");
				se.hirt.diskspace.App.requestQuit();
			});
		}

		boolean isShowing() {
			return menu.isShowing();
		}

		void hide() {
			menu.hide();
		}

		void install(Node node, PathTargetResolver resolver) {
			install(node, resolver, false);
		}

		/**
		 * @param viewActions
		 * 		when {@code true}, append the view-level actions (Help, Rescan, Toggle Units, Toggle Visualization, Quit).
		 * 		Used by the canvas, where there's no other obvious place to surface these actions for mouse-only users.
		 */
		void install(Node node, PathTargetResolver resolver, boolean viewActions) {
			node.setOnContextMenuRequested(e -> {
				if (System.nanoTime() - lastHideNanos < TOGGLE_DEBOUNCE_NANOS) {
					// Right-click-to-dismiss: swallow the OS-generated re-open that follows the
					// auto-hide so the user isn't trapped having to pick an action.
					e.consume();
					return;
				}
				PathTarget t = resolver.resolve(e);
				boolean hasPath = (t != null && t.path() != null);
				if (!hasPath && !viewActions) {
					menu.hide();
					return;
				}
				menu.getItems().clear();
				if (hasPath) {
					pending = t;
					headerItem.setText(shortLabel(t.path()));
					stageItem.setDisable(t.stageAction() == null);
					menu.getItems().addAll(headerItem, new SeparatorMenuItem());
					switch (t.kind()) {
					case DIRECTORY -> menu.getItems().add(openItem);
					case FILE -> menu.getItems().addAll(openItem, openLocationItem);
					case AGGREGATE -> menu.getItems().add(openLocationItem);
					}
					menu.getItems().addAll(copyItem, new SeparatorMenuItem(), stageItem);
				} else {
					pending = null;
				}
				if (viewActions) {
					if (hasPath) {
						// Sector menu: keep it tight. Re-scan and Toggle Visualization stay because
						// they're the most likely "I want to do something to the view from here"
						// follow-ups; Help / Toggle Units / Quit live on the empty-canvas menu.
						menu.getItems().add(new SeparatorMenuItem());
						menu.getItems().addAll(rescanItem, toggleVizItem);
					} else {
						// Empty-canvas menu: full set of view-level actions for mouse-only users.
						menu.getItems()
								.addAll(helpItem, rescanItem, toggleUnitsItem, toggleVizItem, new SeparatorMenuItem(),
										quitItem);
					}
				}
				menu.show(node, e.getScreenX(), e.getScreenY());
				e.consume();
			});
		}
	}

	private static String shortLabel(Path p) {
		if (p == null)
			return "";
		Path name = p.getFileName();
		return name != null ? name.toString() : p.toString();
	}

	/**
	 * JFR duration event spanning a window during which a particular {@link RenderMode} was displayed — from the moment
	 * a {@link DiskView} is built (or the user toggles modes) until the next toggle or until the view is shut down.
	 * JMC's event browser shows one row per window, with the {@code renderCount} field telling you how many actual
	 * repaints happened during that span — a 7 s window with 420 renders is very different from a 7 s window with 4.
	 * <p>Spans user-interaction time, not render time. For per-frame cost see {@link RenderEvent}.
	 * <p>Works in JVM mode and in native-image builds compiled with
	 * {@code --enable-monitoring=jfr}. Default-enabled, no extra registration required.
	 */
	@jdk.jfr.Name("se.hirt.diskspace.VisualizationActive")
	@jdk.jfr.Label("Visualization Active")
	@jdk.jfr.Category({"DiskSpace", "UI"})
	@jdk.jfr.Description("A period during which a particular visualization mode was displayed for a particular disk. " + "Spans user-interaction time, not render time. Use Render events for per-frame cost.")
	public static class VisualizationEvent extends jdk.jfr.Event {
		@jdk.jfr.Label("Mode")
		@jdk.jfr.Description("SUNBURST or HEATMAP.")
		String mode;
		@jdk.jfr.Label("Disk")
		@jdk.jfr.Description("Display name of the scanned disk.")
		String disk;
		@jdk.jfr.Label("Render Count")
		@jdk.jfr.Description("Number of actual repaints that happened while this mode was active.")
		int renderCount;
		@jdk.jfr.Label("Scan ID")
		@jdk.jfr.Description("Correlation ID matching the Scan / Render events of the same scan run.")
		long scanId;
	}

	/**
	 * JFR event fired around each repaint of the visualization on the JavaFX thread — the wall-clock time the FX thread
	 * spent re-laying-out and drawing. {@code trigger} tells you why the repaint happened (scan-update / mode-change /
	 * resize / user / …) and {@code nodeCount} approximates tree size at render time, so you can answer "how does
	 * render cost scale with the tree?" directly from the recording.
	 * <p>{@code @StackTrace(false)}: the call site is fixed and known; capturing a stack
	 * per render would be the bulk of the event's cost.
	 */
	@jdk.jfr.Name("se.hirt.diskspace.Render")
	@jdk.jfr.Label("Visualization Render")
	@jdk.jfr.Category({"DiskSpace", "UI"})
	@jdk.jfr.Description("One repaint of the visualization on the JavaFX UI thread.")
	@jdk.jfr.StackTrace(false)
	public static class RenderEvent extends jdk.jfr.Event {
		@jdk.jfr.Label("Mode")
		@jdk.jfr.Description("SUNBURST or HEATMAP.")
		String mode;
		@jdk.jfr.Label("Trigger")
		@jdk.jfr.Description("Why this render was scheduled: scan-update / mode-change / resize / user / scan-start / scan-complete / scan-error / rescan / anim / auto.")
		String trigger;
		@jdk.jfr.Label("Node Count")
		@jdk.jfr.Description("Approximate live file count at render time (tracks tree growth during a scan).")
		long nodeCount;
		@jdk.jfr.Label("Width")
		@jdk.jfr.Description("Canvas width in pixels at render time.")
		int widthPx;
		@jdk.jfr.Label("Height")
		@jdk.jfr.Description("Canvas height in pixels at render time.")
		int heightPx;
		@jdk.jfr.Label("Scan ID")
		@jdk.jfr.Description("Correlation ID matching the Scan event of the active scan, or 0 if no scan is in flight.")
		long scanId;
	}

	/**
	 * JFR instant event fired when the user takes an action that changes view state — a keypress, a navigation click,
	 * etc. The key field is the literal key name (or {@code "Click"} for mouse), the operation field describes what
	 * happened ({@code "toggle-mode"}, {@code "rescan"}, {@code "drill-in"}, …). Lets JMC line up "user pressed V" with
	 * the {@link RenderEvent} it caused.
	 */
	@jdk.jfr.Name("se.hirt.diskspace.UserAction")
	@jdk.jfr.Label("User Action")
	@jdk.jfr.Category({"DiskSpace", "UI"})
	@jdk.jfr.Description("Keypress or click and the operation it triggered. Instant event — duration is zero.")
	@jdk.jfr.StackTrace(false)
	public static class UserActionEvent extends jdk.jfr.Event {
		@jdk.jfr.Label("Key")
		@jdk.jfr.Description("Key name (e.g. V, R, Esc) or 'Click' for mouse.")
		String key;
		@jdk.jfr.Label("Operation")
		@jdk.jfr.Description("Effect of the action: toggle-mode, rescan, drill-in, drill-out, toggle-units, …")
		String operation;
		@jdk.jfr.Label("Mode")
		@jdk.jfr.Description("Visualization mode at the time of the action.")
		String mode;
		@jdk.jfr.Label("Scan ID")
		long scanId;
	}

	/**
	 * Fires a {@link UserActionEvent} for the given key + operation. Cheap when JFR isn't recording — instant events
	 * with no stack trace are essentially a timestamp write.
	 */
	private void emitUserAction(String key, String operation) {
		UserActionEvent e = new UserActionEvent();
		e.key = key;
		e.operation = operation;
		e.mode = currentMode.name();
		e.scanId = currentScanId;
		e.commit();
	}

	/**
	 * JFR event fired once per scan, at completion (or cancel/error). Records the final tree dimensions and total bytes
	 * so scan-throughput is a first-class metric in JMC. Use {@code scanId} to join Render events from the same run.
	 */
	@jdk.jfr.Name("se.hirt.diskspace.Scan")
	@jdk.jfr.Label("Disk Scan")
	@jdk.jfr.Category({"DiskSpace", "Scanner"})
	@jdk.jfr.Description("Top-level disk scan from start to completion or cancellation.")
	public static class ScanEvent extends jdk.jfr.Event {
		@jdk.jfr.Label("Scan ID")
		@jdk.jfr.Description("Correlation ID also stamped on Render and VisualizationActive events from the same run.")
		long scanId;
		@jdk.jfr.Label("Root Path")
		@jdk.jfr.Description("Filesystem root that was scanned (e.g. C:\\, /home).")
		String root;
		@jdk.jfr.Label("Strategy")
		@jdk.jfr.Description("Which scanner ran: MFT / Parallel(N) / Sequential.")
		String strategy;
		@jdk.jfr.Label("File Count")
		@jdk.jfr.Description("Total number of files counted by the scan (excludes directories).")
		long fileCount;
		@jdk.jfr.Label("Total Bytes")
		@jdk.jfr.Description("Sum of file sizes captured by the scan, in bytes.")
		@jdk.jfr.DataAmount
		long totalBytes;
		@jdk.jfr.Label("Permission Denied Count")
		@jdk.jfr.Description("Number of files or directories the scanner couldn't read because of permission denials.")
		long permissionDeniedCount;
		@jdk.jfr.Label("Outcome")
		@jdk.jfr.Description("How the scan ended: complete (normal completion), error (exception), or cancelled (user navigated away).")
		String outcome;
	}

	/**
	 * Begins a new {@link VisualizationEvent} for the current {@link #currentMode} on the current target volume,
	 * replacing any in-flight event reference. Cheap when JFR isn't recording — {@code Event.begin()} is a single
	 * nanoTime read. Resets the per-window render counter so the event's {@code renderCount} reflects only this
	 * window.
	 */
	private void startVizEvent() {
		vizEventRenderCount = 0;
		VisualizationEvent e = new VisualizationEvent();
		e.mode = currentMode.name();
		e.disk = target.displayName();
		e.scanId = currentScanId;
		e.begin();
		currentVizEvent = e;
	}

	/**
	 * Ends + commits the current {@link VisualizationEvent}, if any. Idempotent. Captures {@link #vizEventRenderCount}
	 * into the event payload before commit.
	 */
	private void endVizEvent() {
		VisualizationEvent e = currentVizEvent;
		if (e != null) {
			currentVizEvent = null;
			e.renderCount = vizEventRenderCount;
			e.end();
			e.commit();
		}
	}
}
