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
package se.hirt.diskspace.model;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * macOS-specific volume queries that go beyond what {@link java.nio.file.FileStore} exposes. On APFS,
 * {@code FileStore.getTotalSpace()/getUsableSpace()} return container-wide totals — the same numbers regardless of which volume in the
 * container is queried — because the underlying {@code statfs(2)} call reports container blocks. The per-volume figures Finder and Disk
 * Utility show come from {@code getattrlist(2)} with {@code ATTR_VOL_SPACEUSED}, which Java NIO doesn't surface. {@code df(1)} exposes that
 * attribute in its {@code Used} column, so we shell out and parse the output.
 */
final class MacVolumeInfo {

	private MacVolumeInfo() {
	}

	static boolean isMac() {
		String os = System.getProperty("os.name", "").toLowerCase();
		return os.startsWith("mac") || os.startsWith("darwin");
	}

	/**
	 * Runs {@code df -k <path>} and returns the Used column converted to bytes (it's reported in 1024-byte blocks). Returns -1 if anything
	 * fails — caller should fall back to the Java NIO computation.
	 */
	static long spaceUsed(Path path) {
		if (!isMac() || path == null)
			return -1L;
		try {
			ProcessBuilder pb = new ProcessBuilder("df", "-k", path.toString());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String header;
			String dataLine;
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				header = r.readLine();
				dataLine = r.readLine();
				// Drain the rest so the process can exit cleanly.
				while (r.readLine() != null) { /* discard */ }
			}
			if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return -1L;
			}
			if (p.exitValue() != 0 || header == null || dataLine == null)
				return -1L;

			String[] hdr = header.trim().split("\\s+");
			int usedCol = -1;
			for (int i = 0; i < hdr.length; i++) {
				if (hdr[i].equalsIgnoreCase("Used")) {
					usedCol = i;
					break;
				}
			}
			if (usedCol < 0)
				return -1L;

			String[] cols = dataLine.trim().split("\\s+");
			if (usedCol >= cols.length)
				return -1L;
			long blocks = Long.parseLong(cols[usedCol]);
			return blocks * 1024L;
		} catch (Exception ignore) {
			return -1L;
		}
	}
}
