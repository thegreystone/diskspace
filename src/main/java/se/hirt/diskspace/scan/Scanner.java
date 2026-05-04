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

import java.nio.file.Path;

import se.hirt.diskspace.model.DirectoryNode;

public interface Scanner {

    /**
     * Starts scanning {@code root} on a background thread. The listener's {@code onStart}
     * fires synchronously with the live, mutating tree before the thread is started — UI
     * code can begin observing the root immediately. Other callbacks fire on the scan
     * thread; UI code is responsible for marshalling to the JavaFX Application Thread.
     */
    void scan(Path root, ScanListener listener);

    void cancel();

    interface ScanListener {
        /** Called once, before scanning begins, with the live tree root. */
        void onStart(DirectoryNode liveRoot);

        /** Periodic progress, throttled by the scanner. {@code currentPath} may be null. */
        void onProgress(long files, long bytes, String currentPath);

        /** Called before {@link #onComplete} if any entries were inaccessible (permission denied). */
        default void onPermissionsDenied(long count) {}

        /** Final tree (children sorted by size desc, recursively). */
        void onComplete(DirectoryNode root);

        void onError(Throwable t);
    }
}
