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
package se.hirt.diskspace.nativeimage;

import java.io.InputStream;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.sun.javafx.iio.ImageLoader;

/**
 * Native-image only: keeps AWT, Java2D and ImageIO out of the executable.
 * <p>When none of JavaFX's built-in decoders (PNG, JPEG, GIF, BMP) recognises an image, {@code ImageStorage} falls
 * back to {@code com.sun.javafx.iio.java2d.J2DImageLoaderFactory}, which decodes through {@code javax.imageio}. It
 * looks the factory up with {@code Class.forName} on a constant name, which native-image folds at build time, so the
 * whole ImageIO / Java2D / AWT graph becomes reachable from any {@code Image} load and the build ships
 * {@code libawt}, {@code libawt_xawt}, {@code libjavajpeg}, {@code liblcms} and {@code libfontmanager}.
 * <p>DiskSpace only loads PNGs (its own icons and the modena stylesheet's), all handled by the built-in decoders, so
 * the fallback never runs. Substituting the lookup with JavaFX's own "java.desktop isn't available" answer
 * ({@code null}) removes it from the analysis. If a future JavaFX renames or re-signatures the method, the native build
 * fails with "could not find target method". AWT arriving by some other route would go unnoticed here; the CI build
 * checks for that by failing when AWT libraries show up next to the executable (see docs/DEVGUIDE.md).
 * <p>The JVM never loads this class; the annotations are only read by the native-image builder.
 */
@TargetClass(className = "com.sun.javafx.iio.ImageStorage")
final class Target_com_sun_javafx_iio_ImageStorage {

	@Substitute
	private ImageLoader tryCreateJ2DImageLoader(InputStream stream) {
		return null;
	}
}
