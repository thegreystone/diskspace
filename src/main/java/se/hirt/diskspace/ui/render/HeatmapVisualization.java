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

import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.ui.SizeFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Heatmap (squarified-treemap) visualization. Renders the directory tree as nested rectangles whose area is
 * proportional to {@code totalBytes()}, packed via the Bruls/Huijgen/van Wijk squarify algorithm to keep aspect ratios
 * close to square.
 * <p>Drill-in / drill-out transitions are animated: top-level cells from the old layout shrink and fade in the first
 * half of the transition, then top-level cells from the new layout grow and appear in the second half.</p>
 */
public final class HeatmapVisualization implements Visualization {

	private static final double HEATMAP_TOP_INSET = 36.0;
	private static final double HEATMAP_MIN_RECURSE_PX = 12.0;
	private static final double HEATMAP_LABEL_MIN_W = 100.0;
	private static final double HEATMAP_LABEL_MIN_H = 24.0;
	private static final double HEATMAP_INNER_PAD = 2.0;
	private static final long ANIM_DURATION_NANOS = 350_000_000L;

	private VisualizationHost host;
	/** Hit-test cache, populated during render in painter order; consumed by {@link #hitTest} in reverse. */
	private final List<RectHit> rects = new ArrayList<>();

	// ---- animation state ------------------------------------------------

	private boolean animating;
	private long animStartNanos;
	/** Top-level cell positions captured before the drill. */
	private List<LayoutCell> animOldCells = List.of();
	/** Top-level cell positions for the new layout; null until computed on the first animation frame. */
	private List<LayoutCell> animNewCells;
	/**
	 * For drill-in: the old canvas-space rectangle of the cell that was clicked (we expand from it). For drill-out:
	 * null (we collapse old cells into the parent's new position instead).
	 */
	private double[] animDrillRect;   // {x, y, w, h} or null
	/** The node we came FROM (old viewRoot) — used in drill-out to find the target rect in the new layout. */
	private DirectoryNode animFromNode;

	private final AnimationTimer animTimer = new AnimationTimer() {
		@Override
		public void handle(long now) {
			if (now - animStartNanos >= ANIM_DURATION_NANOS) {
				animating = false;
				stop();
			}
			if (host != null)
				host.requestRedraw("heatmap-anim");
		}
	};

	@Override
	public void attach(VisualizationHost host) {
		this.host = host;
	}

	@Override
	public void shutdown() {
		animTimer.stop();
		animating = false;
	}

	@Override
	public boolean isAnimating() {
		return animating;
	}

	@Override
	public void layoutWillChange() {
		if (rects.isEmpty())
			return;
		animOldCells = rectsToLayoutCells();
		animNewCells = null;
		animDrillRect = null;
		animFromNode = null;
		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
	}

	@Override
	public void viewRootChanged(DirectoryNode previous, DirectoryNode current) {
		if (previous == null || current == null || previous == current)
			return;
		animOldCells = rectsToLayoutCells();
		animNewCells = null;
		animFromNode = previous;
		// Drill-in: find current (= new viewRoot) in the old rects — it was a visible cell.
		animDrillRect = null;
		for (RectHit r : rects) {
			if (r.node() == current) {
				animDrillRect = new double[] {r.x(), r.y(), r.w(), r.h()};
				break;
			}
		}
		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
	}

	@Override
	public void render(GraphicsContext g, double w, double h, RenderContext ctx) {
		rects.clear();
		if (ctx.scanRoot() == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}
		try {
			if (animating) {
				drawAnimatedFrame(g, w, h, ctx);
			} else {
				drawHeatmap(g, w, h, ctx);
				drawHoverOverlay(g, w, h, ctx);
			}
		} catch (RuntimeException ex) {
			drawCenterText(g, w / 2, h / 2, "Heatmap render error — see logs");
			throw ex;
		}
	}

	@Override
	public HitResult hitTest(double mx, double my) {
		// Iterate in reverse so deeper rects (added later by recursion) win the hit — nested children draw on top
		// of their parent, so the topmost one is the one the user sees under the cursor.
		for (int i = rects.size() - 1; i >= 0; i--) {
			RectHit r = rects.get(i);
			if (r.contains(mx, my)) {
				if (r.node() != null)
					return new HitResult.OnNode(r.node());
				if (r.unaccounted())
					return HitResult.Special.UNACCOUNTED;
				if (r.freeSpace())
					return HitResult.Special.FREE_SPACE;
				return HitResult.Special.NONE;
			}
		}
		return HitResult.Special.NONE;
	}

	// ---- animation --------------------------------------------------------

	private void drawAnimatedFrame(GraphicsContext g, double canvasW, double canvasH, RenderContext ctx) {
		if (animNewCells == null)
			animNewCells = computeFullLayout(ctx, canvasW, canvasH);

		double elapsed = System.nanoTime() - animStartNanos;
		double t = smoothStep(Math.min(1.0, elapsed / (double) ANIM_DURATION_NANOS));

		double availH = canvasH - HEATMAP_TOP_INSET;

		if (animDrillRect == null && animFromNode == null) {
			// ---- Layout change (same viewRoot, e.g. hide-free-space toggle) --------
			// Build a key→cell map for both old and new so we can match by identity.
			java.util.Map<Object, LayoutCell> oldByKey = buildCellMap(animOldCells);
			java.util.Map<Object, LayoutCell> newByKey = buildCellMap(animNewCells);

			// Disappearing cells (e.g. free-space being hidden): shrink in place.
			for (LayoutCell old : animOldCells) {
				if (old.node() != null && old.node().parent() != null)
					continue; // only top-level
				if (!newByKey.containsKey(cellKey(old))) {
					double cx = old.x() + old.w() / 2, cy = old.y() + old.h() / 2;
					drawCellAlpha(g, old, lerp(old.x(), cx, t), lerp(old.y(), cy, t), lerp(old.w(), 0, t),
							lerp(old.h(), 0, t), 1.0 - t);
				}
			}
			// All new cells: lerp from old position (if matched) or grow from centroid.
			for (LayoutCell newCell : animNewCells) {
				LayoutCell oldCell = oldByKey.get(cellKey(newCell));
				double sx = oldCell != null ? oldCell.x() : newCell.x() + newCell.w() / 2;
				double sy = oldCell != null ? oldCell.y() : newCell.y() + newCell.h() / 2;
				double sw = oldCell != null ? oldCell.w() : 0;
				double sh = oldCell != null ? oldCell.h() : 0;
				drawCell(g, newCell, lerp(sx, newCell.x(), t), lerp(sy, newCell.y(), t), lerp(sw, newCell.w(), t),
						lerp(sh, newCell.h(), t));
			}
		} else if (animDrillRect != null) {
			// ---- Drill-in ------------------------------------------------
			// Treat the drill rect as the new viewport. Old sibling cells are pushed off the
			// canvas edges by the same zoom that brings new cells in from inside the drill rect.
			double dx = animDrillRect[0], dy = animDrillRect[1], dw = animDrillRect[2], dh = animDrillRect[3];

			// Old cells (siblings + the drill node itself): apply the forward zoom — they fly off
			// the canvas edges as the drill rect expands. The drill node's children (new cells)
			// replace it, so we don't draw the drill node separately.
			for (LayoutCell cell : animOldCells) {
				double endX = dx == 0 ? 0 : (cell.x() - dx) / dw * canvasW;
				double endY = HEATMAP_TOP_INSET + (cell.y() - dy) / dh * availH;
				double endW = cell.w() / dw * canvasW;
				double endH = cell.h() / dh * availH;
				drawCell(g, cell, lerp(cell.x(), endX, t), lerp(cell.y(), endY, t), lerp(cell.w(), endW, t),
						lerp(cell.h(), endH, t));
			}
			// New cells: grow from their position inside the old drill rect to fill the canvas.
			for (LayoutCell cell : animNewCells) {
				double startX = dx + (cell.x() / canvasW) * dw;
				double startY = dy + ((cell.y() - HEATMAP_TOP_INSET) / availH) * dh;
				double startW = (cell.w() / canvasW) * dw;
				double startH = (cell.h() / availH) * dh;
				drawCell(g, cell, lerp(startX, cell.x(), t), lerp(startY, cell.y(), t), lerp(startW, cell.w(), t),
						lerp(startH, cell.h(), t));
			}
		} else {
			// ---- Drill-out -----------------------------------------------
			// Find the parent rect in the new layout (where animFromNode will live).
			double px = 0, py = HEATMAP_TOP_INSET, pw = canvasW, ph = availH;
			for (LayoutCell cell : animNewCells) {
				if (cell.node() == animFromNode) {
					px = cell.x();
					py = cell.y();
					pw = cell.w();
					ph = cell.h();
					break;
				}
			}
			final double fpx = px, fpy = py, fpw = pw, fph = ph;

			// Old cells collapse into the parent rect using the inverse zoom.
			for (LayoutCell cell : animOldCells) {
				double endX = fpx + (cell.x() / canvasW) * fpw;
				double endY = fpy + ((cell.y() - HEATMAP_TOP_INSET) / availH) * fph;
				double endW = (cell.w() / canvasW) * fpw;
				double endH = (cell.h() / availH) * fph;
				drawCell(g, cell, lerp(cell.x(), endX, t), lerp(cell.y(), endY, t), lerp(cell.w(), endW, t),
						lerp(cell.h(), endH, t));
			}
			// New sibling cells (everything except animFromNode and its children) zoom in
			// from outside the parent rect.
			for (LayoutCell cell : animNewCells) {
				if (cell.node() == animFromNode)
					continue;
				double startX = fpx + (cell.x() / canvasW) * fpw;
				double startY = fpy + ((cell.y() - HEATMAP_TOP_INSET) / availH) * fph;
				double startW = (cell.w() / canvasW) * fpw;
				double startH = (cell.h() / availH) * fph;
				// Use the inverse zoom: siblings start at their "outside-parent" position.
				double zoomX = fpx == 0 ? 0 : (cell.x() - fpx) / fpw * canvasW;
				double zoomY = HEATMAP_TOP_INSET + (cell.y() - fpy) / fph * availH;
				double zoomW = cell.w() / fpw * canvasW;
				double zoomH = cell.h() / fph * availH;
				drawCell(g, cell, lerp(zoomX, cell.x(), t), lerp(zoomY, cell.y(), t), lerp(zoomW, cell.w(), t),
						lerp(zoomH, cell.h(), t));
			}
		}
	}

	private void drawCell(GraphicsContext g, LayoutCell cell, double x, double y, double w, double h) {
		drawCellAlpha(g, cell, x, y, w, h, 1.0);
	}

	private void drawCellAlpha(
			GraphicsContext g, LayoutCell cell, double x, double y, double w, double h, double alpha) {
		if (w < 0.5 || h < 0.5 || alpha <= 0.01)
			return;
		Color base = cell.color();
		Color fill = alpha >= 1.0 ? base
				: new Color(base.getRed(), base.getGreen(), base.getBlue(), base.getOpacity() * alpha);
		g.setFill(fill);
		g.fillRect(x, y, w, h);
		if (w > 1.5 && h > 1.5) {
			Color bg = host.scheme().background();
			g.setStroke(alpha >= 1.0 ? bg : new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), alpha));
			g.setLineWidth(1.0);
			g.strokeRect(x + 0.5, y + 0.5, Math.max(0, w - 1), Math.max(0, h - 1));
		}
		if (w >= HEATMAP_LABEL_MIN_W && h >= HEATMAP_LABEL_MIN_H) {
			String name = cell.freeSpace() ? "Free"
					: cell.unaccounted() ? "Other" : cell.node() != null ? cell.node().name() : null;
			if (name != null) {
				long bytes = cell.node() != null ? cell.node().totalBytes() : cell.bytes();
				Color tc = textOn(base);
				g.setFill(alpha >= 1.0 ? tc : new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), alpha));
				g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
				g.setTextAlign(TextAlignment.LEFT);
				g.setTextBaseline(VPos.TOP);
				String suf = "  " + SizeFormat.format(bytes);
				int chars = Math.max(2, (int) ((w - 12) / 6.5) - suf.length());
				g.fillText(truncate(name, chars) + suf, x + 6, y + 4, Math.max(0, w - 12));
			}
		}
	}

	private static Object cellKey(LayoutCell c) {
		if (c.node() != null)
			return c.node();
		if (c.freeSpace())
			return "FREE";
		return "UNACCOUNTED";
	}

	private static java.util.Map<Object, LayoutCell> buildCellMap(List<LayoutCell> cells) {
		java.util.Map<Object, LayoutCell> map = new java.util.LinkedHashMap<>();
		for (LayoutCell c : cells)
			map.putIfAbsent(cellKey(c), c);
		return map;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/**
	 * Compute every visible cell position (all depths) without drawing. Mirrors the full recursive logic of
	 * {@link #drawHeatmap} + {@link #drawTreemapCell}.
	 */
	private List<LayoutCell> computeFullLayout(RenderContext ctx, double canvasW, double canvasH) {
		DirectoryNode viewRoot = ctx.viewRoot();
		if (viewRoot == null)
			return List.of();
		double x = 0, y = HEATMAP_TOP_INSET;
		double availW = canvasW, availH = canvasH - HEATMAP_TOP_INSET;
		if (availW < 4 || availH < 4)
			return List.of();

		List<TreemapItem> items = buildTopLevelTreemapItems(ctx);
		long totalBytes = 0;
		for (TreemapItem it : items)
			totalBytes += Math.max(0, it.bytes());
		if (totalBytes <= 0 || items.isEmpty())
			return List.of();

		double scale = (availW * availH) / (double) totalBytes;
		List<LayoutCell> out = new ArrayList<>();

		TreemapItem freeItem = null;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).freeSpace()) {
				freeItem = items.remove(i);
				break;
			}
		}
		if (freeItem != null && freeItem.bytes() > 0) {
			double freeArea = freeItem.bytes() * scale;
			double freeW = Math.min(availW, freeArea / availH);
			if (freeW >= 1) {
				double freeX = x + availW - freeW;
				collectCell(freeItem, freeX, y, freeW, availH, 0, out, ctx);
				availW -= freeW;
			}
		}
		if (availW >= 4 && !items.isEmpty()) {
			items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
			squarifyCollect(items, x, y, availW, availH, scale, 0, out, ctx);
		}
		return out;
	}

	private void squarifyCollect(
			List<TreemapItem> items, double x, double y, double w, double h, double scale,
			int depth, List<LayoutCell> out, RenderContext ctx) {
		if (items.isEmpty() || w < 1 || h < 1)
			return;
		List<TreemapItem> remaining = new ArrayList<>(items);
		while (!remaining.isEmpty() && w >= 1 && h >= 1) {
			double shortSide = Math.min(w, h);
			List<TreemapItem> row = new ArrayList<>();
			double rowSum = 0, rowMin = Double.POSITIVE_INFINITY, rowMax = 0;
			while (!remaining.isEmpty()) {
				TreemapItem next = remaining.get(0);
				double nextArea = Math.max(0, next.bytes()) * scale;
				if (nextArea <= 0) {
					remaining.remove(0);
					continue;
				}
				double trialSum = rowSum + nextArea;
				double trialMin = Math.min(rowMin, nextArea);
				double trialMax = Math.max(rowMax, nextArea);
				double curWorst =
						row.isEmpty() ? Double.POSITIVE_INFINITY : worstAspect(rowSum, rowMin, rowMax, shortSide);
				if (row.isEmpty() || worstAspect(trialSum, trialMin, trialMax, shortSide) <= curWorst) {
					row.add(next);
					rowSum = trialSum;
					rowMin = trialMin;
					rowMax = trialMax;
					remaining.remove(0);
				} else
					break;
			}
			if (row.isEmpty())
				break;
			double thickness = rowSum / shortSide;
			boolean along = w < h;
			double rw = along ? w : thickness, rh = along ? thickness : h;
			double offset = 0;
			double rs = rowSum;
			for (TreemapItem t : row) {
				double frac = Math.max(0, t.bytes()) * scale / rs;
				double rx = along ? x + offset : x;
				double ry = along ? y : y + offset;
				double tw = along ? rw * frac : rw;
				double th = along ? rh : rh * frac;
				collectCell(t, rx, ry, tw, th, depth, out, ctx);
				if (along)
					offset += tw;
				else
					offset += th;
			}
			if (along) {
				y += thickness;
				h -= thickness;
			} else {
				x += thickness;
				w -= thickness;
			}
		}
	}

	private void collectCell(
			TreemapItem item, double x, double y, double w, double h, int depth, List<LayoutCell> out,
			RenderContext ctx) {
		if (w < 1.0 || h < 1.0)
			return;
		out.add(new LayoutCell(item.node(), x, y, w, h, item.color(), item.bytes(), item.unaccounted(),
				item.freeSpace()));

		if (item.node() == null || item.node().isFileSector() || item.node().children().isEmpty())
			return;
		double childX = x + HEATMAP_INNER_PAD, childY = y + HEATMAP_INNER_PAD;
		double childW = w - 2 * HEATMAP_INNER_PAD, childH = h - 2 * HEATMAP_INNER_PAD;
		if (w >= HEATMAP_LABEL_MIN_W && h >= HEATMAP_LABEL_MIN_H) {
			double labelBand = 16;
			if (childH > labelBand + HEATMAP_MIN_RECURSE_PX) {
				childY += labelBand;
				childH -= labelBand;
			}
		}
		if (childW < HEATMAP_MIN_RECURSE_PX || childH < HEATMAP_MIN_RECURSE_PX)
			return;

		List<DirectoryNode> kids = item.node().children();
		List<TreemapItem> childItems = new ArrayList<>(kids.size());
		long childTotal = 0;
		for (DirectoryNode k : kids) {
			long b = k.totalBytes();
			if (b <= 0)
				continue;
			childItems.add(new TreemapItem(k, b, host.colors().colorFor(k), false, false));
			childTotal += b;
		}
		if (childTotal > 0 && !childItems.isEmpty()) {
			childItems.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
			squarifyCollect(childItems, childX, childY, childW, childH, (childW * childH) / (double) childTotal,
					depth + 1, out, ctx);
		}
	}

	/** Snapshot every visible cell (all depths) from the current {@link #rects} hit-test cache. */
	private List<LayoutCell> rectsToLayoutCells() {
		List<LayoutCell> out = new ArrayList<>(rects.size());
		for (RectHit r : rects)
			out.add(new LayoutCell(r.node(), r.x(), r.y(), r.w(), r.h(), colorForRect(r), 0, r.unaccounted(),
					r.freeSpace()));
		return out;
	}

	private Color colorForRect(RectHit r) {
		if (r.freeSpace())
			return host.scheme().capacityTrack();
		if (r.unaccounted())
			return host.scheme().surface().brighter();
		if (r.node() != null)
			return host.colors().colorFor(r.node());
		return host.scheme().surface();
	}

	private static double smoothStep(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	// ---- drawing --------------------------------------------------------

	private void drawHeatmap(GraphicsContext g, double w, double h, RenderContext ctx) {
		DirectoryNode viewRoot = ctx.viewRoot();
		if (viewRoot == null)
			return;
		double x = 0;
		double y = HEATMAP_TOP_INSET;
		double availW = w;
		double availH = h - HEATMAP_TOP_INSET;
		if (availW < 4 || availH < 4)
			return;

		List<TreemapItem> items = buildTopLevelTreemapItems(ctx);
		long totalBytes = 0;
		for (TreemapItem it : items)
			totalBytes += Math.max(0, it.bytes());
		if (totalBytes <= 0 || items.isEmpty()) {
			drawCenterText(g, w / 2.0, y + availH / 2.0, ctx.scanning() ? "Scanning…" : "Empty");
			return;
		}

		double scale = (availW * availH) / (double) totalBytes;

		// Free space is pinned to a fixed strip on the right so it doesn't shuffle with the squarified layout —
		// visual convention from capacity bars, and otherwise reads as buggy when the largest item lands on the
		// left.
		TreemapItem freeItem = null;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).freeSpace()) {
				freeItem = items.remove(i);
				break;
			}
		}
		if (freeItem != null && freeItem.bytes() > 0) {
			double freeArea = freeItem.bytes() * scale;
			double freeW = Math.min(availW, freeArea / availH);
			if (freeW >= 1) {
				double freeX = x + availW - freeW;
				drawTreemapCell(g, freeItem, freeX, y, freeW, availH, 0, ctx);
				availW -= freeW;
			}
		}

		if (availW < 4 || items.isEmpty())
			return;

		// Stable sort desc; ties keep declaration order so the unaccounted virtual entry stays positioned
		// predictably relative to its siblings.
		items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
		squarify(g, items, x, y, availW, availH, scale, 0, ctx);
	}

	private List<TreemapItem> buildTopLevelTreemapItems(RenderContext ctx) {
		DirectoryNode viewRoot = ctx.viewRoot();
		NodeColorResolver colors = host.colors();
		List<TreemapItem> items = new ArrayList<>();
		for (DirectoryNode child : viewRoot.children()) {
			items.add(new TreemapItem(child, child.totalBytes(), colors.colorFor(child), false, false));
		}
		Volume target = ctx.target();
		if (viewRoot == ctx.scanRoot() && target.totalBytes() > 0) {
			long unaccounted = Math.max(0L, target.usedBytes() - viewRoot.totalBytes());
			if (unaccounted > 0) {
				items.add(new TreemapItem(null, unaccounted, host.scheme().surface().brighter(), true, false));
			}
			// Skip the free-space cell when hide-free-space (H) is on. The remaining items keep their normal
			// squarified layout; with no freeItem to pull out, drawHeatmap's right-strip pinning is a no-op and
			// the data fills the full canvas width.
			if (!ctx.hideFreeSpace()) {
				long free = Math.max(0L, target.usableBytes());
				if (free > 0) {
					items.add(new TreemapItem(null, free, host.scheme().capacityTrack(), false, true));
				}
			}
		}
		return items;
	}

	/**
	 * Squarified treemap (Bruls/Huijgen/van Wijk). Items are pixel-area-scaled via {@code scale}. Walks items in
	 * size-desc order, packing them into rows along the rectangle's short side until adding the next item would worsen
	 * the row's worst aspect ratio, then commits the row and continues on the remaining strip.
	 */
	private void squarify(
			GraphicsContext g, List<TreemapItem> items, double x, double y, double w, double h,
			double scale, int depth, RenderContext ctx) {
		if (items.isEmpty() || w < 1 || h < 1)
			return;
		List<TreemapItem> remaining = new ArrayList<>(items);
		while (!remaining.isEmpty() && w >= 1 && h >= 1) {
			double shortSide = Math.min(w, h);
			List<TreemapItem> row = new ArrayList<>();
			double rowSum = 0;
			double rowMin = Double.POSITIVE_INFINITY;
			double rowMax = 0;
			while (!remaining.isEmpty()) {
				TreemapItem next = remaining.get(0);
				double nextArea = Math.max(0, next.bytes()) * scale;
				if (nextArea <= 0) {
					remaining.remove(0);
					continue;
				}
				double trialSum = rowSum + nextArea;
				double trialMin = Math.min(rowMin, nextArea);
				double trialMax = Math.max(rowMax, nextArea);
				double currentWorst =
						row.isEmpty() ? Double.POSITIVE_INFINITY : worstAspect(rowSum, rowMin, rowMax, shortSide);
				double trialWorst = worstAspect(trialSum, trialMin, trialMax, shortSide);
				if (row.isEmpty() || trialWorst <= currentWorst) {
					row.add(next);
					rowSum = trialSum;
					rowMin = trialMin;
					rowMax = trialMax;
					remaining.remove(0);
				} else {
					break;
				}
			}
			if (row.isEmpty())
				break;
			double thickness = rowSum / shortSide;
			boolean rowAlongTop = w < h;
			if (rowAlongTop) {
				layoutRow(g, row, x, y, w, thickness, scale, true, depth, ctx);
				y += thickness;
				h -= thickness;
			} else {
				layoutRow(g, row, x, y, thickness, h, scale, false, depth, ctx);
				x += thickness;
				w -= thickness;
			}
		}
	}

	private static double worstAspect(double sum, double min, double max, double shortSide) {
		if (sum <= 0 || shortSide <= 0)
			return Double.POSITIVE_INFINITY;
		double s2 = sum * sum;
		double w2 = shortSide * shortSide;
		return Math.max((w2 * max) / s2, s2 / (w2 * min));
	}

	private void layoutRow(
			GraphicsContext g, List<TreemapItem> row, double x, double y, double w, double h,
			double scale, boolean rowAlongTop, int depth, RenderContext ctx) {
		double rowSum = 0;
		for (TreemapItem t : row)
			rowSum += Math.max(0, t.bytes()) * scale;
		if (rowSum <= 0)
			return;
		double offset = 0;
		for (TreemapItem t : row) {
			double area = Math.max(0, t.bytes()) * scale;
			double frac = area / rowSum;
			double rx, ry, rw, rh;
			if (rowAlongTop) {
				rx = x + offset;
				ry = y;
				rw = w * frac;
				rh = h;
				offset += rw;
			} else {
				rx = x;
				ry = y + offset;
				rw = w;
				rh = h * frac;
				offset += rh;
			}
			drawTreemapCell(g, t, rx, ry, rw, rh, depth, ctx);
		}
	}

	private void drawTreemapCell(
			GraphicsContext g, TreemapItem item, double x, double y, double w, double h, int depth,
			RenderContext ctx) {
		// Sub-pixel cull. Anything thinner than 1 px on either axis can't render visibly (Canvas's fillRect will
		// antialias to nothing) and we'd still pay for getNodeColor, hover-state derivation, fillRect, and a
		// rects.add hit-test entry. JFR flagged the recursive squarify/drawTreemapCell chain as the FX-thread
		// CPU dominant on big trees; this is the cheapest and biggest cut to its max-render time.
		if (w < 1.0 || h < 1.0)
			return;

		Color base = item.color();
		boolean hovered = false;
		DirectoryNode hoverNode = ctx.hoverNode();
		if (item.node() != null && hoverNode == item.node()) {
			base = base.deriveColor(0, 1.20, 0.85, 1.0);
			hovered = true;
		} else if (item.freeSpace() && ctx.hoveringFreeSpace()) {
			base = host.scheme().capacityTrack().brighter();
			hovered = true;
		} else if (item.unaccounted() && ctx.hoveringUnaccounted()) {
			base = host.scheme().surface().brighter().brighter();
			hovered = true;
		}
		double alpha = (item.node() == null || item.node().isDone()) ? 1.0 : 0.45;
		if (hovered)
			alpha = Math.min(1.0, alpha + 0.10);
		// Avoid Color.deriveColor() here: it always does RGB→HSB→RGB even when hue/sat/bright are no-ops (the
		// 0,1,1 args), allocating a fresh Color per cell. JFR flagged this as the dominant per-cell cost on
		// million-cell heatmap renders. Two cheap fast paths:
		//   - alpha == 1.0 (post-scan, common case): base is already opaque, reuse it as-is.
		//   - alpha < 1.0: direct constructor does the same thing as deriveColor's alpha-only path without the
		//     HSB roundtrip.
		Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);

		g.setFill(fill);
		g.fillRect(x, y, w, h);

		if (w > 1.5 && h > 1.5) {
			g.setStroke(host.scheme().background());
			g.setLineWidth(1.0);
			g.strokeRect(x + 0.5, y + 0.5, Math.max(0, w - 1), Math.max(0, h - 1));
		}

		rects.add(new RectHit(item.node(), x, y, w, h, item.unaccounted(), item.freeSpace()));

		boolean recursable = item.node() != null && !item.node().isFileSector() && !item.node().children().isEmpty();
		double childX = x + HEATMAP_INNER_PAD;
		double childY = y + HEATMAP_INNER_PAD;
		double childW = w - 2 * HEATMAP_INNER_PAD;
		double childH = h - 2 * HEATMAP_INNER_PAD;

		if (w >= HEATMAP_LABEL_MIN_W && h >= HEATMAP_LABEL_MIN_H && (item.node() != null || item.freeSpace() || item.unaccounted())) {
			String name;
			long bytes;
			if (item.node() != null) {
				name = item.node().name();
				bytes = item.node().totalBytes();
			} else if (item.freeSpace()) {
				name = "Free";
				bytes = ctx.target().usableBytes();
			} else {
				name = "Other";
				bytes = item.bytes();
			}
			drawTreemapLabel(g, x, y, w, name, bytes, base);
			if (recursable) {
				double labelBand = 16;
				if (childH > labelBand + HEATMAP_MIN_RECURSE_PX) {
					childY += labelBand;
					childH -= labelBand;
				}
			}
		}

		if (recursable && childW >= HEATMAP_MIN_RECURSE_PX && childH >= HEATMAP_MIN_RECURSE_PX) {
			List<DirectoryNode> kids = item.node().children();
			NodeColorResolver colors = host.colors();
			List<TreemapItem> childItems = new ArrayList<>(kids.size());
			long childTotal = 0;
			for (DirectoryNode k : kids) {
				long b = k.totalBytes();
				if (b <= 0)
					continue;
				childItems.add(new TreemapItem(k, b, colors.colorFor(k), false, false));
				childTotal += b;
			}
			if (childTotal > 0 && !childItems.isEmpty()) {
				childItems.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
				double childScale = (childW * childH) / (double) childTotal;
				squarify(g, childItems, childX, childY, childW, childH, childScale, depth + 1, ctx);
			}
		}
	}

	private void drawTreemapLabel(
			GraphicsContext g, double x, double y, double w, String name, long bytes,
			Color fillBase) {
		Color textColor = textOn(fillBase);
		g.setFill(textColor);
		g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
		g.setTextAlign(TextAlignment.LEFT);
		g.setTextBaseline(VPos.TOP);
		String size = SizeFormat.format(bytes);
		double padded = Math.max(0, w - 12);
		String sizeSuffix = "  " + size;
		int approxCharsForName = Math.max(2, (int) (padded / 6.5) - sizeSuffix.length());
		String shown = truncate(name, approxCharsForName) + sizeSuffix;
		g.fillText(shown, x + 6, y + 4, padded);
	}

	private void drawHoverOverlay(GraphicsContext g, double w, double h, RenderContext ctx) {
		String name;
		long bytes;
		DirectoryNode hoverNode = ctx.hoverNode();
		if (hoverNode != null) {
			name = hoverNode.name();
			bytes = hoverNode.totalBytes();
		} else if (ctx.hoveringFreeSpace()) {
			name = "Free";
			bytes = ctx.target().usableBytes();
		} else if (ctx.hoveringUnaccounted()) {
			name = "Other";
			DirectoryNode scanRoot = ctx.scanRoot();
			bytes = Math.max(0, ctx.target().usedBytes() - (scanRoot != null ? scanRoot.totalBytes() : 0));
		} else if (ctx.scanning() && ctx.progressPath() != null) {
			name = tailPath(ctx.progressPath());
			bytes = ctx.progressBytes();
		} else {
			return;
		}
		String text = truncate(name, 60) + "  —  " + SizeFormat.format(bytes);
		g.setFont(Font.font("Segoe UI", 11));
		double pad = 8;
		double textW = Math.min(w - 24, 6.5 * text.length() + 2 * pad);
		double boxH = 22;
		double boxX = 12;
		double boxY = h - boxH - 12;
		g.setFill(host.scheme().surface().deriveColor(0, 1, 1, 0.85));
		g.fillRoundRect(boxX, boxY, textW, boxH, 6, 6);
		g.setFill(host.scheme().textPrimary());
		g.setTextAlign(TextAlignment.LEFT);
		g.setTextBaseline(VPos.CENTER);
		g.fillText(text, boxX + pad, boxY + boxH / 2.0, textW - 2 * pad);
	}

	private void drawCenterText(GraphicsContext g, double cx, double cy, String text) {
		g.setFill(host.scheme().textMuted());
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.setFont(Font.font("Segoe UI", 13));
		g.fillText(text, cx, cy);
	}

	// ---- helpers --------------------------------------------------------

	private static Color textOn(Color bg) {
		double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
		return lum > 0.55 ? Color.gray(0.10) : Color.gray(0.95);
	}

	private static String truncate(String s, int max) {
		if (s == null)
			return "";
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
	}

	private static String tailPath(String p) {
		if (p == null)
			return "";
		int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
		return slash < 0 ? p : p.substring(slash + 1);
	}

	// ---- value types ----------------------------------------------------

	/**
	 * Hit-test rect for the heatmap. {@code node == null} means the rectangle is the free-space or unaccounted virtual
	 * entry at scan root.
	 */
	private record RectHit(DirectoryNode node, double x, double y, double w, double h, boolean unaccounted,
	                       boolean freeSpace) {
		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}

	/**
	 * Heatmap item — a directory child or a virtual entry (free / unaccounted) used while building the squarified
	 * layout. {@code bytes} drives the layout area; {@code color} is the resolved fill before alpha/hover modulation.
	 */
	private record TreemapItem(DirectoryNode node, long bytes, Color color, boolean unaccounted, boolean freeSpace) {
	}

	/** A top-level cell captured for animation — its position, color, and identity. */
	private record LayoutCell(DirectoryNode node, double x, double y, double w, double h, Color color, long bytes,
	                          boolean unaccounted, boolean freeSpace) {
	}
}
