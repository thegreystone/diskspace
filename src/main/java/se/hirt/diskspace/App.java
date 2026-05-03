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
package se.hirt.diskspace;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import se.hirt.diskspace.ui.MainWindow;
import se.hirt.diskspace.ui.theme.ColorScheme;

public final class App extends Application {

    @Override
    public void start(Stage stage) {
        ColorScheme scheme = ColorScheme.DARK;

        MainWindow main = new MainWindow(scheme);
        Scene scene = new Scene(main.getRoot(), 1100, 700);
        scene.setFill(scheme.background());
        if (scheme.stylesheet() != null) {
            var url = App.class.getResource(scheme.stylesheet());
            if (url != null) {
                scene.getStylesheets().add(url.toExternalForm());
            }
        }

        stage.setTitle("diskspace");
        stage.getIcons().addAll(loadAppIcons());
        stage.setScene(scene);
        stage.show();
    }

    /** Loads the runtime window icons in all available pre-rendered sizes. JavaFX picks the
     *  closest match for each display context (titlebar at 16, taskbar at 32, alt-tab at 256).
     *  The bundle icon (.exe / .app / .desktop) is a separate, build-time artifact. */
    private static java.util.List<Image> loadAppIcons() {
        java.util.List<Image> icons = new java.util.ArrayList<>();
        for (int size : new int[]{16, 32, 64, 128, 256}) {
            var url = App.class.getResource("/se/hirt/diskspace/icon-" + size + ".png");
            if (url != null) {
                icons.add(new Image(url.toExternalForm()));
            }
        }
        return icons;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
