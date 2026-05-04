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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live, thread-safe size tree. The scanner mutates from a background thread; the JavaFX
 * thread reads. {@link #addFile} propagates each file's size up to every ancestor's
 * {@link #totalBytes}, so reads at any time give an accurate running total of everything
 * scanned so far. Children are appended via a copy-on-write list during the scan and
 * replaced with a sorted snapshot on completion.
 */
public final class DirectoryNode {

    public enum ScanState { QUEUED, SCANNING, DONE }

    private final DirectoryNode parent;
    private final String name;
    private final Path path;

    private final AtomicLong ownBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger totalFileCount = new AtomicInteger();

    /** Volatile so post-scan replacement with a sorted list publishes safely to readers. */
    private volatile List<DirectoryNode> children = new CopyOnWriteArrayList<>();

    /** Scan state: QUEUED until the scanner enters this dir, SCANNING while inside, DONE when exited. */
    private volatile ScanState state = ScanState.QUEUED;

    public DirectoryNode(DirectoryNode parent, String name, Path path) {
        this.parent = parent;
        this.name = name;
        this.path = path;
    }

    public DirectoryNode parent()         { return parent; }
    public String name()                  { return name; }
    public Path path()                    { return path; }
    public List<DirectoryNode> children() { return children; }
    public long ownBytes()                { return ownBytes.get(); }
    public long totalBytes()              { return totalBytes.get(); }
    public int totalFileCount()           { return totalFileCount.get(); }
    public ScanState state()              { return state; }
    public boolean isDone()               { return state == ScanState.DONE; }
    public void setScanning()             { state = ScanState.SCANNING; }
    public void markDone()                { state = ScanState.DONE; }

    public DirectoryNode addChild(String name, Path path) {
        DirectoryNode child = new DirectoryNode(this, name, path);
        children.add(child);
        return child;
    }

    /** For synthetic post-scan nodes (e.g. the "Hidden" subtree on macOS): bumps this node's
     *  {@code totalBytes} without propagating to ancestors and without affecting file count.
     *  The caller is responsible for bumping ancestors separately when wiring these nodes
     *  into the live tree. */
    public void addSyntheticBytes(long bytes) {
        totalBytes.addAndGet(bytes);
    }

    /** Records a file of {@code size} bytes; propagates totals up the ancestor chain. */
    public void addFile(long size) {
        ownBytes.addAndGet(size);
        for (DirectoryNode n = this; n != null; n = n.parent) {
            n.totalBytes.addAndGet(size);
            n.totalFileCount.incrementAndGet();
        }
    }

    /** Reverses {@link #addFile} after a file at this dir is deleted on disk. */
    public void removeFile(long size) {
        ownBytes.addAndGet(-size);
        for (DirectoryNode n = this; n != null; n = n.parent) {
            n.totalBytes.addAndGet(-size);
            n.totalFileCount.addAndGet(-1);
        }
    }

    /** Removes a child subtree after it has been deleted on disk. Subtracts the child's
     *  contribution from this node and every ancestor. */
    public void removeChild(DirectoryNode child) {
        if (child == null) return;
        long bytes = child.totalBytes();
        int count = child.totalFileCount();
        if (!children.remove(child)) return;
        for (DirectoryNode n = this; n != null; n = n.parent) {
            n.totalBytes.addAndGet(-bytes);
            n.totalFileCount.addAndGet(-count);
        }
    }

    /** After scan completion: sort children by size desc, recursively. */
    public void sortBySizeRecursive() {
        for (DirectoryNode c : children) {
            c.sortBySizeRecursive();
        }
        if (children.size() > 1) {
            List<DirectoryNode> sorted = new ArrayList<>(children);
            sorted.sort(Comparator.comparingLong(DirectoryNode::totalBytes).reversed());
            children = new CopyOnWriteArrayList<>(sorted);
        }
    }
}
