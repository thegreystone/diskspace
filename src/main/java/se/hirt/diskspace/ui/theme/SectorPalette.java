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
	private static final Color[] BASE = {Color.web("#5BC9C9"), Color.web("#4DA8E0"), Color.web("#6B6FE0"), Color.web("#9466E0"),
			Color.web("#C95FB7"), Color.web("#D85A8E"), Color.web("#E07260"), Color.web("#E0A04F"), Color.web("#A8C95F"),
			Color.web("#5FC982"), Color.web("#7AB8A0"), Color.web("#6B8FB5"),};

	/**
	 * Distinct neutral grey reserved for the synthetic "Hidden" sector — never collides with the regular palette so users can spot it at a
	 * glance. Darker than the large-file grey, lighter than the "Smaller files" near-black, so the three greys read as a legible scale.
	 */
	private static final Color HIDDEN_BASE = Color.web("#484C54");

	/**
	 * Dark grey for the "Smaller files" aggregate sector — distinct from {@link #HIDDEN_BASE} and from the regular palette, so it reads as
	 * "uninteresting bulk" at a glance.
	 */
	private static final Color SMALLER_FILES_BASE = Color.web("#3D4148");

	/**
	 * Light grey for large-file sectors (single files ≥ 1 GB). Lighter than {@link #SMALLER_FILES_BASE} and {@link #HIDDEN_BASE} so it's
	 * clearly distinguishable, but still neutral so it doesn't compete with real folder colors.
	 */
	private static final Color LARGE_FILE_BASE = Color.web("#C8CDD4");

	private SectorPalette() {
	}

	/**
	 * Number of distinct palette colors. Callers can use this with {@link #atIndex(int, int)} to enumerate or to dedupe top-level color
	 * allocation.
	 */
	public static int paletteSize() {
		return BASE.length;
	}

	/**
	 * Returns the palette color at {@code i}, with the same depth-darkening behavior as {@link #forName}. {@code i} is taken modulo
	 * {@link #paletteSize()}. Used by collision-avoidance code that needs a specific index, not a hashed one.
	 */
	public static Color atIndex(int i, int depth) {
		Color base = BASE[Math.floorMod(i, BASE.length)];
		double factor = Math.max(0.55, 1.0 - depth * 0.08);
		return base.deriveColor(0, 1.0, factor, 1.0);
	}

	public static Color forName(String name, int depth) {
		if ("Hidden".equals(name)) {
			double factor = Math.max(0.55, 1.0 - depth * 0.08);
			return HIDDEN_BASE.deriveColor(0, 1.0, factor, 1.0);
		}
		if ("Smaller files".equals(name)) {
			double factor = Math.max(0.55, 1.0 - depth * 0.08);
			return SMALLER_FILES_BASE.deriveColor(0, 1.0, factor, 1.0);
		}
		int hash = name == null ? 0 : name.hashCode();
		Color base = BASE[Math.floorMod(hash, BASE.length)];
		// Slightly darken with depth so deeper rings recede visually.
		double factor = Math.max(0.55, 1.0 - depth * 0.08);
		return base.deriveColor(0, 1.0, factor, 1.0);
	}

	/**
	 * Color for a file-sector node: {@code "Smaller files"} aggregates pick up their dark grey via {@link #forName}; everything else (i.e.
	 * an individual large file) gets a neutral medium grey rather than a hashed-by-name palette color, so files don't compete visually with
	 * folders.
	 */
	public static Color forFileSector(String name, int depth) {
		if ("Smaller files".equals(name)) {
			return forName(name, depth);
		}
		double factor = Math.max(0.55, 1.0 - depth * 0.08);
		return LARGE_FILE_BASE.deriveColor(0, 1.0, factor, 1.0);
	}
}
