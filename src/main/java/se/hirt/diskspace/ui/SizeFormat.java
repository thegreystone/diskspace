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

/**
 * App-wide unit formatting for byte counts. {@link Mode#DECIMAL} (1000-based, "GB") matches Finder, Disk Utility, and drive manufacturer
 * specs. {@link Mode#BINARY} (1024-based, "GiB") matches {@code df -h}/{@code du -h}. Toggled at runtime via the {@code U} shortcut.
 */
public final class SizeFormat {

	public enum Mode {DECIMAL, BINARY}

	private static volatile Mode mode = Mode.DECIMAL;

	private SizeFormat() {
	}

	public static Mode mode() {
		return mode;
	}

	public static Mode toggle() {
		Mode next = (mode == Mode.DECIMAL) ? Mode.BINARY : Mode.DECIMAL;
		mode = next;
		return next;
	}

	public static String format(long bytes) {
		if (mode == Mode.BINARY) {
			final long KIB = 1024L, MIB = KIB * 1024L, GIB = MIB * 1024L, TIB = GIB * 1024L;
			if (bytes >= TIB)
				return String.format("%.1f TiB", bytes / (double) TIB);
			if (bytes >= GIB)
				return String.format("%.1f GiB", bytes / (double) GIB);
			if (bytes >= MIB)
				return String.format("%.0f MiB", bytes / (double) MIB);
			if (bytes >= KIB)
				return String.format("%.0f KiB", bytes / (double) KIB);
			return bytes + " B";
		}
		final long KB = 1000L, MB = KB * 1000L, GB = MB * 1000L, TB = GB * 1000L;
		if (bytes >= TB)
			return String.format("%.1f TB", bytes / (double) TB);
		if (bytes >= GB)
			return String.format("%.1f GB", bytes / (double) GB);
		if (bytes >= MB)
			return String.format("%.0f MB", bytes / (double) MB);
		if (bytes >= KB)
			return String.format("%.0f KB", bytes / (double) KB);
		return bytes + " B";
	}
}
