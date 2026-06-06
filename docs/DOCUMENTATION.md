# DiskSpace — Documentation

End-user reference for DiskSpace. The [README](../README.md) covers the elevator pitch, downloads, and the keybindings
table; contributors will want [DEVGUIDE.md](DEVGUIDE.md) instead.

## Contents

- [Installing on Windows: getting past Edge / SmartScreen](#installing-on-windows-getting-past-edge--smartscreen)
- [Interacting while data streams in](#interacting-while-data-streams-in)
- [Preferences](#preferences)
    - [Storage location](#storage-location)
    - [Coloring modes](#coloring-modes)
- [Voronoi visualization](#voronoi-visualization)
    - [Level of detail (LOD)](#level-of-detail-lod)
- [Mac notes](#mac-notes)
- [Troubleshooting](#troubleshooting)

## Installing on Windows: getting past Edge / SmartScreen

The Windows installer and standalone `.exe` are **code-signed** with an Open Source Developer cert (via
Certum). The publisher line on every dialog reads **Open Source Developer Marcus Hirt** — not *Unknown* — so neither
warning below implies the binary is unsafe. SmartScreen still shows them for now: cert reputation builds with download
volume and time, and until enough installs have happened, Edge treats the file as "not commonly downloaded". Both
warnings can be dismissed; the dialogs are just buried behind a few clicks.

### Edge download warning — the three-step "Keep anyway" dance

Edge's warning UI no longer surfaces *Keep* as a top-level button. To save the file:

1. Edge shows a download pill that says **"diskspace-…-setup.exe isn't commonly downloaded. Make sure you trust …
   before you open it"**, with *Delete* highlighted.
2. Click the **three-dot menu (⋯)** next to the pill. Choose **Keep**.
3. A confirmation dialog appears, again with *Delete* highlighted. Click the **dropdown caret (▼)** on the right side
   of the *Delete* button — it expands to reveal **Keep anyway**. Click that.

The file is now on disk. (Yes, three clicks to save a signed binary is excessive; this is Edge's design, not mine.)

### Running the installer

Double-clicking the saved file triggers a separate OS-level **"Windows protected your PC"** dialog. Click
**More info** to reveal the publisher line — it should read **Open Source Developer Marcus Hirt** — then **Run
anyway**. Approve the UAC prompt that follows.

### PowerShell alternative (skips the Edge UI entirely)

```powershell
$url = "https://github.com/thegreystone/diskspace/releases/latest/download/diskspace-0.3.0-windows-x86_64-setup.exe"
$out = "$env:USERPROFILE\Downloads\diskspace-setup.exe"
Invoke-WebRequest -Uri $url -OutFile $out
Unblock-File $out         # clears the "downloaded from internet" mark so the OS doesn't re-warn
Start-Process $out         # runs the installer (UAC prompt will appear)
```

Substitute the version in the URL for the release you want, or use `curl.exe -L -o $out $url` if you prefer. Edit
`$url` for the standalone `.exe` if you'd rather skip the installer.

### Other workarounds

- **Different browser.** Firefox and Chrome show different download warning UX that still includes a *Keep / Save
  anyway* path with fewer clicks.
- **Last resort: temporarily disable SmartScreen for downloads.** `edge://settings/privacy` → *Security* section →
  toggle *Microsoft Defender SmartScreen* off, download, toggle it back on. Heavy-handed but works on locked-down
  Edge configurations.

### Help the warnings go away faster

On the *"Windows protected your PC"* dialog there's a **"Report this app as safe"** link — Microsoft uses those
reports to build the cert's reputation in SmartScreen, and once enough land the warnings stop showing entirely for
everyone. Verifying the publisher reads **Open Source Developer Marcus Hirt** before clicking it is a good habit.

## Interacting while data streams in

DiskSpace never holds you behind a loading screen. Both the disk picker and the visualizations fill in progressively,
and you can act on whatever is already there.

**The disk picker.** Every disk appears immediately as a placeholder row, then each is read on its own background
thread. A row stays dimmed and gently pulsing — with a "…" where its size and type will go — until **both** are known,
at which point it lights up and becomes clickable. The storage type is needed before you can pick a disk because it
decides the fastest scanner for that medium, so a row isn't selectable until its type is in. A small spinner beside the
*Choose a disk* heading shows overall progress (*Looking for disks…*, then *Identifying disk types…* as the
SSD / HDD / Network tags fill in). Disks resolve independently, so a slow or unresponsive volume — a network share, a
flaky USB card reader — never holds up the others. A disk that can't be read (offline, disconnected, or failing media)
is **left out of the picker by default**; press `H` to toggle showing it, where it appears as a dimmed, non-selectable
**Unavailable** entry. The default is configurable under **Preferences → Disk picker → Hide unavailable disks**.

**The visualizations.** A scan streams its results into the sunburst / heatmap / Voronoi view as it walks the tree, so
the picture builds up live instead of appearing only once the scan finishes. You can **interact with the view the whole
time** — drill into a folder, navigate up and down with the arrow keys, hover for details, or cycle visualization (`V`)
and coloring (`C`) — all while data is still being gathered underneath. The view keeps updating around whatever you're
looking at.

## Preferences

Right-click anywhere on the picker or in the empty space of an open disk view and choose **Preferences…** to set
startup defaults:

| Setting                | What it controls                                                     |
|------------------------|----------------------------------------------------------------------|
| Default visualization  | Sunburst (radial), Squarified Treemap, or Voronoi on each newly opened tab. |
| Default size unit      | Decimal (GB) or Binary (GiB) — applied at process start.             |
| Default coloring       | Coloring mode used by each newly opened tab.                         |
| Hide unavailable disks | Whether unreadable disks are left out of the picker (on by default). |

The `V` / `U` / `C` shortcuts still toggle the same things in-session without touching the persisted defaults, and `H`
does the same for hiding unavailable disks in the picker.

### Storage location

Settings persist to your OS's user-config directory and are picked up automatically on the next launch:

| Platform | Path                                                                         |
|----------|------------------------------------------------------------------------------|
| Windows  | `%APPDATA%\DiskSpace\settings.properties`                                    |
| macOS    | `~/Library/Application Support/DiskSpace/settings.properties`                |
| Linux    | `$XDG_CONFIG_HOME/diskspace/settings.properties` (defaults to `~/.config/…`) |

The file is plain `java.util.Properties` text — readable and hand-editable. For tests or scripted runs, point at a
different file with `-Ddiskspace.settings.file=/path/to/file`.

### Coloring modes

Coloring is pluggable. Two modes ship in-tree today:

- **Classic** — each top-level folder gets a saturated hue from a 12-colour palette and its descendants inherit that
  hue, lightening toward the rim. Large files are neutral grey so folder structure stays the focal point.
- **Black & White** — folders are pure grayscale (darker with depth); single large files glow bright red and the
  *Smaller files* aggregate is orange. Useful for spotting heavy hitters without colour cues from siblings.

Press `C` to cycle the current tab's mode in-session, or set the persistent default in Preferences.

More modes will land over time. If you'd like to contribute one — or have an idea — see
[DEVGUIDE § Adding a coloring mode](DEVGUIDE.md#adding-a-coloring-mode) for the recipe.

## Voronoi visualization

The Voronoi mode (cycled to via `V`) renders the directory tree as a circular partition of polygonal cells whose areas
are proportional to byte counts — a weighted power diagram with two hierarchy levels visible at once: the current view
root's direct children, then their own children inside each top-level cell. Compared to the sunburst and the
squarified-treemap heatmap, it has no built-in depth bias and tends to make dominant subtrees pop visually.

### Level of detail (LOD)

The underlying weighted-Voronoi algorithm (Bowyer-Watson + Lloyd relaxation) is `O(n²)` per layout call, so handing it
a folder with tens or hundreds of thousands of direct children would freeze the UI for minutes (the layout runs on the
JavaFX application thread). DiskSpace caps the per-level cell count and rolls everything below the cut into a single
aggregate **Smaller** cell, then renders only that bounded set. With the defaults, a folder with millions of underlying
files is laid out as **at most ~129 top-level cells** (128 largest + 1 "Smaller"), keeping any single navigation well
under 50 ms regardless of folder size.

Three system properties tune the LOD behaviour — pass them at JVM startup if you want different trade-offs between
detail and speed:

| Property                            | Default | What it controls                                                                                                                  |
|-------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------|
| `diskspace.voronoi.maxSites.top`    | `128`   | Maximum top-level cells (direct children of the current view root). Children past this rank roll up into one "Smaller" cell.      |
| `diskspace.voronoi.maxSites.sub`    | `64`    | Maximum sub-cells inside each top-level cell. Children past this rank roll up into a "Smaller" sub-cell.                          |
| `diskspace.voronoi.subCellMinArea`  | `2500`  | Pixel-area floor (in px²) for triggering sub-cell layout. Parents projecting smaller than this skip the sub-Voronoi (≈ 50×50 px). |

Non-numeric or non-positive values log a warning and fall back to the default.

To experiment from the native binary, append them to the launch command:

```
diskspace -Ddiskspace.voronoi.maxSites.top=256 -Ddiskspace.voronoi.maxSites.sub=128
```

Or from a JVM dev run:

```
mvn javafx:run -Djavafx.options="-Ddiskspace.voronoi.maxSites.top=256"
```

Higher values give more individual cells (more detail) but cost more per render; lower values render faster and feel
snappier on navigation but show fewer distinct items per view. The pixel-area floor is a separate guard: even with a
huge `maxSites.sub` you won't pay sub-cell layout cost on parents too small to read a label inside anyway.

**Smaller cells** render in a muted theme shade (surface, darker) so they're visually distinct from real per-node
cells. They aren't clickable — there's no single node to drill into — but the rolled-up children are still in the file
table on the right with their actual byte counts. So the LOD only hides the *geometry*, not the data.

## Mac notes

DiskSpace handles several APFS- and macOS-specific quirks. None of them require user intervention except where noted.
A couple of quirks behave differently depending on whether you're running the released native binary or the JVM-mode
dev runner (`mvn javafx:run`); these are called out inline.

**Scan root is `/System/Volumes/Data`, not `/`.** On modern macOS, `/` is a read-only sealed APFS system snapshot. The
user-mutable filesystem is mounted at `/System/Volumes/Data` and surfaced into `/` via *firmlinks* (`/Users`,
`/Applications`, `/private`, …). Scanning from `/` would cross those firmlinks back into Data and double-count
everything. When you pick "Macintosh HD", DiskSpace silently rewrites the scan root to `/System/Volumes/Data`.

**"OS used / OS free" reflects the Data volume only.** APFS volumes inside one container share a free-space pool, but
each volume has its own block count. DiskSpace queries the `FileStore` at the scan root, so "OS used" matches what
`df /System/Volumes/Data` prints — the bytes a *user* can actually free. The system snapshot, Preboot, VM swap, and
local Time Machine snapshots aren't represented because they aren't user-deletable from a file browser anyway.

**APFS clones — native binary matches OS-reported usage; JVM dev mode can over-count.** The native binary uses the
macOS bulk scanner (`getattrlistbulk`) and, on APFS roots, asks for `ATTR_CMNEXT_CLONE_REFCNT`, `ATTR_CMNEXT_CLONEID`,
and `ATTR_CMNEXT_PRIVATESIZE` alongside `ATTR_FILE_ALLOCSIZE`. Files currently sharing extents with at least one
sibling (clone refcount ≥ 2) are deduplicated: the first member of each clone family is charged its full `allocsize`
(shared + private blocks), siblings are charged only their CoW-modified private bytes. Total for an N-member family
equals the actual on-disk usage. With heavy cloning (Xcode SDKs, simulator runtimes, `cp -c` artifacts) scanned
totals come out within a percent or two of what `df` and "About This Mac → Storage" report. On non-APFS roots
(HFS+, NFS, SMB, external exFAT/FAT) the CMNEXT request is suppressed automatically so there's no per-entry overhead.
The JVM dev runner doesn't have these native bindings and falls back to Java NIO's `unix:blocks × 512`, which sums
each clone's full block count. There, totals can sit 20–30% above actual used space on a developer machine — a known
limitation of the dev path, not a bug to worry about. The released binary you ship is the accurate one.

**Firmlinks, hard links, and bind-mounts are deduped.** The scanner tracks visited inodes (`fileKey`), so a directory
or file reachable via more than one path is only counted once.

**Cross-volume boundaries aren't crossed.** Subdirectories that live on a different APFS volume — most commonly
iOS/watchOS Simulator data volumes mounted under `Library/Developer/CoreSimulator/Volumes` — are detected by device ID
and skipped, so they don't get folded into the parent volume's totals.

**Full Disk Access is recommended.** Without FDA, several user-visible folders (Mail, Messages, Safari history,
Calendar, Reminders) are unreadable. On first run DiskSpace shows a prompt with a shortcut to the right pane in System
Settings; click "Don't ask again" to suppress. Even with FDA granted, a small residual of inaccessible paths is normal
— sandboxed app containers and per-process `/private/var/folders` directories deny access regardless.

**Volume label resolution via `/Volumes`.** The Finder-visible label of an APFS volume (e.g. "Macintosh HD") doesn't
appear in the path hierarchy under `/`. DiskSpace looks under `/Volumes` for an entry whose inode matches the scan
root and uses that name when found, otherwise falls back to the device node.

**Trash vs permanent delete (JVM dev mode only).** Under `mvn javafx:run`, the deletion confirmation dialog reads
"Move to Trash" — `java.awt.Desktop.MOVE_TO_TRASH` is available, and items can be restored from Finder. The released
native binary doesn't initialise AWT (JavaFX native-image builds skip it on purpose), so trash isn't reachable and the
dialog falls back to "Delete permanently". Items deleted from the native binary on macOS can't be restored from
Finder.

**The `Hidden → Other` bucket includes purgeable space.** macOS doesn't expose its purgeable-space figure (Time
Machine local snapshots, swap, sleepimage, system caches) through any shell command we can portably call from Java,
so we don't break it out as its own row. It's lumped into `Other` along with filesystem overhead, the Spotlight
index, and other users' home folders. To investigate the contents directly: `tmutil listlocalsnapshots /` lists Time
Machine snapshots, `diskutil apfs listSnapshots /System/Volumes/Data` lists *all* snapshots (including third-party),
and Disk Utility's "First Aid" can flag filesystem errors that inflate this bucket.

## Troubleshooting

- **Window appears blank or fails to render**: confirm hardware acceleration / OpenGL drivers are installed. JavaFX
  falls back to software rendering, but it is slow.
- **Linux native binary exits at startup with `Error initializing QuantumRenderer: no suitable pipeline found`**:
  JavaFX's hardware-qualifier check rejects what XWayland exposes on many Wayland desktops, and the GluonFX native
  image doesn't include the software Prism pipeline as a fallback, so the app fails to start. Bypass the qualifier
  check with `-Dprism.forceGPU=true`:
  ```bash
  ./diskspace-<version>-linux-x86_64 -Dprism.forceGPU=true
  ```
  Add an alias / desktop file if it's a recurring need. (Not needed on `mvn javafx:run` — the JVM-mode JavaFX runtime
  includes the SW pipeline and degrades gracefully.)

Build / native-image issues live in [DEVGUIDE § Developer troubleshooting](DEVGUIDE.md#developer-troubleshooting).
