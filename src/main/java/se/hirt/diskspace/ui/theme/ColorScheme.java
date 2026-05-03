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

public record ColorScheme(
        Color background,
        Color surface,
        Color textPrimary,
        Color textMuted,
        Color accent,
        Color capacityTrack,
        Color capacityFillLow,
        Color capacityFillMid,
        Color capacityFillHigh,
        String stylesheet) {

    public static final ColorScheme DARK = new ColorScheme(
            Color.web("#000000"),
            Color.web("#121214"),
            Color.web("#EDEDED"),
            Color.web("#7A7A82"),
            Color.web("#7AD3D9"),
            Color.web("#1F1F23"),
            Color.web("#5BC9A7"),
            Color.web("#E0B650"),
            Color.web("#D9594E"),
            "/se/hirt/diskspace/ui/theme/dark.css");

    public static final ColorScheme LIGHT = new ColorScheme(
            Color.web("#FFFFFF"),
            Color.web("#F4F4F5"),
            Color.web("#1A1A1A"),
            Color.web("#7A7A82"),
            Color.web("#1F8E96"),
            Color.web("#E5E5E8"),
            Color.web("#3FA585"),
            Color.web("#C99030"),
            Color.web("#C0382E"),
            null);

    public Color capacityFillFor(double fraction) {
        if (fraction < 0.70) return capacityFillLow();
        if (fraction < 0.90) return capacityFillMid();
        return capacityFillHigh();
    }
}
