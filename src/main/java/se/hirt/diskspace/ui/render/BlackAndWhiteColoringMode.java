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
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Test / accent-only colouring: folders render in grayscale, single large files in bright red, and "Smaller files"
 * aggregates in orange. Lets users spot heavy hitters at a glance without folder hues distracting from them.
 */
public final class BlackAndWhiteColoringMode implements ColoringMode {

	private static final Color LARGE_FILE_RED = Color.web("#E63946");
	private static final Color SMALLER_FILES_ORANGE = Color.web("#F2A055");

	@Override
	public String id() {
		return "bw";
	}

	@Override
	public String displayName() {
		return "Black & White";
	}

	@Override
	public String description() {
		return "Folders are pure grayscale (darker deeper in the tree). Single large files glow bright red and " + "the \"Smaller files\" aggregate is orange, so the things that actually consume disk space " + "pop against an otherwise neutral background.";
	}

	@Override
	public NodeColorResolver createResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf) {
		return new Resolver(scheme);
	}

	/**
	 * Stateless aside from a per-node colour cache and the current scan root reference. {@code rankOf} is unused — the
	 * mode is rank-agnostic on purpose: the goal is to surface absolute size class (red / orange) rather than relative
	 * position among siblings.
	 */
	private static final class Resolver implements NodeColorResolver {

		private final ColorScheme scheme;
		private final Map<DirectoryNode, Color> cache = new IdentityHashMap<>();
		private DirectoryNode scanRoot;

		Resolver(ColorScheme scheme) {
			this.scheme = scheme;
		}

		@Override
		public void setScanRoot(DirectoryNode root) {
			this.scanRoot = root;
			cache.clear();
		}

		@Override
		public void onScanComplete() {
			cache.clear();
		}

		@Override
		public Color colorFor(DirectoryNode node) {
			if (node == null || node == scanRoot)
				return scheme.surface();
			Color cached = cache.get(node);
			if (cached != null)
				return cached;

			Color computed;
			if (node.isFileSector()) {
				computed = "Smaller files".equals(node.name()) ? SMALLER_FILES_ORANGE : LARGE_FILE_RED;
			} else {
				computed = grayForDepth(depthFromScanRoot(node));
			}
			cache.put(node, computed);
			return computed;
		}

		/**
		 * Maps tree depth to a gray value. Depth 1 (immediate children of the scan root) starts at a light gray and
		 * each ring darkens by a fixed step, clamped so deep trees don't go fully black. Works against both DARK and
		 * LIGHT schemes because gray rings are visible against any background.
		 */
		private static Color grayForDepth(int depth) {
			double v = Math.max(0.18, 0.78 - Math.max(0, depth - 1) * 0.10);
			return Color.color(v, v, v);
		}

		private int depthFromScanRoot(DirectoryNode node) {
			int d = 0;
			for (DirectoryNode n = node; n != null && n != scanRoot; n = n.parent())
				d++;
			return d;
		}
	}
}
