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

    /** Distinct neutral grey reserved for the synthetic "Hidden" sector — never collides
     *  with the regular palette so users can spot it at a glance. */
    private static final Color HIDDEN_BASE = Color.web("#6E7280");

    private SectorPalette() {}

    public static Color forName(String name, int depth) {
        if ("Hidden".equals(name)) {
            double factor = Math.max(0.55, 1.0 - depth * 0.08);
            return HIDDEN_BASE.deriveColor(0, 1.0, factor, 1.0);
        }
        int hash = name == null ? 0 : name.hashCode();
        Color base = BASE[Math.floorMod(hash, BASE.length)];
        // Slightly darken with depth so deeper rings recede visually.
        double factor = Math.max(0.55, 1.0 - depth * 0.08);
        return base.deriveColor(0, 1.0, factor, 1.0);
    }
}
