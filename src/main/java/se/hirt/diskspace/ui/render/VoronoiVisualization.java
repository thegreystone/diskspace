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
import se.hirt.diskspace.ui.SizeFormat;
import se.hirt.diskspace.ui.VoronoiLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Voronoi (weighted power-diagram) treemap visualization. Renders the directory tree as a circular partition whose
 * cells have areas proportional to {@code totalBytes()}, computed by {@link VoronoiLayout}.
 * <p>No animation — Voronoi cell shapes shuffle entirely on drill, so coordinate interpolation is not coherent.
 * {@link #viewRootChanged} invalidates the layout cache; {@link #isAnimating} always returns {@code false}.</p>
 */
public final class VoronoiVisualization implements Visualization {

	private static final java.util.logging.Logger LOG =
			java.util.logging.Logger.getLogger(VoronoiVisualization.class.getName());

	private static final double TOP_INSET = 36.0;
	private static final int DISK_SIDES = 64;

	private VisualizationHost host;

	/** Hit-test cache, rebuilt each render when the layout cache is valid. */
	private List<CellHit> cellHits = List.of();

	/** Layout cache invalidation keys. */
	private DirectoryNode lastViewRoot;
	private double lastW, lastH;
	private long lastTotalBytes;
	private boolean lastHideFreeSpace;

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
		lastViewRoot = null;
	}

	@Override
	public void render(GraphicsContext g, double w, double h, RenderContext ctx) {
		if (ctx.scanRoot() == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}
		drawVoronoi(g, w, h, ctx);
		drawHoverOverlay(g, w, h, ctx);
	}

	@Override
	public HitResult hitTest(double x, double y) {
		for (CellHit hit : cellHits) {
			if (VoronoiLayout.contains(hit.polygon(), x, y)) {
				if (hit.node() != null)
					return new HitResult.OnNode(hit.node());
				if (hit.freeSpace())
					return HitResult.Special.FREE_SPACE;
				if (hit.unaccounted())
					return HitResult.Special.UNACCOUNTED;
				return HitResult.Special.NONE;
			}
		}
		return HitResult.Special.NONE;
	}

	// ---- drawing --------------------------------------------------------

	private void drawVoronoi(GraphicsContext g, double w, double h, RenderContext ctx) {
		DirectoryNode viewRoot = ctx.viewRoot();
		if (viewRoot == null)
			return;

		double cx = w / 2.0;
		double cy = (h + TOP_INSET) / 2.0;
		double radius = Math.min(w / 2.0, (h - TOP_INSET) / 2.0) - 12;
		if (radius < 40) {
			drawCenterText(g, w / 2.0, h / 2.0, "Window too small for Voronoi");
			return;
		}

		List<TreemapItem> items = buildTopLevelTreemapItems(ctx);
		long totalBytes = 0;
		for (TreemapItem it : items)
			totalBytes += Math.max(0, it.bytes());
		if (items.isEmpty() || totalBytes <= 0) {
			drawCenterText(g, w / 2.0, cy, ctx.scanning() ? "Scanning…" : "Empty");
			return;
		}

		// Key the cache on the sum of individual item bytes, not the root total.
		// viewRoot.totalBytes() stays constant when dedup or streaming redistributes bytes
		// across children; itemsBytesSum catches those per-item changes.
		if (viewRoot != lastViewRoot || w != lastW || h != lastH || totalBytes != lastTotalBytes
				|| ctx.hideFreeSpace() != lastHideFreeSpace) {
			cellHits = computeLayout(items, cx, cy, radius, totalBytes);
			lastViewRoot = viewRoot;
			lastW = w;
			lastH = h;
			lastTotalBytes = totalBytes;
			lastHideFreeSpace = ctx.hideFreeSpace();
		}

		g.setStroke(host.scheme().background().brighter());
		g.setLineWidth(1.0);
		g.strokeOval(cx - radius, cy - radius, 2 * radius, 2 * radius);

		for (CellHit hit : cellHits)
			drawCellFill(g, hit, ctx);
		for (CellHit hit : cellHits)
			drawSubCells(g, hit);
		for (CellHit hit : cellHits)
			drawCellStroke(g, hit);
		DirectoryNode hoverNode = ctx.hoverNode();
		if (hoverNode != null) {
			for (CellHit hit : cellHits) {
				if (hit.node() == hoverNode) {
					drawCellHover(g, hit);
					break;
				}
			}
		}
		for (CellHit hit : cellHits)
			drawCellLabel(g, hit, ctx);
	}

	private List<CellHit> computeLayout(List<TreemapItem> items, double cx, double cy, double radius, long totalBytes) {
		items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
		double[] weights = new double[items.size()];
		for (int i = 0; i < items.size(); i++)
			weights[i] = Math.max(1.0, items.get(i).bytes());
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, DISK_SIDES);
		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);
		List<CellHit> hits = new ArrayList<>(cells.size());
		if (LOG.isLoggable(java.util.logging.Level.FINE)) {
			double diskArea = VoronoiLayout.polygonArea(bounds);
			StringBuilder sb = new StringBuilder("Voronoi layout (totalBytes=")
					.append(SizeFormat.format(totalBytes)).append(", items=").append(items.size()).append("):\n");
			for (int i = 0; i < cells.size(); i++) {
				TreemapItem item = items.get(i);
				double cellArea = VoronoiLayout.polygonArea(cells.get(i).polygon());
				double targetArea = diskArea * weights[i] / totalBytes;
				String label = item.freeSpace() ? "Free" : item.unaccounted() ? "Unaccounted"
						: item.node() != null ? item.node().name() : "?";
				sb.append(String.format("  [%2d] %-35s bytes=%-12s weight=%-12.0f targetArea=%7.1f cellArea=%7.1f ratio=%.2f%n",
						i, label, SizeFormat.format(item.bytes()), weights[i], targetArea, cellArea,
						targetArea > 0 ? cellArea / targetArea : 0));
			}
			LOG.fine(sb.toString());
		}
		for (int i = 0; i < cells.size(); i++) {
			TreemapItem item = items.get(i);
			List<VoronoiLayout.Pt> poly = cells.get(i).polygon();
			List<CellHit> subCells = computeSubCells(item.node(), poly);
			hits.add(new CellHit(item.node(), poly, item.unaccounted(), item.freeSpace(), subCells));
		}
		return hits;
	}

	private List<CellHit> computeSubCells(DirectoryNode node, List<VoronoiLayout.Pt> parentPoly) {
		if (node == null || parentPoly.size() < 3)
			return List.of();
		List<DirectoryNode> children = node.children();
		if (children.size() < 2)
			return List.of();
		if (VoronoiLayout.polygonArea(parentPoly) < 400)
			return List.of();
		double[] weights = new double[children.size()];
		for (int i = 0; i < children.size(); i++)
			weights[i] = Math.max(1.0, children.get(i).totalBytes());
		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(parentPoly, weights);
		List<CellHit> sub = new ArrayList<>(cells.size());
		for (int i = 0; i < cells.size(); i++)
			sub.add(new CellHit(children.get(i), cells.get(i).polygon(), false, false, List.of()));
		return sub;
	}

	private void drawCellFill(GraphicsContext g, CellHit hit, RenderContext ctx) {
		List<VoronoiLayout.Pt> poly = hit.polygon();
		if (poly.size() < 3)
			return;
		double[] xs = toXs(poly);
		double[] ys = toYs(poly);

		Color base;
		if (hit.freeSpace()) {
			base = host.scheme().capacityTrack();
		} else if (hit.unaccounted()) {
			base = host.scheme().surface().brighter();
		} else if (hit.node() != null) {
			base = host.colors().colorFor(hit.node());
		} else {
			base = host.scheme().surface();
		}

		boolean hovered = (hit.node() != null && hit.node() == ctx.hoverNode())
				|| (hit.freeSpace() && ctx.hoveringFreeSpace())
				|| (hit.unaccounted() && ctx.hoveringUnaccounted());
		if (hovered)
			base = base.deriveColor(0, 1.20, 0.85, 1.0);

		double alpha = (hit.node() == null || hit.node().isDone()) ? 1.0 : 0.45;
		Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
		g.setFill(fill);
		g.fillPolygon(xs, ys, poly.size());
	}

	private void drawCellStroke(GraphicsContext g, CellHit hit) {
		List<VoronoiLayout.Pt> poly = hit.polygon();
		if (poly.size() < 3)
			return;
		g.setStroke(host.scheme().background());
		g.setLineWidth(3.0);
		g.strokePolygon(toXs(poly), toYs(poly), poly.size());
	}

	private void drawSubCells(GraphicsContext g, CellHit parent) {
		Color bg = host.scheme().background();
		for (CellHit sub : parent.subCells()) {
			List<VoronoiLayout.Pt> poly = sub.polygon();
			if (poly.size() < 3)
				continue;
			double[] xs = toXs(poly);
			double[] ys = toYs(poly);
			Color base = host.colors().colorFor(sub.node());
			// Blend 40% toward the theme background so sub-cells read as a quieter
			// echo of the parent palette — automatically muted in dark mode and
			// lightened in light mode.
			Color muted = blend(base, bg, 0.40);
			double alpha = (sub.node() == null || sub.node().isDone()) ? 1.0 : 0.45;
			Color fill = (alpha >= 1.0) ? muted : new Color(muted.getRed(), muted.getGreen(), muted.getBlue(), alpha);
			g.setFill(fill);
			g.fillPolygon(xs, ys, poly.size());
			g.setStroke(host.scheme().background().brighter().brighter());
			g.setLineWidth(0.75);
			g.strokePolygon(xs, ys, poly.size());
		}
	}

	private static Color blend(Color a, Color b, double t) {
		return new Color(
				a.getRed()   * (1 - t) + b.getRed()   * t,
				a.getGreen() * (1 - t) + b.getGreen() * t,
				a.getBlue()  * (1 - t) + b.getBlue()  * t,
				1.0);
	}

	private void drawCellHover(GraphicsContext g, CellHit hit) {
		List<VoronoiLayout.Pt> poly = hit.polygon();
		if (poly.size() < 3)
			return;
		g.setStroke(host.scheme().accent());
		g.setLineWidth(4.0);
		g.strokePolygon(toXs(poly), toYs(poly), poly.size());
	}

	private void drawCellLabel(GraphicsContext g, CellHit hit, RenderContext ctx) {
		List<VoronoiLayout.Pt> poly = hit.polygon();
		if (poly.size() < 3 || VoronoiLayout.polygonArea(poly) < 1500)
			return;
		String name;
		if (hit.freeSpace())
			name = "Free";
		else if (hit.unaccounted())
			name = "Other";
		else if (hit.node() != null)
			name = hit.node().name();
		else
			return;

		VoronoiLayout.Pt c = VoronoiLayout.centroid(poly);
		g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.setStroke(Color.rgb(0, 0, 0, 0.85));
		g.setLineWidth(3);
		g.strokeText(name, c.x(), c.y());
		g.setFill(Color.WHITE);
		g.fillText(name, c.x(), c.y());
		g.setTextAlign(TextAlignment.LEFT);
		g.setTextBaseline(VPos.BASELINE);
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

	private List<TreemapItem> buildTopLevelTreemapItems(RenderContext ctx) {
		DirectoryNode viewRoot = ctx.viewRoot();
		NodeColorResolver colors = host.colors();
		List<TreemapItem> items = new ArrayList<>();
		for (DirectoryNode child : viewRoot.children()) {
			if (child.totalBytes() > 0)
				items.add(new TreemapItem(child, child.totalBytes(), false, false));
		}
		if (viewRoot == ctx.scanRoot() && ctx.target().totalBytes() > 0) {
			long unaccounted = Math.max(0L, ctx.target().usedBytes() - viewRoot.totalBytes());
			if (unaccounted > 0)
				items.add(new TreemapItem(null, unaccounted, true, false));
			if (!ctx.hideFreeSpace()) {
				long free = Math.max(0L, ctx.target().usableBytes());
				if (free > 0)
					items.add(new TreemapItem(null, free, false, true));
			}
		}
		return items;
	}

	private void drawCenterText(GraphicsContext g, double cx, double cy, String text) {
		g.setFill(host.scheme().textMuted());
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.setFont(Font.font("Segoe UI", 13));
		g.fillText(text, cx, cy);
	}

	// ---- helpers --------------------------------------------------------

	private static double[] toXs(List<VoronoiLayout.Pt> poly) {
		double[] xs = new double[poly.size()];
		for (int i = 0; i < poly.size(); i++)
			xs[i] = poly.get(i).x();
		return xs;
	}

	private static double[] toYs(List<VoronoiLayout.Pt> poly) {
		double[] ys = new double[poly.size()];
		for (int i = 0; i < poly.size(); i++)
			ys[i] = poly.get(i).y();
		return ys;
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

	private record CellHit(DirectoryNode node, List<VoronoiLayout.Pt> polygon, boolean unaccounted,
	                        boolean freeSpace, List<CellHit> subCells) {
	}

	private record TreemapItem(DirectoryNode node, long bytes, boolean unaccounted, boolean freeSpace) {
	}
}
