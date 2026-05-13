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
import se.hirt.diskspace.ui.theme.SectorPalette;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Default {@link NodeColorResolver}. Implements DaisyDisk-style family inheritance:
 * <ul>
 *   <li>{@code scanRoot} → {@link ColorScheme#surface()} (used by the sunburst hub).</li>
 *   <li>File sectors (large files, "Smaller files") → grey via {@link SectorPalette#forFileSector}, regardless of
 *       family.</li>
 *   <li>Family root (immediate child of {@code scanRoot}) → palette pick by name with collision avoidance, so two
 *       top-level siblings whose names hash to the same bucket don't render in the same colour.</li>
 *   <li>Deeper descendants → parent's colour with a small hue shift, mild saturation pull-back, and lightening
 *       proportional to sibling rank + shrink fraction. The rank-0 child stays close to its parent (the "trunk"
 *       continuation); higher-rank siblings drift lighter and slightly hue-shifted, creating the soft halo at the
 *       rim.</li>
 * </ul>
 *
 * <p>Caches the computed colour per node so a single render doesn't recompute the recursive parent chain N times.
 * The cache is invalidated when the scan completes (so final size-order ranks supersede mid-scan ones) and on
 * rescan.</p>
 */
public final class NodeColorResolverImpl implements NodeColorResolver {

	private final ColorScheme scheme;
	/**
	 * Sibling rank lookup. Supplied by the host because it shares a cache with the sunburst layout, which clears
	 * its per-render cache at the start of every paint. Keeping rank lookup external avoids two competing caches
	 * for the same data.
	 */
	private final ToIntFunction<DirectoryNode> rankOf;

	private final Map<DirectoryNode, Color> colorCache = new IdentityHashMap<>();
	/**
	 * Maps each top-level family root to its allocated palette index. Allocation walks forward from
	 * {@code name.hashCode() % paletteSize} to the first unclaimed bucket — keeps two top-level siblings whose
	 * names hash identically from rendering in the same colour (e.g. "System" and "Applications" both hash to idx
	 * 11 on JDK 25).
	 */
	private final Map<DirectoryNode, Integer> topLevelPaletteIdx = new IdentityHashMap<>();
	/**
	 * Top-level folders whose descendants have already been "stabilised" (their cached colours dropped on the
	 * tick the folder finished). Membership prevents the stabilisation from re-running every tick.
	 */
	private final Set<DirectoryNode> finalizedTopLevels =
			Collections.newSetFromMap(new IdentityHashMap<DirectoryNode, Boolean>());

	private DirectoryNode scanRoot;
	private DirectoryNode hiddenNode;

	public NodeColorResolverImpl(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf) {
		this.scheme = scheme;
		this.rankOf = rankOf;
	}

	/** Update the scan root reference. Clears all caches because family roots and ranks now resolve differently. */
	public void setScanRoot(DirectoryNode newScanRoot) {
		this.scanRoot = newScanRoot;
		colorCache.clear();
		topLevelPaletteIdx.clear();
		finalizedTopLevels.clear();
	}

	/** Update the synthetic "Hidden" node reference so it can be excluded from per-tick stabilisation. */
	public void setHiddenNode(DirectoryNode newHiddenNode) {
		this.hiddenNode = newHiddenNode;
	}

	/**
	 * Called when a scan completes: drop colours and the top-level palette allocation so the final size-order
	 * picks the palette indices in size-descending order. A node briefly cached as rank-0 stays cached as rank-0
	 * unless we invalidate; same for the top-level palette allocation.
	 */
	public void onScanComplete() {
		colorCache.clear();
		topLevelPaletteIdx.clear();
	}

	/**
	 * Per-tick maintenance for live scans. Detect any top-level folder that just transitioned to {@code DONE} and
	 * drop its descendants' cached colours so the next render derives them against the now-final sort order. The
	 * top-level node itself stays cached because its colour is hash-based via {@link #allocateTopLevelIdx}, not
	 * rank-based — it doesn't shift during the scan.
	 */
	public void stabilizeFinalizedTopLevels() {
		if (scanRoot == null)
			return;
		for (DirectoryNode c : scanRoot.children()) {
			if (c == hiddenNode)
				continue;
			if (c.isDone() && finalizedTopLevels.add(c)) {
				clearDescendantColors(c);
			}
		}
	}

	@Override
	public Color colorFor(DirectoryNode node) {
		if (node == null || node == scanRoot)
			return scheme.surface();
		Color cached = colorCache.get(node);
		if (cached != null)
			return cached;

		Color computed;
		if (node.isFileSector()) {
			int d = depthFromScanRoot(node);
			computed = SectorPalette.forFileSector(node.name(), Math.max(0, d - 1));
		} else if (node.parent() == scanRoot || node.parent() == null) {
			// Family root — palette pick by name with collision avoidance.
			if ("Hidden".equals(node.name())) {
				// Hidden has its own reserved grey via SectorPalette.forName.
				computed = SectorPalette.forName("Hidden", 0);
			} else {
				computed = SectorPalette.atIndex(allocateTopLevelIdx(node), 0);
			}
		} else {
			// Drive lightness off how much the child shrinks relative to its parent, not off depth. A child that
			// takes ~100% of its parent (a true "trunk" continuation) keeps the parent's colour exactly — without
			// this the colour washes toward white in deep single-folder chains. Side branches with low fraction
			// lighten and hue-shift more, which is what creates the soft halo at the rim.
			Color parentColor = colorFor(node.parent());
			int rank = rankOf.applyAsInt(node);
			DirectoryNode p = node.parent();
			long parentBytes = p.totalBytes();
			double frac = parentBytes > 0 ? Math.min(1.0, (double) node.totalBytes() / parentBytes) : 1.0;
			double shrink = Math.max(0.0, 1.0 - frac);
			// Trunk vs branch — two different regimes:
			//
			//  * Rank 0 (trunk): the largest-descendant chain. Apply a small baseline darkening (5%) plus
			//    shrink-proportional darkening, so deep trunks visibly deepen ring-by-ring like roots into ground,
			//    even when each step takes ~100% of its parent. Saturation stays close to the parent's so warm
			//    colours don't go muddy.
			//
			//  * Rank ≥ 1 (side branches): brightness has hard clipping at 1.0, so once brightFactor pushes past
			//    that, additional lightening does nothing. Push saturation DOWN aggressively instead — that's what
			//    makes a branch read as "pale / washed-out" relative to its parent rather than just "still
			//    saturated yellow." Combined with the brightness lift, the result is the cream/pastel halo at the
			//    rim.
			double brightFactor;
			double satFactor;
			if (rank == 0) {
				brightFactor = Math.max(0.65, 0.95 - shrink * 0.20);
				satFactor = Math.max(0.88, 1.0 - shrink * 0.04);
			} else {
				brightFactor = Math.min(1.35, 1.0 + shrink * 0.20 + rank * 0.05);
				satFactor = Math.max(0.30, 1.0 - shrink * 0.40 - rank * 0.04);
			}
			double hueShift = Math.min(20.0, rank * 5.0);
			if (rank == 0) {
				// Yellow-family trunks read as muddy/olive when darkened straight down. Pull the hue toward red
				// (-8° at full shrink) so darker yellows go amber/orange — what the eye expects from "shaded
				// yellow" in nature.
				double pHue = parentColor.getHue();
				if (pHue >= 30 && pHue <= 90) {
					hueShift -= shrink * 8.0;
				}
			}
			computed = parentColor.deriveColor(hueShift, satFactor, brightFactor, 1.0);
		}
		colorCache.put(node, computed);
		return computed;
	}

	/**
	 * Returns the palette index this top-level family will use, allocating on first access. Starts from
	 * {@code name.hashCode() % paletteSize} and walks forward to the first index not already claimed by a
	 * previously-allocated sibling — so two top-level siblings whose names happen to hash to the same bucket
	 * can't render identical.
	 */
	private int allocateTopLevelIdx(DirectoryNode node) {
		Integer cached = topLevelPaletteIdx.get(node);
		if (cached != null)
			return cached;
		int n = SectorPalette.paletteSize();
		Set<Integer> used = new HashSet<>(topLevelPaletteIdx.values());
		int idx = Math.floorMod(node.name().hashCode(), n);
		int tries = 0;
		while (used.contains(idx) && tries < n) {
			idx = (idx + 1) % n;
			tries++;
		}
		topLevelPaletteIdx.put(node, idx);
		return idx;
	}

	private int depthFromScanRoot(DirectoryNode node) {
		int d = 0;
		for (DirectoryNode n = node; n != null && n != scanRoot; n = n.parent())
			d++;
		return d;
	}

	private void clearDescendantColors(DirectoryNode root) {
		Deque<DirectoryNode> stack = new ArrayDeque<>();
		for (DirectoryNode c : root.children())
			stack.push(c);
		while (!stack.isEmpty()) {
			DirectoryNode n = stack.pop();
			colorCache.remove(n);
			for (DirectoryNode c : n.children())
				stack.push(c);
		}
	}
}
