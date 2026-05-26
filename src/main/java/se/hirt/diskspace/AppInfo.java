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
package se.hirt.diskspace;

import org.graalvm.nativeimage.ImageInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Static metadata about the running app, surfaced by the About / License overlays.
 * <p>
 * Version is read from {@code /se/hirt/diskspace/app.properties}, a Maven-filtered resource whose {@code version=} line
 * is substituted from {@code pom.xml}'s {@code <version>} at {@code process-resources} time. Single source of truth:
 * bumping the pom version is enough; no companion constant to update. See the {@code <resources>} block in
 * {@code pom.xml} for the filtering config, and {@code -H:IncludeResources} in the {@code native} profile if the
 * GraalVM analyzer ever stops auto-detecting this resource path.
 */
public final class AppInfo {

	public static final String NAME = "DiskSpace";
	public static final String TAGLINE = "A cross-platform disk space visualizer";
	public static final String COPYRIGHT = "© 2026 Marcus Hirt";
	public static final String GITHUB_URL = "https://github.com/thegreystone/diskspace";

	private AppInfo() {
	}

	public static String version() {
		try (InputStream in = AppInfo.class.getResourceAsStream("/se/hirt/diskspace/app.properties")) {
			if (in != null) {
				Properties props = new Properties();
				props.load(in);
				String v = props.getProperty("version");
				if (v != null && !v.isEmpty())
					return v;
			}
		} catch (IOException ignored) {
			// Fall through to the unknown marker.
		}
		return "(unknown)";
	}

	/**
	 * Short human label for what the binary is running on. Native-image distinguishes itself via
	 * {@link ImageInfo#inImageRuntimeCode()} so the About overlay can honestly say "GraalVM Native Image" rather than
	 * the misleading {@code java.version} value Substrate reports (which is the build-host JDK, not a runtime).
	 */
	public static String runtimeDescription() {
		if (ImageInfo.inImageRuntimeCode()) {
			return "GraalVM Native Image";
		}
		return "Java " + System.getProperty("java.version", "(unknown)");
	}

	public static String licenseText() {
		try (InputStream in = AppInfo.class.getResourceAsStream("/se/hirt/diskspace/LICENSE.txt")) {
			if (in == null)
				return "(LICENSE.txt not bundled in this build)";
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(error reading LICENSE.txt: " + e.getMessage() + ")";
		}
	}
}
