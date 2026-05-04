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

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.ui.theme.ColorScheme;

public final class PickerView {

    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;
    private static final long TIB = GIB * 1024L;

    private final BorderPane root;
    private final ColorScheme scheme;
    private final Consumer<Volume> onSelection;

    public PickerView(ColorScheme scheme, Consumer<Volume> onSelection) {
        this.scheme = scheme;
        this.onSelection = onSelection;

        Label title = new Label("Choose a disk");
        title.setStyle(
                "-fx-text-fill: " + toCss(scheme.textPrimary()) + ";"
                        + "-fx-font-size: 22px; -fx-font-weight: 600;"
                        + "-fx-padding: 0 0 18 0;");

        VBox rows = new VBox(12);
        for (Volume v : Volume.enumerate()) {
            rows.getChildren().add(buildRow(v));
        }

        Button choose = new Button("Choose folder…");
        choose.setStyle(
                "-fx-background-color: transparent;"
                        + "-fx-text-fill: " + toCss(scheme.accent()) + ";"
                        + "-fx-border-color: " + toCss(scheme.accent()) + ";"
                        + "-fx-border-radius: 6; -fx-background-radius: 6;"
                        + "-fx-padding: 8 14 8 14; -fx-cursor: hand;");
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
                "-fx-background: " + toCss(scheme.background()) + ";"
                        + "-fx-background-color: " + toCss(scheme.background()) + ";");

        root = new BorderPane(scroll);
        root.setStyle("-fx-background-color: " + toCss(scheme.background()) + ";");
    }

    public Region getRoot() {
        return root;
    }

    private Region buildRow(Volume v) {
        Label name = new Label(v.displayName());
        name.setStyle(
                "-fx-text-fill: " + toCss(scheme.textPrimary()) + ";"
                        + "-fx-font-size: 15px; -fx-font-weight: 600;");

        Label path = new Label(v.root().toString());
        path.setStyle(
                "-fx-text-fill: " + toCss(scheme.textMuted()) + ";"
                        + "-fx-font-size: 11px;");

        Label total = new Label(humanSize(v.totalBytes()));
        total.setStyle(
                "-fx-text-fill: " + toCss(scheme.textMuted()) + ";"
                        + "-fx-font-size: 12px;");

        Region bar = buildCapacityBar(v.usedFraction());
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox barRow = new HBox(12, bar, total);
        barRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, name, path, barRow);
        box.setPadding(new Insets(14, 16, 14, 16));
        box.setStyle(
                "-fx-background-color: " + toCss(scheme.surface()) + ";"
                        + "-fx-background-radius: 10; -fx-cursor: hand;");
        box.setOnMouseEntered(e ->
                box.setStyle(box.getStyle() + "-fx-effect: dropshadow(gaussian, "
                        + toCss(scheme.accent()) + ", 12, 0.15, 0, 0);"));
        box.setOnMouseExited(e ->
                box.setStyle(
                        "-fx-background-color: " + toCss(scheme.surface()) + ";"
                                + "-fx-background-radius: 10; -fx-cursor: hand;"));
        box.setOnMouseClicked(e -> onSelection.accept(v));
        return box;
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
        if (bytes >= TIB) return String.format("%.1f TB", bytes / (double) TIB);
        if (bytes >= GIB) return String.format("%.0f GB", bytes / (double) GIB);
        if (bytes >= MIB) return String.format("%.0f MB", bytes / (double) MIB);
        if (bytes >= KIB) return String.format("%.0f KB", bytes / (double) KIB);
        return bytes + " B";
    }

    private static String toCss(Color c) {
        return String.format("rgba(%d,%d,%d,%.3f)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255),
                c.getOpacity());
    }

}
