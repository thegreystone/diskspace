# DiskSpace — Developer Guide

For contributors. End-user docs (downloading, preferences, macOS quirks) live in
[DOCUMENTATION.md](DOCUMENTATION.md); the elevator pitch and keybindings table live in the
[README](../README.md).

## Contents

- [Building from source](#building-from-source)
    - [Run on the JVM](#run-on-the-jvm)
    - [Native image](#native-image)
- [Profiling](#profiling)
    - [Capturing a recording](#capturing-a-recording)
    - [Analysing with JMC 10](#analysing-with-jmc-10)
    - [Custom events](#custom-events)
- [Adding a coloring mode](#adding-a-coloring-mode)
- [Releasing](#releasing)
    - [Signing Windows artifacts](#signing-windows-artifacts)
- [Developer troubleshooting](#developer-troubleshooting)
- [AI policy](#ai-policy)

## Building from source

### Run on the JVM

**Prerequisites:** Java 21+ and Maven 3.9+.

```bash
mvn javafx:run
```

This runs the app on HotSpot via the `javafx-maven-plugin`. The plugin downloads JavaFX automatically.

To pass CLI arguments to the launched app — the `-debug` flag in particular — use `-Djavafx.args=...` (this is the
plugin's user property for `<commandlineArgs>`; the more common `-Dexec.args` belongs to a different plugin and is
silently ignored here):

```bash
mvn javafx:run -Djavafx.args="-debug"
```

### Native image

**Prerequisites:** [GraalVM 21 LTS](https://www.oracle.com/java/technologies/downloads/#graalvmjava21) with
`native-image`, Maven 3.9+, and the platform toolchain:

- **Windows**: Visual Studio 2022 with the "Desktop development with C++" workload.
- **macOS**: Xcode Command Line Tools.
- **Linux**: `gcc`, plus dev headers for X11/GTK as required by JavaFX.

```bash
mvn -Pnative gluonfx:build gluonfx:nativerun
```

The native binary will be at `target/gluonfx/<arch>-<os>/diskspace[.exe]`.

> Native builds are pinned to GraalVM 21 LTS — GraalVM 25's Substrate VM has a JNI version regression that breaks
> JavaFX `glass` at startup. The `gluonfx-maven-plugin` reads `GRAALVM_HOME` (not `JAVA_HOME`) —
> `scripts/build-native.ps1` overrides both.

> **Linux only**: recent GraalVM 21.x builds moved their static libraries into a `glibc/` subdirectory that
> `gluonfx-maven-plugin` 1.0.27 (Substrate 0.0.68) doesn't know about, so `gluonfx:build` fails at the link step
> with `Missing library libjvm.a not in linkpath …/lib/svm/clibraries/linux-amd64`. Until upstream
> ([gluonhq/substrate#1318](https://github.com/gluonhq/substrate/issues/1318)) ships a fix, run
> `bash scripts/patch-graalvm-static-libs-linux.sh` once after installing/upgrading GraalVM — it symlinks the
> libraries to the location Substrate expects. CI does this automatically.

## Profiling

DiskSpace is instrumented for [JDK Flight Recorder](https://docs.oracle.com/en/java/javase/21/jfapi/) and ships four
custom events that make it straightforward to correlate user interactions, scans, and per-frame render cost in a
recording. The native image is built with `--enable-monitoring=jfr` so production binaries can be profiled the same
way as JVM-mode runs.

### Capturing a recording

The `-debug` flag turns on FINE logging and starts a JFR recording dumped on exit:

```bash
# JVM dev (note: -Djavafx.args, NOT -Dexec.args — the javafx-maven-plugin uses its own property)
mvn javafx:run -Djavafx.args="-debug"

# Native binary
./DiskSpace -debug
```

The output filename is `diskspace.jfr` (JVM) or `diskspace-native.jfr` (native), written to the working directory.
Override with `-Ddiskspace.jfr.file=/path/to/out.jfr` if you want a different name or location.

The recording uses JFR's `profile` preset — method profiling at higher rates, lock contention, and TLAB allocations —
so the data you actually want for performance investigation is captured out of the box. Overhead is a few percent of
CPU; if `profile` can't be loaded for some reason (older JDK, stripped JFR), it transparently falls back to default
settings.

### Analysing with JMC 10

Open the `.jfr` file in [JDK Mission Control 10](https://www.oracle.com/java/technologies/jdk-mission-control.html).
The **AI Insights** view is the recommended starting point — it surfaces hot methods, GC pressure, and lock
contention in plain prose so you can skip the manual click-through. From there:

- **Method Profiling → Hot Methods**: where the FX thread is actually spending its CPU. Cross-reference with the
  *Render* custom event below to attribute hot frames to a specific visualization mode and trigger.
- **Memory → Allocations in New TLAB**: catches per-frame allocation pressure. The render path was rewritten once
  already after JMC pointed out a per-render `IdentityHashMap` + `Long.valueOf` allocation storm in TimSort.
- **Event Browser → DiskSpace** category: the custom events are grouped under `DiskSpace / UI` and
  `DiskSpace / Scanner` so you can filter on them directly.

### Custom events

Four event types ship in-tree (all under the `se.hirt.diskspace.*` namespace) and answer questions that built-in JFR
events can't:

| Event                                   | Type     | When it fires                                       | Useful for                                                                                                |
|-----------------------------------------|----------|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `se.hirt.diskspace.Scan`                | Duration | One per scan, start → completion / cancellation     | Strategy + outcome + file/byte counts per scan. Correlates downstream events via `scanId`.                |
| `se.hirt.diskspace.VisualizationActive` | Duration | One per window of a particular mode being on screen | "Did the user actually look at heatmap?" Render-count field tells you how many frames painted per window. |
| `se.hirt.diskspace.Render`              | Duration | Per repaint of the canvas on the FX thread          | Per-frame cost attributed by `trigger` (resize / mode-change / scan-update / user / anim / …).            |
| `se.hirt.diskspace.UserAction`          | Instant  | Keypress or right-click menu action                 | Reconstructs the user's interaction timeline; pairs with renders to attribute pauses to a specific input. |

Every event from a given scan run carries the same `scanId` correlation id, so filtering on it gives you "everything
that happened during scan #N" in one slice. The `Render` event also captures `nodeCount`, `width`, and `height` so
you can plot render-cost-vs-tree-size or render-cost-vs-canvas-size without leaving JMC.

> Gluon-distributed GraalVM historically shipped a broken FlightRecorder engine
> ([gluonhq/substrate#1354](https://github.com/gluonhq/substrate/issues/1354)) — recordings open but contain no
> events. CI native builds use Oracle GraalVM directly; if you're rolling your own and the recording is empty,
> check which distribution is on `GRAALVM_HOME`.

## Adding a coloring mode

End-user behaviour and the two built-in modes are covered in
[DOCUMENTATION.md § Coloring modes](DOCUMENTATION.md#coloring-modes); this section is the implementer's recipe.

A new mode is two things: an implementation of `ColoringMode`, and one line added to the registry list in
`se.hirt.diskspace.ui.render.ColoringModes`. That's the whole contract — no service files, no reflection, no
build-time wiring tricks, no JVM-vs-native special cases. Submit the addition as a PR and it ships in the next
release.

### The two interfaces

`se.hirt.diskspace.ui.render.ColoringMode` is the factory — DiskSpace asks each registered mode for a fresh resolver
when a new tab is opened:

```java
public interface ColoringMode {
	String id();                          // stable persistence key, kebab-case

	String displayName();                 // appears in the Preferences picker

	String description();                 // one or two sentences, shown under the picker

	NodeColorResolver createResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf);
}
```

`se.hirt.diskspace.ui.render.NodeColorResolver` is the per-`DiskView` worker — it answers "what colour for this
node?" and gets four optional lifecycle hooks (each with a default no-op) for cache invalidation:

```java
public interface NodeColorResolver {
	Color colorFor(DirectoryNode node);

	default void setScanRoot(DirectoryNode root) {
	}

	default void setHiddenNode(DirectoryNode hidden) {
	}

	default void onScanComplete() {
	}

	default void stabilizeFinalizedTopLevels() {
	}
}
```

`rankOf` returns the child's index in its parent's size-descending sibling list — `0` is the largest child. Use it if
you want a rank-aware palette; ignore it for absolute-size schemes.

### Minimal example

```java
package com.example.diskspace;

import javafx.scene.paint.Color;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.ui.render.ColoringMode;
import se.hirt.diskspace.ui.render.NodeColorResolver;
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.util.function.ToIntFunction;

public final class RainbowColoringMode implements ColoringMode {

	@Override
	public String id() {
		return "rainbow";
	}

	@Override
	public String displayName() {
		return "Rainbow";
	}

	@Override
	public String description() {
		return "Sibling rank picks a hue from a 12-step rainbow palette. " + "Useful for spotting which children dominate at a glance.";
	}

	@Override
	public NodeColorResolver createResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf) {
		return node -> Color.hsb((rankOf.applyAsInt(node) * 30) % 360, 0.65, 0.85);
	}
}
```

Register it by adding one entry to the list in `ColoringModes.MODES`:

```java
private static final List<ColoringMode> MODES = List.of(new ClassicColoringMode(), new BlackAndWhiteColoringMode(),
		new RainbowColoringMode());        // ← your mode
```

The new mode shows up in the Preferences picker the next time the app starts. List order is display order; the first
entry is also the default for fresh installs, so don't reorder casually.

### Implementation tips

- **Cache per node** if your `colorFor` is non-trivial. The renderer calls it every frame for every visible node; even
  a small allocation per call will show up in JFR. See `NodeColorResolverImpl` for the pattern used by the Classic
  mode.
- **Invalidate on `setScanRoot`** and `onScanComplete` — sibling ranks shift while a scan is live, so colours derived
  from rank or final size order should be dropped at those transitions. `stabilizeFinalizedTopLevels` is called once
  per live tick if you want fine-grained invalidation per top-level folder as it finishes.
- **Honour the `ColorScheme`** if your mode is theme-aware — `scheme.surface()` is the right background colour for
  the sunburst hub / scan-root sector.
- **Pick a stable `id`.** It's the key written to `settings.properties`. Changing it later silently resets every user's
  saved preference to the default. The id also de-duplicates against built-ins — providers with a colliding id are
  logged and skipped at startup.

## Releasing

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds native binaries for
Linux / macOS / Windows and creates a GitHub Release with all artifacts attached. macOS binaries
are signed and notarised inside CI using the secrets the workflow already references.

### Signing Windows artifacts

CI publishes the Windows release with only the **unsigned bare**
`diskspace-<v>-windows-x86_64.exe`. The Inno Setup installer is built and signed locally as part
of the post-release signing step, so the installer always wraps an already-signed binary — if
Inno wrapped an unsigned exe (as it did in CI before this split), the user would end up with an
unsigned binary in `Program Files` that AV/SmartScreen rescans could still flag, defeating most
of the point of signing.

[`scripts/sign-release.ps1`](../scripts/sign-release.ps1) does the whole post-release flow in
one shot:

1. Downloads the unsigned bare exe from the GitHub release.
2. Signs it with the Certum cert (SimplySign Desktop must be logged in).
3. Builds the Inno Setup installer locally, wrapping the **signed** bare exe.
4. Signs the installer.
5. Uploads bare exe (clobbering the unsigned one) and the new installer.

Why all signing is post-release rather than in CI: Certum's cloud signing requires SimplySign
Desktop + an OTP push from the mobile app, and GitHub-hosted Windows runners are headless (no
GUI session to drive SimplySign).

**One-time setup on your signing machine:**

1. Install **SimplySign Desktop** and the **SimplySign mobile app**, paired against your Certum
   account.
2. Install **Inno Setup 6** ([https://jrsoftware.org/isdl.php](https://jrsoftware.org/isdl.php)).
   The script defaults to `C:\Program Files (x86)\Inno Setup 6\ISCC.exe`; pass `-Iscc` if yours
   is elsewhere.
3. Install the **Windows SDK** (gets you `signtool.exe`; the script auto-finds it under
   `C:\Program Files (x86)\Windows Kits\10\bin\…\x64`).
4. Install the **GitHub CLI** (`gh`) and authenticate against the repo.
5. Add to your PowerShell `$PROFILE`:

   ```powershell
   $env:DISKSPACE_SIGN_THUMBPRINT = '<SHA1 thumbprint of your Certum cert>'
   ```

   The thumbprint isn't sensitive (it's readable from any signed binary). It lives in your
   profile rather than the repo so cert renewal is a one-line edit on your machine, not a commit.
   Always pin by thumbprint — never use `signtool /a`, which can silently pick the wrong cert
   if your store has more than one (e.g. a stray self-signed dev cert).

**Per release:**

1. Push the `v*` tag and wait for the release workflow to publish (~15 min for the full matrix).
   The release will show only the bare Windows `.exe` at this point; no installer yet.
2. Open SimplySign Desktop, log in (OTP from the mobile app).
3. Run:

   ```powershell
   .\scripts\sign-release.ps1 -Tag v0.2.8
   ```

   ~30 seconds. The script signs the bare exe, builds and signs the installer, and uploads both
   to the release.

SmartScreen reputation for the cert builds over downloads + time — fresh Certum OSS certs ship
without instant SmartScreen trust, so early-release users may still see "Windows protected your
PC" until reputation accumulates. Edge's download warning ("not commonly downloaded") fades on
the same curve.

## Developer troubleshooting

- **`mvn javafx:run` complains about missing JavaFX modules**: ensure `JAVA_HOME` points to a JDK 21+ and that Maven
  is using it (`mvn -v`). The plugin downloads JavaFX automatically.
- **Native build fails on Windows**: open a "x64 Native Tools Command Prompt for VS 2022" and run
  `mvn -Pnative gluonfx:build` from there — the C++ toolchain must be on `PATH`.
- **Native build fails on Linux with `Missing library libjvm.a`**: GraalVM static-library layout changed; run
  `bash scripts/patch-graalvm-static-libs-linux.sh` once. See the Linux note in
  [Native image](#native-image).
- **JFR file is empty in a native build**: make sure the build includes `--enable-monitoring=jfr` and
  `-R:+FlightRecorder` (set in the `native` profile), and that the link step pulls in `libmanagement_ext` (set in
  the `native-mac` / `native-linux` profiles). Gluon-distributed GraalVM historically shipped a broken FlightRecorder
  engine (gluonhq/substrate#1354) — build against Oracle GraalVM for a working JFR.

End-user-facing issues (blank window, Wayland pipeline fallback) live in
[DOCUMENTATION.md § Troubleshooting](DOCUMENTATION.md#troubleshooting).

## AI policy

Contributions made in collaboration with AI tools are welcome. Use whatever helps you work.

That said, the AI is a collaborator, not the contributor. **You** are responsible for reviewing and
testing every change before you open a PR: read the diff line by line, understand what it does and why,
and run it. Submitting code you haven't read or exercised, on the grounds that "the model said it
works", is not enough.

When you put your name on a PR, you are vouching for it. **You** are ultimately responsible for the
contribution, regardless of how much of it an AI wrote.
