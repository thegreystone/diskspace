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
 * <p>No animation — treemap layouts shuffle every rectangle on drill, so a coordinate-by-coordinate interpolation
 * doesn't visually map to anything coherent. {@link #viewRootChanged} is a no-op; {@link #isAnimating} always returns
 * {@code false}.</p>
 */
public final class HeatmapVisualization implements Visualization {

	private static final double HEATMAP_TOP_INSET = 36.0;
	private static final double HEATMAP_MIN_RECURSE_PX = 12.0;
	private static final double HEATMAP_LABEL_MIN_W = 100.0;
	private static final double HEATMAP_LABEL_MIN_H = 24.0;
	private static final double HEATMAP_INNER_PAD = 2.0;

	private VisualizationHost host;
	/** Hit-test cache, populated during render in painter order; consumed by {@link #hitTest} in reverse. */
	private final List<RectHit> rects = new ArrayList<>();

	@Override
	public void attach(VisualizationHost host) {
		this.host = host;
	}

	@Override
	public boolean isAnimating() {
		return false;
	}

	@Override
	public void viewRootChanged(DirectoryNode previous, DirectoryNode current) {
		// Treemap layouts shuffle every rectangle on drill, so a polar / coordinate lerp doesn't apply.
	}

	@Override
	public void render(GraphicsContext g, double w, double h, RenderContext ctx) {
		rects.clear();
		if (ctx.scanRoot() == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}
		try {
			drawHeatmap(g, w, h, ctx);
			drawHoverOverlay(g, w, h, ctx);
		} catch (RuntimeException ex) {
			// Wrap to keep a paint-time exception from bricking the live ticker. The host's higher-level wrapper
			// also catches, but mirroring the previous behaviour here lets the surrounding scene keep its sane
			// frame state.
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
			long free = Math.max(0L, target.usableBytes());
			if (free > 0) {
				items.add(new TreemapItem(null, free, host.scheme().capacityTrack(), false, true));
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
}
