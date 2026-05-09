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
 * Live, thread-safe size tree. The scanner mutates from a background thread; the JavaFX thread reads. {@link #addFile} propagates each
 * file's size up to every ancestor's {@link #totalBytes}, so reads at any time give an accurate running total of everything scanned so
 * far.
 * <p>Children are appended to a plain {@link ArrayList} guarded by {@link #childrenLock} and reads return a snapshot under the same lock.
 * The earlier {@link CopyOnWriteArrayList} per-add cost was {@code O(K)} (array copy on every {@code add}); for parents with many children
 * (~5K+ on volumes like {@code C:\Windows\WinSxS}) this dominated phase 1 allocation. Plain ArrayList add is amortised {@code O(1)}, and
 * the snapshot copy on read costs the FX thread {@code O(K)} per {@link #children()} call instead of {@code O(K)} per add — net win since
 * scans add far more often than the FX thread renders.
 */
public final class DirectoryNode {

	public enum ScanState {QUEUED, SCANNING, DONE}

	/**
	 * A file recorded for sunburst display (≥ 1 GB on the volume). Capturing only large files keeps memory bounded — typical scans hit ≤ a
	 * few dozen of these.
	 */
	public record FileRecord(String name, long size) {
	}

	private final DirectoryNode parent;
	private final String name;
	private final Path path;

	private final AtomicLong ownBytes = new AtomicLong();
	private final AtomicLong totalBytes = new AtomicLong();
	private final AtomicInteger totalFileCount = new AtomicInteger();

	/**
	 * Per-directory list of large files (immediate children, not recursive). Mutated by the scanner thread; published to readers via the
	 * COW list.
	 */
	private final List<FileRecord> largeFiles = new CopyOnWriteArrayList<>();

	/** Sum of immediate-file bytes that fell below the large-file threshold. */
	private final AtomicLong smallerFilesBytes = new AtomicLong();

	/**
	 * True for synthetic "file" sectors (large-file leaves and "Smaller files" aggregates) injected post-scan. They render in the sunburst
	 * but clicking them drills to the containing directory rather than into the leaf itself.
	 */
	private volatile boolean fileSector;

	/**
	 * Mutable child list. All access — read or write — must hold {@link #childrenLock}. Reads expose a fresh snapshot to callers (see
	 * {@link #children()}) so no caller can hold an iterator across a concurrent {@link #addChild} call.
	 */
	private final List<DirectoryNode> children = new ArrayList<>();
	private final Object childrenLock = new Object();

	/** Scan state: QUEUED until the scanner enters this dir, SCANNING while inside, DONE when exited. */
	private volatile ScanState state = ScanState.QUEUED;

	/**
	 * True iff {@link #children} is currently sorted by {@link #totalBytes()} descending and {@code totalBytes()} for those children is
	 * stable (no concurrent writers). Set at the end of {@link #sortBySizeRecursive} once size workers have drained; cleared on any
	 * subsequent {@link #addChild} or {@link #removeChild}. Lets the renderer skip per-render snapshot+sort and reuse a pre-computed rank
	 * map indefinitely.
	 */
	private volatile boolean sortStableByTotalBytes;

	public DirectoryNode(DirectoryNode parent, String name, Path path) {
		this.parent = parent;
		this.name = name;
		this.path = path;
	}

	public DirectoryNode parent() {
		return parent;
	}

	public String name() {
		return name;
	}

	public Path path() {
		return path;
	}

	/**
	 * Returns a stable snapshot of the current children. Each call allocates and the FX thread typically calls this once per render-pass
	 * per parent, so the cost is bounded; in exchange callers get an iterator that can never throw
	 * {@link java.util.ConcurrentModificationException} regardless of what the scanner thread is doing.
	 */
	public List<DirectoryNode> children() {
		synchronized (childrenLock) {
			return new ArrayList<>(children);
		}
	}

	public long ownBytes() {
		return ownBytes.get();
	}

	public long totalBytes() {
		return totalBytes.get();
	}

	public int totalFileCount() {
		return totalFileCount.get();
	}

	public ScanState state() {
		return state;
	}

	public boolean isDone() {
		return state == ScanState.DONE;
	}

	public void setScanning() {
		state = ScanState.SCANNING;
	}

	public void markDone() {
		state = ScanState.DONE;
	}

	public List<FileRecord> largeFiles() {
		return largeFiles;
	}

	public long smallerFilesBytes() {
		return smallerFilesBytes.get();
	}

	public boolean isFileSector() {
		return fileSector;
	}

	public void markFileSector() {
		fileSector = true;
	}

	public void addLargeFile(String fileName, long size) {
		largeFiles.add(new FileRecord(fileName, size));
	}

	public void addSmallerFileBytes(long size) {
		smallerFilesBytes.addAndGet(size);
	}

	public DirectoryNode addChild(String name, Path path) {
		DirectoryNode child = new DirectoryNode(this, name, path);
		synchronized (childrenLock) {
			children.add(child);
			sortStableByTotalBytes = false;
		}
		return child;
	}

	/**
	 * Returns true iff {@link #children()} is in size-descending order and the children's sizes are stable
	 * (post-{@link #sortBySizeRecursive}, pre any new mutation). Renderers can use this to skip per-render snapshot+sort and reuse a cached
	 * rank map.
	 */
	public boolean isSortStableByTotalBytes() {
		return sortStableByTotalBytes;
	}

	/**
	 * For synthetic post-scan nodes (e.g. the "Hidden" subtree on macOS): bumps this node's {@code totalBytes} without propagating to
	 * ancestors and without affecting file count. The caller is responsible for bumping ancestors separately when wiring these nodes into
	 * the live tree.
	 */
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

	/**
	 * Removes a child subtree after it has been deleted on disk. Subtracts the child's contribution from this node and every ancestor.
	 */
	public void removeChild(DirectoryNode child) {
		if (child == null)
			return;
		long bytes = child.totalBytes();
		int count = child.totalFileCount();
		boolean removed;
		synchronized (childrenLock) {
			removed = children.remove(child);
			if (removed)
				sortStableByTotalBytes = false;
		}
		if (!removed)
			return;
		for (DirectoryNode n = this; n != null; n = n.parent) {
			n.totalBytes.addAndGet(-bytes);
			n.totalFileCount.addAndGet(-count);
		}
	}

	/**
	 * After scan completion: sort children by size desc, recursively. Snapshots the child list before recursing so the per-node lock is
	 * never held across a recursive call (avoids depth-proportional lock-holding on deep trees), then sorts in place under the lock.
	 */
	public void sortBySizeRecursive() {
		List<DirectoryNode> snap;
		synchronized (childrenLock) {
			snap = new ArrayList<>(children);
		}
		for (DirectoryNode c : snap) {
			c.sortBySizeRecursive();
		}
		synchronized (childrenLock) {
			if (children.size() > 1) {
				children.sort(Comparator.comparingLong(DirectoryNode::totalBytes).reversed());
			}
			sortStableByTotalBytes = true;
		}
	}
}
