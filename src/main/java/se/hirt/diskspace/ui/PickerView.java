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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.ScanStrategy;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PickerView {

	private final StackPane root;
	private final ColorScheme scheme;
	private final Consumer<Volume> onSelection;
	private final List<Runnable> sizeRefreshers = new ArrayList<>();
	private final StackPane helpOverlay;
	/**
	 * Lifted to an instance field so {@link #dispatchTopLevelKey} can fire it on S. Constructed during {@link #PickerView} setup; never
	 * null after the constructor returns.
	 */
	private Runnable toggleStrategy;

	public PickerView(ColorScheme scheme, Consumer<Volume> onSelection) {
		this.scheme = scheme;
		this.onSelection = onSelection;

		Label title = new Label("Choose a disk");
		title.setStyle("-fx-text-fill: " + toCss(
				scheme.textPrimary()) + ";" + "-fx-font-size: 22px; -fx-font-weight: 600;" + "-fx-padding: 0 0 18 0;");

		VBox rows = new VBox(12);
		for (Volume v : Volume.enumerate()) {
			rows.getChildren().add(buildRow(v));
		}

		Button choose = new Button("Choose folder…");
		choose.setStyle(
				"-fx-background-color: transparent;" + "-fx-text-fill: " + toCss(scheme.accent()) + ";" + "-fx-border-color: " + toCss(
						scheme.accent()) + ";" + "-fx-border-radius: 6; -fx-background-radius: 6;" + "-fx-padding: 8 14 8 14; -fx-cursor: hand;");
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
		// to cycle through AUTO → MFT → PARALLEL → SEQUENTIAL. Tooltip explains the choice;
		// row tooltips regenerate on hover so per-disk strategy text picks up the change
		// automatically.
		Label strategyLabel = new Label();
		strategyLabel.setStyle(
				"-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 0 0 0 12;");
		Runnable refreshStrategy = () -> strategyLabel.setText("Scan: " + Scanner.PREFERENCE.get().label() + "  ·  click or S to cycle");
		refreshStrategy.run();
		Tooltip strategyTip = new Tooltip();
		strategyTip.setShowDelay(Duration.millis(300));
		strategyTip.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
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

		VBox content = new VBox(title, rows, chooseRow);
		content.setPadding(new Insets(36, 48, 36, 48));
		content.setMaxWidth(720);

		StackPane centered = new StackPane(content);
		centered.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");
		StackPane.setAlignment(content, Pos.TOP_CENTER);

		ScrollPane scroll = new ScrollPane(centered);
		scroll.setFitToWidth(true);
		scroll.setFitToHeight(true);
		scroll.setStyle(
				"-fx-background: " + toCss(scheme.background()) + ";" + "-fx-background-color: " + toCss(scheme.background()) + ";");

		BorderPane body = new BorderPane(scroll);
		body.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");

		helpOverlay = buildHelpOverlay();
		helpOverlay.setVisible(false);
		helpOverlay.setManaged(false);

		root = new StackPane(body, helpOverlay);
		root.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");

		// Esc toggles the help overlay; U toggles size units; S cycles the scan-strategy
		// preference (AUTO → MFT → PARALLEL → SEQUENTIAL). Filter at root so shortcuts fire
		// regardless of which descendant currently has focus (button, scroll viewport, etc).
		// The same dispatch is also exposed via dispatchTopLevelKey so MainWindow can route
		// shortcuts in here when focus has fled to the TabPane header.
		root.setFocusTraversable(true);
		root.addEventFilter(KeyEvent.KEY_PRESSED, this::dispatchTopLevelKey);
		root.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene != null)
				root.requestFocus();
		});
	}

	public Region getRoot() {
		return root;
	}

	/**
	 * Top-level command dispatch. Shared between this view's own root filter and MainWindow's BorderPane handler so Esc/U keep working when
	 * focus has drifted to the TabPane header (e.g. just after closing the last DiskView tab and the auto-created picker is selected).
	 * While the help overlay is visible we swallow other keys so they don't trigger silently behind it.
	 */
	public void dispatchTopLevelKey(KeyEvent e) {
		if (e.getCode() == KeyCode.ESCAPE) {
			toggleHelp();
			e.consume();
			return;
		}
		// Modifier-held keys (Cmd-Q, etc.) belong to native handlers — get out of the way.
		if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown())
			return;
		// Q quits unconditionally — must work even when the help overlay is up. Route
		// through App.requestQuit so scanners are cancelled before the toolkit tears down.
		if (e.getCode() == KeyCode.Q) {
			se.hirt.diskspace.App.requestQuit();
			e.consume();
			return;
		}
		if (helpOverlay.isVisible()) {
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
		}
	}

	private void toggleHelp() {
		boolean show = !helpOverlay.isVisible();
		helpOverlay.setVisible(show);
		helpOverlay.setManaged(show);
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
		addHelpRow(grid, row++, "S", "Cycle scan strategy (Auto / MFT / Parallel / Sequential)");
		addHelpRow(grid, row++, "Q", "Quit DiskSpace");

		Label sub = new Label("After picking a disk");
		sub.setStyle("-fx-text-fill: " + toCss(
				scheme.textMuted()) + ";" + "-fx-font-size: 12px; -fx-font-weight: 600;" + "-fx-padding: 12 0 2 0;");
		grid.add(sub, 0, row++, 2, 1);

		addHelpRow(grid, row++, "←  ↑", "Go up one level");
		addHelpRow(grid, row++, "→  ↓", "Go forward (replay an up step)");
		addHelpRow(grid, row++, "E  F", "Open in system file explorer");
		addHelpRow(grid, row++, "Del", "Stage / unstage selection for deletion");
		addHelpRow(grid, row++, "R", "Re-scan the current disk");
		addHelpRow(grid, row++, "V", "Toggle visualization (sunburst / heatmap)");

		Label title = new Label("Keyboard Shortcuts");
		title.setStyle("-fx-text-fill: " + toCss(
				scheme.textPrimary()) + ";" + "-fx-font-size: 18px; -fx-font-weight: 600;" + "-fx-padding: 0 0 14 0;");

		Label hint = new Label("Press Esc to close");
		hint.setStyle("-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-padding: 14 0 0 0;");

		VBox card = new VBox(title, grid, hint);
		card.setAlignment(Pos.TOP_LEFT);
		card.setPadding(new Insets(24, 28, 20, 28));
		card.setMaxWidth(460);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		card.setStyle("-fx-background-color: " + toCss(
				scheme.surface()) + ";" + "-fx-background-radius: 12;" + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0.25, 0, 4);");

		StackPane overlay = new StackPane(card);
		overlay.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
		// Backdrop click dismisses; card clicks report the card (or a descendant) as target,
		// so this check naturally ignores them.
		overlay.setOnMouseClicked(e -> {
			if (e.getTarget() == overlay)
				toggleHelp();
		});
		return overlay;
	}

	private void addHelpRow(GridPane grid, int row, String key, String desc) {
		Label k = new Label(key);
		k.setStyle("-fx-text-fill: " + toCss(
				scheme.accent()) + ";" + "-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;" + "-fx-font-size: 13px; -fx-font-weight: 600;");
		k.setMinWidth(64);
		Label d = new Label(desc);
		d.setStyle("-fx-text-fill: " + toCss(scheme.textPrimary()) + ";" + "-fx-font-size: 13px;");
		grid.add(k, 0, row);
		grid.add(d, 1, row);
	}

	private Region buildRow(Volume v) {
		Label name = new Label(v.displayName());
		name.setStyle("-fx-text-fill: " + toCss(scheme.textPrimary()) + ";" + "-fx-font-size: 15px; -fx-font-weight: 600;");

		Label path = new Label(v.root().toString());
		path.setStyle("-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 11px;");

		Label total = new Label(humanSize(v.totalBytes()));
		total.setStyle("-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 12px;");
		// Without this, the label keeps the width it was first laid out with and clips when
		// the unit toggle widens the text (e.g. "228 GB" → "213 GiB").
		total.setMinWidth(Region.USE_PREF_SIZE);
		sizeRefreshers.add(() -> total.setText(humanSize(v.totalBytes())));

		VBox sizeBlock = new VBox(2);
		sizeBlock.setAlignment(Pos.CENTER_RIGHT);
		String tagText = v.storageProfile() == null ? "" : v.storageProfile().shortLabel();
		if (!tagText.isEmpty()) {
			Label tag = new Label(tagText);
			tag.setStyle("-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-font-size: 11px; -fx-font-weight: 600;");
			sizeBlock.getChildren().add(tag);
		}
		sizeBlock.getChildren().add(total);

		Region bar = buildCapacityBar(v.usedFraction());
		HBox.setHgrow(bar, Priority.ALWAYS);
		HBox barRow = new HBox(12, bar, sizeBlock);
		barRow.setAlignment(Pos.CENTER_LEFT);

		VBox box = new VBox(4, name, path, barRow);
		box.setPadding(new Insets(14, 16, 14, 16));
		String baseStyle = "-fx-background-color: " + toCss(scheme.surface()) + ";" + "-fx-background-radius: 10; -fx-cursor: hand;";
		box.setStyle(baseStyle);
		box.setOnMouseEntered(
				e -> box.setStyle(baseStyle + "-fx-effect: dropshadow(gaussian, " + toCss(scheme.accent()) + ", 12, 0.15, 0, 0);"));
		box.setOnMouseExited(e -> box.setStyle(baseStyle));
		box.setOnMouseClicked(e -> onSelection.accept(v));

		// Rich tooltip with FS, storage type, sizes (with %), and a per-profile description
		// of how the scan will run. Regenerated on each show so it picks up the U-key unit
		// toggle without needing a refresher hook. Monospace font so the column-style key:
		// value lines actually line up — JavaFX's default proportional font + tab characters
		// produces inconsistent column positions.
		Tooltip tip = new Tooltip();
		tip.setShowDelay(Duration.millis(300));
		tip.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
		tip.setOnShowing(e -> tip.setText(buildVolumeTooltip(v)));
		Tooltip.install(box, tip);
		return box;
	}

	/** Width keys are padded to in the top key/value block. Longest = "File system". */
	private static final int TOOLTIP_KEY_WIDTH = 11;
	/**
	 * Word-wrap target for value text in the tooltip. Tuned for the monospace 12px font so the rendered width stays comfortably narrow
	 * without orphaning short trailing words on their own line.
	 */
	private static final int TOOLTIP_WRAP_WIDTH = 50;

	private static String buildVolumeTooltip(Volume v) {
		StringBuilder sb = new StringBuilder();
		sb.append(v.displayName()).append('\n');
		sb.append(v.root()).append("\n\n");

		String fs = v.fsType();
		String storage =
				v.storageProfile() == null || v.storageProfile().shortLabel().isEmpty() ? "Unknown" : v.storageProfile().shortLabel();
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
				.append("NTFS on Windows with admin uses the MFT scanner; everything\n")
				.append("else falls back to parallel walking sized to the profile.");
		case MFT -> sb.append("Force the MFT scanner. Falls back to parallel walking\n")
				.append("when the volume isn't NTFS or the process isn't elevated.");
		case PARALLEL -> sb.append("Always use the parallel directory-walking scanner with\n")
				.append("per-profile pool size (HDD=1, SSD=8, network=16). Skips MFT\n")
				.append("even when available — useful for A/B comparison.");
		case SEQUENTIAL -> sb.append("Force single-threaded directory walking. Mostly a\n")
				.append("debug knob for measuring the speedup parallelism gives us.");
		}
		sb.append("\n\nClick or press S to cycle (Auto → MFT → Parallel → Sequential).");
		return sb.toString();
	}

	private static void appendKeyValue(StringBuilder sb, String key, String value) {
		sb.append(String.format("%-" + TOOLTIP_KEY_WIDTH + "s : %s%n", key, value));
	}

	/**
	 * Word-wraps {@code value} to {@link #TOOLTIP_WRAP_WIDTH}, indenting continuation lines to align under the first value character (i.e.
	 * {@code keyWidth + " : ".length}).
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
	 * Word-wraps {@code value} and emits each line indented to the value column — used to attach a wrapped continuation paragraph to a
	 * previously-emitted key/value line.
	 */
	private static void appendIndentedWrap(StringBuilder sb, String value) {
		String indent = " ".repeat(TOOLTIP_KEY_WIDTH + 3);
		for (String line : wordWrap(value, TOOLTIP_WRAP_WIDTH)) {
			sb.append(indent).append(line).append('\n');
		}
	}

	/**
	 * Greedy word-wrap. Long single tokens (e.g. URLs) overflow the target width rather than getting hyphenated — this is fine for our
	 * short, prose-only descriptions.
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

	private Region buildCapacityBar(double fraction) {
		double f = Math.max(0.0, Math.min(1.0, fraction));
		double height = 10;

		Rectangle track = new Rectangle();
		track.setHeight(height);
		track.setArcWidth(height);
		track.setArcHeight(height);
		track.setFill(scheme.capacityTrack());

		Rectangle fill = new Rectangle();
		fill.setHeight(height);
		fill.setArcWidth(height);
		fill.setArcHeight(height);
		fill.setFill(scheme.capacityFillFor(f));

		StackPane stack = new StackPane(track, fill);
		StackPane.setAlignment(track, Pos.CENTER_LEFT);
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		stack.setMinHeight(height);
		stack.setPrefHeight(height);

		track.widthProperty().bind(stack.widthProperty());
		fill.widthProperty().bind(stack.widthProperty().multiply(f));
		return stack;
	}

	private static String humanSize(long bytes) {
		return SizeFormat.format(bytes);
	}

	private static String toCss(Color c) {
		return String.format("rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
				(int) Math.round(c.getBlue() * 255), c.getOpacity());
	}

}
