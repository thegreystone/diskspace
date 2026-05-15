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

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.ui.theme.ColorScheme;

public final class MainWindow {

	private final BorderPane root;
	private final TabPane tabs;
	private ColorScheme scheme;
	private final Tab plusTab;

	public MainWindow(ColorScheme scheme) {
		this.scheme = scheme;

		tabs = new TabPane();
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

		plusTab = new Tab("+");
		plusTab.setClosable(false);
		plusTab.setContent(buildHint());
		tabs.getTabs().add(plusTab);

		// Selecting "+" opens a fresh picker tab and selects it.
		tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
			if (newT == plusTab) {
				Tab created = openPickerTab();
				tabs.getSelectionModel().select(created);
			}
		});

		// Start with one picker tab already open.
		Tab first = openPickerTab();
		tabs.getSelectionModel().select(first);

		root = new BorderPane(tabs);
		restyle();

		// Single-key shortcuts should reach the active tab's content (DiskView or
		// PickerView) even when focus is on the TabPane header. Each content also
		// installs its own key handler / filter on its root, which runs first and
		// consumes — so this only takes effect when focus is outside the active content.
		root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
			Tab selected = tabs.getSelectionModel().getSelectedItem();
			if (selected == null || selected == plusTab)
				return;
			Object data = selected.getUserData();
			if (data instanceof DiskView dv) {
				dv.dispatchTopLevelKey(e);
			} else if (data instanceof PickerView pv) {
				pv.dispatchTopLevelKey(e);
			}
		});

		// Pull focus into the active tab's content whenever selection changes so keyboard
		// shortcuts target the visible tab without an extra click. Critically, this also
		// fires when the user closes the last DiskView tab and the auto-created picker
		// becomes selected — otherwise focus is left on the TabPane header and Esc / U
		// silently miss the picker's filter.
		tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
			if (newT == null || newT == plusTab)
				return;
			Object data = newT.getUserData();
			if (data instanceof DiskView dv) {
				dv.getRoot().requestFocus();
			} else if (data instanceof PickerView pv) {
				pv.getRoot().requestFocus();
			}
		});
	}

	public Region getRoot() {
		return root;
	}

	/**
	 * Live theme handoff. Called by {@code App}'s {@link se.hirt.diskspace.ui.theme.Theme} listener after the new
	 * scheme has been stored. Refreshes our own inline styles, rebuilds the "+" hint pane (a transient label — cheaper
	 * to recreate than to restyle in place), and fans out to every open tab so per-view inline styles get re-applied
	 * too.
	 */
	public void applyTheme(ColorScheme newScheme) {
		this.scheme = newScheme;
		restyle();
		plusTab.setContent(buildHint());
		for (Tab t : tabs.getTabs()) {
			Object data = t.getUserData();
			if (data instanceof DiskView dv) {
				dv.applyTheme(newScheme);
			} else if (data instanceof PickerView pv) {
				pv.applyTheme(newScheme);
			}
		}
	}

	private void restyle() {
		tabs.setStyle("-fx-background-color: " + toCss(
				scheme.background()) + ";" + "-fx-tab-min-height: 28; -fx-tab-max-height: 28;");
		root.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");
	}

	/**
	 * Cancel all in-flight scans and stop animation timers across open tabs. Called from
	 * {@link se.hirt.diskspace.App#requestQuit()} so background callbacks aren't still firing when the toolkit starts
	 * tearing down.
	 */
	public void shutdown() {
		for (Tab t : tabs.getTabs()) {
			Object data = t.getUserData();
			if (data instanceof DiskView dv) {
				try {
					dv.shutdown();
				} catch (RuntimeException ignored) {
					// Swallow — we're on the way out.
				}
			}
		}
	}

	private Tab openPickerTab() {
		Tab tab = new Tab("New disk");
		PickerView picker = new PickerView(scheme, v -> swapToSunburst(tab, v));
		// Stash before setContent so the selection listener (and the MainWindow key
		// handler) find a content object as soon as the tab becomes visible.
		tab.setUserData(picker);
		tab.setContent(picker.getRoot());
		// Insert before the "+" tab so "+" stays last.
		int insertAt = tabs.getTabs().indexOf(plusTab);
		tabs.getTabs().add(insertAt, tab);
		return tab;
	}

	private void swapToSunburst(Tab tab, Volume v) {
		tab.setText(v.displayName());
		if (!v.deviceName().equals(v.displayName())) {
			tab.setTooltip(new javafx.scene.control.Tooltip(v.deviceName()));
		}
		DiskView dv = new DiskView(v, scheme);
		// Stash before setContent so the selectedItemProperty listener (which fires when
		// the picker swap re-flows layout) finds a DiskView when it goes looking.
		tab.setUserData(dv);
		tab.setContent(dv.getRoot());
		// Keys should target the new view immediately, before the user clicks anything.
		dv.getRoot().requestFocus();
	}

	private Region buildHint() {
		Label hint = new Label("Click + to open a disk picker.");
		hint.setStyle("-fx-text-fill: " + toCss(scheme.textMuted()) + ";" + "-fx-padding: 36;");
		BorderPane p = new BorderPane(hint);
		p.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");
		return p;
	}

	private static String toCss(Color c) {
		return String.format("rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
				(int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
	}
}
