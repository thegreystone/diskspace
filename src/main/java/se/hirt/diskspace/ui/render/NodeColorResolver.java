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

import javafx.scene.paint.Color;
import se.hirt.diskspace.model.DirectoryNode;

/**
 * Resolves the display color of a {@link DirectoryNode}. The same colour is used by both the canvas (sunburst sector /
 * heatmap cell) and the table swatch in the right-hand pane, so centralising the lookup keeps them in lockstep.
 * <p>Concrete implementations encapsulate palette + scheme + family-inheritance rules. The renderers don't need to
 * know any of that — they call {@link #colorFor} and paint.</p>
 * <p>Resolvers are owned by a {@code DiskView} for the lifetime of one scan target. The lifecycle hooks below let an
 * implementation cache results without going stale across rescans and live-scan ticks. They default to no-op so simple
 * stateless palettes don't need to override anything.</p>
 */
public interface NodeColorResolver {

	/**
	 * @param node
	 * 		the node to colour; never {@code null}
	 * @return the fill color for {@code node}'s sector / cell. The scan root typically returns the scheme's surface
	 * 		colour (used by the sunburst hub).
	 */
	Color colorFor(DirectoryNode node);

	/** Called on (re)scan start and on shutdown (with {@code null}). Default: no-op. */
	default void setScanRoot(DirectoryNode root) {
	}

	/** Called when the synthetic "Hidden" node is injected, or with {@code null} on teardown. Default: no-op. */
	default void setHiddenNode(DirectoryNode hidden) {
	}

	/** Called when the in-flight scan finishes successfully. Default: no-op. */
	default void onScanComplete() {
	}

	/** Called once per live-scan tick so resolvers can drop caches for any top-level folder that just finalised. */
	default void stabilizeFinalizedTopLevels() {
	}
}
