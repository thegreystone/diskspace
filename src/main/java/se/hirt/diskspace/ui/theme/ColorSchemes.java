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

import java.util.List;
import java.util.Locale;

/**
 * Registry of available {@link ColorScheme} instances. List order is display order in the Preferences picker — the
 * first entry is the default for fresh installs and the fallback when a saved id no longer resolves.
 * <p>Mirrors {@link se.hirt.diskspace.ui.render.ColoringModes} so the pattern is consistent: lookup by stable
 * {@code id()}, fallback to {@link #defaultMode()}, ordered {@link #all()} for the {@code T} keybinding to cycle.
 */
public final class ColorSchemes {

	private static final List<ColorScheme> SCHEMES = List.of(ColorScheme.DARK, ColorScheme.LIGHT);

	private ColorSchemes() {
	}

	public static List<ColorScheme> all() {
		return SCHEMES;
	}

	/** The scheme used when no preference is saved or the saved id no longer resolves. Always non-null. */
	public static ColorScheme defaultMode() {
		return SCHEMES.get(0);
	}

	/**
	 * Lookup by stable id. Falls back to {@link #defaultMode()} if no registered scheme matches. Input is normalised
	 * ({@code trim()} + {@code toLowerCase(Locale.ROOT)}) so hand-edited settings files survive whitespace and case
	 * drift.
	 */
	public static ColorScheme byId(String id) {
		if (id != null) {
			String normalised = id.trim().toLowerCase(Locale.ROOT);
			for (ColorScheme s : SCHEMES) {
				if (s.id().equals(normalised))
					return s;
			}
		}
		return defaultMode();
	}

	/** Next scheme in registry order, wrapping. Used by the {@code T} keybinding. */
	public static ColorScheme next(ColorScheme current) {
		if (SCHEMES.size() < 2)
			return defaultMode();
		int idx = 0;
		for (int i = 0; i < SCHEMES.size(); i++) {
			if (SCHEMES.get(i).id().equals(current.id())) {
				idx = i;
				break;
			}
		}
		return SCHEMES.get((idx + 1) % SCHEMES.size());
	}
}
