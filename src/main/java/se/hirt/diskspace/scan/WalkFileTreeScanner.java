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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;

import se.hirt.diskspace.model.DirectoryNode;

/**
 * Single-threaded {@link Files#walkFileTree} scanner. The tree is built and updated live —
 * each visited file's size propagates up to all ancestors' totals. Symlinks are not
 * followed; I/O errors on individual entries are skipped silently.
 */
public final class WalkFileTreeScanner implements Scanner {

    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 10 Hz

    private volatile boolean cancelled;
    private volatile Thread thread;

    @Override
    public void scan(Path rootPath, ScanListener listener) {
        cancelled = false;
        DirectoryNode root = new DirectoryNode(null, displayName(rootPath), rootPath);
        listener.onStart(root);

        thread = new Thread(() -> {
            try {
                doScan(rootPath, root, listener);
                if (!cancelled) {
                    root.sortBySizeRecursive();
                    listener.onComplete(root);
                }
            } catch (Throwable t) {
                if (!cancelled) {
                    listener.onError(t);
                }
            }
        }, "diskspace-scan-" + rootPath);
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
        Deque<DirectoryNode> stack = new ArrayDeque<>();
        stack.push(rootNode);
        long[] lastProgressNanos = {0L};
        long[] fileCount = {0L};
        long[] byteCount = {0L};
        String[] currentPath = {null};

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancelled) return FileVisitResult.TERMINATE;
                if (!dir.equals(rootPath)) {
                    DirectoryNode child = stack.peek().addChild(displayName(dir), dir);
                    stack.push(child);
                }
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
                    long size = attrs.size();
                    stack.peek().addFile(size);
                    fileCount[0]++;
                    byteCount[0] += size;
                    maybeEmitProgress(listener, fileCount[0], byteCount[0], currentPath[0], lastProgressNanos);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });

        // Final progress emission so UI sees the final numbers before sort.
        listener.onProgress(fileCount[0], byteCount[0], currentPath[0]);
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
}
