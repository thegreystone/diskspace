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
package se.hirt.diskspace.ui;

import java.util.*;

/**
 * Weighted Voronoi (power diagram) treemap layout. Computes a partition of a convex bounding polygon into N cells whose
 * areas are proportional to a given weight vector. Pure geometry — does not know about {@code DirectoryNode} or any
 * model class. The caller passes weights, gets polygons back in the same order, and is responsible for mapping cells
 * back to its data.
 * <p><b>Algorithm</b> (Nocaj-Brandes 2012, simplified for one-shot layout):
 * <ol>
 *   <li>Place initial sites in a small angular ring around the bounds centroid (Lloyd relaxation will spread them).</li>
 *   <li>Each iteration: compute the regular triangulation (Bowyer-Watson with power-circle conflict test); for each site, derive its
 *       cell by collecting circumcenters of incident triangles, sort CCW, clip to bounds (Sutherland-Hodgman); measure the cell's area;
 *       adjust the site's weight toward closing the gap to its target area; nudge the site toward its cell's centroid (Lloyd relaxation,
 *       damped by {@link #LLOYD}).</li>
 *   <li>Stop when the worst per-cell area error drops below {@link #DEFAULT_THRESHOLD} or after {@link #DEFAULT_MAX_ITERATIONS}.</li>
 * </ol>
 * <p><b>Performance:</b> {@code O(N²)} per Bowyer-Watson rebuild. For typical disk-treemap inputs (≤ 50 cells per level) one full layout
 * costs ~10–30 ms on the FX thread. The caller is expected to cache results across renders that don't change the input — see
 * {@code DiskView}'s use site for the per-{@code (viewRoot, canvas-size)} cache.
 */
public final class VoronoiLayout {

	private static final int DEFAULT_MAX_ITERATIONS = 50;
	private static final double DEFAULT_THRESHOLD = 0.01;
	private static final double STEP = 0.5;
	private static final double LLOYD = 0.3;

	private VoronoiLayout() {
	}

	/** 2D point. Public so it appears in callers' signatures without leaking internal types. */
	public record Pt(double x, double y) {
		double distSq(Pt o) {
			double dx = x - o.x;
			double dy = y - o.y;
			return dx * dx + dy * dy;
		}
	}

	/**
	 * Layout result for one input weight: a CCW polygon (possibly empty if the corresponding cell vanished during
	 * balancing). Index in the input weight array equals index of the returned cell.
	 */
	public record Cell(List<Pt> polygon) {
		public boolean isEmpty() {
			return polygon == null || polygon.size() < 3;
		}
	}

	/**
	 * Computes Voronoi cells inside {@code bounds} with target areas proportional to {@code weights}. Default iteration
	 * cap and convergence threshold; callers needing different tuning can use the four-arg overload.
	 */
	public static List<Cell> compute(List<Pt> bounds, double[] weights) {
		return compute(bounds, weights, DEFAULT_MAX_ITERATIONS, DEFAULT_THRESHOLD);
	}

	public static List<Cell> compute(List<Pt> bounds, double[] weights, int maxIterations, double threshold) {
		if (weights.length == 0)
			return List.of();
		if (weights.length == 1) {
			return List.of(new Cell(List.copyOf(bounds)));
		}

		double totalWeight = 0;
		for (double w : weights)
			totalWeight += Math.max(0, w);
		if (totalWeight <= 0)
			return blankCells(weights.length);

		double boundsArea = polygonArea(bounds);
		if (boundsArea < 1)
			return blankCells(weights.length);

		// Compute raw target areas, then apply a minimum floor of 5% of the equal-area
		// share. Items below this threshold need 20× or more shrinkage from their initial
		// equal-area Voronoi cell, which requires large negative power weights that remove
		// the site from Bowyer-Watson triangulations entirely. The floor trades perfect
		// proportionality for guaranteed visibility; labels still show the real byte count.
		double equalShare = boundsArea / weights.length;
		double minTargetArea = equalShare * 0.05;
		double[] targetAreas = new double[weights.length];
		double targetSum = 0;
		for (int i = 0; i < weights.length; i++) {
			targetAreas[i] = Math.max(minTargetArea, Math.max(0, weights[i]) / totalWeight * boundsArea);
			targetSum += targetAreas[i];
		}
		// Re-normalise so targets still sum to boundsArea after flooring.
		for (int i = 0; i < targetAreas.length; i++)
			targetAreas[i] *= boundsArea / targetSum;

		List<Site> sites = initialSitesInPolygon(weights.length, bounds, weights);
		List<List<Pt>> cellPolys = balance(sites, bounds, targetAreas, boundsArea, maxIterations, threshold);
		List<Cell> result = new ArrayList<>(cellPolys.size());
		for (List<Pt> p : cellPolys)
			result.add(new Cell(p));
		return result;
	}

	/** Returns true iff {@code (x, y)} is inside the CCW convex polygon {@code poly} (edge inclusive). */
	public static boolean contains(List<Pt> poly, double x, double y) {
		int n = poly.size();
		if (n < 3)
			return false;
		for (int i = 0; i < n; i++) {
			Pt a = poly.get(i);
			Pt b = poly.get((i + 1) % n);
			double cross = (b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x());
			if (cross < 0)
				return false;
		}
		return true;
	}

	/**
	 * Polygon centroid via the standard signed-area formula. Falls back to vertex-average if the polygon is
	 * degenerate.
	 */
	public static Pt centroid(List<Pt> poly) {
		int n = poly.size();
		if (n < 3) {
			double cx = 0, cy = 0;
			for (Pt p : poly) {
				cx += p.x();
				cy += p.y();
			}
			return new Pt(cx / Math.max(1, n), cy / Math.max(1, n));
		}
		double a = 0, cx = 0, cy = 0;
		for (int i = 0; i < n; i++) {
			Pt p1 = poly.get(i);
			Pt p2 = poly.get((i + 1) % n);
			double cross = p1.x() * p2.y() - p2.x() * p1.y();
			a += cross;
			cx += (p1.x() + p2.x()) * cross;
			cy += (p1.y() + p2.y()) * cross;
		}
		if (Math.abs(a) < 1e-12) {
			double sx = 0, sy = 0;
			for (Pt p : poly) {
				sx += p.x();
				sy += p.y();
			}
			return new Pt(sx / n, sy / n);
		}
		double f = 1.0 / (3.0 * a);
		return new Pt(cx * f, cy * f);
	}

	/** Shoelace polygon area. Returns absolute value (winding-sign-independent). */
	public static double polygonArea(List<Pt> poly) {
		int n = poly.size();
		if (n < 3)
			return 0;
		double s = 0;
		for (int i = 0; i < n; i++) {
			Pt p1 = poly.get(i);
			Pt p2 = poly.get((i + 1) % n);
			s += p1.x() * p2.y() - p2.x() * p1.y();
		}
		return Math.abs(s) * 0.5;
	}

	/** Regular {@code sides}-gon approximation of a circle, CCW. Used to feed {@link #compute} a disk-shaped boundary. */
	public static List<Pt> approximateDisk(double cx, double cy, double r, int sides) {
		List<Pt> out = new ArrayList<>(sides);
		for (int i = 0; i < sides; i++) {
			double angle = 2.0 * Math.PI * i / sides;
			out.add(new Pt(cx + r * Math.cos(angle), cy + r * Math.sin(angle)));
		}
		return out;
	}

	// ── internals ───────────────────────────────────────────────────────────

	private static List<Cell> blankCells(int n) {
		List<Cell> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			out.add(new Cell(List.of()));
		return out;
	}

	/** Mutable weighted site (position + power weight). Mutated in-place across iterations. */
	private static final class Site {
		double x, y;
		double weight;

		Site(double x, double y, double weight) {
			this.x = x;
			this.y = y;
			this.weight = weight;
		}

		Pt pos() {
			return new Pt(x, y);
		}
	}

	/** Regular-triangulation triangle. Cached power center and squared power radius for the conflict test. */
	private static final class WTriangle {
		final Site a, b, c;
		final Pt powerCenter;
		final double powerRadiusSq;

		WTriangle(Site a, Site b, Site c) {
			this.a = a;
			this.b = b;
			this.c = c;
			this.powerCenter = powerCenter(a, b, c);
			this.powerRadiusSq = a.pos().distSq(powerCenter) - a.weight;
		}

		boolean conflicts(Site s) {
			return s.pos().distSq(powerCenter) - s.weight < powerRadiusSq;
		}

		boolean has(Site s) {
			return a == s || b == s || c == s;
		}

		boolean hasEdge(Site p, Site q) {
			return (a == p || b == p || c == p) && (a == q || b == q || c == q);
		}
	}

	/**
	 * Power center of three weighted sites — the dual vertex equidistant in power from a, b, c. Reduces to the standard
	 * circumcenter when all weights are zero. Solves the 2×2 linear system from the bisector equations via Cramer's
	 * rule.
	 */
	private static Pt powerCenter(Site a, Site b, Site c) {
		double ax = a.x, ay = a.y, bx = b.x, by = b.y, cx = c.x, cy = c.y;
		double dx1 = bx - ax, dy1 = by - ay;
		double dx2 = cx - ax, dy2 = cy - ay;
		double det = dx1 * dy2 - dy1 * dx2;
		if (Math.abs(det) < 1e-12)
			return new Pt(Double.NaN, Double.NaN);
		double fa = ax * ax + ay * ay - a.weight;
		double fb = bx * bx + by * by - b.weight;
		double fc = cx * cx + cy * cy - c.weight;
		double rhs1 = (fb - fa) * 0.5;
		double rhs2 = (fc - fa) * 0.5;
		double px = (rhs1 * dy2 - rhs2 * dy1) / det;
		double py = (dx1 * rhs2 - dx2 * rhs1) / det;
		return new Pt(px, py);
	}

	private static List<WTriangle> bowyerWatsonWeighted(List<Site> sites) {
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
		for (Site s : sites) {
			if (s.x < minX)
				minX = s.x;
			if (s.y < minY)
				minY = s.y;
			if (s.x > maxX)
				maxX = s.x;
			if (s.y > maxY)
				maxY = s.y;
		}
		double cx = (minX + maxX) / 2.0, cy = (minY + maxY) / 2.0;
		double size = Math.max(maxX - minX, maxY - minY) * 30.0 + 1.0;
		Site sa = new Site(cx - size, cy - size, 0);
		Site sb = new Site(cx + size, cy - size, 0);
		Site sc = new Site(cx, cy + size, 0);
		List<WTriangle> tris = new ArrayList<>();
		tris.add(new WTriangle(sa, sb, sc));
		for (Site site : sites) {
			List<WTriangle> bad = new ArrayList<>();
			for (WTriangle t : tris)
				if (t.conflicts(site))
					bad.add(t);
			List<Site[]> holeEdges = new ArrayList<>();
			for (int i = 0; i < bad.size(); i++) {
				WTriangle t = bad.get(i);
				Site[][] edges = {{t.a, t.b}, {t.b, t.c}, {t.c, t.a}};
				for (Site[] e : edges) {
					boolean shared = false;
					for (int j = 0; j < bad.size(); j++) {
						if (j == i)
							continue;
						if (bad.get(j).hasEdge(e[0], e[1])) {
							shared = true;
							break;
						}
					}
					if (!shared)
						holeEdges.add(e);
				}
			}
			// Wrap `bad` in an identity-keyed Set before removeAll: ArrayList.removeAll() walks
			// the receiver and calls bad.contains() on every element, which is O(|bad|) when
			// `bad` is an ArrayList. With ~2n triangles in `tris` and ~5-10 in `bad` per site,
			// that's an extra ~10× factor on top of the already-O(n²) outer loop -- the dominant
			// frame in JFR profiles of the hang on millions-of-nodes scans. Identity hashing is
			// safe because WTriangles are created in this method; no equals/hashCode reliance.
			Set<WTriangle> badSet = Collections.newSetFromMap(new IdentityHashMap<>(bad.size() * 2));
			badSet.addAll(bad);
			tris.removeAll(badSet);
			for (Site[] e : holeEdges)
				tris.add(new WTriangle(e[0], e[1], site));
		}
		return tris;
	}

	private static List<Pt> cellAroundSite(List<WTriangle> tris, Site site) {
		List<Pt> centers = new ArrayList<>();
		for (WTriangle t : tris) {
			if (!t.has(site))
				continue;
			Pt pc = t.powerCenter;
			// powerCenter() returns (NaN, NaN) for degenerate (near-collinear) triangles. Including those
			// here would propagate NaN through atan2 sort comparisons (non-transitive, can hit TimSort's
			// "comparison method violates its general contract" exception) and through polygonArea /
			// centroid / contains on the resulting cell. Drop them — the cell ends up with one fewer
			// vertex, which is the right outcome for a triangle that contributed no geometric content.
			if (Double.isFinite(pc.x()) && Double.isFinite(pc.y()))
				centers.add(pc);
		}
		if (centers.size() < 3)
			return List.of();
		final double sx = site.x, sy = site.y;
		centers.sort(
				(p1, p2) -> Double.compare(Math.atan2(p1.y() - sy, p1.x() - sx), Math.atan2(p2.y() - sy, p2.x() - sx)));
		return centers;
	}

	private static List<Pt> clipPolygonToConvex(List<Pt> subject, List<Pt> clip) {
		if (subject.isEmpty() || clip.size() < 3)
			return List.of();
		List<Pt> output = new ArrayList<>(subject);
		int m = clip.size();
		for (int i = 0; i < m; i++) {
			if (output.isEmpty())
				break;
			Pt e1 = clip.get(i);
			Pt e2 = clip.get((i + 1) % m);
			List<Pt> input = output;
			output = new ArrayList<>(input.size() + 2);
			for (int j = 0; j < input.size(); j++) {
				Pt curr = input.get(j);
				Pt prev = input.get((j - 1 + input.size()) % input.size());
				boolean currIn = isLeftOfOrOn(e1, e2, curr);
				boolean prevIn = isLeftOfOrOn(e1, e2, prev);
				if (currIn) {
					if (!prevIn)
						output.add(intersect(prev, curr, e1, e2));
					output.add(curr);
				} else if (prevIn) {
					output.add(intersect(prev, curr, e1, e2));
				}
			}
		}
		return output;
	}

	private static boolean isLeftOfOrOn(Pt e1, Pt e2, Pt p) {
		return (e2.x() - e1.x()) * (p.y() - e1.y()) - (e2.y() - e1.y()) * (p.x() - e1.x()) >= 0;
	}

	private static Pt intersect(Pt p, Pt q, Pt a, Pt b) {
		double x1 = p.x(), y1 = p.y(), x2 = q.x(), y2 = q.y();
		double x3 = a.x(), y3 = a.y(), x4 = b.x(), y4 = b.y();
		double denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
		if (Math.abs(denom) < 1e-12)
			return q;
		double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
		return new Pt(x1 + t * (x2 - x1), y1 + t * (y2 - y1));
	}

	private static List<Site> initialSitesInPolygon(int k, List<Pt> bounds, double[] weights) {
		Pt c = centroid(bounds);
		double R = Math.sqrt(polygonArea(bounds) / Math.PI) * 0.55;
		// Vogel / sunflower spiral: item i is placed at radius R×√((i+0.5)/k) and angle
		// i×golden_angle. Items are assumed to arrive in weight-desc order, so the heaviest
		// site starts near the centre (√(0.5/k) ≈ small) and smaller sites fan outward.
		// This gives each site a distinct 2-D position, not just an angular one, so small
		// items have spatial separation from the dominant item instead of all sitting on the
		// same ring where the dominant item's growing weight sweeps them away.
		final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0)); // ≈ 2.399 rad
		List<Site> out = new ArrayList<>(k);
		for (int i = 0; i < k; i++) {
			double r = R * Math.sqrt((i + 0.5) / k);
			double angle = i * GOLDEN_ANGLE;
			out.add(new Site(c.x() + r * Math.cos(angle), c.y() + r * Math.sin(angle), 0));
		}
		return out;
	}

	/** Iterative weight balancing. Mutates {@code sites} in place. Returns the per-site clipped cell polygons (CCW). */
	private static List<List<Pt>> balance(
			List<Site> sites, List<Pt> bounds, double[] targetAreas, double boundsArea, int maxIter, double threshold) {
		// All weights start at 0 (standard unweighted Voronoi). The iterative step below
		// drives each weight toward the value that makes its cell hit targetArea.
		// Warm-starting with target-derived values looks appealing but causes dominant
		// sites (large weight) to absorb adjacent medium sites in the first Bowyer-Watson
		// pass, leaving those sites with zero area that the clamp then locks in permanently.

		List<List<Pt>> cells = null;
		for (int iter = 0; iter < maxIter; iter++) {
			List<WTriangle> tris = bowyerWatsonWeighted(sites);
			cells = new ArrayList<>(sites.size());
			double maxRelError = 0;
			for (int i = 0; i < sites.size(); i++) {
				Site s = sites.get(i);
				List<Pt> raw = cellAroundSite(tris, s);
				List<Pt> cell = clipPolygonToConvex(raw, bounds);
				cells.add(cell);
				if (cell.size() < 3 || targetAreas[i] <= 0)
					continue;
				double area = polygonArea(cell);
				double err = Math.abs(area - targetAreas[i]) / targetAreas[i];
				if (err > maxRelError)
					maxRelError = err;
				// Allow negative weights so sites needing less than their equal-Voronoi share
				// can give territory to neighbours. But cap the magnitude: once weight goes
				// below -boundsArea the power circle is so large that Bowyer-Watson removes
				// all the site's triangles, causing area to flip to 0 and the weight to
				// reverse — the "flickering" Nocaj-Brandes warn about past 50 iterations.
				s.weight = Math.max(-boundsArea, s.weight + STEP * (targetAreas[i] - area));
				Pt centroidPt = centroid(cell);
				s.x += LLOYD * (centroidPt.x() - s.x);
				s.y += LLOYD * (centroidPt.y() - s.y);
			}
			if (maxRelError < threshold)
				break;
		}
		return cells;
	}
}
