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

import java.util.Locale;

/**
 * {@link RenderSurface} that records drawing calls as an SVG document instead of painting pixels. Used to export a
 * visualization as crisp, resolution-independent vector art — driven by the exact same {@code render(...)} code that
 * paints the canvas, so the output always matches what's on screen.
 * <p>The surface works in the same y-down pixel space as the canvas, so the {@code viewBox} is simply {@code 0 0
 * width height} and no coordinate flipping is needed — callers already pass final pixel coordinates.</p>
 * <h2>Notable conversions</h2>
 * <ul>
 *   <li><b>Arcs.</b> {@code GraphicsContext.arc}'s "line to start, then arc" semantics map to an SVG {@code L} followed
 *       by {@code A}. The SVG sweep-flag is inverted relative to our angle sense: a positive (CCW, y-up) extent draws
 *       counter-clockwise on screen, which is sweep-flag {@code 0} in SVG's y-down system. Arcs of 360° or more can't
 *       be expressed as a single {@code A} command and are split in half.</li>
 *   <li><b>Numbers.</b> Always formatted with {@link Locale#ROOT}; the default locale here may be Swiss, whose decimal
 *       comma would otherwise corrupt every coordinate.</li>
 *   <li><b>Fonts.</b> Family is emitted with a {@code sans-serif} fallback because the UI font (Segoe UI) won't exist on
 *       every machine that opens the SVG; weight/style are recovered from the JavaFX {@link Font} name.</li>
 * </ul>
 * Not thread-safe; build and serialize on one thread (the JavaFX application thread, where rendering happens).
 */
public final class SvgSurface implements RenderSurface {

	private static final double FULL_CIRCLE_EPS = 359.999;

	private final StringBuilder body = new StringBuilder(4096);

	private Color fill = Color.BLACK;
	private Color stroke = Color.BLACK;
	private double lineWidth = 1.0;
	private StrokeLineCap lineCap;
	private Font font = Font.getDefault();
	private TextAlignment textAlign = TextAlignment.LEFT;
	private VPos textBaseline = VPos.BASELINE;

	/** Accumulated `d` for the path currently being built; {@code null} until {@link #beginPath()}. */
	private StringBuilder path;

	/**
	 * Wrap everything drawn so far in a complete, standalone SVG document sized to the given pixel dimensions.
	 */
	public String toSvg(double width, double height) {
		StringBuilder doc = new StringBuilder(body.length() + 256);
		doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
		doc.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(num(width)).append("\" height=\"")
				.append(num(height)).append("\" viewBox=\"0 0 ").append(num(width)).append(' ').append(num(height))
				.append("\">\n");
		doc.append(body);
		doc.append("</svg>\n");
		return doc.toString();
	}

	// ---- state ----------------------------------------------------------

	@Override
	public void setFill(Color color) {
		this.fill = color;
	}

	@Override
	public void setStroke(Color color) {
		this.stroke = color;
	}

	@Override
	public void setLineWidth(double width) {
		this.lineWidth = width;
	}

	@Override
	public void setLineCap(StrokeLineCap cap) {
		this.lineCap = cap;
	}

	@Override
	public void setFont(Font font) {
		this.font = font;
	}

	@Override
	public void setTextAlign(TextAlignment align) {
		this.textAlign = align;
	}

	@Override
	public void setTextBaseline(VPos baseline) {
		this.textBaseline = baseline;
	}

	// ---- path building --------------------------------------------------

	@Override
	public void beginPath() {
		path = new StringBuilder(128);
	}

	@Override
	public void moveTo(double x, double y) {
		ensurePath().append("M ").append(num(x)).append(' ').append(num(y)).append(' ');
	}

	@Override
	public void lineTo(double x, double y) {
		ensurePath().append("L ").append(num(x)).append(' ').append(num(y)).append(' ');
	}

	@Override
	public void arc(double centerX, double centerY, double radiusX, double radiusY, double startAngle, double length) {
		appendArc(ensurePath(), centerX, centerY, radiusX, radiusY, startAngle, length, true);
	}

	@Override
	public void closePath() {
		ensurePath().append("Z ");
	}

	@Override
	public void fill() {
		if (path == null || path.length() == 0)
			return;
		body.append("  <path d=\"").append(path.toString().trim()).append('"');
		paint("fill", fill);
		body.append("/>\n");
	}

	@Override
	public void stroke() {
		if (path == null || path.length() == 0)
			return;
		body.append("  <path d=\"").append(path.toString().trim()).append("\" fill=\"none\"");
		paint("stroke", stroke);
		strokeProps();
		body.append("/>\n");
	}

	// ---- direct shapes --------------------------------------------------

	@Override
	public void fillOval(double x, double y, double w, double h) {
		double rx = w / 2.0;
		double ry = h / 2.0;
		double cx = x + rx;
		double cy = y + ry;
		if (rx == ry) {
			body.append("  <circle cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" r=\"")
					.append(num(rx)).append('"');
		} else {
			body.append("  <ellipse cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" rx=\"")
					.append(num(rx)).append("\" ry=\"").append(num(ry)).append('"');
		}
		paint("fill", fill);
		body.append("/>\n");
	}

	@Override
	public void fillRect(double x, double y, double w, double h) {
		rect(x, y, w, h, 0, 0, true);
	}

	@Override
	public void strokeRect(double x, double y, double w, double h) {
		rect(x, y, w, h, 0, 0, false);
	}

	@Override
	public void fillRoundRect(double x, double y, double w, double h, double arcWidth, double arcHeight) {
		// GraphicsContext arcWidth/arcHeight are full corner-arc diameters; SVG rx/ry are radii.
		rect(x, y, w, h, arcWidth / 2.0, arcHeight / 2.0, true);
	}

	@Override
	public void strokeArc(double x, double y, double w, double h, double startAngle, double arcExtent, ArcType closure) {
		double rx = w / 2.0;
		double ry = h / 2.0;
		double cx = x + rx;
		double cy = y + ry;
		if (Math.abs(arcExtent) >= FULL_CIRCLE_EPS) {
			// A full ring can't be one <path> arc; a stroked circle/ellipse renders it cleanly.
			if (rx == ry) {
				body.append("  <circle cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" r=\"")
						.append(num(rx)).append("\" fill=\"none\"");
			} else {
				body.append("  <ellipse cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" rx=\"")
						.append(num(rx)).append("\" ry=\"").append(num(ry)).append("\" fill=\"none\"");
			}
			paint("stroke", stroke);
			strokeProps();
			body.append("/>\n");
			return;
		}
		double a0 = Math.toRadians(startAngle);
		double sx = cx + rx * Math.cos(a0);
		double sy = cy - ry * Math.sin(a0);
		StringBuilder d = new StringBuilder(64);
		d.append("M ").append(num(sx)).append(' ').append(num(sy)).append(' ');
		appendArc(d, cx, cy, rx, ry, startAngle, arcExtent, false);
		body.append("  <path d=\"").append(d.toString().trim()).append("\" fill=\"none\"");
		paint("stroke", stroke);
		strokeProps();
		body.append("/>\n");
	}

	// ---- text -----------------------------------------------------------

	@Override
	public void fillText(String text, double x, double y) {
		fillText(text, x, y, -1);
	}

	@Override
	public void fillText(String text, double x, double y, double maxWidth) {
		// maxWidth is a clip hint on canvas; the painters pre-truncate their strings, so the export ignores it.
		if (text == null || text.isEmpty())
			return;
		body.append("  <text x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" font-family=\"")
				.append(escape(font.getFamily())).append(", sans-serif\" font-size=\"").append(num(font.getSize()))
				.append('"');
		String weight = fontWeight();
		if (weight != null)
			body.append(" font-weight=\"").append(weight).append('"');
		if (isItalic())
			body.append(" font-style=\"italic\"");
		body.append(" text-anchor=\"").append(anchor()).append("\" dominant-baseline=\"").append(baseline()).append('"');
		paint("fill", fill);
		body.append('>').append(escape(text)).append("</text>\n");
	}

	// ---- helpers --------------------------------------------------------

	private StringBuilder ensurePath() {
		if (path == null)
			path = new StringBuilder(128);
		return path;
	}

	private void rect(double x, double y, double w, double h, double rx, double ry, boolean filled) {
		body.append("  <rect x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" width=\"").append(num(w))
				.append("\" height=\"").append(num(h)).append('"');
		if (rx > 0)
			body.append(" rx=\"").append(num(rx)).append('"');
		if (ry > 0)
			body.append(" ry=\"").append(num(ry)).append('"');
		if (filled) {
			paint("fill", fill);
		} else {
			body.append(" fill=\"none\"");
			paint("stroke", stroke);
			strokeProps();
		}
		body.append("/>\n");
	}

	/**
	 * Append one arc segment to {@code d}. When {@code connect} is true, a leading {@code L} to the arc's start point
	 * reproduces {@link javafx.scene.canvas.GraphicsContext#arc}'s implicit line-to-start. Arcs spanning a full circle
	 * or more are halved, since a single SVG {@code A} command degenerates at 360°.
	 */
	private void appendArc(
			StringBuilder d, double cx, double cy, double rx, double ry, double start, double length, boolean connect) {
		if (Math.abs(length) >= FULL_CIRCLE_EPS) {
			double half = length / 2.0;
			appendArc(d, cx, cy, rx, ry, start, half, connect);
			appendArc(d, cx, cy, rx, ry, start + half, half, false);
			return;
		}
		double a0 = Math.toRadians(start);
		double a1 = Math.toRadians(start + length);
		double sx = cx + rx * Math.cos(a0);
		double sy = cy - ry * Math.sin(a0);
		double ex = cx + rx * Math.cos(a1);
		double ey = cy - ry * Math.sin(a1);
		int largeArc = Math.abs(length) > 180.0 ? 1 : 0;
		// Positive length is CCW in our y-up angle math, which renders counter-clockwise on the y-down screen — that's
		// SVG sweep-flag 0. Negative length is the clockwise case, flag 1.
		int sweep = length >= 0 ? 0 : 1;
		if (connect)
			d.append("L ").append(num(sx)).append(' ').append(num(sy)).append(' ');
		d.append("A ").append(num(rx)).append(' ').append(num(ry)).append(" 0 ").append(largeArc).append(' ')
				.append(sweep).append(' ').append(num(ex)).append(' ').append(num(ey)).append(' ');
	}

	private void paint(String attr, Color c) {
		int r = (int) Math.round(c.getRed() * 255);
		int g = (int) Math.round(c.getGreen() * 255);
		int b = (int) Math.round(c.getBlue() * 255);
		body.append(' ').append(attr).append("=\"rgb(").append(r).append(',').append(g).append(',').append(b)
				.append(")\"");
		double opacity = c.getOpacity();
		if (opacity < 1.0)
			body.append(' ').append(attr).append("-opacity=\"").append(num(opacity)).append('"');
	}

	private void strokeProps() {
		body.append(" stroke-width=\"").append(num(lineWidth)).append('"');
		if (lineCap != null && lineCap != StrokeLineCap.BUTT)
			body.append(" stroke-linecap=\"").append(lineCap.name().toLowerCase(Locale.ROOT)).append('"');
	}

	private String anchor() {
		return switch (textAlign) {
			case CENTER -> "middle";
			case RIGHT -> "end";
			default -> "start";
		};
	}

	private String baseline() {
		return switch (textBaseline) {
			case TOP -> "hanging";
			case CENTER -> "central";
			case BOTTOM -> "text-after-edge";
			default -> "alphabetic";
		};
	}

	private String fontWeight() {
		String s = (font.getName() + ' ' + font.getStyle()).toLowerCase(Locale.ROOT);
		if (s.contains("semi") || s.contains("demi"))
			return "600";
		if (s.contains("bold"))
			return "bold";
		return null;
	}

	private boolean isItalic() {
		return (font.getName() + ' ' + font.getStyle()).toLowerCase(Locale.ROOT).contains("italic");
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			switch (ch) {
				case '&' -> sb.append("&amp;");
				case '<' -> sb.append("&lt;");
				case '>' -> sb.append("&gt;");
				case '"' -> sb.append("&quot;");
				default -> sb.append(ch);
			}
		}
		return sb.toString();
	}

	/** Locale-independent, compact number: integers print without a decimal point, others to 3 dp with trailing zeros trimmed. */
	private static String num(double v) {
		if (v == Math.rint(v) && !Double.isInfinite(v))
			return Long.toString((long) v);
		String s = String.format(Locale.ROOT, "%.3f", v);
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '0')
			end--;
		if (end > 0 && s.charAt(end - 1) == '.')
			end--;
		return s.substring(0, end);
	}
}
