# diskspace

[![Java 21 LTS](https://img.shields.io/badge/Java-21%20LTS-blue)](https://adoptium.net/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-blueviolet)](https://openjfx.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

A cross-platform disk space visualizer using a sunburst view of where your space went, deliberately minimal feature surface, distributed as native binaries via GraalVM.

## Status

Early prototype. The picker view enumerates mounted volumes and the tabbed shell works; sunburst rendering and scanning are stubs.

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
| `→`       | Go forward — replay a step you went up from. Stops when there's nothing left.|
| `E`       | Open the hovered sector (or the current view) in your system file explorer.  |

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

## Troubleshooting

- **`mvn javafx:run` complains about missing JavaFX modules**: ensure `JAVA_HOME` points to a JDK 25+, and that Maven is using it (`mvn -v`). The plugin downloads JavaFX automatically.
- **Native build fails on Windows**: open a "x64 Native Tools Command Prompt for VS 2022" and run `mvn -Pnative gluonfx:build` from there — the C++ toolchain must be on `PATH`.
- **Window appears blank or fails to render**: confirm hardware acceleration / OpenGL drivers are installed. JavaFX falls back to software rendering, but it is slow.
