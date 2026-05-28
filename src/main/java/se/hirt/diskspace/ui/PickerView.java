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

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.ScanStrategy;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.settings.Settings;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.Theme;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PickerView {

	private final StackPane root;
	private ColorScheme scheme;
	private final Consumer<Volume> onSelection;
	private final List<Runnable> sizeRefreshers = new ArrayList<>();
	/**
	 * Per-region restylers registered as each component is built. {@link #applyTheme} walks the list so every inline
	 * style that captured a {@code scheme.xxx()} colour at construction time gets re-run against the new scheme.
	 * Parallel to {@link #sizeRefreshers}, which the {@code U} key uses for the same fan-out shape.
	 */
	private final List<Runnable> styleRefreshers = new ArrayList<>();
	private final StackPane helpOverlay;
	private final StackPane aboutOverlay;
	private final StackPane licenseOverlay;
	/**
	 * Lifted to an instance field so {@link #dispatchTopLevelKey} can fire it on S. Constructed during
	 * {@link #PickerView} setup; never null after the constructor returns.
	 */
	private Runnable toggleStrategy;
	/**
	 * Opacity breathing on the disk-discovery progress marker while work is in flight — the same gentle pulse the
	 * scanning badge uses in {@code DiskView} (1.0 → 0.5 → 1.0 every ~1.2 s). Also drives {@link #placeholderOpacity}
	 * in lock-step. Started by {@link #startEnumeration}, and stopped by {@link #hideProgress} once everything has
	 * resolved.
	 */
	private final Timeline progressPulse;
	/** Disk-discovery marker (wheel + status text) at the right of the title row. */
	private final HBox progressRow;
	/** Status text inside {@link #progressRow}: "Looking for disks…" then "Identifying disk types…". */
	private final Label scanningLabel;
	/**
	 * Shared opacity driver for placeholder breathing, animated 0.75 ↔ 0.4 by {@link #progressPulse}. Each
	 * not-yet-ready disk row binds its opacity to this so the whole list of pending rows breathes in unison; a row
	 * unbinds (snapping to full opacity when ready, or a static dim when unavailable) the moment its fate is decided.
	 */
	private final DoubleProperty placeholderOpacity = new SimpleDoubleProperty(1.0);
	/** FX-thread-only: disk rows not yet in a terminal state (ready or unavailable). Hits 0 → hide the marker. */
	private int pendingRows;
	/** FX-thread-only: roots whose size resolve hasn't finished. Hits 0 → flip the status text to "Identifying…". */
	private int resolvesRemaining;
	/**
	 * Whether settled-unavailable disks are hidden. Seeded from the persistent preference when the picker is built, and
	 * flipped in-session by the {@code H} shortcut (session-only, like {@code U}/{@code V}/{@code C}/{@code T} — it
	 * doesn't rewrite the saved default). When on, an unreadable disk's row is kept in the list but
	 * {@code managed=false} so toggling can reveal it again without losing its place.
	 */
	private boolean hideUnavailableDisks = Settings.get().hideUnavailableDisks();
	/** Every disk row, in root order. Held so the {@code H} toggle can revisit the settled-unavailable ones. */
	private List<DiskRow> diskRows = List.of();
	/**
	 * Set by {@link #dispose()} when the picker's tab is swapped to a disk view or closed. The streaming callbacks
	 * check it and become no-ops, so abandoned enumeration doesn't keep mutating detached nodes or competing with a
	 * freshly-started scan. FX-thread-only.
	 */
	private boolean disposed;

	public PickerView(ColorScheme scheme, Consumer<Volume> onSelection) {
		this.scheme = scheme;
		this.onSelection = onSelection;

		Label title = new Label("Choose a disk");
		styleRefreshers.add(() -> title.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-size: 22px; -fx-font-weight: 600;"));

		// The disk list is populated asynchronously (see startEnumeration). Resolving a volume's
		// free/total space hits the medium, and a failing one — a dead SD card in a reader, a
		// stalled USB drive — can block for tens of seconds; doing that synchronously on the FX
		// thread (as the old Volume.enumerate() loop here did) wedged the whole startup. So the
		// picker shows immediately and rows slot in as each root reports back, in root order.
		VBox rows = new VBox(12);

		// Discreet "still discovering disks" marker, parked on the right of the title row and
		// hidden once every root has reported in (or the watchdog gives up on an unresponsive
		// one). JavaFX's ProgressIndicator with no progress value set runs in indeterminate mode
		// — the built-in spinning wheel. Small and muted so it reads as a hint, not a modal wait.
		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setPrefSize(16, 16);
		spinner.setMinSize(16, 16);
		spinner.setMaxSize(16, 16);
		styleRefreshers.add(() -> spinner.setStyle("-fx-progress-color: " + toCss(this.scheme.accent()) + ";"));
		scanningLabel = new Label("Looking for disks…");
		styleRefreshers.add(() -> scanningLabel.setStyle(
				"-fx-text-fill: " + toCss(this.scheme.textMuted()) + ";" + "-fx-font-size: 12px;"));
		progressRow = new HBox(8, scanningLabel, spinner);
		progressRow.setAlignment(Pos.CENTER_RIGHT);
		// Breathe the whole marker (label + wheel) in and out while it's up, the same trick the
		// scanning badge uses in DiskView, so the status reads as a soft "still working" hint rather
		// than a solid, intrusive label. The same timeline also drives placeholderOpacity (0.75 ↔
		// 0.4) so the not-yet-ready disk rows breathe in sync with it. One 600 ms keyframe +
		// autoReverse = a ~1.2 s round trip; INDEFINITE keeps it going until hideProgress stops it.
		progressPulse = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(progressRow.opacityProperty(), 1.0),
				new KeyValue(placeholderOpacity, 0.75)),
				new KeyFrame(Duration.millis(600), new KeyValue(progressRow.opacityProperty(), 0.5),
						new KeyValue(placeholderOpacity, 0.4)));
		progressPulse.setAutoReverse(true);
		progressPulse.setCycleCount(Timeline.INDEFINITE);

		Region titleSpacer = new Region();
		HBox.setHgrow(titleSpacer, Priority.ALWAYS);
		HBox titleRow = new HBox(title, titleSpacer, progressRow);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		// The 18px gap below the header used to be the title label's bottom padding; moved here so
		// the wheel and its label centre vertically against the title text, not its padded box.
		titleRow.setPadding(new Insets(0, 0, 18, 0));

		Button choose = new Button("Choose folder…");
		styleRefreshers.add(() -> choose.setStyle("-fx-background-color: transparent;" + "-fx-text-fill: " + toCss(
				this.scheme.accent()) + ";" + "-fx-border-color: " + toCss(
				this.scheme.accent()) + ";" + "-fx-border-radius: 6; -fx-background-radius: 6;" + "-fx-padding: 8 14 8 14; -fx-cursor: hand;"));
		choose.setOnAction(e -> {
			DirectoryChooser dc = new DirectoryChooser();
			dc.setTitle("Choose folder to scan");
			Window w = choose.getScene() == null ? null : choose.getScene().getWindow();
			File picked = dc.showDialog(w);
			if (picked != null) {
				onSelection.accept(Volume.from(picked.toPath()));
			}
		});

		// Discreet scan-strategy indicator on the right of the bottom row. Click or press S
		// to cycle through AUTO → BULK → MFT → PARALLEL → SEQUENTIAL, skipping the strategies
		// whose primary provider isn't registered on this platform (so on macOS the visible
		// cycle is AUTO → BULK → PARALLEL → SEQUENTIAL and on Windows it's AUTO → MFT →
		// PARALLEL → SEQUENTIAL). Tooltip explains the current choice; row tooltips regenerate
		// on hover so per-disk strategy text picks up the change automatically.
		Label strategyLabel = new Label();
		styleRefreshers.add(() -> strategyLabel.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 0 0 0 12;"));
		Runnable refreshStrategy = () -> strategyLabel.setText(
				"Scan: " + Scanner.PREFERENCE.get().label() + "  ·  click or S to cycle");
		refreshStrategy.run();
		Tooltip strategyTip = new Tooltip();
		strategyTip.setShowDelay(Duration.millis(300));
		strategyTip.setStyle(
				"-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
		strategyTip.setOnShowing(e -> strategyTip.setText(buildStrategyTooltip()));
		Tooltip.install(strategyLabel, strategyTip);
		this.toggleStrategy = () -> {
			Scanner.PREFERENCE.updateAndGet(Scanner::nextAvailable);
			refreshStrategy.run();
		};
		strategyLabel.setOnMouseClicked(e -> toggleStrategy.run());

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox chooseRow = new HBox(choose, spacer, strategyLabel);
		chooseRow.setAlignment(Pos.CENTER_LEFT);
		chooseRow.setPadding(new Insets(20, 0, 0, 0));

		VBox content = new VBox(titleRow, rows, chooseRow);
		content.setPadding(new Insets(36, 48, 36, 48));
		content.setMaxWidth(720);

		StackPane centered = new StackPane(content);
		styleRefreshers.add(() -> centered.setStyle("-fx-background-color: " + toCss(this.scheme.background()) + ";"));
		StackPane.setAlignment(content, Pos.TOP_CENTER);

		ScrollPane scroll = new ScrollPane(centered);
		scroll.setFitToWidth(true);
		scroll.setFitToHeight(true);
		styleRefreshers.add(() -> scroll.setStyle(
				"-fx-background: " + toCss(this.scheme.background()) + ";" + "-fx-background-color: " + toCss(
						this.scheme.background()) + ";"));

		BorderPane body = new BorderPane(scroll);
		styleRefreshers.add(() -> body.setStyle("-fx-background-color: " + toCss(this.scheme.background()) + ";"));

		helpOverlay = buildHelpOverlay();
		helpOverlay.setVisible(false);
		helpOverlay.setManaged(false);
		aboutOverlay = buildAboutOverlay();
		aboutOverlay.setVisible(false);
		aboutOverlay.setManaged(false);
		licenseOverlay = buildLicenseOverlay();
		licenseOverlay.setVisible(false);
		licenseOverlay.setManaged(false);

		// Stack order: license on top of about so opening license over an open about reads
		// as a layer and Esc closes the topmost.
		root = new StackPane(body, helpOverlay, aboutOverlay, licenseOverlay);
		styleRefreshers.add(() -> root.setStyle("-fx-background-color: " + toCss(this.scheme.background()) + ";"));

		// Apply initial styles now that every refresher is registered.
		restyle();

		// Mouse-only parity with the keyboard shortcuts above. Disk-specific actions (Re-scan,
		// Toggle Visualization) don't appear because there's no scan running yet in the picker.
		installContextMenu();

		// Esc toggles the help overlay; U toggles size units; S cycles the scan-strategy
		// preference (AUTO → BULK → MFT → PARALLEL → SEQUENTIAL, skipping platform-unavailable
		// strategies). T flips the theme (dark / light). Filter at root so shortcuts fire
		// regardless of which descendant currently has focus (button, scroll viewport, etc).
		// The same dispatch is also exposed via dispatchTopLevelKey so MainWindow can route
		// shortcuts in here when focus has fled to the TabPane header.
		root.setFocusTraversable(true);
		root.addEventFilter(KeyEvent.KEY_PRESSED, this::dispatchTopLevelKey);
		root.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene != null)
				root.requestFocus();
		});

		// Kick off disk discovery only after the whole picker is built and styled, so the rows
		// that stream in land on a fully-initialised view.
		startEnumeration(rows);
	}

	/**
	 * How long a row may stay pending before the watchdog forces it to a terminal state. Rows normally settle (become
	 * clickable, or "Unavailable") as their resolve + storage-type probe finish, and the marker hides the moment the
	 * last one does; this is purely a backstop for genuinely-stuck media (a dead SD card, a stalled reader) whose
	 * {@link Volume#resolve} or storage probe can block indefinitely. Generous on purpose so it never cuts off a
	 * legitimately-slow-but-working disk — a disk hung this long is dead, and the picker has been usable the whole
	 * time. When it fires, a still-pending row is forced terminal: resolved-with-unknown-type if its size came in,
	 * otherwise "Unavailable".
	 */
	private static final long ENUMERATION_GRACE_MS = 30_000;

	/**
	 * Builds a placeholder row for every disk up front (in root order) and resolves them off the FX thread, one virtual
	 * thread per root. A row starts dimmed, breathing, and non-clickable; it becomes clickable only once <em>both</em>
	 * its size has resolved <em>and</em> its medium has been classified — the storage type picks the optimal scanner,
	 * so a scan mustn't start without it. A root that can't be read (or that the watchdog gives up on) settles to a
	 * dimmed "Unavailable" entry rather than vanishing.
	 * <p>The {@code scanningLabel} text tracks the phase: "Looking for disks…" while any size is still resolving, then
	 * "Identifying disk types…" once they're all in and only the medium probes remain.
	 * <p>The background threads only do the blocking work and marshal results back via {@link Platform#runLater};
	 * every counter and row mutation happens on the FX thread, so {@link #pendingRows} / {@link #resolvesRemaining}
	 * need no synchronization.
	 */
	private void startEnumeration(VBox rows) {
		List<Path> roots = Volume.rootDirectories();
		if (roots.isEmpty()) {
			hideProgress();
			return;
		}
		progressPulse.playFromStart();
		pendingRows = roots.size();
		resolvesRemaining = roots.size();

		// Placeholder rows for every disk, in root order, all on screen immediately. They breathe
		// (opacity bound to placeholderOpacity) and aren't clickable until they resolve + classify.
		diskRows = new ArrayList<>(roots.size());
		for (Path root : roots) {
			DiskRow row = new DiskRow(root);
			diskRows.add(row);
			rows.getChildren().add(row.node);
		}
		// Style the freshly-built placeholder rows against the active scheme (their refreshers were
		// just registered, after the constructor's own restyle()).
		restyle();

		for (DiskRow row : diskRows) {
			Thread.ofVirtual().name("picker-resolve-" + row.root).start(() -> {
				Volume v = Volume.resolve(row.root); // may block for a long time on dead media
				Platform.runLater(() -> {
					if (disposed)
						return; // tab swapped/closed while we were resolving — drop the result
					if (--resolvesRemaining == 0)
						scanningLabel.setText("Identifying disk types…");
					if (v == null) {
						onUnavailable(row);
						return;
					}
					row.applyResolved(v);   // size + bar appear; row stays breathing + non-clickable
					fillStorageTag(v, row); // classify the medium, then mark the row ready/clickable
				});
			});
		}

		// Backstop: force any row still pending after the grace period to a terminal state, so the
		// list can't breathe forever on stuck media.
		Thread.ofVirtual().name("picker-enum-watchdog").start(() -> {
			try {
				Thread.sleep(ENUMERATION_GRACE_MS);
			} catch (InterruptedException e) {
				return;
			}
			Platform.runLater(() -> {
				if (disposed)
					return;
				for (DiskRow row : diskRows) {
					if (row.resolved) {
						if (row.markReady(StorageProfile.UNKNOWN))
							rowSettled();
					} else {
						onUnavailable(row);
					}
				}
			});
		});
	}

	/**
	 * Classifies the medium behind {@code v} on a virtual thread (the probe can block), then marks {@code row} ready on
	 * the FX thread — filling in the storage-type tag and finally enabling the click that starts a scan. An
	 * {@link StorageProfile#UNKNOWN} result still readies the row (the scanner falls back to its default); it just
	 * shows no tag.
	 */
	private void fillStorageTag(Volume v, DiskRow row) {
		Thread.ofVirtual().name("picker-profile-" + v.root()).start(() -> {
			StorageProfile probed;
			try {
				probed = Volume.probeStorageProfile(v);
			} catch (RuntimeException e) {
				probed = StorageProfile.UNKNOWN;
			}
			StorageProfile profile = probed == null ? StorageProfile.UNKNOWN : probed;
			Platform.runLater(() -> {
				if (disposed)
					return;
				if (row.markReady(profile))
					rowSettled();
			});
		});
	}

	/**
	 * Settles {@code row} as unavailable: a dimmed, non-clickable "Unavailable" entry, hidden right away when
	 * {@link #hideUnavailableDisks} is on. The node stays in the list either way (just {@code managed=false} when
	 * hidden), so the {@code H} toggle can reveal it later without losing its place. Counts as settled exactly once —
	 * the {@code markUnavailable} guard makes a late watchdog/resolve pass a no-op.
	 */
	private void onUnavailable(DiskRow row) {
		if (!row.markUnavailable())
			return; // already terminal
		applyUnavailableVisibility(row);
		rowSettled();
	}

	/**
	 * Show or hide one settled-unavailable row per {@link #hideUnavailableDisks}; the node stays in the list either
	 * way.
	 */
	private void applyUnavailableVisibility(DiskRow row) {
		row.node.setManaged(!hideUnavailableDisks);
		row.node.setVisible(!hideUnavailableDisks);
	}

	/** Flip whether settled-unavailable disks are shown. Session-only (doesn't rewrite the saved preference). */
	private void toggleHideUnavailable() {
		hideUnavailableDisks = !hideUnavailableDisks;
		for (DiskRow row : diskRows) {
			if (row.isUnavailable())
				applyUnavailableVisibility(row);
		}
	}

	/** One row reached a terminal state (ready or unavailable); hide the marker once they all have. FX thread only. */
	private void rowSettled() {
		if (--pendingRows == 0)
			hideProgress();
	}

	private void hideProgress() {
		progressPulse.stop();
		progressRow.setOpacity(1.0); // undo whatever mid-pulse opacity we stopped on
		progressRow.setVisible(false);
		progressRow.setManaged(false);
	}

	/**
	 * One row in the picker. Created as a dimmed, breathing, non-clickable placeholder showing just the disk's root;
	 * {@link #applyResolved} fills in name / size / capacity bar (still not clickable), and {@link #markReady} — called
	 * once the medium is classified — stops the breathing, snaps to full opacity, shows the type tag, and enables the
	 * click that starts a scan. {@link #markUnavailable} is the terminal alternative for a disk that can't be read.
	 * <p>All methods run on the FX thread. {@link #terminal} makes the ready / unavailable decision first-wins, so a
	 * slow resolve or probe landing after the watchdog already settled the row is harmlessly ignored.
	 */
	private final class DiskRow {
		private final Path root;
		private final VBox node;
		private final Label nameLabel;
		private final Label pathLabel;
		private final Label sizeLabel;
		private final Label tagLabel;
		private final Rectangle barTrack = new Rectangle();
		private final Rectangle barFill = new Rectangle();
		private final DoubleProperty barFraction = new SimpleDoubleProperty(0);
		private final String[] baseStyle = new String[1];
		private final String[] hoverStyle = new String[1];

		private Volume current;   // null until applyResolved
		private boolean resolved; // size known (applyResolved ran)
		private boolean terminal; // ready or unavailable — first wins

		DiskRow(Path root) {
			this.root = root;
			String rootText = root.toString();

			nameLabel = new Label(rootText);
			styleRefreshers.add(() -> nameLabel.setStyle("-fx-text-fill: " + toCss(
					scheme.textPrimary()) + ";" + "-fx-font-size: 15px; -fx-font-weight: 600;"));

			pathLabel = new Label(rootText);
			styleRefreshers.add(() -> pathLabel.setStyle(
					"-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 11px;"));

			// Size slot starts as a placeholder ellipsis; applyResolved swaps in the real figure.
			sizeLabel = new Label("…");
			styleRefreshers.add(() -> sizeLabel.setStyle(
					"-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 12px;"));
			// Pin min width to preferred so the growable bar shrinks instead of clipping this label.
			sizeLabel.setMinWidth(Region.USE_PREF_SIZE);

			// Type slot likewise starts as a placeholder ellipsis; markReady / markUnavailable replace it.
			tagLabel = new Label("⋯");
			styleRefreshers.add(() -> tagLabel.setStyle("-fx-text-fill: " + toCss(
					scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-font-weight: 600;"));
			tagLabel.setMinWidth(Region.USE_PREF_SIZE);

			VBox sizeBlock = new VBox(2, tagLabel, sizeLabel);
			sizeBlock.setAlignment(Pos.CENTER_RIGHT);

			double height = 10;
			for (Rectangle r : new Rectangle[] {barTrack, barFill}) {
				r.setHeight(height);
				r.setArcWidth(height);
				r.setArcHeight(height);
			}
			StackPane barStack = new StackPane(barTrack, barFill);
			StackPane.setAlignment(barTrack, Pos.CENTER_LEFT);
			StackPane.setAlignment(barFill, Pos.CENTER_LEFT);
			barStack.setMinHeight(height);
			barStack.setPrefHeight(height);
			// Pin the bar's min width to 0. Otherwise the StackPane computes its min width from its
			// Rectangle children — whose widths are bound right back to the StackPane's width — a
			// feedback loop. It's harmless while the row's min width stays under the content's
			// maxWidth, but a wide tag (e.g. "Unavailable") can push it over, at which point the loop
			// runs away and the cards grow wider every layout pass, eating the centred margins. Hgrow
			// still stretches the bar to fill the leftover width.
			barStack.setMinWidth(0);
			barTrack.widthProperty().bind(barStack.widthProperty());
			barFill.widthProperty().bind(barStack.widthProperty().multiply(barFraction));
			styleRefreshers.add(() -> {
				barTrack.setFill(scheme.capacityTrack());
				barFill.setFill(scheme.capacityFillFor(barFraction.get()));
			});
			HBox.setHgrow(barStack, Priority.ALWAYS);
			HBox barRow = new HBox(12, barStack, sizeBlock);
			barRow.setAlignment(Pos.CENTER_LEFT);

			node = new VBox(4, nameLabel, pathLabel, barRow);
			node.setPadding(new Insets(14, 16, 14, 16));
			// Dimmed + breathing until the row is settled: bound to the shared placeholderOpacity pulse.
			node.opacityProperty().bind(placeholderOpacity);

			styleRefreshers.add(this::refreshBoxStyle);
			node.setOnMouseEntered(e -> {
				if (clickable())
					node.setStyle(hoverStyle[0]);
			});
			node.setOnMouseExited(e -> {
				if (clickable())
					node.setStyle(baseStyle[0]);
			});
			node.setOnMouseClicked(e -> {
				if (clickable())
					onSelection.accept(current);
			});

			Tooltip tip = new Tooltip();
			tip.setShowDelay(Duration.millis(300));
			tip.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
			tip.setOnShowing(e -> tip.setText(tooltipText()));
			Tooltip.install(node, tip);
		}

		/** Ready (resolved + classified) rows are the only ones the user can pick. */
		private boolean clickable() {
			return terminal && resolved && current != null;
		}

		/** Settled, but unreadable — the "Unavailable" terminal state (as opposed to a ready or still-pending row). */
		private boolean isUnavailable() {
			return terminal && !resolved;
		}

		private void refreshBoxStyle() {
			String base = "-fx-background-color: " + toCss(scheme.surface()) + ";" + "-fx-background-radius: 10;";
			if (clickable())
				base += "-fx-cursor: hand;"; // hand only on the rows you can actually pick
			baseStyle[0] = base;
			hoverStyle[0] = base + "-fx-effect: dropshadow(gaussian, " + toCss(scheme.accent()) + ", 12, 0.15, 0, 0);";
			node.setStyle(baseStyle[0]);
		}

		/**
		 * Fills in the resolved name / size / capacity bar. The row stays dimmed + non-clickable until
		 * {@link #markReady}.
		 */
		void applyResolved(Volume v) {
			if (terminal)
				return;
			current = v;
			resolved = true;
			nameLabel.setText(v.displayName());
			pathLabel.setText(v.root().toString());
			sizeLabel.setText(humanSize(v.totalBytes()));
			// Now that this row has a size, let the U-key unit toggle reformat it too.
			sizeRefreshers.add(() -> sizeLabel.setText(humanSize(v.totalBytes())));
			barFraction.set(v.usedFraction());
			barFill.setFill(scheme.capacityFillFor(v.usedFraction()));
		}

		/**
		 * Terminal: the medium is classified (possibly {@link StorageProfile#UNKNOWN}), so finalize the row — stop the
		 * breathing, snap to full opacity, show the type tag (hidden for UNKNOWN), and enable the click. Returns
		 * {@code true} if this call settled the row, {@code false} if it was already terminal. Valid only once
		 * resolved.
		 */
		boolean markReady(StorageProfile profile) {
			if (terminal || !resolved)
				return false;
			terminal = true;
			current = current.withStorageProfile(profile);
			node.opacityProperty().unbind();
			node.setOpacity(1.0);
			applyTagText(tagLabel, profile);
			refreshBoxStyle(); // repaint with the hand cursor now that it's clickable
			return true;
		}

		/**
		 * Terminal alternative for a disk we couldn't read (pseudo-fs, permission denied, or hung past the watchdog): a
		 * statically dimmed, non-clickable "Unavailable" entry. Returns {@code true} if this call settled the row.
		 */
		boolean markUnavailable() {
			if (terminal)
				return false;
			terminal = true;
			resolved = false;
			node.opacityProperty().unbind();
			node.setOpacity(0.5);
			sizeLabel.setText("");
			tagLabel.setText("Unavailable");
			tagLabel.setVisible(true);
			tagLabel.setManaged(true);
			return true;
		}

		private String tooltipText() {
			if (resolved && current != null)
				return buildVolumeTooltip(current);
			if (terminal)
				return "Unavailable — could not read " + root;
			return "Reading " + root + " …";
		}
	}

	public Region getRoot() {
		return root;
	}

	/**
	 * Releases the picker's background work when its tab is swapped to a disk view or closed (see
	 * {@code MainWindow}). The resolve/probe virtual threads can't be interrupted mid-syscall, but this stops the
	 * progress pulse immediately and flips {@link #disposed} so any results they post afterwards are dropped — so an
	 * abandoned enumeration doesn't keep animating or compete for CPU with a freshly-started scan. Idempotent; must be
	 * called on the FX thread.
	 */
	public void dispose() {
		disposed = true;
		progressPulse.stop();
	}

	/**
	 * Live theme handoff. Called by {@link MainWindow#applyTheme} after the {@link Theme} listener fires. Replays every
	 * registered inline-style block against the new scheme, then refreshes capacity-bar fills (which are bound to
	 * {@code scheme.capacity*}) — those aren't styled via CSS so they need an explicit re-fill.
	 */
	public void applyTheme(ColorScheme newScheme) {
		this.scheme = newScheme;
		restyle();
	}

	private void restyle() {
		for (Runnable r : styleRefreshers)
			r.run();
	}

	/**
	 * Top-level command dispatch. Shared between this view's own root filter and MainWindow's BorderPane handler so
	 * Esc/U keep working when focus has drifted to the TabPane header (e.g. just after closing the last DiskView tab
	 * and the auto-created picker is selected). While the help overlay is visible we swallow other keys so they don't
	 * trigger silently behind it.
	 */
	public void dispatchTopLevelKey(KeyEvent e) {
		// Esc always closes the topmost overlay (license > about > help), or opens help
		// when none are visible.
		if (e.getCode() == KeyCode.ESCAPE) {
			if (licenseOverlay.isVisible()) {
				toggleLicense();
			} else if (aboutOverlay.isVisible()) {
				toggleAbout();
			} else {
				toggleHelp();
			}
			e.consume();
			return;
		}
		// Modifier-held keys (Cmd-Q, etc.) belong to native handlers — get out of the way.
		if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown())
			return;
		// Q quits unconditionally — must work even when overlays are up. Route through
		// App.requestQuit so scanners are cancelled before the toolkit tears down.
		if (e.getCode() == KeyCode.Q) {
			se.hirt.diskspace.App.requestQuit();
			e.consume();
			return;
		}
		// A toggles the About overlay from anywhere. Handled before the overlay-swallow
		// below so users can open About while help is on screen.
		if (e.getCode() == KeyCode.A) {
			toggleAbout();
			e.consume();
			return;
		}
		// L toggles the license overlay from anywhere — including over the About card,
		// which advertises "Press L for license". Handled before the overlay-swallow
		// below so it works while other overlays are visible.
		if (e.getCode() == KeyCode.L) {
			toggleLicense();
			e.consume();
			return;
		}
		if (helpOverlay.isVisible() || aboutOverlay.isVisible() || licenseOverlay.isVisible()) {
			e.consume();
			return;
		}
		if (e.getCode() == KeyCode.U) {
			SizeFormat.toggle();
			for (Runnable r : sizeRefreshers)
				r.run();
			e.consume();
		} else if (e.getCode() == KeyCode.S) {
			toggleStrategy.run();
			e.consume();
		} else if (e.getCode() == KeyCode.T) {
			Theme.toggle();
			e.consume();
		} else if (e.getCode() == KeyCode.H) {
			toggleHideUnavailable();
			e.consume();
		}
	}

	/**
	 * Right-click anywhere in the picker offers the keyboard-shortcut actions plus Preferences and Quit. Disk-specific
	 * things (Re-scan, Toggle Visualization) intentionally don't appear — they need a scan target that doesn't exist
	 * yet at this stage.
	 */
	private void installContextMenu() {
		MenuItem helpItem = new MenuItem("Show Keyboard Shortcuts");
		helpItem.setOnAction(e -> toggleHelp());

		MenuItem toggleUnitsItem = new MenuItem("Toggle Size Units");
		toggleUnitsItem.setOnAction(e -> {
			SizeFormat.toggle();
			for (Runnable r : sizeRefreshers)
				r.run();
		});

		MenuItem cycleStrategyItem = new MenuItem("Cycle Scan Strategy");
		cycleStrategyItem.setOnAction(e -> toggleStrategy.run());

		MenuItem toggleUnavailableItem = new MenuItem("Show / Hide Unavailable Disks");
		toggleUnavailableItem.setOnAction(e -> toggleHideUnavailable());

		MenuItem toggleThemeItem = new MenuItem("Toggle Theme");
		toggleThemeItem.setOnAction(e -> Theme.toggle());

		MenuItem preferencesItem = new MenuItem("Preferences…");
		preferencesItem.setOnAction(e -> PreferencesDialog.show());

		MenuItem aboutItem = new MenuItem("About DiskSpace…");
		aboutItem.setOnAction(e -> toggleAbout());

		MenuItem quitItem = new MenuItem("Quit");
		quitItem.setOnAction(e -> se.hirt.diskspace.App.requestQuit());

		ContextMenu menu = new ContextMenu();
		menu.getItems().addAll(helpItem, toggleUnitsItem, cycleStrategyItem, toggleUnavailableItem, toggleThemeItem,
				new SeparatorMenuItem(), preferencesItem, aboutItem, new SeparatorMenuItem(), quitItem);

		root.setOnContextMenuRequested(e -> {
			menu.show(root, e.getScreenX(), e.getScreenY());
			e.consume();
		});
	}

	private void toggleHelp() {
		boolean show = !helpOverlay.isVisible();
		helpOverlay.setVisible(show);
		helpOverlay.setManaged(show);
		if (!show)
			root.requestFocus();
	}

	private void toggleAbout() {
		boolean show = !aboutOverlay.isVisible();
		aboutOverlay.setVisible(show);
		aboutOverlay.setManaged(show);
		if (!show)
			root.requestFocus();
	}

	private void toggleLicense() {
		boolean show = !licenseOverlay.isVisible();
		licenseOverlay.setVisible(show);
		licenseOverlay.setManaged(show);
		if (!show)
			root.requestFocus();
	}

	private StackPane buildHelpOverlay() {
		GridPane grid = new GridPane();
		grid.setHgap(20);
		grid.setVgap(8);

		int row = 0;
		addHelpRow(grid, row++, "Esc", "Show / hide this help");
		addHelpRow(grid, row++, "U", "Toggle size units (GB / GiB)");
		addHelpRow(grid, row++, "S", "Cycle scan strategy (Auto / Bulk / MFT / Parallel / Sequential)");
		addHelpRow(grid, row++, "H", "Show / hide unavailable disks");
		addHelpRow(grid, row++, "T", "Toggle theme (dark / light)");
		addHelpRow(grid, row++, "A", "Show About");
		addHelpRow(grid, row++, "L", "Show license");
		addHelpRow(grid, row++, "Q", "Quit DiskSpace");

		Label sub = new Label("After picking a disk");
		styleRefreshers.add(() -> sub.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 12px; -fx-font-weight: 600;" + "-fx-padding: 12 0 2 0;"));
		grid.add(sub, 0, row++, 2, 1);

		addHelpRow(grid, row++, "←  ↑", "Go up one level");
		addHelpRow(grid, row++, "→  ↓", "Go forward (replay an up step)");
		addHelpRow(grid, row++, "E  F", "Open in system file explorer");
		addHelpRow(grid, row++, "Del", "Stage / unstage selection for deletion");
		addHelpRow(grid, row++, "R", "Re-scan the current disk");
		addHelpRow(grid, row++, "V", "Toggle visualization (sunburst / heatmap)");
		addHelpRow(grid, row++, "C", "Cycle coloring mode");
		addHelpRow(grid, row++, "H", "Hide / show free space");

		Label title = new Label("Keyboard Shortcuts");
		styleRefreshers.add(() -> title.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-size: 18px; -fx-font-weight: 600;" + "-fx-padding: 0 0 14 0;"));

		Label hint = new Label("Press Esc to close");
		styleRefreshers.add(() -> hint.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 14 0 0 0;"));

		VBox card = new VBox(title, grid, hint);
		card.setAlignment(Pos.TOP_LEFT);
		card.setPadding(new Insets(24, 28, 20, 28));
		card.setMaxWidth(460);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		styleRefreshers.add(() -> card.setStyle("-fx-background-color: " + toCss(
				this.scheme.surface()) + ";" + "-fx-background-radius: 12;" + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0.25, 0, 4);"));

		StackPane overlay = new StackPane(card);
		styleRefreshers.add(() -> overlay.setStyle("-fx-background-color: " + toCss(this.scheme.overlayScrim()) + ";"));
		// Backdrop click dismisses; card clicks report the card (or a descendant) as target,
		// so this check naturally ignores them.
		overlay.setOnMouseClicked(e -> {
			if (e.getTarget() == overlay)
				toggleHelp();
		});
		return overlay;
	}

	private StackPane buildAboutOverlay() {
		ImageView icon = new ImageView();
		var iconUrl = se.hirt.diskspace.App.class.getResource("/se/hirt/diskspace/icon-128.png");
		if (iconUrl != null) {
			icon.setImage(new Image(iconUrl.toExternalForm()));
			icon.setFitWidth(96);
			icon.setFitHeight(96);
			icon.setPreserveRatio(true);
			icon.setSmooth(true);
		}

		Label appName = new Label(se.hirt.diskspace.AppInfo.NAME + "  " + se.hirt.diskspace.AppInfo.version());
		styleRefreshers.add(() -> appName.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-size: 22px; -fx-font-weight: 600;"));

		Label tagline = new Label(se.hirt.diskspace.AppInfo.TAGLINE);
		styleRefreshers.add(() -> tagline.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 12px; -fx-padding: 2 0 0 0;"));

		VBox header = new VBox(2, appName, tagline);
		header.setAlignment(Pos.CENTER_LEFT);

		HBox topRow = new HBox(18, icon, header);
		topRow.setAlignment(Pos.CENTER_LEFT);

		Label copyright = new Label(se.hirt.diskspace.AppInfo.COPYRIGHT);
		styleRefreshers.add(() -> copyright.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-size: 12px; -fx-padding: 20 0 0 0;"));

		Hyperlink github = new Hyperlink(se.hirt.diskspace.AppInfo.GITHUB_URL);
		github.setOnAction(e -> {
			var hs = se.hirt.diskspace.App.hostServices();
			if (hs != null)
				hs.showDocument(se.hirt.diskspace.AppInfo.GITHUB_URL);
		});
		styleRefreshers.add(() -> github.setStyle("-fx-text-fill: " + toCss(
				this.scheme.accent()) + ";" + "-fx-font-size: 12px; -fx-padding: 2 0 0 -4;" + "-fx-border-color: transparent; -fx-underline: true;"));

		Label runtime = new Label(se.hirt.diskspace.AppInfo.runtimeDescription());
		styleRefreshers.add(() -> runtime.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 14 0 0 0;"));

		Label hint = new Label("Press L for license  ·  Esc to close");
		styleRefreshers.add(() -> hint.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 18 0 0 0;"));

		VBox card = new VBox(topRow, copyright, github, runtime, hint);
		card.setAlignment(Pos.TOP_LEFT);
		card.setPadding(new Insets(24, 28, 20, 28));
		card.setMaxWidth(500);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		styleRefreshers.add(() -> card.setStyle("-fx-background-color: " + toCss(
				this.scheme.surface()) + ";" + "-fx-background-radius: 12;" + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0.25, 0, 4);"));

		StackPane overlay = new StackPane(card);
		styleRefreshers.add(() -> overlay.setStyle("-fx-background-color: " + toCss(this.scheme.overlayScrim()) + ";"));
		overlay.setOnMouseClicked(e -> {
			if (e.getTarget() == overlay)
				toggleAbout();
		});
		return overlay;
	}

	private StackPane buildLicenseOverlay() {
		Label title = new Label("License");
		styleRefreshers.add(() -> title.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-size: 18px; -fx-font-weight: 600; -fx-padding: 0 0 14 0;"));

		Label body = new Label(se.hirt.diskspace.AppInfo.licenseText());
		body.setWrapText(false);
		styleRefreshers.add(() -> body.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textPrimary()) + ";" + "-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;" + "-fx-font-size: 11px;"));

		ScrollPane scroll = new ScrollPane(body);
		scroll.setFitToWidth(true);
		scroll.setPrefViewportHeight(360);
		scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

		Label hint = new Label("Press Esc to close");
		styleRefreshers.add(() -> hint.setStyle("-fx-text-fill: " + toCss(
				this.scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 14 0 0 0;"));

		VBox card = new VBox(title, scroll, hint);
		card.setAlignment(Pos.TOP_LEFT);
		card.setPadding(new Insets(24, 28, 20, 28));
		card.setMaxWidth(640);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		styleRefreshers.add(() -> card.setStyle("-fx-background-color: " + toCss(
				this.scheme.surface()) + ";" + "-fx-background-radius: 12;" + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0.25, 0, 4);"));

		StackPane overlay = new StackPane(card);
		styleRefreshers.add(() -> overlay.setStyle("-fx-background-color: " + toCss(this.scheme.overlayScrim()) + ";"));
		overlay.setOnMouseClicked(e -> {
			if (e.getTarget() == overlay)
				toggleLicense();
		});
		return overlay;
	}

	private void addHelpRow(GridPane grid, int row, String key, String desc) {
		Label k = new Label(key);
		styleRefreshers.add(() -> k.setStyle("-fx-text-fill: " + toCss(
				this.scheme.accent()) + ";" + "-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;" + "-fx-font-size: 13px; -fx-font-weight: 600;"));
		k.setMinWidth(64);
		Label d = new Label(desc);
		styleRefreshers.add(
				() -> d.setStyle("-fx-text-fill: " + toCss(this.scheme.textPrimary()) + ";" + "-fx-font-size: 13px;"));
		grid.add(k, 0, row);
		grid.add(d, 1, row);
	}

	/** Sets the storage-type tag's text and toggles its visibility — {@link StorageProfile#UNKNOWN} hides it entirely. */
	private static void applyTagText(Label tag, StorageProfile profile) {
		String text = profile == null ? "" : profile.shortLabel();
		tag.setText(text);
		boolean show = !text.isEmpty();
		tag.setVisible(show);
		tag.setManaged(show);
	}

	/** Width keys are padded to in the top key/value block. Longest = "File system". */
	private static final int TOOLTIP_KEY_WIDTH = 11;
	/**
	 * Word-wrap target for value text in the tooltip. Tuned for the monospace 12px font so the rendered width stays
	 * comfortably narrow without orphaning short trailing words on their own line.
	 */
	private static final int TOOLTIP_WRAP_WIDTH = 50;

	private static String buildVolumeTooltip(Volume v) {
		StringBuilder sb = new StringBuilder();
		sb.append(v.displayName()).append('\n');
		sb.append(v.root()).append("\n\n");

		String fs = v.fsType();
		String storage = v.storageProfile() == null || v.storageProfile().shortLabel().isEmpty() ? "Unknown"
				: v.storageProfile().shortLabel();
		appendKeyValue(sb, "File system", fs == null || fs.isBlank() ? "—" : fs);
		appendKeyValue(sb, "Storage", storage);
		// Resolved strategy + description from Scanner — reflects the current preference
		// (AUTO vs PARALLEL) and per-volume capability (e.g. NTFS+Windows+admin → MFT).
		appendKeyValue(sb, "Scan", Scanner.strategyLabelFor(v));
		appendIndentedWrap(sb, Scanner.strategyDescriptionFor(v));

		long total = v.totalBytes();
		long used = v.usedBytes();
		long free = v.usableBytes();
		if (total > 0) {
			sb.append('\n');
			String usedStr = humanSize(used);
			String freeStr = humanSize(free);
			String totalStr = humanSize(total);
			// Right-align all three sizes to the widest so digit columns line up regardless
			// of unit length (e.g. "543 GB" vs "1.86 TB").
			int sizeWidth = Math.max(usedStr.length(), Math.max(freeStr.length(), totalStr.length()));
			double usedPct = used * 100.0 / total;
			double freePct = free * 100.0 / total;
			String sizeFmt = "%-5s : %" + sizeWidth + "s  (%3.0f%%)%n";
			sb.append(String.format(sizeFmt, "Used", usedStr, usedPct));
			sb.append(String.format(sizeFmt, "Free", freeStr, freePct));
			sb.append(String.format("%-5s : %" + sizeWidth + "s%n", "Total", totalStr));
		}

		return sb.toString();
	}

	private static String buildStrategyTooltip() {
		ScanStrategy s = Scanner.PREFERENCE.get();
		StringBuilder sb = new StringBuilder();
		sb.append("Scan strategy: ").append(s.label()).append("\n\n");
		switch (s) {
		case AUTO -> sb.append("Pick the fastest available scanner per disk.\n")
				.append("NTFS on Windows with admin uses the MFT scanner;\n")
				.append("local volumes on macOS use the bulk scanner; everything\n")
				.append("else falls back to parallel walking sized to the profile.");
		case BULK -> sb.append("Force the macOS bulk scanner (getattrlistbulk).\n")
				.append("Falls back to parallel walking on non-Mac builds or\n")
				.append("network volumes where per-syscall amortisation loses.");
		case MFT -> sb.append("Force the MFT scanner. Falls back to parallel walking\n")
				.append("when the volume isn't NTFS or the process isn't elevated.");
		case PARALLEL -> sb.append("Always use the parallel directory-walking scanner with\n")
				.append("per-profile pool size (HDD=1, SSD=8, network=16). Skips\n")
				.append("MFT / Bulk even when available — useful for A/B comparison.");
		case SEQUENTIAL -> sb.append("Force single-threaded directory walking. Mostly a\n")
				.append("debug knob for measuring the speedup parallelism gives us.");
		}
		sb.append("\n\nClick or press S to cycle through the strategies available on this platform.");
		return sb.toString();
	}

	private static void appendKeyValue(StringBuilder sb, String key, String value) {
		sb.append(String.format("%-" + TOOLTIP_KEY_WIDTH + "s : %s%n", key, value));
	}

	/**
	 * Word-wraps {@code value} to {@link #TOOLTIP_WRAP_WIDTH}, indenting continuation lines to align under the first
	 * value character (i.e. {@code keyWidth + " : ".length}).
	 */
	private static void appendWrappedKeyValue(StringBuilder sb, String key, String value) {
		List<String> lines = wordWrap(value, TOOLTIP_WRAP_WIDTH);
		if (lines.isEmpty()) {
			appendKeyValue(sb, key, "");
			return;
		}
		sb.append(String.format("%-" + TOOLTIP_KEY_WIDTH + "s : %s%n", key, lines.get(0)));
		String indent = " ".repeat(TOOLTIP_KEY_WIDTH + 3); // " : " = 3 chars
		for (int i = 1; i < lines.size(); i++) {
			sb.append(indent).append(lines.get(i)).append('\n');
		}
	}

	/**
	 * Word-wraps {@code value} and emits each line indented to the value column — used to attach a wrapped continuation
	 * paragraph to a previously-emitted key/value line.
	 */
	private static void appendIndentedWrap(StringBuilder sb, String value) {
		String indent = " ".repeat(TOOLTIP_KEY_WIDTH + 3);
		for (String line : wordWrap(value, TOOLTIP_WRAP_WIDTH)) {
			sb.append(indent).append(line).append('\n');
		}
	}

	/**
	 * Greedy word-wrap. Long single tokens (e.g. URLs) overflow the target width rather than getting hyphenated — this
	 * is fine for our short, prose-only descriptions.
	 */
	private static List<String> wordWrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			if (current.length() == 0) {
				current.append(word);
			} else if (current.length() + 1 + word.length() <= width) {
				current.append(' ').append(word);
			} else {
				lines.add(current.toString());
				current.setLength(0);
				current.append(word);
			}
		}
		if (current.length() > 0)
			lines.add(current.toString());
		return lines;
	}

	private static String humanSize(long bytes) {
		return SizeFormat.format(bytes);
	}

	private static String toCss(Color c) {
		return String.format("rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
				(int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
	}

}
