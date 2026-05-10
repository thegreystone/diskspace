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
package se.hirt.diskspace.scan;

import se.hirt.diskspace.model.DirectoryNode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Shared scan-completion timing and throughput logging. Format is identical for sequential and parallel scanners so A/B
 * comparisons in the log are trivial — search for {@code "Scan complete:"}.
 */
final class ScanTiming {

	private ScanTiming() {
	}

	/**
	 * Emits a multi-line INFO log summarising elapsed wall time, file/byte counts pulled from the scan root's running
	 * totals, and derived throughput (files/sec and bytes/sec).
	 */
	static void log(Logger log, Path rootPath, DirectoryNode root, long startNanos, String strategy) {
		long elapsedNanos = System.nanoTime() - startNanos;
		double seconds = elapsedNanos / 1_000_000_000.0;
		long files = root.totalFileCount();
		long bytes = root.totalBytes();
		long filesPerSec = seconds > 0 ? Math.round(files / seconds) : 0;
		double bytesPerSec = seconds > 0 ? bytes / seconds : 0.0;
		log.info(String.format(Locale.ROOT,
				"Scan complete: %s%n" + "  Strategy   : %s%n" + "  Wall time  : %.2fs%n" + "  Files      : %,d%n" + "  Bytes      : %s (%,d)%n" + "  Throughput : %,d files/s, %s/s",
				rootPath, strategy, seconds, files, formatBytes(bytes), bytes, filesPerSec,
				formatBytes((long) bytesPerSec)));
	}

	private static String formatBytes(long bytes) {
		if (bytes >= 1_000_000_000_000L)
			return String.format(Locale.ROOT, "%.2f TB", bytes / 1_000_000_000_000.0);
		if (bytes >= 1_000_000_000L)
			return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
		if (bytes >= 1_000_000L)
			return String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0);
		if (bytes >= 1_000L)
			return String.format(Locale.ROOT, "%.2f KB", bytes / 1_000.0);
		return bytes + " B";
	}
}
