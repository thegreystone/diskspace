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

import se.hirt.diskspace.ui.theme.ColorScheme;

/**
 * Callbacks a {@link Visualization} uses to talk back to its host (the surrounding view). Lets the visualization stay
 * decoupled from JavaFX timing infrastructure and theme management: it asks the host to redraw and looks up colours,
 * rather than reaching into the host's internals.
 */
public interface VisualizationHost {

	/**
	 * Request a redraw. A visualization calls this from its own animation loop to advance interpolated frames, or
	 * whenever it has updated its internal state and needs the host to repaint.
	 *
	 * @param trigger
	 * 		short label for telemetry — surfaces in the JFR render event so painters can be attributed.
	 */
	void requestRedraw(String trigger);

	/** Resolves the display colour of any {@link se.hirt.diskspace.model.DirectoryNode}. */
	NodeColorResolver colors();

	/** The active colour scheme — background, surface, accent, etc. */
	ColorScheme scheme();
}
