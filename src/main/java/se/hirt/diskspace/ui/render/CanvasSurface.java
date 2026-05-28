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
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * {@link RenderSurface} backed by a live JavaFX {@link GraphicsContext}. Pure 1:1 delegation — this is the on-screen
 * path, so it adds nothing beyond forwarding each call to the canvas. The interface was modelled on the exact subset of
 * {@code GraphicsContext} the painters use precisely so this class can stay a trivial passthrough.
 */
public final class CanvasSurface implements RenderSurface {

	private final GraphicsContext g;

	public CanvasSurface(GraphicsContext g) {
		this.g = g;
	}

	@Override
	public void setFill(Color color) {
		g.setFill(color);
	}

	@Override
	public void setStroke(Color color) {
		g.setStroke(color);
	}

	@Override
	public void setLineWidth(double width) {
		g.setLineWidth(width);
	}

	@Override
	public void setLineCap(StrokeLineCap cap) {
		g.setLineCap(cap);
	}

	@Override
	public void setFont(Font font) {
		g.setFont(font);
	}

	@Override
	public void setTextAlign(TextAlignment align) {
		g.setTextAlign(align);
	}

	@Override
	public void setTextBaseline(VPos baseline) {
		g.setTextBaseline(baseline);
	}

	@Override
	public void beginPath() {
		g.beginPath();
	}

	@Override
	public void moveTo(double x, double y) {
		g.moveTo(x, y);
	}

	@Override
	public void lineTo(double x, double y) {
		g.lineTo(x, y);
	}

	@Override
	public void arc(double centerX, double centerY, double radiusX, double radiusY, double startAngle, double length) {
		g.arc(centerX, centerY, radiusX, radiusY, startAngle, length);
	}

	@Override
	public void closePath() {
		g.closePath();
	}

	@Override
	public void fill() {
		g.fill();
	}

	@Override
	public void stroke() {
		g.stroke();
	}

	@Override
	public void fillOval(double x, double y, double w, double h) {
		g.fillOval(x, y, w, h);
	}

	@Override
	public void fillRect(double x, double y, double w, double h) {
		g.fillRect(x, y, w, h);
	}

	@Override
	public void strokeRect(double x, double y, double w, double h) {
		g.strokeRect(x, y, w, h);
	}

	@Override
	public void fillRoundRect(double x, double y, double w, double h, double arcWidth, double arcHeight) {
		g.fillRoundRect(x, y, w, h, arcWidth, arcHeight);
	}

	@Override
	public void strokeArc(double x, double y, double w, double h, double startAngle, double arcExtent, ArcType closure) {
		g.strokeArc(x, y, w, h, startAngle, arcExtent, closure);
	}

	@Override
	public void fillText(String text, double x, double y) {
		g.fillText(text, x, y);
	}

	@Override
	public void fillText(String text, double x, double y, double maxWidth) {
		g.fillText(text, x, y, maxWidth);
	}
}
