# DiskSpace

[![Java 21 LTS](https://img.shields.io/badge/Java-21%20LTS-blue)](https://adoptium.net/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-blueviolet)](https://openjfx.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

A cross-platform disk space visualizer using a sunburst view of where your space went, deliberately minimal feature surface, distributed as native binaries as well as runnable with Java.

![DiskSpace sunburst view, drilled into Program Files](docs/screenshots/diskspace.png)

## Status

Working preview. Sunburst with live scan-as-you-watch, animated drill-down, breadcrumb navigation, and a folders-and-files table are all in. Optional staged deletion (move to Trash where supported, otherwise permanent delete) is gated behind an explicit confirmation dialog; on macOS, a one-time prompt offers to grant Full Disk Access so protected locations like Mail and Messages get scanned. The native build pipeline is verified on Windows and macOS (Oracle GraalVM 21 LTS); Linux builds in CI but hasn't been smoke-tested locally yet.

## Quick Start

### Option A: Native Binary (recommended)

No Java installation required. Download the binary for your platform from the Releases page (TBD):

| Platform | File |
|----------|------|
| Linux x86_64 | `diskspace-<version>-linux-x86_64` |
| Linux aarch64 | `diskspace-<version>-linux-aarch64` |
| macOS Apple Silicon | `diskspace-<version>-macos-aarch64` |
| Windows x86_64 | `diskspace-<version>-windows-x86_64.exe` |

On Linux, make the binary executable: `chmod +x diskspace-*-linux-*`.

### Option B: Run from source (dev)

Requires Java 21+ and Maven 3.9+:

```bash
mvn javafx:run
```

## Keybindings

| Key       | Action                                                                       |
|-----------|------------------------------------------------------------------------------|
| `←` / `↑` | Go up one level. Stops at the scan root.                                     |
| `→` / `↓` | Go forward. Replay a step you went up from. Stops when there's nothing left. |
| `E`       | Open the hovered sector (or the current view) in your system file explorer.  |
| `Del`     | Stage selected rows (or the current section) for deletion. Press again on a staged row to unstage. |
| `R`       | Re-scan the current disk from scratch.                                       |
| `U`       | Toggle size units between decimal (GB, default) and binary (GiB).            |

You can also click any segment of the breadcrumb in the top-left to jump directly to that ancestor, or click the center hub to reset all the way back to the scan root.

## Building from Source

### Run on the JVM

**Prerequisites:** Java 21+ and Maven 3.9+.

```bash
mvn javafx:run
```

### Native image

**Prerequisites:** [GraalVM 21 LTS](https://www.oracle.com/java/technologies/downloads/#graalvmjava21) with `native-image`, Maven 3.9+, and the platform toolchain:

- **Windows**: Visual Studio 2022 with the "Desktop development with C++" workload.
- **macOS**: Xcode Command Line Tools.
- **Linux**: `gcc`, plus dev headers for X11/GTK as required by JavaFX.

```bash
mvn -Pnative gluonfx:build gluonfx:nativerun
```

The native binary will be at `target/gluonfx/<arch>-<os>/diskspace[.exe]`.

> Native builds are pinned to GraalVM 21 LTS. GraalVM 25's Substrate VM has a JNI version regression that breaks JavaFX `glass` at startup; see `DESIGN.md § 7.5` for details. The `gluonfx-maven-plugin` reads `GRAALVM_HOME` (not `JAVA_HOME`) — `build-native.ps1` overrides both.

## Mac Notes

DiskSpace handles several APFS- and macOS-specific quirks. None of them require user intervention except where noted.

**Scan root is `/System/Volumes/Data`, not `/`.** On modern macOS, `/` is a read-only sealed APFS system snapshot. The user-mutable filesystem is mounted at `/System/Volumes/Data` and surfaced into `/` via *firmlinks* (`/Users`, `/Applications`, `/private`, …). Scanning from `/` would cross those firmlinks back into Data and double-count everything. When you pick "Macintosh HD", DiskSpace silently rewrites the scan root to `/System/Volumes/Data`.

**"OS used / OS free" reflects the Data volume only.** APFS volumes inside one container share a free-space pool, but each volume has its own block count. DiskSpace queries the `FileStore` at the scan root, so "OS used" matches what `df /System/Volumes/Data` prints — the bytes a *user* can actually free. The system snapshot, Preboot, VM swap, and local Time Machine snapshots aren't represented because they aren't user-deletable from a file browser anyway.

**APFS clones can inflate scanned totals.** Per-file sizes come from `st_blocks × 512`. Cloned files (Xcode SDKs, simulator runtimes, system installers, large copy-on-write `cp -c` artifacts) share extents physically but each clone reports its full block count. The scanner can therefore report a sum that exceeds the volume's actual used space — sometimes by 20–30% on a developer machine. Java's NIO doesn't expose extent identity, so portable dedup isn't possible; clone-aware accounting would require Apple-specific syscalls (`getattrlist` / `fcntl(F_LOG2PHYS_EXT)`). Treat scanned totals as an upper bound; `df` is the truth for the volume.

**Firmlinks, hard links, and bind-mounts are deduped.** The scanner tracks visited inodes (`fileKey`), so a directory or file reachable via more than one path is only counted once.

**Cross-volume boundaries aren't crossed.** Subdirectories that live on a different APFS volume — most commonly iOS/watchOS Simulator data volumes mounted under `Library/Developer/CoreSimulator/Volumes` — are detected by device ID and skipped, so they don't get folded into the parent volume's totals.

**Full Disk Access is recommended.** Without FDA, several user-visible folders (Mail, Messages, Safari history, Calendar, Reminders) are unreadable. On first run DiskSpace shows a prompt with a shortcut to the right pane in System Settings; click "Don't ask again" to suppress. Even with FDA granted, a small residual of inaccessible paths is normal — sandboxed app containers and per-process `/private/var/folders` directories deny access regardless.

**Volume label resolution via `/Volumes`.** The Finder-visible label of an APFS volume (e.g. "Macintosh HD") doesn't appear in the path hierarchy under `/`. DiskSpace looks under `/Volumes` for an entry whose inode matches the scan root and uses that name when found, otherwise falls back to the device node.

**Trash vs permanent delete.** macOS exposes `java.awt.Desktop.MOVE_TO_TRASH`, so the deletion confirmation dialog reads "Move to Trash" and items can be restored from Finder. The fallback "Delete permanently" path is only used on platforms that don't expose a trash API.

## Troubleshooting

- **`mvn javafx:run` complains about missing JavaFX modules**: ensure `JAVA_HOME` points to a JDK 25+, and that Maven is using it (`mvn -v`). The plugin downloads JavaFX automatically.
- **Native build fails on Windows**: open a "x64 Native Tools Command Prompt for VS 2022" and run `mvn -Pnative gluonfx:build` from there — the C++ toolchain must be on `PATH`.
- **Window appears blank or fails to render**: confirm hardware acceleration / OpenGL drivers are installed. JavaFX falls back to software rendering, but it is slow.
