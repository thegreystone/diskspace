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
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PickerView {

	private final BorderPane root;
	private final ColorScheme scheme;
	private final Consumer<Volume> onSelection;
	private final List<Runnable> sizeRefreshers = new ArrayList<>();

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
		HBox chooseRow = new HBox(choose);
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

		root = new BorderPane(scroll);
		root.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");

		// U toggles size units app-wide. Filter at root so it fires regardless of which
		// descendant currently has focus (button, scroll viewport, etc).
		root.setFocusTraversable(true);
		root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
			if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown())
				return;
			if (e.getCode() == KeyCode.U) {
				SizeFormat.toggle();
				for (Runnable r : sizeRefreshers)
					r.run();
				e.consume();
			}
		});
		root.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene != null)
				root.requestFocus();
		});
	}

	public Region getRoot() {
		return root;
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
	/** Word-wrap target for value text in the tooltip. Tuned for the monospace 12px font
	 *  so the rendered width stays comfortably narrow without orphaning short trailing
	 *  words on their own line. */
	private static final int TOOLTIP_WRAP_WIDTH = 50;

	private static String buildVolumeTooltip(Volume v) {
		StringBuilder sb = new StringBuilder();
		sb.append(v.displayName()).append('\n');
		sb.append(v.root()).append("\n\n");

		String fs = v.fsType();
		String storage = v.storageProfile() == null || v.storageProfile().shortLabel().isEmpty()
				? "Unknown"
				: v.storageProfile().shortLabel();
		appendKeyValue(sb, "File system", fs == null || fs.isBlank() ? "—" : fs);
		appendKeyValue(sb, "Storage", storage);
		if (v.storageProfile() != null) {
			appendWrappedKeyValue(sb, "Scan", v.storageProfile().tooltipDescription());
		}

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

	private static void appendKeyValue(StringBuilder sb, String key, String value) {
		sb.append(String.format("%-" + TOOLTIP_KEY_WIDTH + "s : %s%n", key, value));
	}

	/** Word-wraps {@code value} to {@link #TOOLTIP_WRAP_WIDTH}, indenting continuation
	 *  lines to align under the first value character (i.e. {@code keyWidth + " : ".length}). */
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

	/** Greedy word-wrap. Long single tokens (e.g. URLs) overflow the target width rather
	 *  than getting hyphenated — this is fine for our short, prose-only descriptions. */
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
		if (current.length() > 0) lines.add(current.toString());
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
