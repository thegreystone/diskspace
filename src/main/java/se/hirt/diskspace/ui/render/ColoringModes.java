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
package se.hirt.diskspace.ui.render;

import java.util.List;

/**
 * Registry of available {@link ColoringMode} implementations. List order is display order in the Preferences picker —
 * the first entry is also the default for fresh installs and the fallback when a saved id no longer resolves.
 * <p>To add a new mode: implement {@link ColoringMode} and add a {@code new YourColoringMode()} entry to
 * {@link #MODES} below. That's the whole contract — no service files, no reflection, no build-time wiring. A
 * closed-world native image picks the new mode up the same way it picks up everything else.
 */
public final class ColoringModes {

	private static final List<ColoringMode> MODES = List.of(new ClassicColoringMode(), new BlackAndWhiteColoringMode());

	private ColoringModes() {
	}

	public static List<ColoringMode> all() {
		return MODES;
	}

	/** The mode used when no preference is saved or the saved id no longer resolves. Always non-null. */
	public static ColoringMode defaultMode() {
		return MODES.get(0);
	}

	/** Lookup by stable id. Falls back to {@link #defaultMode()} if no registered mode matches. */
	public static ColoringMode byId(String id) {
		if (id != null) {
			for (ColoringMode m : MODES) {
				if (m.id().equals(id))
					return m;
			}
		}
		return defaultMode();
	}
}
