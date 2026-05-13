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

import javafx.scene.canvas.GraphicsContext;
import se.hirt.diskspace.model.DirectoryNode;

/**
 * A self-contained way of visualising a scanned directory tree on a canvas. Owns its own rendering, hit-testing, and
 * animation state — different visualisations animate differently (sunburst lerps annular sectors, a heatmap might
 * cross-fade cells, a voronoi could morph cell centroids), and that knowledge belongs <em>inside</em> the
 * visualization, not in the surrounding view.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #attach} is called once after construction so the visualization can capture the host callbacks.</li>
 *   <li>{@link #render} is called every paint pulse. The {@link RenderContext} is a read-only snapshot built by the
 *       host; the visualization may also paint mid-animation frames it computes from its internal state.</li>
 *   <li>{@link #hitTest} is called from input handlers; the visualization queries whatever geometry it cached
 *       during the last render.</li>
 *   <li>{@link #viewRootChanged} is called when the user drills in/out. The visualization decides whether to
 *       animate and, if so, kicks off its own animation loop — calling
 *       {@link VisualizationHost#requestRedraw} on each tick.</li>
 *   <li>{@link #shutdown} is called when the visualization is detached so it can stop animation timers and
 *       release JFR / other resources.</li>
 * </ol>
 *
 * <p>{@link #isAnimating} is queried by the host to gate input — clicks and hover should be ignored while a
 * transition is mid-flight, otherwise the user can drill into a sector that's still moving and confuse the
 * animator.</p>
 */
public interface Visualization {

	/** Called once after construction. */
	void attach(VisualizationHost host);

	/** Paint the current frame. */
	void render(GraphicsContext g, double width, double height, RenderContext ctx);

	/** Hit-test against the geometry produced by the most recent {@link #render}. */
	HitResult hitTest(double x, double y);

	/**
	 * Notify the visualization that the view root has changed (drill-in or drill-out). The visualization decides
	 * whether to animate the transition.
	 *
	 * @param previous the view root before the change, or {@code null} on first attachment
	 * @param current  the new view root
	 */
	void viewRootChanged(DirectoryNode previous, DirectoryNode current);

	/** {@code true} while an animation pulse sequence is in flight. The host should not deliver input during this. */
	boolean isAnimating();

	/** Optional cleanup hook — stop timers, commit pending JFR events, etc. Default is no-op. */
	default void shutdown() {
		// no-op
	}
}
