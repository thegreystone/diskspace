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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import se.hirt.diskspace.model.DirectoryNode;

/**
 * Single-threaded {@link Files#walkFileTree} scanner. The tree is built and updated live —
 * each visited file's size propagates up to all ancestors' totals. Symlinks are not
 * followed; I/O errors on individual entries are skipped silently.
 */
public final class WalkFileTreeScanner implements Scanner {

    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 10 Hz

    /** Files at or above this size get their own sunburst sector; smaller files are summed
     *  per-directory into a "Smaller files" aggregate sector. 1 GB decimal. */
    private static final long LARGE_FILE_THRESHOLD_BYTES = 1_000_000_000L;

    private volatile boolean cancelled;
    private volatile Thread thread;
    private long permDeniedCount;

    @Override
    public void scan(Path rootPath, ScanListener listener) {
        cancelled = false;
        DirectoryNode root = new DirectoryNode(null, displayName(rootPath), rootPath);
        listener.onStart(root);

        thread = new Thread(() -> {
            try {
                doScan(rootPath, root, listener);
                if (!cancelled) {
                    finishUnscanned(root);
                    root.sortBySizeRecursive();
                    if (permDeniedCount > 0) listener.onPermissionsDenied(permDeniedCount);
                    listener.onComplete(root);
                }
            } catch (Throwable t) {
                if (!cancelled) {
                    listener.onError(t);
                }
            }
        }, "DiskSpace-scan-" + rootPath);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void cancel() {
        cancelled = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void doScan(Path rootPath, DirectoryNode rootNode, ScanListener listener) throws IOException {
        permDeniedCount = 0;
        Deque<DirectoryNode> stack = new ArrayDeque<>();
        stack.push(rootNode);
        long[] lastProgressNanos = {0L};
        long[] fileCount = {0L};
        long[] byteCount = {0L};
        String[] currentPath = {null};
        Set<Object> seenKeys = new HashSet<>();

        // Restrict scan to the same device as the root so we don't cross into
        // separately-mounted volumes (e.g. iOS/watchOS simulator APFS volumes
        // that appear as subdirectories under Library/Developer/CoreSimulator).
        long rootDev = deviceOf(Files.readAttributes(rootPath, BasicFileAttributes.class).fileKey());

        // Path → node lookup so pre-listed QUEUED children can be found when the
        // scanner later enters them, avoiding duplicate node creation.
        Map<Path, DirectoryNode> nodeByPath = new HashMap<>();
        nodeByPath.put(rootPath, rootNode);
        rootNode.setScanning();

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancelled) return FileVisitResult.TERMINATE;
                Object key = attrs.fileKey();
                if (key != null) {
                    if (rootDev >= 0 && deviceOf(key) != rootDev) {
                        // Different device — separately-mounted volume; don't cross it.
                        DirectoryNode queued = nodeByPath.get(dir);
                        if (queued != null) queued.markDone();
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!seenKeys.add(key)) {
                        // Already visited via a firmlink or bind-mount — skip to avoid double-counting.
                        DirectoryNode queued = nodeByPath.get(dir);
                        if (queued != null) queued.markDone();
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                if (!dir.equals(rootPath)) {
                    DirectoryNode node = nodeByPath.get(dir);
                    if (node == null) {
                        node = stack.peek().addChild(displayName(dir), dir);
                        nodeByPath.put(dir, node);
                    }
                    node.setScanning();
                    stack.push(node);
                }
                // Pre-list this directory's immediate subdirs as QUEUED so the table
                // shows them right away, before the scanner actually enters them.
                preListSubdirs(dir, stack.peek(), rootDev, seenKeys, nodeByPath);
                currentPath[0] = dir.toString();
                maybeEmitProgress(listener, fileCount[0], byteCount[0], currentPath[0], lastProgressNanos);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                stack.peek().markDone();
                if (!dir.equals(rootPath)) {
                    stack.pop();
                }
                return cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile()) {
                    Object key = attrs.fileKey();
                    if (key != null && !seenKeys.add(key)) {
                        // Hard-linked file already counted via another directory entry.
                        return FileVisitResult.CONTINUE;
                    }
                    long size = physicalSize(file, attrs);
                    DirectoryNode dir = stack.peek();
                    dir.addFile(size);
                    if (size >= LARGE_FILE_THRESHOLD_BYTES) {
                        Path fname = file.getFileName();
                        dir.addLargeFile(fname == null ? file.toString() : fname.toString(), size);
                    } else {
                        dir.addSmallerFileBytes(size);
                    }
                    fileCount[0]++;
                    byteCount[0] += size;
                    maybeEmitProgress(listener, fileCount[0], byteCount[0], currentPath[0], lastProgressNanos);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                if (exc instanceof java.nio.file.AccessDeniedException) permDeniedCount++;
                return cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });

        // Final progress emission so UI sees the final numbers before sort.
        listener.onProgress(fileCount[0], byteCount[0], currentPath[0]);
    }

    /** Returns the physical disk allocation for a file using unix:blocks when available,
     *  falling back to the logical size. unix:blocks is in 512-byte units (POSIX). */
    private static long physicalSize(Path file, BasicFileAttributes attrs) {
        try {
            Object blocks = Files.getAttribute(file, "unix:blocks", LinkOption.NOFOLLOW_LINKS);
            if (blocks instanceof Long b) return b * 512L;
        } catch (Exception ignore) {}
        return attrs.size();
    }

    private static void maybeEmitProgress(ScanListener listener, long files, long bytes,
                                          String path, long[] lastProgressNanos) {
        long now = System.nanoTime();
        if (now - lastProgressNanos[0] > PROGRESS_INTERVAL_NANOS) {
            lastProgressNanos[0] = now;
            listener.onProgress(files, bytes, path);
        }
    }

    private static String displayName(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    /** After the walk, ensure no node is left in QUEUED or SCANNING state. */
    private static void finishUnscanned(DirectoryNode node) {
        if (node.state() != DirectoryNode.ScanState.DONE) node.markDone();
        for (DirectoryNode child : node.children()) {
            finishUnscanned(child);
        }
    }

    /**
     * Reads {@code dir}'s direct children and adds any subdirectories that pass the device
     * and dedup checks as QUEUED children of {@code parent}. Called in preVisitDirectory so
     * the UI can show all siblings immediately rather than one-by-one as the DFS proceeds.
     */
    private static void preListSubdirs(Path dir, DirectoryNode parent, long rootDev,
                                       Set<Object> seenKeys, Map<Path, DirectoryNode> nodeByPath) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                if (nodeByPath.containsKey(child)) continue;
                try {
                    BasicFileAttributes a = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!a.isDirectory()) continue;
                    Object k = a.fileKey();
                    if (k != null && rootDev >= 0 && deviceOf(k) != rootDev) continue;
                    if (k != null && seenKeys.contains(k)) continue;
                    DirectoryNode childNode = parent.addChild(displayName(child), child);
                    nodeByPath.put(child, childNode);
                } catch (IOException ignore) {}
            }
        } catch (IOException ignore) {}
    }

    /**
     * Extracts the device ID from a POSIX fileKey whose toString() is "(dev=0x...,ino=...)".
     * Returns -1 if the format is not recognised (Windows, unknown OS).
     */
    private static long deviceOf(Object fileKey) {
        if (fileKey == null) return -1;
        String s = fileKey.toString();
        int i = s.indexOf("dev=");
        if (i < 0) return -1;
        int j = s.indexOf(',', i);
        if (j < 0) j = s.indexOf(')', i);
        if (j < 0) return -1;
        try {
            return Long.decode(s.substring(i + 4, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
