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

/**
 * Palette + identity for a UI theme. Two are registered today (see {@link ColorSchemes}): {@link #DARK} (default) and
 * {@link #LIGHT}.
 * <p>{@link #id()} is the stable key used to persist the user's startup preference in
 * {@link se.hirt.diskspace.settings.Settings}; {@link #displayName()} and {@link #description()} drive the Preferences
 * dialog row, mirroring the contract used by {@link se.hirt.diskspace.ui.render.ColoringMode}.
 * <p><b>Mode.</b> {@link #mode()} declares whether the scheme is fundamentally dark- or light-background. Code that
 * needs a binary "am I on a dark or light background?" decision — typically because it's choosing between a
 * translucent-white tint and a translucent-black one — should call {@link #isDark()} rather than comparing
 * {@link #id()} strings, so adding a third scheme (say, {@code "solarized-dark"}) doesn't require touching every call
 * site. Most palette decisions, though, are better expressed by adding a dedicated field below — see the row-tint and
 * sector-grey fields for examples.
 * <p><b>Row tints.</b> {@link #rowHover}, {@link #rowAltStrong}, {@link #rowAltWeak} carry the scheme-appropriate
 * "subtle overlay" used for table row hover and alternating zebra stripes. Dark schemes use translucent white; light
 * schemes use translucent black.
 * <p><b>Sector greys.</b> {@link #largeFileSector}, {@link #smallerFilesSector}, {@link #hiddenSector} are the neutral
 * greys the Classic coloring mode paints onto file-only nodes (so they don't compete with the colourful folder
 * palette). The relationship to preserve when adding a new scheme: {@code largeFileSector} should sit furthest from
 * {@link #background} (most visible — these are individually meaningful files), {@code smallerFilesSector} closest
 * (recedes — uninteresting bulk), and {@code hiddenSector} in between. Authors of new schemes can use the existing
 * values for {@link #DARK} / {@link #LIGHT} as anchors: pick three greys whose distance-from-background follows the
 * same ordering and you'll get the same visual semantics.
 */
public record ColorScheme(String id, String displayName, String description, Mode mode, Color background, Color surface,
                          Color textPrimary, Color textMuted, Color accent, Color capacityTrack, Color capacityFillLow,
                          Color capacityFillMid, Color capacityFillHigh, Color rowHover, Color rowAltStrong,
                          Color rowAltWeak, Color overlayScrim, Color largeFileSector, Color smallerFilesSector,
                          Color hiddenSector, String stylesheet) {

	/**
	 * Whether a scheme sits on a fundamentally dark or light background. Drives binary-branching decisions where a
	 * dedicated palette field would be overkill (e.g. picking between a translucent-white and translucent-black scrim
	 * formula). When in doubt, prefer adding a typed Color field over branching on {@code Mode} — explicit beats
	 * conditional.
	 */
	public enum Mode {DARK, LIGHT}

	public static final ColorScheme DARK = new ColorScheme("dark", "Dark",
			"High-contrast dark palette tuned for OLED and dim rooms.", Mode.DARK, Color.web("#000000"),
			Color.web("#121214"), Color.web("#EDEDED"), Color.web("#7A7A82"), Color.web("#7AD3D9"),
			Color.web("#1F1F23"), Color.web("#5BC9A7"), Color.web("#E0B650"), Color.web("#D9594E"),
			Color.rgb(255, 255, 255, 0.12), Color.rgb(255, 255, 255, 0.075), Color.rgb(255, 255, 255, 0.045),
			Color.rgb(0, 0, 0, 0.55), Color.web("#C8CDD4"), Color.web("#3D4148"), Color.web("#484C54"),
			"/se/hirt/diskspace/ui/theme/dark.css");

	public static final ColorScheme LIGHT = new ColorScheme("light", "Light",
			"Bright palette for daylight and shared screens.", Mode.LIGHT, Color.web("#FFFFFF"), Color.web("#F4F4F5"),
			Color.web("#1A1A1A"), Color.web("#5C5C66"), Color.web("#1F8E96"), Color.web("#E5E5E8"),
			Color.web("#3FA585"), Color.web("#C99030"), Color.web("#C0382E"), Color.rgb(0, 0, 0, 0.10),
			Color.rgb(0, 0, 0, 0.06), Color.rgb(0, 0, 0, 0.03), Color.rgb(0, 0, 0, 0.35), Color.web("#2A2F36"),
			Color.web("#B8BCC2"), Color.web("#A0A4AA"), "/se/hirt/diskspace/ui/theme/light.css");

	/** Convenience for the common "am I dark?" branch. Equivalent to {@code mode() == Mode.DARK}. */
	public boolean isDark() {
		return mode == Mode.DARK;
	}

	public Color capacityFillFor(double fraction) {
		if (fraction < 0.70)
			return capacityFillLow();
		if (fraction < 0.90)
			return capacityFillMid();
		return capacityFillHigh();
	}
}
