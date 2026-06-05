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
import se.hirt.diskspace.ui.SizeFormat;
import se.hirt.diskspace.ui.VoronoiLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Voronoi (weighted power-diagram) treemap visualization. Renders the directory tree as a circular partition whose
 * cells have areas proportional to {@code totalBytes()}, computed by {@link VoronoiLayout}.
 * <p>Drill-in / drill-out transitions are animated: each cell's polygon morphs from its old centroid and area to
 * its new centroid and area over {@link #ANIM_DURATION_NANOS} ns, eased with a smooth-step curve.</p>
 */
public final class VoronoiVisualization implements Visualization {

	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(
			VoronoiVisualization.class.getName());

	private static final double TOP_INSET = 36.0;
	private static final int DISK_SIDES = 64;
	private static final long ANIM_DURATION_NANOS = 350_000_000L;

	// ---- LOD / aggregation knobs ----------------------------------------
	// Weighted Voronoi (Bowyer-Watson + Lloyd relaxation) is O(n²) per layout call and the Lloyd loop
	// multiplies that by the iteration count, so site count is the only knob that materially controls
	// wall-clock cost. D3-voronoi-treemap's design centre is ~500 sites; we cap per-level and aggregate
	// the rest into a "Smaller" cell. Tunable at runtime via system property so values can be benchmarked
	// without recompile — pick the smallest number that still looks meaningful for the data.
	/** Max top-level cells (direct children of the view root). Extras roll up into a single "Smaller" cell. */
	private static final int TOP_LEVEL_MAX_SITES = readPositiveIntProperty("diskspace.voronoi.maxSites.top", 128);
	/** Max sub-cells per top-level parent. Extras roll up into a single "Smaller" sub-cell. */
	private static final int SUB_CELL_MAX_SITES = readPositiveIntProperty("diskspace.voronoi.maxSites.sub", 64);
	/** Minimum parent-polygon area (px²) below which sub-cells are skipped entirely. ~50×50 px by default. */
	private static final int SUB_CELL_MIN_PARENT_AREA = readPositiveIntProperty("diskspace.voronoi.subCellMinArea",
			2500);

	private static int readPositiveIntProperty(String name, int defaultValue) {
		String v = System.getProperty(name);
		if (v == null || v.isBlank())
			return defaultValue;
		try {
			int parsed = Integer.parseInt(v.trim());
			if (parsed > 0)
				return parsed;
			LOG.warning(() -> "Ignoring non-positive value for " + name + "=" + v + "; using default " + defaultValue);
			return defaultValue;
		} catch (NumberFormatException e) {
			LOG.warning(() -> "Ignoring non-numeric value for " + name + "=" + v + "; using default " + defaultValue);
			return defaultValue;
		}
	}

	private VisualizationHost host;

	/** Hit-test cache, rebuilt each render when the layout cache is valid. */
	private List<CellHit> cellHits = List.of();

	/** Layout cache invalidation keys. */
	private DirectoryNode lastViewRoot;
	private double lastW, lastH;
	private long lastTotalBytes;
	private boolean lastHideFreeSpace;

	// ---- animation state ------------------------------------------------

	private boolean animating;
	private long animStartNanos;
	/** Polygons from the layout immediately before the drill. */
	private List<CellHit> animOldCells = List.of();
	/** Polygons for the new layout; null until computed on the first animation frame. */
	private List<CellHit> animNewCells;
	/** The node that was drilled into (new viewRoot); used to identify which cell "blows up". */
	private DirectoryNode animDrillNode;

	private final AnimationTimer animTimer = new AnimationTimer() {
		@Override
		public void handle(long now) {
			if (now - animStartNanos >= ANIM_DURATION_NANOS) {
				animating = false;
				stop();
			}
			if (host != null)
				host.requestRedraw("voronoi-anim");
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

	/**
	 * Total layout sites (top-level cells + all sub-cells) the Voronoi computed for the most recently painted frame,
	 * post-LOD aggregation. Differs from {@code nodeCount} on the same Render event because the visualizer caps each
	 * level via the {@code diskspace.voronoi.maxSites.*} properties and rolls the remainder into a single "Smaller"
	 * cell — so for a folder with millions of children this will be at most {@code top + top × sub} regardless of tree
	 * size. During a drill animation the count comes from the in-flight {@code animNewCells} so the value reflects the
	 * *target* layout rather than the fading old one.
	 */
	@Override
	public int lastRenderSiteCount() {
		List<CellHit> active = (animNewCells != null) ? animNewCells : cellHits;
		int total = active.size();
		for (CellHit hit : active)
			total += hit.subCells().size();
		return total;
	}

	@Override
	public void viewRootChanged(DirectoryNode previous, DirectoryNode current) {
		if (previous == null || current == null || previous == current || cellHits.isEmpty()) {
			lastViewRoot = null;
			return;
		}
		animOldCells = List.copyOf(cellHits);
		animNewCells = null;
		animDrillNode = current;
		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
		lastViewRoot = null;
	}

	@Override
	public void render(GraphicsContext g, double w, double h, RenderContext ctx) {
		if (ctx.scanRoot() == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}
		if (animating) {
			drawVoronoiAnimated(g, w, h, ctx);
		} else {
			drawVoronoi(g, w, h, ctx);
		}
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
		if (viewRoot != lastViewRoot || w != lastW || h != lastH || totalBytes != lastTotalBytes || ctx.hideFreeSpace() != lastHideFreeSpace) {
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

	private void drawVoronoiAnimated(GraphicsContext g, double w, double h, RenderContext ctx) {
		DirectoryNode viewRoot = ctx.viewRoot();
		if (viewRoot == null)
			return;
		double cx = w / 2.0;
		double cy = (h + TOP_INSET) / 2.0;
		double radius = Math.min(w / 2.0, (h - TOP_INSET) / 2.0) - 12;
		if (radius < 40)
			return;

		List<TreemapItem> items = buildTopLevelTreemapItems(ctx);
		long totalBytes = 0;
		for (TreemapItem it : items)
			totalBytes += Math.max(0, it.bytes());
		if (items.isEmpty() || totalBytes <= 0)
			return;

		if (animNewCells == null) {
			animNewCells = computeLayout(items, cx, cy, radius, totalBytes);
			cellHits = animNewCells;
			lastViewRoot = viewRoot;
			lastW = w;
			lastH = h;
			lastTotalBytes = totalBytes;
			lastHideFreeSpace = ctx.hideFreeSpace();
		}

		double elapsed = System.nanoTime() - animStartNanos;
		double rawT = Math.min(1.0, elapsed / (double) ANIM_DURATION_NANOS);

		g.setStroke(host.scheme().background().brighter());
		g.setLineWidth(1.0);
		g.strokeOval(cx - radius, cy - radius, 2 * radius, 2 * radius);

		// Find the old polygon for the cell that was drilled into (may be null for drill-out).
		List<VoronoiLayout.Pt> drillOldPoly = null;
		for (CellHit hit : animOldCells) {
			if (hit.node() == animDrillNode) {
				drillOldPoly = hit.polygon();
				break;
			}
		}
		boolean drillIn = drillOldPoly != null && drillOldPoly.size() >= 3;

		if (drillIn) {
			drawDrillIn(g, ctx, rawT, cx, cy, radius, drillOldPoly);
		} else {
			drawDrillOut(g, ctx, rawT, cx, cy, radius);
		}
	}

	/**
	 * Drill-in: two phases. Phase 1 (0→0.5): the clicked cell expands to fill the whole disk while siblings collapse
	 * and fade. Phase 2 (0.5→1): the new children grow from their centroids into their final positions.
	 */
	private void drawDrillIn(
			GraphicsContext g, RenderContext ctx, double rawT, double cx, double cy, double radius,
			List<VoronoiLayout.Pt> drillOldPoly) {
		if (rawT <= 0.5) {
			double p = smoothStep(rawT * 2);

			VoronoiLayout.Pt diskC = new VoronoiLayout.Pt(cx, cy);

			// Phase 1a: siblings collapse to their centroids and fade out.
			for (CellHit hit : animOldCells) {
				if (hit.polygon() == drillOldPoly)
					continue;
				List<VoronoiLayout.Pt> poly = hit.polygon();
				if (poly.size() < 3)
					continue;
				VoronoiLayout.Pt c = VoronoiLayout.centroid(poly);
				List<VoronoiLayout.Pt> collapsed = scalePoly(poly, c, c, 1.0 - p);
				double alpha = 1.0 - p;
				drawCellFillAlpha(g, hit, collapsed, alpha, ctx);
			}
			// Sibling strokes on top of fills.
			for (CellHit hit : animOldCells) {
				if (hit.polygon() == drillOldPoly)
					continue;
				List<VoronoiLayout.Pt> poly = hit.polygon();
				if (poly.size() < 3)
					continue;
				VoronoiLayout.Pt c = VoronoiLayout.centroid(poly);
				List<VoronoiLayout.Pt> collapsed = scalePoly(poly, c, c, 1.0 - p);
				drawStrokeAlpha(g, collapsed, 3.0, host.scheme().background(), 1.0 - p);
			}

			// Phase 1b: clicked cell morphs from its polygon shape into a circle filling the disk.
			List<VoronoiLayout.Pt> expanded = morphToCircle(drillOldPoly, diskC, radius, p);
			// Find this cell's hit to get its color.
			CellHit drillHit = null;
			for (CellHit hit : animOldCells)
				if (hit.polygon() == drillOldPoly) {
					drillHit = hit;
					break;
				}
			if (drillHit != null) {
				CellHit fake = new CellHit(drillHit.node(), expanded, drillHit.unaccounted(), drillHit.freeSpace(),
						List.of());
				drawCellFill(g, fake, ctx);
				drawCellStroke(g, fake);
			}

		} else {
			double p = smoothStep((rawT - 0.5) * 2);

			// Phase 2: new children grow from their centroids.
			List<CellHit> growing = new ArrayList<>(animNewCells.size());
			for (CellHit newHit : animNewCells) {
				VoronoiLayout.Pt c = VoronoiLayout.centroid(newHit.polygon());
				List<VoronoiLayout.Pt> poly = scalePoly(newHit.polygon(), c, c, p);
				growing.add(new CellHit(newHit.node(), poly, newHit.unaccounted(), newHit.freeSpace(), newHit.smaller(),
						newHit.subCells()));
			}
			for (CellHit hit : growing)
				drawCellFill(g, hit, ctx);
			for (CellHit hit : growing)
				drawSubCells(g, hit);
			for (CellHit hit : growing)
				drawCellStroke(g, hit);
			for (CellHit hit : growing)
				drawCellLabel(g, hit, ctx);
		}
	}

	/**
	 * Drill-out: two phases. Phase 1 (0→0.5): current children collapse toward the disk centre. Phase 2 (0.5→1): new
	 * parent-level cells grow from their centroids.
	 */
	private void drawDrillOut(GraphicsContext g, RenderContext ctx, double rawT, double cx, double cy, double radius) {
		VoronoiLayout.Pt diskC = new VoronoiLayout.Pt(cx, cy);

		if (rawT <= 0.5) {
			double p = smoothStep(rawT * 2);
			for (CellHit hit : animOldCells) {
				List<VoronoiLayout.Pt> poly = hit.polygon();
				if (poly.size() < 3)
					continue;
				VoronoiLayout.Pt c = VoronoiLayout.centroid(poly);
				// Move centroid toward disk centre and shrink.
				VoronoiLayout.Pt lerpC = new VoronoiLayout.Pt(lerp(c.x(), diskC.x(), p), lerp(c.y(), diskC.y(), p));
				List<VoronoiLayout.Pt> collapsed = scalePoly(poly, c, lerpC, 1.0 - p * 0.7);
				double alpha = 1.0 - p;
				drawCellFillAlpha(g, hit, collapsed, alpha, ctx);
			}
			for (CellHit hit : animOldCells) {
				List<VoronoiLayout.Pt> poly = hit.polygon();
				if (poly.size() < 3)
					continue;
				VoronoiLayout.Pt c = VoronoiLayout.centroid(poly);
				VoronoiLayout.Pt lerpC = new VoronoiLayout.Pt(lerp(c.x(), diskC.x(), p), lerp(c.y(), diskC.y(), p));
				List<VoronoiLayout.Pt> collapsed = scalePoly(poly, c, lerpC, 1.0 - p * 0.7);
				drawStrokeAlpha(g, collapsed, 3.0, host.scheme().background(), 1.0 - p);
			}
		} else {
			double p = smoothStep((rawT - 0.5) * 2);
			List<CellHit> growing = new ArrayList<>(animNewCells.size());
			for (CellHit newHit : animNewCells) {
				VoronoiLayout.Pt c = VoronoiLayout.centroid(newHit.polygon());
				// The cell for the drilled-out node expands from full disk; others from centroid.
				List<VoronoiLayout.Pt> poly;
				if (newHit.node() == animDrillNode) {
					// Morph from full circle back to the cell polygon.
					poly = morphFromCircle(newHit.polygon(), diskC, radius, p);
				} else {
					poly = scalePoly(newHit.polygon(), c, c, p);
				}
				growing.add(new CellHit(newHit.node(), poly, newHit.unaccounted(), newHit.freeSpace(), newHit.smaller(),
						newHit.subCells()));
			}
			for (CellHit hit : growing)
				drawCellFill(g, hit, ctx);
			for (CellHit hit : growing)
				drawSubCells(g, hit);
			for (CellHit hit : growing)
				drawCellStroke(g, hit);
			for (CellHit hit : growing)
				drawCellLabel(g, hit, ctx);
		}
	}

	private void drawCellFillAlpha(
			GraphicsContext g, CellHit hit, List<VoronoiLayout.Pt> poly, double alpha, RenderContext ctx) {
		if (poly.size() < 3 || alpha <= 0.01)
			return;
		CellHit fake = new CellHit(hit.node(), poly, hit.unaccounted(), hit.freeSpace(), List.of());
		// Temporarily scale alpha by multiplying into the fill — reuse drawCellFill but clip alpha.
		// We draw a plain colored fill here to control opacity independently.
		Color base;
		if (hit.freeSpace())
			base = host.scheme().capacityTrack();
		else if (hit.unaccounted())
			base = host.scheme().surface().brighter();
		else if (hit.smaller())
			base = host.scheme().surface().darker();
		else if (hit.node() != null)
			base = host.colors().colorFor(hit.node());
		else
			base = host.scheme().surface();
		boolean hovered = (hit.node() != null && hit.node() == ctx.hoverNode());
		if (hovered)
			base = base.deriveColor(0, 1.20, 0.85, 1.0);
		double nodeAlpha = (hit.node() == null || hit.node().isDone()) ? 1.0 : 0.45;
		Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), nodeAlpha * alpha);
		g.setFill(fill);
		g.fillPolygon(toXs(poly), toYs(poly), poly.size());
	}

	private void drawStrokeAlpha(
			GraphicsContext g, List<VoronoiLayout.Pt> poly, double lineWidth, Color color, double alpha) {
		if (poly.size() < 3 || alpha <= 0.01)
			return;
		g.setStroke(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
		g.setLineWidth(lineWidth);
		g.strokePolygon(toXs(poly), toYs(poly), poly.size());
	}

	/**
	 * Morphs {@code oldPoly} toward {@code newPoly} at interpolation factor {@code t} (0 = old, 1 = new). Uses centroid
	 * translation + area-proportional scale applied to the new polygon's shape, so vertex-count differences between old
	 * and new are not an issue. Cells with no old polygon (newly appeared) grow from their centroid; cells with no new
	 * polygon collapse to a point.
	 */
	private static List<VoronoiLayout.Pt> lerpPolygon(
			List<VoronoiLayout.Pt> oldPoly, List<VoronoiLayout.Pt> newPoly, double t) {
		if (newPoly == null || newPoly.size() < 3)
			return List.of();
		VoronoiLayout.Pt newC = VoronoiLayout.centroid(newPoly);
		if (oldPoly == null || oldPoly.size() < 3) {
			return scalePoly(newPoly, newC, newC, t);
		}
		VoronoiLayout.Pt oldC = VoronoiLayout.centroid(oldPoly);
		VoronoiLayout.Pt lerpC = new VoronoiLayout.Pt(lerp(oldC.x(), newC.x(), t), lerp(oldC.y(), newC.y(), t));
		double oldA = VoronoiLayout.polygonArea(oldPoly);
		double newA = VoronoiLayout.polygonArea(newPoly);
		double scale = (oldA > 0 && newA > 0) ? lerp(Math.sqrt(oldA / newA), 1.0, t) : 1.0;
		return scalePoly(newPoly, newC, lerpC, scale);
	}

	/**
	 * Morphs a polygon toward a circle. Each vertex is lerped toward the point on the target circle at the same angle
	 * from the polygon's centroid, so at t=1 every vertex lies exactly on the circle and the shape is a smooth disc.
	 */
	private static List<VoronoiLayout.Pt> morphToCircle(
			List<VoronoiLayout.Pt> poly, VoronoiLayout.Pt circleCenter, double circleRadius, double t) {
		VoronoiLayout.Pt centroid = VoronoiLayout.centroid(poly);
		List<VoronoiLayout.Pt> out = new ArrayList<>(poly.size());
		for (VoronoiLayout.Pt v : poly) {
			double angle = Math.atan2(v.y() - centroid.y(), v.x() - centroid.x());
			double tx = circleCenter.x() + circleRadius * Math.cos(angle);
			double ty = circleCenter.y() + circleRadius * Math.sin(angle);
			out.add(new VoronoiLayout.Pt(lerp(v.x(), tx, t), lerp(v.y(), ty, t)));
		}
		return out;
	}

	/**
	 * Reverse of {@link #morphToCircle}: each vertex of {@code poly} is lerped from the point on the source circle at
	 * that vertex's angle to its final polygon position.
	 */
	private static List<VoronoiLayout.Pt> morphFromCircle(
			List<VoronoiLayout.Pt> poly, VoronoiLayout.Pt circleCenter, double circleRadius, double t) {
		VoronoiLayout.Pt centroid = VoronoiLayout.centroid(poly);
		List<VoronoiLayout.Pt> out = new ArrayList<>(poly.size());
		for (VoronoiLayout.Pt v : poly) {
			double angle = Math.atan2(v.y() - centroid.y(), v.x() - centroid.x());
			double sx = circleCenter.x() + circleRadius * Math.cos(angle);
			double sy = circleCenter.y() + circleRadius * Math.sin(angle);
			out.add(new VoronoiLayout.Pt(lerp(sx, v.x(), t), lerp(sy, v.y(), t)));
		}
		return out;
	}

	/** Scale {@code poly} around {@code anchor}, then translate so the anchor lands at {@code center}. */
	private static List<VoronoiLayout.Pt> scalePoly(
			List<VoronoiLayout.Pt> poly, VoronoiLayout.Pt anchor, VoronoiLayout.Pt center, double scale) {
		List<VoronoiLayout.Pt> out = new ArrayList<>(poly.size());
		for (VoronoiLayout.Pt v : poly)
			out.add(new VoronoiLayout.Pt(center.x() + (v.x() - anchor.x()) * scale,
					center.y() + (v.y() - anchor.y()) * scale));
		return out;
	}

	private static double smoothStep(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private List<CellHit> computeLayout(List<TreemapItem> items, double cx, double cy, double radius, long totalBytes) {
		// Cap top-level cell count before handing to the O(n²) weighted Voronoi: extras roll up into a
		// single "Smaller" cell carrying the summed bytes. Without this, drilling into a folder with ~50k
		// direct children stalls the UI thread for minutes (see JFR profile diskspace-fixed.jfr).
		items = aggregateSmaller(items, TOP_LEVEL_MAX_SITES);
		items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
		double[] weights = new double[items.size()];
		for (int i = 0; i < items.size(); i++)
			weights[i] = Math.max(1.0, items.get(i).bytes());
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, DISK_SIDES);
		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);
		List<CellHit> hits = new ArrayList<>(cells.size());
		if (LOG.isLoggable(java.util.logging.Level.FINE)) {
			double diskArea = VoronoiLayout.polygonArea(bounds);
			StringBuilder sb = new StringBuilder("Voronoi layout (totalBytes=").append(SizeFormat.format(totalBytes))
					.append(", items=").append(items.size()).append("):\n");
			for (int i = 0; i < cells.size(); i++) {
				TreemapItem item = items.get(i);
				double cellArea = VoronoiLayout.polygonArea(cells.get(i).polygon());
				double targetArea = diskArea * weights[i] / totalBytes;
				String label = item.freeSpace() ? "Free" : item.unaccounted() ? "Unaccounted"
						: item.smaller() ? "Smaller (" + item.aggregatedChildCount() + " items)"
								: item.node() != null ? item.node().name() : "?";
				sb.append(String.format(
						"  [%2d] %-35s bytes=%-12s weight=%-12.0f targetArea=%7.1f cellArea=%7.1f ratio=%.2f%n", i,
						label, SizeFormat.format(item.bytes()), weights[i], targetArea, cellArea,
						targetArea > 0 ? cellArea / targetArea : 0));
			}
			LOG.fine(sb.toString());
		}
		for (int i = 0; i < cells.size(); i++) {
			TreemapItem item = items.get(i);
			List<VoronoiLayout.Pt> poly = cells.get(i).polygon();
			List<CellHit> subCells = computeSubCells(item.node(), poly);
			hits.add(new CellHit(item.node(), poly, item.unaccounted(), item.freeSpace(), item.smaller(), subCells));
		}
		return hits;
	}

	private List<CellHit> computeSubCells(DirectoryNode node, List<VoronoiLayout.Pt> parentPoly) {
		if (node == null || parentPoly.size() < 3)
			return List.of();
		List<DirectoryNode> children = node.children();
		if (children.size() < 2)
			return List.of();
		// Pixel-area gate: parents too small to read a label inside don't get a sub-Voronoi computed at
		// all. Cheap children == cheap render. Threshold is tunable via -Ddiskspace.voronoi.subCellMinArea.
		if (VoronoiLayout.polygonArea(parentPoly) < SUB_CELL_MIN_PARENT_AREA)
			return List.of();

		// Build TreemapItems for the children and aggregate the tail into a single "Smaller" sub-cell so
		// the per-parent Voronoi never exceeds SUB_CELL_MAX_SITES. Mirrors what computeLayout does at the
		// top level; both are the same Bowyer-Watson + Lloyd loop and both blow up the same way without
		// a cap.
		List<TreemapItem> subItems = new ArrayList<>(children.size());
		for (DirectoryNode child : children)
			subItems.add(new TreemapItem(child, Math.max(1L, child.totalBytes()), false, false));
		subItems = aggregateSmaller(subItems, SUB_CELL_MAX_SITES);

		double[] weights = new double[subItems.size()];
		for (int i = 0; i < subItems.size(); i++)
			weights[i] = Math.max(1.0, subItems.get(i).bytes());
		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(parentPoly, weights);
		List<CellHit> sub = new ArrayList<>(cells.size());
		for (int i = 0; i < cells.size(); i++) {
			TreemapItem item = subItems.get(i);
			sub.add(new CellHit(item.node(), cells.get(i).polygon(), false, false, item.smaller(), List.of()));
		}
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
		} else if (hit.smaller()) {
			// "Smaller" aggregate cell — neutral muted tone, distinct from both real-node colors and from
			// the lighter unaccounted/free shades. Tracks the active theme via scheme().surface().
			base = host.scheme().surface().darker();
		} else if (hit.node() != null) {
			base = host.colors().colorFor(hit.node());
		} else {
			base = host.scheme().surface();
		}

		boolean hovered = (hit.node() != null && hit.node() == ctx.hoverNode()) || (hit.freeSpace() && ctx.hoveringFreeSpace()) || (hit.unaccounted() && ctx.hoveringUnaccounted());
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
			// Smaller sub-cells (the per-parent aggregate of all tiny children) have no node — use the
			// theme's surface darker as a neutral base instead of calling colorFor(null).
			Color base = sub.smaller() || sub.node() == null ? host.scheme().surface().darker()
					: host.colors().colorFor(sub.node());
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
		return new Color(a.getRed() * (1 - t) + b.getRed() * t, a.getGreen() * (1 - t) + b.getGreen() * t,
				a.getBlue() * (1 - t) + b.getBlue() * t, 1.0);
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

	private record CellHit(DirectoryNode node, List<VoronoiLayout.Pt> polygon, boolean unaccounted, boolean freeSpace,
	                       boolean smaller, List<CellHit> subCells) {
		/** Convenience constructor for the historical 5-arg shape; defaults {@code smaller} to {@code false}. */
		CellHit(
				DirectoryNode node, List<VoronoiLayout.Pt> polygon, boolean unaccounted, boolean freeSpace,
				List<CellHit> subCells) {
			this(node, polygon, unaccounted, freeSpace, false, subCells);
		}
	}

	private record TreemapItem(DirectoryNode node, long bytes, boolean unaccounted, boolean freeSpace, boolean smaller,
	                           int aggregatedChildCount) {
		/**
		 * Convenience constructor for the historical 4-arg shape; defaults {@code smaller} and the aggregate count to
		 * zero.
		 */
		TreemapItem(DirectoryNode node, long bytes, boolean unaccounted, boolean freeSpace) {
			this(node, bytes, unaccounted, freeSpace, false, 0);
		}
	}

	/**
	 * Caps a list of treemap items to {@code maxSites} by keeping the (max−1) largest and rolling everything else into
	 * a single trailing {@code smaller} item carrying the summed bytes and the count of rolled-up siblings. Returns the
	 * original list unchanged when it already fits. Mutates the input by sorting it descending by bytes — caller
	 * doesn't reuse it afterwards. Sites that are special (free space, unaccounted) are kept as-is and counted toward
	 * the cap; aggregation only collapses real children whose individual bytes are too small to be worth a dedicated
	 * cell.
	 */
	private static List<TreemapItem> aggregateSmaller(List<TreemapItem> items, int maxSites) {
		if (items.size() <= maxSites)
			return items;
		items.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
		List<TreemapItem> kept = new ArrayList<>(maxSites);
		long aggregatedBytes = 0;
		int aggregatedCount = 0;
		for (int i = 0; i < items.size(); i++) {
			if (i < maxSites - 1) {
				kept.add(items.get(i));
			} else {
				aggregatedBytes += items.get(i).bytes();
				aggregatedCount++;
			}
		}
		if (aggregatedBytes > 0 && aggregatedCount > 0)
			kept.add(new TreemapItem(null, aggregatedBytes, false, false, true, aggregatedCount));
		return kept;
	}
}
