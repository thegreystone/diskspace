/*
 * Copyright (C) 2026 Marcus Hirt
 * Licensed under BSD-3-Clause. See LICENSE.
 */
package se.hirt.diskspace.ui.theme;

import javafx.scene.paint.Color;

public final class SectorPalette {

    /** Twelve harmonious, saturated-but-not-screaming colors. Works on dark and light backgrounds. */
    private static final Color[] BASE = {
            Color.web("#5BC9C9"),
            Color.web("#4DA8E0"),
            Color.web("#6B6FE0"),
            Color.web("#9466E0"),
            Color.web("#C95FB7"),
            Color.web("#D85A8E"),
            Color.web("#E07260"),
            Color.web("#E0A04F"),
            Color.web("#A8C95F"),
            Color.web("#5FC982"),
            Color.web("#7AB8A0"),
            Color.web("#6B8FB5"),
    };

    private SectorPalette() {}

    public static Color forName(String name, int depth) {
        int hash = name == null ? 0 : name.hashCode();
        Color base = BASE[Math.floorMod(hash, BASE.length)];
        // Slightly darken with depth so deeper rings recede visually.
        double factor = Math.max(0.55, 1.0 - depth * 0.08);
        return base.deriveColor(0, 1.0, factor, 1.0);
    }
}
