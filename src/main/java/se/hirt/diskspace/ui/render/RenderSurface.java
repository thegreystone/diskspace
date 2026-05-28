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
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The drawing surface a {@link Visualization} paints onto. It is a deliberately thin subset of JavaFX's
 * {@link javafx.scene.canvas.GraphicsContext} — exactly the primitives the sunburst and heatmap painters use, with the
 * same method names and argument shapes so a visualization's draw code reads identically whether it is targeting the
 * live canvas or an exporter.
 * <p>Two implementations exist: {@link CanvasSurface} delegates straight through to a {@code GraphicsContext} for
 * on-screen rendering, and {@link SvgSurface} emits an SVG document. Because both visualizations draw through this one
 * interface, an SVG export can never silently drift from what's on screen — there is a single drawing path.</p>
 * <p>Coordinate and angle conventions match {@code GraphicsContext} verbatim: pixel space with the origin at the
 * top-left and {@code y} growing downward, and arc angles in degrees measured counter-clockwise from the positive
 * x-axis (so callers that compute points as {@code (cx + r*cos a, cy - r*sin a)} stay correct on every surface).</p>
 */
public interface RenderSurface {

	// ---- paint / stroke / text state ------------------------------------

	void setFill(Color color);

	void setStroke(Color color);

	void setLineWidth(double width);

	void setLineCap(StrokeLineCap cap);

	void setFont(Font font);

	void setTextAlign(TextAlignment align);

	void setTextBaseline(VPos baseline);

	// ---- path building --------------------------------------------------

	void beginPath();

	void moveTo(double x, double y);

	void lineTo(double x, double y);

	/**
	 * Append an arc to the current path, matching {@link javafx.scene.canvas.GraphicsContext#arc}: a line is drawn from
	 * the current point to the arc's start point, then the arc itself. Angles are degrees, CCW-positive, measured from
	 * the positive x-axis in the same y-down pixel space as every other coordinate here.
	 */
	void arc(double centerX, double centerY, double radiusX, double radiusY, double startAngle, double length);

	void closePath();

	/** Fill the current path with the current fill paint. The path is retained so a following {@link #stroke()} reuses it. */
	void fill();

	/** Stroke the current path with the current stroke paint / line width. */
	void stroke();

	// ---- direct shapes --------------------------------------------------

	void fillOval(double x, double y, double w, double h);

	void fillRect(double x, double y, double w, double h);

	void strokeRect(double x, double y, double w, double h);

	void fillRoundRect(double x, double y, double w, double h, double arcWidth, double arcHeight);

	void strokeArc(double x, double y, double w, double h, double startAngle, double arcExtent, ArcType closure);

	// ---- text -----------------------------------------------------------

	void fillText(String text, double x, double y);

	void fillText(String text, double x, double y, double maxWidth);
}
