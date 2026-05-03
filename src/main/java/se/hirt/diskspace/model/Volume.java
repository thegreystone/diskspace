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

import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record Volume(String displayName, Path root, long totalBytes, long usableBytes, String fsType) {

    public long usedBytes() {
        return Math.max(0L, totalBytes - usableBytes);
    }

    public double usedFraction() {
        return totalBytes == 0 ? 0.0 : (double) usedBytes() / (double) totalBytes;
    }

    public static List<Volume> enumerate() {
        List<Volume> volumes = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                FileStore store = Files.getFileStore(root);
                if (isPseudoFs(store.type())) {
                    continue;
                }
                String name = store.name();
                if (name == null || name.isBlank()) {
                    name = root.toString();
                }
                volumes.add(new Volume(
                        name,
                        root,
                        store.getTotalSpace(),
                        store.getUsableSpace(),
                        store.type()));
            } catch (Exception ignore) {
                // Volume not accessible (offline drive, permission denied) — skip silently.
            }
        }
        return volumes;
    }

    private static boolean isPseudoFs(String type) {
        if (type == null) return false;
        return switch (type.toLowerCase()) {
            case "proc", "sysfs", "tmpfs", "devtmpfs", "cgroup", "cgroup2",
                 "devpts", "securityfs", "pstore", "autofs", "overlay",
                 "squashfs", "fuse.gvfsd-fuse", "fuse.portal", "tracefs",
                 "debugfs", "configfs", "bpf", "binfmt_misc", "mqueue",
                 "hugetlbfs", "rpc_pipefs", "fusectl" -> true;
            default -> false;
        };
    }
}
