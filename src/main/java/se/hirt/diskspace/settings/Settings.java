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
package se.hirt.diskspace.settings;

import se.hirt.diskspace.ui.DiskView.RenderMode;
import se.hirt.diskspace.ui.SizeFormat;
import se.hirt.diskspace.ui.render.ColoringMode;
import se.hirt.diskspace.ui.render.ColoringModes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent app-level preferences. Loaded once at startup, saved on every {@link #save()}.
 * <p>Storage lives in the platform's user-config directory:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\DiskSpace\settings.properties}</li>
 *   <li>macOS: {@code ~/Library/Application Support/DiskSpace/settings.properties}</li>
 *   <li>Linux: {@code $XDG_CONFIG_HOME/diskspace/settings.properties}
 *       (falls back to {@code ~/.config/diskspace/settings.properties})</li>
 * </ul>
 * <p>File format is a plain {@link Properties} text file so users can hand-edit it if they want,
 * and so the format survives without a JSON dependency.
 * <p>Missing file = first launch; we return defaults and create the file on first {@link #save()}.
 * Malformed entries fall back to defaults silently — a stale enum name shouldn't break startup.
 */
public final class Settings {

	private static final Logger LOG = Logger.getLogger(Settings.class.getName());

	private static final String KEY_DEFAULT_VISUALIZATION = "default.visualization";
	private static final String KEY_DEFAULT_SIZE_UNIT = "default.size.unit";
	private static final String KEY_DEFAULT_COLORING_MODE = "default.coloring.mode";

	private static final RenderMode FALLBACK_VISUALIZATION = RenderMode.SUNBURST;
	private static final SizeFormat.Mode FALLBACK_SIZE_UNIT = SizeFormat.Mode.DECIMAL;

	private static volatile Settings instance;

	public static Settings get() {
		Settings s = instance;
		if (s == null) {
			synchronized (Settings.class) {
				s = instance;
				if (s == null) {
					s = load();
					instance = s;
				}
			}
		}
		return s;
	}

	private final Path file;
	private RenderMode defaultVisualization;
	private SizeFormat.Mode defaultSizeUnit;
	private ColoringMode defaultColoringMode;

	private Settings(
			Path file, RenderMode defaultVisualization, SizeFormat.Mode defaultSizeUnit,
			ColoringMode defaultColoringMode) {
		this.file = file;
		this.defaultVisualization = defaultVisualization;
		this.defaultSizeUnit = defaultSizeUnit;
		this.defaultColoringMode = defaultColoringMode;
	}

	public synchronized RenderMode defaultVisualization() {
		return defaultVisualization;
	}

	public synchronized void setDefaultVisualization(RenderMode mode) {
		this.defaultVisualization = mode != null ? mode : FALLBACK_VISUALIZATION;
	}

	public synchronized SizeFormat.Mode defaultSizeUnit() {
		return defaultSizeUnit;
	}

	public synchronized void setDefaultSizeUnit(SizeFormat.Mode mode) {
		this.defaultSizeUnit = mode != null ? mode : FALLBACK_SIZE_UNIT;
	}

	public synchronized ColoringMode defaultColoringMode() {
		return defaultColoringMode;
	}

	public synchronized void setDefaultColoringMode(ColoringMode mode) {
		this.defaultColoringMode = mode != null ? mode : ColoringModes.defaultMode();
	}

	/**
	 * Persist current values. Creates the parent directory if missing. Logged-and-swallowed on failure — a read-only
	 * config dir shouldn't take the app down, the worst case is that the choice isn't remembered across launches.
	 */
	public synchronized void save() {
		Properties p = new Properties();
		p.setProperty(KEY_DEFAULT_VISUALIZATION, defaultVisualization.name());
		p.setProperty(KEY_DEFAULT_SIZE_UNIT, defaultSizeUnit.name());
		p.setProperty(KEY_DEFAULT_COLORING_MODE, defaultColoringMode.id());
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				p.store(out, "DiskSpace settings");
			}
			LOG.fine(() -> "Saved settings to " + file);
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Could not save settings to " + file, e);
		}
	}

	private static Settings load() {
		Path file = resolveSettingsFile();
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				p.load(in);
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Could not read settings from " + file + " — using defaults", e);
			}
		}
		RenderMode viz = parseEnum(p.getProperty(KEY_DEFAULT_VISUALIZATION), RenderMode.class, FALLBACK_VISUALIZATION);
		SizeFormat.Mode unit = parseEnum(p.getProperty(KEY_DEFAULT_SIZE_UNIT), SizeFormat.Mode.class,
				FALLBACK_SIZE_UNIT);
		ColoringMode coloring = ColoringModes.byId(p.getProperty(KEY_DEFAULT_COLORING_MODE));
		return new Settings(file, viz, unit, coloring);
	}

	private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
		if (raw == null || raw.isBlank())
			return fallback;
		try {
			return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			LOG.warning(
					() -> "Unknown " + type.getSimpleName() + " value in settings: '" + raw + "' — using " + fallback);
			return fallback;
		}
	}

	/**
	 * Resolves the settings file path. Honours {@code -Ddiskspace.settings.file=PATH} for tests / debugging; otherwise
	 * uses the OS-native user-config directory.
	 */
	static Path resolveSettingsFile() {
		String override = System.getProperty("diskspace.settings.file");
		if (override != null && !override.isBlank()) {
			return Paths.get(override).toAbsolutePath();
		}
		return userConfigDir().resolve("settings.properties");
	}

	private static Path userConfigDir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData)
					: Paths.get(home, "AppData", "Roaming");
			return base.resolve("DiskSpace");
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return Paths.get(home, "Library", "Application Support", "DiskSpace");
		}
		// Linux / other Unix
		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path base = (xdg != null && !xdg.isBlank()) ? Paths.get(xdg) : Paths.get(home, ".config");
		return base.resolve("diskspace");
	}
}
