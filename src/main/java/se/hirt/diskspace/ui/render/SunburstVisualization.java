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
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;
import se.hirt.diskspace.ui.SizeFormat;

import java.util.*;

/**
 * Sunburst visualization. Renders the directory tree as a polar chart — concentric rings, one per depth level, each
 * ring sliced into angular sectors proportional to {@code totalBytes()}. Owns its own animation state and timer:
 * drill-in/drill-out transitions interpolate sector positions over {@value #ANIM_DURATION_MS} ms.
 * <p>The hub (centre disc) shows the title and size of the currently-focused node — either the {@link
 * RenderContext#viewRoot}, the {@link RenderContext#hoverNode}, or the special free-space / unaccounted regions. During
 * an active scan the hub also displays the scanner's progress overrides from {@link RenderContext#hubState}.</p>
 */
public final class SunburstVisualization implements Visualization {

	// ---- ring geometry --------------------------------------------------

	/**
	 * First {@value} rings render at full thickness ({@code normalW}); after that, up to {@link #THIN_RINGS} additional
	 * rings render at {@link #THIN_RING_FACTOR} × normalW so deep trees still fit on screen without collapsing the
	 * outer halo.
	 */
	private static final int NORMAL_RINGS = 5;
	private static final int THIN_RINGS = 4;
	private static final int MAX_DEPTH = NORMAL_RINGS + THIN_RINGS;
	private static final double THIN_RING_FACTOR = 0.2;
	private static final double HUB_RADIUS = 78;
	private static final double MIN_VISIBLE_SWEEP_DEG = 0.6;

	// ---- animation ------------------------------------------------------

	private static final int ANIM_DURATION_MS = 350;
	private static final long ANIM_DURATION_NANOS = ANIM_DURATION_MS * 1_000_000L;

	// ---- host + colours -------------------------------------------------

	private VisualizationHost host;

	// ---- hit-test cache (populated during render, consumed during hitTest) ----

	private final List<SectorRect> sectors = new ArrayList<>();
	/** Cached canvas centre + ring widths from the last render, used by {@link #hitTest}. */
	private double lastCx, lastCy, lastNormalW, lastThinW;

	// ---- animation state ------------------------------------------------

	private boolean animating;
	private long animStartNanos;
	private DirectoryNode animOldViewRoot;
	private DirectoryNode animNewViewRoot;
	private Map<DirectoryNode, Layout> animOld;
	private Map<DirectoryNode, Layout> animNew;
	private final AnimationTimer animTimer = new AnimationTimer() {
		@Override
		public void handle(long now) {
			if (now - animStartNanos >= ANIM_DURATION_NANOS) {
				animating = false;
				stop();
				if (host != null)
					host.requestRedraw("anim-end");
				return;
			}
			if (host != null)
				host.requestRedraw("anim-tick");
		}
	};

	@Override
	public void attach(VisualizationHost host) {
		this.host = host;
	}

	@Override
	public boolean isAnimating() {
		return animating;
	}

	@Override
	public void shutdown() {
		animTimer.stop();
		animating = false;
	}

	@Override
	public void viewRootChanged(DirectoryNode previous, DirectoryNode current) {
		if (previous == null || current == null || previous == current)
			return;
		// We don't have the previous render's RenderContext here — the host has it, but pushing it via this hook
		// would couple lifecycle to draw state. Instead we'll animate using layouts the host computes on the
		// fly; the call site that triggers this kick (DiskView.select / handleDelete) is responsible for
		// invoking {@link #beginAnimation} with concrete before/after layouts.
		// (This default no-op keeps the contract honest; DiskView is expected to call beginAnimation directly.)
	}

	/**
	 * Kick off a drill-in/drill-out animation. The before-layout and after-layout maps are pre-computed by the host
	 * using {@link #computeLayout} so we can lerp between them. The visualization runs its own timer; on each tick it
	 * asks the host to redraw, and the next render paints the interpolated frame.
	 */
	public void beginAnimation(
			DirectoryNode oldViewRoot, DirectoryNode newViewRoot, Map<DirectoryNode, Layout> oldL,
			Map<DirectoryNode, Layout> newL) {
		animOldViewRoot = oldViewRoot;
		animNewViewRoot = newViewRoot;
		animOld = oldL;
		animNew = newL;
		animStartNanos = System.nanoTime();
		animating = true;
		animTimer.start();
	}

	/** Cancel any in-flight animation. */
	public void cancelAnimation() {
		if (animating) {
			animating = false;
			animTimer.stop();
		}
	}

	@Override
	public void render(GraphicsContext g, double w, double h, RenderContext ctx) {
		sectors.clear();
		if (ctx.scanRoot() == null) {
			drawCenterText(g, w / 2, h / 2, "Scanning…");
			return;
		}

		double cx = w / 2.0;
		double cy = h / 2.0;
		double maxR = Math.max(40, Math.min(w, h) * 0.46);
		double normalW = (maxR - HUB_RADIUS) / (NORMAL_RINGS + THIN_RINGS * THIN_RING_FACTOR);
		if (normalW < 6)
			normalW = 6;
		double thinW = normalW * THIN_RING_FACTOR;
		lastCx = cx;
		lastCy = cy;
		lastNormalW = normalW;
		lastThinW = thinW;

		// Draw sectors first, hub on top so anti-aliasing edges are clipped cleanly.
		if (animating) {
			drawAnimatedFrame(g, cx, cy, normalW, thinW, ctx);
		} else if (ctx.viewRoot() != null) {
			drawLayout(g, cx, cy, normalW, thinW, computeLayout(ctx.viewRoot(), w, h, ctx), ctx);
		}

		drawHub(g, cx, cy, ctx);
	}

	@Override
	public HitResult hitTest(double mx, double my) {
		if (sectors.isEmpty())
			return HitResult.Special.NONE;
		double dx = mx - lastCx;
		double dy = my - lastCy;
		double r = Math.hypot(dx, dy);
		if (r < HUB_RADIUS)
			return HitResult.Special.HUB;
		double theta = Math.toDegrees(Math.atan2(-dy, dx));
		if (theta < 0)
			theta += 360;
		for (SectorRect s : sectors) {
			if (r >= s.r1 && r <= s.r2 && angleInSweep(theta, s.startDeg, s.sweepDeg)) {
				if (s.node != null)
					return new HitResult.OnNode(s.node);
				return s.unaccounted ? HitResult.Special.UNACCOUNTED : HitResult.Special.FREE_SPACE;
			}
		}
		return HitResult.Special.NONE;
	}

	// ---- layout ---------------------------------------------------------

	/**
	 * Inner radius of the ring at layout depth {@code depth}. Depths {@code <= NORMAL_RINGS} use full-width rings;
	 * deeper depths use thin rings. Accepts fractional depths so the drill-in animation can interpolate radii
	 * smoothly.
	 */
	private static double ringInnerR(double depth, double normalW, double thinW) {
		if (depth <= 1)
			return HUB_RADIUS;
		double normalRings = Math.min(depth - 1, NORMAL_RINGS);
		double thinRings = Math.max(0, depth - 1 - NORMAL_RINGS);
		return HUB_RADIUS + normalRings * normalW + thinRings * thinW;
	}

	/**
	 * Compute the layout map for {@code rootForView}. Public so the host can pre-compute before/after layouts and hand
	 * them to {@link #beginAnimation}.
	 */
	public Map<DirectoryNode, Layout> computeLayout(DirectoryNode rootForView, double w, double h, RenderContext ctx) {
		Map<DirectoryNode, Layout> out = new HashMap<>();
		if (rootForView == null)
			return out;

		double maxR = Math.max(40, Math.min(w, h) * 0.46);
		double normalW = (maxR - HUB_RADIUS) / (NORMAL_RINGS + THIN_RINGS * THIN_RING_FACTOR);
		if (normalW < 6)
			normalW = 6;
		double thinW = normalW * THIN_RING_FACTOR;

		// When viewing the scan root, ring 1 is the root's children (no anchor ring). When drilled into a sector,
		// ring 1 is that sector at 360° anchoring the view, and ring 2 onward shows its descendants.
		boolean rootHasRing = rootForView != ctx.scanRoot();
		if (rootHasRing) {
			out.put(rootForView, new Layout(1, 90.0, 360.0, host.colors().colorFor(rootForView)));
			layoutChildrenInto(rootForView, 2, 90.0, 360.0, out, normalW, thinW, ctx);
		} else {
			Volume target = ctx.target();
			// When hide-free-space is on (H), the data fills the full 360 -- no free arc, no centred "used wedge".
			// Unaccounted bytes still get their proportional slice via scannedSweep below; they're scanned-but-
			// unattributed, which the user cares about even when they don't want to see the free remainder.
			double usedSweep = ctx.hideFreeSpace() || target.totalBytes() <= 0 ? 360.0 : target.usedFraction() * 360.0;
			double startAngle = 90.0 - usedSweep / 2.0;
			double scannedSweep = target.usedBytes() > 0 ? Math.min(usedSweep,
					usedSweep * rootForView.totalBytes() / (double) target.usedBytes()) : usedSweep;
			layoutChildrenInto(rootForView, 1, startAngle, scannedSweep, out, normalW, thinW, ctx);
		}
		return out;
	}

	/**
	 * Recursive sunburst layout. Two culling thresholds compose:
	 * <ul>
	 *   <li>{@link #MIN_VISIBLE_SWEEP_DEG} — a fixed angle floor, cheapest check; cuts most slivers.</li>
	 *   <li>Pixel-arc check — at the outer radius of this depth's ring, require {@code >= 1 px} of arc length.
	 *       Sectors below that can't render visibly even at high DPI, and crucially we save the recursive descent
	 *       into their subtrees.</li>
	 * </ul>
	 * Both checks let the sweep "consume" the angle (via {@code a += childSweep}) so non-culled siblings keep
	 * their proportional positions; we just skip the {@link Layout} allocation and the recursion for invisible
	 * sectors.
	 */
	private void layoutChildrenInto(
			DirectoryNode parent, int depth, double startDeg, double sweepDeg,
			Map<DirectoryNode, Layout> out, double normalW, double thinW, RenderContext ctx) {
		if (depth > MAX_DEPTH)
			return;
		long total = parent.totalBytes();
		if (total <= 0)
			return;

		double outerR = ringInnerR(depth + 1, normalW, thinW);
		double minSweepFromPixels = Math.toDegrees(1.0 / outerR);

		NodeColorResolver colors = host.colors();

		// Fast path: parent's children are sort-stable AND the parent isn't scanRoot (whose children may include
		// the synthetic hiddenNode, which we always pin last regardless of size). For everyone else,
		// parent.children() is already in size-desc order — skip the snapshot+sort and iterate directly,
		// reading totalBytes() inline. This kills the per-render TimSort that JFR flagged as the dominant
		// render-CPU cost on large trees (1.38M-node SUNBURST snapshots).
		if (parent != ctx.scanRoot() && parent.isSortStableByTotalBytes()) {
			double a = startDeg;
			for (DirectoryNode child : parent.children()) {
				long size = child.totalBytes();
				double frac = size / (double) total;
				double childSweep = sweepDeg * frac;
				if (childSweep < MIN_VISIBLE_SWEEP_DEG || childSweep < minSweepFromPixels) {
					a += childSweep;
					continue;
				}
				out.put(child, new Layout(depth, a, childSweep, colors.colorFor(child)));
				layoutChildrenInto(child, depth + 1, a, childSweep, out, normalW, thinW, ctx);
				a += childSweep;
			}
			return;
		}

		// Slow path (mid-scan, OR scanRoot which needs Hidden pinned last): snapshot child sizes alongside the
		// nodes so the comparator can read primitive long fields (no boxing, no map lookup per comparison).
		List<SizedNode> ordered = snapshotSized(parent.children());
		final DirectoryNode hidden = ctx.hiddenNode();
		ordered.sort((a, b) -> {
			boolean aHidden = (a.node == hidden);
			boolean bHidden = (b.node == hidden);
			if (aHidden != bHidden)
				return aHidden ? 1 : -1;
			return Long.compare(b.size, a.size);
		});

		double a = startDeg;
		for (SizedNode s : ordered) {
			double frac = s.size / (double) total;
			double childSweep = sweepDeg * frac;
			if (childSweep < MIN_VISIBLE_SWEEP_DEG || childSweep < minSweepFromPixels) {
				a += childSweep;
				continue;
			}
			out.put(s.node, new Layout(depth, a, childSweep, colors.colorFor(s.node)));
			layoutChildrenInto(s.node, depth + 1, a, childSweep, out, normalW, thinW, ctx);
			a += childSweep;
		}
	}

	// ---- drawing --------------------------------------------------------

	private void drawLayout(
			GraphicsContext g, double cx, double cy, double normalW, double thinW,
			Map<DirectoryNode, Layout> layout, RenderContext ctx) {
		// Render outer rings first so any anti-aliasing edges are overdrawn cleanly by the inner rings.
		List<Map.Entry<DirectoryNode, Layout>> entries = new ArrayList<>(layout.entrySet());
		entries.sort((a, b) -> Double.compare(b.getValue().depth(), a.getValue().depth()));

		for (Map.Entry<DirectoryNode, Layout> entry : entries) {
			DirectoryNode node = entry.getKey();
			Layout l = entry.getValue();
			if (l.sweepDeg() < MIN_VISIBLE_SWEEP_DEG)
				continue;

			double r1 = ringInnerR(l.depth(), normalW, thinW);
			double r2 = ringInnerR(l.depth() + 1, normalW, thinW);

			Color base = l.color();
			double alpha = node.isDone() ? 1.0 : 0.45;
			if (ctx.hoverNode() == node) {
				// Universal hover: slight darkening + saturation boost. JavaFX brightness clamps at 1.0, so
				// .brighter() on already-light rim sectors is a no-op — darken-plus-saturate guarantees visible
				// change for both vivid and grey.
				base = base.deriveColor(0, 1.20, 0.85, 1.0);
				alpha = Math.min(1.0, alpha + 0.10);
			}
			Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
			drawAnnularSector(g, cx, cy, r1, r2, l.startDeg(), l.sweepDeg(), fill);
			sectors.add(new SectorRect(node, (int) l.depth(), l.startDeg(), l.sweepDeg(), r1, r2, false));
		}

		// "Used" overlay for the scan-root view: the free-space arc (and any unaccounted slice) only render when
		// we're looking at the scan root, because that's the only context where they make sense — once you drill
		// into a folder, the chart is about that folder's contents, not the volume's free space.
		Volume target = ctx.target();
		if (ctx.viewRoot() == ctx.scanRoot() && target.totalBytes() > 0) {
			// Same usedSweep contract as computeLayout above: hide-free-space mode forces the data to span 360 so
			// the free arc (computed later as 360 - usedSweep) collapses to zero and isn't drawn. Unaccounted still
			// shows up because its sweep is a fraction of usedSweep.
			double usedFraction = target.usedFraction();
			double usedSweep = ctx.hideFreeSpace() ? 360.0 : usedFraction * 360.0;
			double startAngle = 90.0 - usedSweep / 2.0;
			double r1 = HUB_RADIUS;
			double r2 = ringInnerR(2, normalW, thinW);

			DirectoryNode scanRoot = ctx.scanRoot();
			long scannedBytes = scanRoot != null ? scanRoot.totalBytes() : 0;
			long unaccountedBytes = Math.max(0, target.usedBytes() - scannedBytes);
			if (unaccountedBytes > 0 && target.usedBytes() > 0) {
				double unaccountedFrac = (double) unaccountedBytes / target.usedBytes();
				double unaccountedSweep = unaccountedFrac * usedSweep;
				double unaccountedStart = startAngle + usedSweep - unaccountedSweep;
				if (unaccountedSweep > MIN_VISIBLE_SWEEP_DEG) {
					Color unaccountedColor = ctx.hoveringUnaccounted() ? host.scheme().surface().brighter().brighter()
							: host.scheme().surface().brighter();
					drawAnnularSector(g, cx, cy, r1, r2, unaccountedStart, unaccountedSweep, unaccountedColor);
					sectors.add(new SectorRect(null, 1, unaccountedStart, unaccountedSweep, r1, r2, true));
				}
			}

			double freeSweep = 360.0 - usedSweep;
			if (freeSweep > MIN_VISIBLE_SWEEP_DEG) {
				double freeStart = startAngle + usedSweep;
				Color freeColor = ctx.hoveringFreeSpace() ? host.scheme().capacityTrack().brighter()
						: host.scheme().capacityTrack();
				drawAnnularSector(g, cx, cy, r1, r2, freeStart, freeSweep, freeColor);
				sectors.add(new SectorRect(null, 1, freeStart, freeSweep, r1, r2, false));
			}
		}
	}

	private void drawAnimatedFrame(
			GraphicsContext g, double cx, double cy, double normalW, double thinW,
			RenderContext ctx) {
		long elapsed = System.nanoTime() - animStartNanos;
		double t = Math.min(1.0, elapsed / (double) ANIM_DURATION_NANOS);
		double e = easeOutCubic(t);

		Set<DirectoryNode> all = new HashSet<>(animOld.keySet());
		all.addAll(animNew.keySet());

		NodeColorResolver colors = host.colors();
		List<FrameEntry> frame = new ArrayList<>(all.size());
		for (DirectoryNode n : all) {
			Layout o = animOld.get(n);
			Layout w = animNew.get(n);
			Layout from, to;
			double alphaScale = 1.0;
			Color color = colors.colorFor(n);
			if (o != null && w != null) {
				from = o;
				to = w;
			} else if (o != null) {
				if (n == animOldViewRoot) {
					// Outgoing inner ring (drill-in): stay in place, fade out as the new viewRoot grows over it.
					from = o;
					to = o;
					alphaScale = 1 - e;
				} else {
					// Sibling/cousin not in new view: shrink in place.
					from = o;
					to = new Layout(o.depth(), o.startDeg() + o.sweepDeg() / 2, 0, color);
				}
			} else {
				if (n == animNewViewRoot) {
					// Incoming inner ring (drill-out): fade in at destination.
					from = w;
					to = w;
					alphaScale = e;
				} else {
					// Newly visible deep node: grow from a point.
					from = new Layout(w.depth(), w.startDeg() + w.sweepDeg() / 2, 0, color);
					to = w;
				}
			}
			double depth = lerp(from.depth(), to.depth(), e);
			double start = lerp(from.startDeg(), to.startDeg(), e);
			double sweep = lerp(from.sweepDeg(), to.sweepDeg(), e);
			if (sweep < 0.05)
				continue;
			frame.add(new FrameEntry(n, depth, start, sweep, alphaScale, color));
		}

		// Render outer rings first so inner rings overdraw on radial overlap regions. For ties on depth (e.g.,
		// growing clicked sector overlapping shrinking siblings in the same ring), draw smaller sweeps first so
		// the larger sweep overdraws.
		frame.sort(Comparator.comparingDouble(FrameEntry::depth).reversed().thenComparingDouble(FrameEntry::sweep));

		for (FrameEntry fe : frame) {
			double r1 = Math.max(1, ringInnerR(fe.depth, normalW, thinW));
			double r2 = Math.max(r1 + 1, ringInnerR(fe.depth + 1, normalW, thinW));
			Color base = fe.color;
			double alpha = (fe.node.isDone() ? 1.0 : 0.45) * fe.alphaScale;
			if (alpha <= 0.001)
				continue;
			Color fill = (alpha >= 1.0) ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
			drawAnnularSector(g, cx, cy, r1, r2, fe.start, fe.sweep, fill);
		}
	}

	private void drawAnnularSector(
			GraphicsContext g, double cx, double cy, double r1, double r2, double startDeg,
			double sweepDeg, Color fill) {
		double a1 = Math.toRadians(startDeg);
		double a2 = Math.toRadians(startDeg + sweepDeg);
		g.setFill(fill);
		g.beginPath();
		g.moveTo(cx + r2 * Math.cos(a1), cy - r2 * Math.sin(a1));
		g.arc(cx, cy, r2, r2, startDeg, sweepDeg);
		g.lineTo(cx + r1 * Math.cos(a2), cy - r1 * Math.sin(a2));
		g.arc(cx, cy, r1, r1, startDeg + sweepDeg, -sweepDeg);
		g.closePath();
		g.fill();

		g.setStroke(host.scheme().background());
		g.setLineWidth(0.8);
		g.stroke();
	}

	private void drawHub(GraphicsContext g, double cx, double cy, RenderContext ctx) {
		Color hubFill = ctx.hoveringHub() ? host.scheme().surface().brighter() : host.scheme().surface();
		g.setFill(hubFill);
		g.fillOval(cx - HUB_RADIUS, cy - HUB_RADIUS, HUB_RADIUS * 2, HUB_RADIUS * 2);

		Volume target = ctx.target();
		DirectoryNode scanRoot = ctx.scanRoot();
		String title;
		String subtitle;
		if (ctx.hoveringFreeSpace()) {
			title = "Free";
			subtitle = SizeFormat.format(target.usableBytes());
		} else if (ctx.hoveringUnaccounted()) {
			title = "Other";
			subtitle = SizeFormat.format(
					Math.max(0, target.usedBytes() - (scanRoot != null ? scanRoot.totalBytes() : 0)));
		} else {
			DirectoryNode focus;
			if (ctx.hoveringHub()) {
				focus = scanRoot;
			} else if (ctx.hoverNode() != null) {
				focus = ctx.hoverNode();
			} else {
				focus = ctx.viewRoot();
			}
			if (focus == null)
				return;
			if (ctx.scanning() && focus == scanRoot && !ctx.hoveringHub() && ctx.hoverNode() == null) {
				// Scanner-driven overrides take precedence; null fields fall back to the bytes/files text that
				// the parallel scanner is happy with.
				Scanner.HubState hs = ctx.hubState();
				title = hs.title() != null ? hs.title() : SizeFormat.format(ctx.progressBytes());
				subtitle = hs.subtitle() != null ? hs.subtitle() : (ctx.progressFiles() + " files");
			} else {
				title = (focus == scanRoot) ? target.displayName() : focus.name();
				subtitle = SizeFormat.format(focus.totalBytes());
			}
		}

		g.setFill(host.scheme().textPrimary());
		g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.fillText(truncate(title, 18), cx, cy - 10, HUB_RADIUS * 1.7);

		g.setFill(host.scheme().textMuted());
		g.setFont(Font.font("Segoe UI", 11));
		g.fillText(subtitle, cx, cy + 8, HUB_RADIUS * 1.7);

		// Third line: only during scan, current path tail.
		if (ctx.scanning() && ctx.hoverNode() == null && !ctx.hoveringHub() && ctx.progressPath() != null) {
			g.setFill(host.scheme().textMuted().deriveColor(0, 1, 1, 0.6));
			g.setFont(Font.font("Segoe UI", 10));
			g.fillText(truncate(tailPath(ctx.progressPath()), 22), cx, cy + 24, HUB_RADIUS * 1.85);
		}

		if (ctx.scanning()) {
			drawHubProgress(g, cx, cy, ctx);
		}
	}

	private void drawHubProgress(GraphicsContext g, double cx, double cy, RenderContext ctx) {
		double r = HUB_RADIUS - 4;
		double thickness = 2.5;

		g.setStroke(host.scheme().accent());
		g.setLineWidth(thickness);
		g.setLineCap(StrokeLineCap.ROUND);

		// Scanner override wins; otherwise compute bytes/usedBytes ourselves (the parallel scanner relies on
		// the fallback). Negative = "no override, use the default path."
		double frac = ctx.hubState().arcFraction();
		if (frac < 0) {
			Volume target = ctx.target();
			long usedBytes = target.totalBytes() - target.usableBytes();
			if (usedBytes > 0 && ctx.progressBytes() > 0) {
				frac = Math.min(1.0, ctx.progressBytes() / (double) usedBytes);
			}
		}

		if (frac >= 0) {
			// Faint full track first.
			g.setStroke(host.scheme().accent().deriveColor(0, 1, 1, 0.18));
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90, 360, ArcType.OPEN);
			// Filled portion clockwise from 12 o'clock.
			g.setStroke(host.scheme().accent());
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90, -360 * frac, ArcType.OPEN);
		} else {
			// Indeterminate: a 60° segment that rotates clockwise once every ~1.6s.
			double offset = (System.nanoTime() / 1_000_000.0 / 4.5) % 360.0; // deg/ms-ish
			g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90 - offset, -60, ArcType.OPEN);
		}
	}

	private void drawCenterText(GraphicsContext g, double cx, double cy, String text) {
		g.setFill(host.scheme().textMuted());
		g.setTextAlign(TextAlignment.CENTER);
		g.setTextBaseline(VPos.CENTER);
		g.setFont(Font.font("Segoe UI", 13));
		g.fillText(text, cx, cy);
	}

	// ---- helpers --------------------------------------------------------

	private static boolean angleInSweep(double theta, double start, double sweep) {
		// Normalize start to [0, 360). Layout angles can grow past 360° as the iterator walks counterclockwise
		// around the full circle (sectors in the upper-right quadrant end up with start > 360°). Without
		// normalization the wrap branch below mis-classifies their range.
		start = ((start % 360) + 360) % 360;
		double end = start + sweep;
		if (end <= 360)
			return theta >= start && theta <= end;
		return theta >= start || theta <= (end - 360);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double easeOutCubic(double t) {
		double inv = 1 - t;
		return 1 - inv * inv * inv;
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

	private static List<SizedNode> snapshotSized(Collection<DirectoryNode> nodes) {
		List<SizedNode> out = new ArrayList<>(nodes.size());
		for (DirectoryNode n : nodes)
			out.add(new SizedNode(n, n.totalBytes()));
		return out;
	}

	// ---- value types ----------------------------------------------------

	/**
	 * Layout entry for one sunburst sector. {@code depth} is fractional so the drill animation can interpolate ring
	 * radii smoothly between depth steps.
	 */
	public record Layout(double depth, double startDeg, double sweepDeg, Color color) {
	}

	/** Frame-time sector after lerping {@link Layout}s for the animation. */
	private record FrameEntry(DirectoryNode node, double depth, double start, double sweep, double alphaScale,
	                          Color color) {
	}

	/**
	 * Hit-test entry recorded during draw. {@code unaccounted=true} flags the special "Other" arc segment, which has no
	 * associated node; otherwise {@code node==null} means free-space arc.
	 */
	private record SectorRect(DirectoryNode node, int depth, double startDeg, double sweepDeg, double r1, double r2,
	                          boolean unaccounted) {
	}

	/** Frozen-size snapshot of a node, used so the sort comparator can read primitive {@code long} fields. */
	private record SizedNode(DirectoryNode node, long size) {
	}
}
