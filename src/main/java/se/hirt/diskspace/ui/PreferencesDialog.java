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

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import se.hirt.diskspace.App;
import se.hirt.diskspace.platform.Capabilities;
import se.hirt.diskspace.settings.Settings;
import se.hirt.diskspace.ui.DiskView.RenderMode;
import se.hirt.diskspace.ui.render.ColoringMode;
import se.hirt.diskspace.ui.render.ColoringModes;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.ColorSchemes;

/**
 * Modal "Preferences" dialog. Persists the user's startup defaults. Changes do not affect the currently open tabs or
 * the current global size-unit toggle — those still respond to the {@code V} / {@code U} shortcuts. The persisted
 * values take effect on next launch and on every newly opened tab.
 */
public final class PreferencesDialog {

	private PreferencesDialog() {
	}

	public static void show() {
		Settings settings = Settings.get();

		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.setTitle("Preferences");
		dialog.setHeaderText("Startup defaults");

		ChoiceBox<RenderMode> vizChoice = new ChoiceBox<>();
		vizChoice.getItems().addAll(RenderMode.values());
		vizChoice.setConverter(new StringConverter<>() {
			@Override
			public String toString(RenderMode mode) {
				return mode == null ? "" : mode.displayName();
			}

			@Override
			public RenderMode fromString(String string) {
				for (RenderMode m : RenderMode.values()) {
					if (m.displayName().equals(string))
						return m;
				}
				return RenderMode.SUNBURST;
			}
		});
		vizChoice.setValue(settings.defaultVisualization());

		Label vizDescription = describeLabel(settings.defaultVisualization().description());
		vizChoice.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
			vizDescription.setText(newV == null ? "" : newV.description());
		});

		ChoiceBox<SizeFormat.Mode> unitChoice = new ChoiceBox<>();
		unitChoice.getItems().addAll(SizeFormat.Mode.values());
		unitChoice.setConverter(new StringConverter<>() {
			@Override
			public String toString(SizeFormat.Mode mode) {
				return mode == null ? "" : mode.displayName();
			}

			@Override
			public SizeFormat.Mode fromString(String string) {
				for (SizeFormat.Mode m : SizeFormat.Mode.values()) {
					if (m.displayName().equals(string))
						return m;
				}
				return SizeFormat.Mode.DECIMAL;
			}
		});
		unitChoice.setValue(settings.defaultSizeUnit());

		Label unitDescription = describeLabel(settings.defaultSizeUnit().description());
		unitChoice.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
			unitDescription.setText(newV == null ? "" : newV.description());
		});

		ChoiceBox<ColorScheme> themeChoice = new ChoiceBox<>();
		themeChoice.getItems().addAll(ColorSchemes.all());
		themeChoice.setConverter(new StringConverter<>() {
			@Override
			public String toString(ColorScheme scheme) {
				return scheme == null ? "" : scheme.displayName();
			}

			@Override
			public ColorScheme fromString(String string) {
				for (ColorScheme s : ColorSchemes.all()) {
					if (s.displayName().equals(string))
						return s;
				}
				return ColorSchemes.defaultMode();
			}
		});
		themeChoice.setValue(settings.defaultColorScheme());

		Label themeDescription = describeLabel(settings.defaultColorScheme().description());
		themeChoice.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
			themeDescription.setText(newV == null ? "" : newV.description());
		});

		ChoiceBox<ColoringMode> coloringChoice = new ChoiceBox<>();
		coloringChoice.getItems().addAll(ColoringModes.all());
		coloringChoice.setConverter(new StringConverter<>() {
			@Override
			public String toString(ColoringMode mode) {
				return mode == null ? "" : mode.displayName();
			}

			@Override
			public ColoringMode fromString(String string) {
				for (ColoringMode m : ColoringModes.all()) {
					if (m.displayName().equals(string))
						return m;
				}
				return ColoringModes.defaultMode();
			}
		});
		coloringChoice.setValue(settings.defaultColoringMode());

		Label coloringDescription = describeLabel(settings.defaultColoringMode().description());
		coloringChoice.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
			coloringDescription.setText(newV == null ? "" : newV.description());
		});

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(10);
		grid.setPadding(new Insets(8, 4, 4, 4));
		ColumnConstraints labelCol = new ColumnConstraints();
		labelCol.setHalignment(HPos.RIGHT);
		ColumnConstraints valueCol = new ColumnConstraints();
		valueCol.setHgrow(Priority.SOMETIMES);
		grid.getColumnConstraints().addAll(labelCol, valueCol);

		grid.add(new Label("Default theme:"), 0, 0);
		grid.add(themeChoice, 1, 0);
		// Description sits in the value column under the picker — a hint, not a labelled field.
		grid.add(themeDescription, 1, 1);
		grid.add(new Label("Default visualization:"), 0, 2);
		grid.add(vizChoice, 1, 2);
		grid.add(vizDescription, 1, 3);
		grid.add(new Label("Default size unit:"), 0, 4);
		grid.add(unitChoice, 1, 4);
		grid.add(unitDescription, 1, 5);
		grid.add(new Label("Default coloring:"), 0, 6);
		grid.add(coloringChoice, 1, 6);
		grid.add(coloringDescription, 1, 7);

		CheckBox hideUnavailableCheck = new CheckBox("Hide unavailable disks");
		hideUnavailableCheck.setSelected(settings.hideUnavailableDisks());
		Label hideUnavailableHint = describeLabel(
				"Leave disks that can't be read (offline, disconnected, or failing media) out of the picker.");
		grid.add(new Label("Disk picker:"), 0, 8);
		grid.add(hideUnavailableCheck, 1, 8);
		grid.add(hideUnavailableHint, 1, 9);

		// Windows-only row: run elevated so the fast NTFS (MFT) scanner can open raw volume handles. Hidden on
		// macOS / Linux / JVM dev mode, where UAC elevation isn't a thing (Capabilities.ELEVATION.isAvailable() is
		// false there), so the checkbox would have nothing useful to do.
		CheckBox elevateCheck = null;
		Settings.ElevationChoice originalElevation = settings.elevationChoice();
		if (Capabilities.ELEVATION.isAvailable()) {
			elevateCheck = new CheckBox("Always start with administrator rights");
			elevateCheck.setSelected(originalElevation == Settings.ElevationChoice.ALWAYS);
			Label elevateHint = describeLabel(
					"Lets DiskSpace use the fast NTFS (MFT) scanner. Windows shows a UAC prompt at each launch.");
			grid.add(new Label("Fast scanning:"), 0, 10);
			grid.add(elevateCheck, 1, 10);
			grid.add(elevateHint, 1, 11);
		}

		dialog.getDialogPane().setContent(grid);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		final CheckBox elevateCheckFinal = elevateCheck;
		dialog.showAndWait().ifPresent(button -> {
			if (button != ButtonType.OK)
				return;
			settings.setDefaultColorScheme(themeChoice.getValue());
			settings.setDefaultVisualization(vizChoice.getValue());
			settings.setDefaultSizeUnit(unitChoice.getValue());
			settings.setDefaultColoringMode(coloringChoice.getValue());
			settings.setHideUnavailableDisks(hideUnavailableCheck.isSelected());
			// Only touch the elevation choice when the checkbox state actually flips relative to ALWAYS, so merely
			// opening Preferences and clicking OK doesn't silently suppress the first-run ASK prompt.
			boolean offerRestartElevated = false;
			if (elevateCheckFinal != null) {
				boolean wantAlways = elevateCheckFinal.isSelected();
				boolean wasAlways = originalElevation == Settings.ElevationChoice.ALWAYS;
				if (wantAlways != wasAlways) {
					settings.setElevationChoice(
							wantAlways ? Settings.ElevationChoice.ALWAYS : Settings.ElevationChoice.NEVER);
					// Turning it on takes effect now only if we're not already elevated; otherwise it just applies
					// to future launches.
					offerRestartElevated = wantAlways && !Capabilities.ELEVATION.isElevated();
				}
			}
			settings.save();
			if (offerRestartElevated) {
				App.relaunchElevatedNow();
			}
		});
	}

	/**
	 * Build a wrap-text label styled as secondary helper copy under a picker. Bounded width keeps long descriptions
	 * from forcing the dialog wider than the picker row above it.
	 */
	private static Label describeLabel(String text) {
		Label l = new Label(text);
		l.setWrapText(true);
		l.setMaxWidth(360);
		l.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-base-color; -fx-opacity: 0.8;");
		return l;
	}
}
