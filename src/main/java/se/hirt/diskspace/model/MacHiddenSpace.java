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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks down the bytes that are "used" at the APFS container level but don't appear in any
 * single user-scannable volume. On macOS the container Used (Java NIO's
 * {@code totalSpace − usableSpace}) usually exceeds Used for the volume actually being
 * scanned (Data), and DiskSpace surfaces that delta in DaisyDisk-style buckets.
 *
 * <p>The data sources are all command-line tools — {@code df -k}, {@code tmutil},
 * {@code diskutil apfs} — which keeps us out of native code and matches the numbers users
 * see in Disk Utility verbatim.
 */
public final class MacHiddenSpace {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MacHiddenSpace.class.getName());

    /**
     * @param otherVolumesBytes  sum of {@code df Used} for sibling APFS volumes in the same
     *                           container as the scan root (System, Preboot, VM, Update, …)
     * @param otherVolumesCount  number of sibling volumes summed
     * @param localSnapshotCount number of Time Machine local snapshots reported by
     *                           {@code tmutil listlocalsnapshots /}
     * @param residualBytes      container-used minus scan-volume-used minus other-volumes-used.
     *                           This is what's "left over" — typically APFS metadata,
     *                           purgeable caches, and snapshot delta blocks.
     */
    public record HiddenSpace(
            long otherVolumesBytes,
            int otherVolumesCount,
            int localSnapshotCount,
            long residualBytes) {

        public long totalBytes() {
            return Math.max(0L, otherVolumesBytes + residualBytes);
        }
    }

    public static final HiddenSpace EMPTY = new HiddenSpace(0L, 0, 0, 0L);

    /** Plain-language descriptions shown in the UI alongside each Hidden row. */
    public static final String DESC_OTHER_VOLUMES =
            "The disk is shared by several volumes. Besides the one DiskSpace scanned, the others hold "
            + "macOS itself, boot files, virtual memory, and recovery data. Their contents aren't "
            + "user-deletable from a file browser.";

    public static final String DESC_SNAPSHOTS =
            "On-disk snapshots — short-lived copies of the volume that let you roll back recent changes. "
            + "Includes Time Machine local snapshots (which macOS deletes automatically when space runs "
            + "low) and any third-party snapshots, e.g. from Carbon Copy Cloner.";

    public static final String DESC_OTHER =
            "A catch-all for bytes the OS uses but doesn't attribute to any single scannable file. "
            + "Typically includes purgeable space (Time Machine local snapshots, swap files, sleepimage, "
            + "and system caches macOS reclaims automatically), filesystem overhead (a few GB is normal), "
            + "the Spotlight index, and other users' home folders if you have any.";

    public static final String DESC_NOT_ACCESSIBLE =
            "Folders the scanner couldn't read. Granting DiskSpace Full Disk Access in System Settings "
            + "reaches Mail, Messages, and most user data. A small residual is normal — sandboxed app "
            + "containers and per-process system directories deny access regardless.";

    private MacHiddenSpace() {}

    /**
     * @param scanRoot              the volume the user is scanning (e.g. {@code /System/Volumes/Data})
     * @param containerUsedBytes    Java NIO's {@code totalSpace − usableSpace} for the FileStore
     * @param scanVolumeUsedBytes   per-volume Used for {@code scanRoot} (from {@link MacVolumeInfo#spaceUsed})
     */
    public static HiddenSpace gather(Path scanRoot, long containerUsedBytes, long scanVolumeUsedBytes) {
        if (!MacVolumeInfo.isMac() || scanRoot == null) {
            return EMPTY;
        }
        try {
            List<DfRow> rows = readDf();
            DfRow scanRow = findRowFor(rows, scanRoot);
            String containerPrefix = scanRow == null ? null : containerPrefix(scanRow.device());

            long otherUsed = 0L;
            int otherCount = 0;
            if (containerPrefix != null) {
                for (DfRow r : rows) {
                    if (r == scanRow) continue;
                    if (r.device() == null) continue;
                    if (!isSiblingDevice(r.device(), containerPrefix)) continue;
                    otherUsed += r.usedBytes();
                    otherCount++;
                }
            }

            long residual = Math.max(0L, containerUsedBytes - scanVolumeUsedBytes - otherUsed);
            int snaps = countLocalSnapshots();
            return new HiddenSpace(otherUsed, otherCount, snaps, residual);
        } catch (Exception e) {
            LOG.fine("HiddenSpace gather failed: " + e);
            return EMPTY;
        }
    }

    // ---- df parsing --------------------------------------------------------

    private record DfRow(String device, long usedBytes, String mount) {}

    private static List<DfRow> readDf() throws Exception {
        List<DfRow> rows = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("df", "-k");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) return rows;
            int usedCol = columnIndex(header, "Used");
            int mountCol = columnIndex(header, "Mounted");  // matches "Mounted on"
            if (usedCol < 0 || mountCol < 0) return rows;

            String line;
            while ((line = r.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length <= Math.max(usedCol, mountCol)) continue;
                try {
                    long usedKB = Long.parseLong(cols[usedCol]);
                    rows.add(new DfRow(cols[0], usedKB * 1024L, cols[mountCol]));
                } catch (NumberFormatException ignore) {
                    // Non-numeric "Used" (e.g. "automount") — skip.
                }
            }
        }
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return rows;
    }

    private static int columnIndex(String header, String name) {
        String[] parts = header.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static DfRow findRowFor(List<DfRow> rows, Path scanRoot) {
        String target = scanRoot.toString();
        for (DfRow r : rows) {
            if (target.equals(r.mount())) return r;
        }
        return null;
    }

    private static final Pattern DISK_PREFIX = Pattern.compile("^(/dev/disk\\d+)s");

    /** Returns the container prefix (e.g. "/dev/disk3") for a device path like
     *  "/dev/disk3s5" or "/dev/disk3s1s1". Returns null for non-APFS device paths. */
    private static String containerPrefix(String device) {
        if (device == null) return null;
        Matcher m = DISK_PREFIX.matcher(device);
        return m.find() ? m.group(1) : null;
    }

    /** True for siblings on the same container, e.g. "/dev/disk3s5" and "/dev/disk3s1s1"
     *  share prefix "/dev/disk3". The next char after the prefix must be a non-digit so
     *  we don't confuse "/dev/disk3" with "/dev/disk30". */
    private static boolean isSiblingDevice(String device, String containerPrefix) {
        if (!device.startsWith(containerPrefix)) return false;
        if (device.length() == containerPrefix.length()) return false;
        char next = device.charAt(containerPrefix.length());
        return !Character.isDigit(next);
    }

    // ---- snapshots ---------------------------------------------------------

    /** Matches the {@code (N found)} count emitted by {@code diskutil apfs listSnapshots}. */
    private static final Pattern SNAPSHOT_COUNT = Pattern.compile("\\((\\d+) found\\)");

    /**
     * Counts user-visible local APFS snapshots on the Data volume. Uses {@code diskutil apfs
     * listSnapshots} rather than {@code tmutil listlocalsnapshots} because the latter only
     * surfaces Time Machine snapshots — third-party tools (Carbon Copy Cloner, etc.) create
     * their own snapshots that {@code tmutil} ignores but {@code diskutil} reports.
     */
    private static int countLocalSnapshots() {
        try {
            ProcessBuilder pb = new ProcessBuilder("diskutil", "apfs", "listSnapshots",
                    "/System/Volumes/Data");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int count = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = SNAPSHOT_COUNT.matcher(line);
                    // Multiple "(N found)" sections can appear if diskutil prints per-volume
                    // groupings; sum across all of them.
                    while (m.find()) {
                        try { count += Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) {}
                    }
                }
            }
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
