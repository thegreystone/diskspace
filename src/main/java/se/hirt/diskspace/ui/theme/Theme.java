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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Process-wide live theme state. In-session only — matches the pattern of
 * {@link se.hirt.diskspace.scan.Scanner#PREFERENCE} and {@code SizeFormat}: the persisted "startup default" lives in
 * {@link se.hirt.diskspace.settings.Settings} and seeds this on launch, but runtime toggles via {@code T} only mutate
 * state here and do not write back to disk.
 * <p>Listeners are notified after the new scheme has been stored, on the calling thread (the JavaFX application thread
 * for the {@code T} keybinding). Subscribers are responsible for thread-safe handoff if they need to mutate scene-graph
 * state.</p>
 */
public final class Theme {

	private static volatile ColorScheme current = ColorSchemes.defaultMode();
	private static final CopyOnWriteArrayList<Consumer<ColorScheme>> LISTENERS = new CopyOnWriteArrayList<>();

	private Theme() {
	}

	public static ColorScheme current() {
		return current;
	}

	/**
	 * Replace the active scheme and fan out to listeners. No-op if {@code scheme} equals the current one — saves a
	 * redraw storm if something accidentally reapplies the same scheme.
	 */
	public static void set(ColorScheme scheme) {
		if (scheme == null || scheme == current)
			return;
		current = scheme;
		for (Consumer<ColorScheme> l : LISTENERS) {
			l.accept(scheme);
		}
	}

	/**
	 * Flip to the next registered scheme. With two schemes this is the dark/light toggle; with more it walks the
	 * registry in order.
	 */
	public static ColorScheme toggle() {
		ColorScheme next = ColorSchemes.next(current);
		set(next);
		return next;
	}

	/** Subscribe to scheme changes. The listener fires on the thread that called {@link #set}. */
	public static void addListener(Consumer<ColorScheme> listener) {
		LISTENERS.add(listener);
	}
}
