# DiskSpace — Documentation

End-user reference for DiskSpace. The [README](../README.md) covers the elevator pitch, downloads, and the keybindings
table; contributors will want [DEVGUIDE.md](DEVGUIDE.md) instead.

## Contents

- [Installing on Windows: getting past Edge / SmartScreen](#installing-on-windows-getting-past-edge--smartscreen)
- [Preferences](#preferences)
    - [Storage location](#storage-location)
    - [Coloring modes](#coloring-modes)
- [Mac notes](#mac-notes)
- [Troubleshooting](#troubleshooting)

## Installing on Windows: getting past Edge / SmartScreen

The Windows installer and standalone `.exe` aren't code-signed yet, so Microsoft Edge / Defender SmartScreen treat
them as "isn't commonly downloaded". On recent Edge versions the warning dialog only offers *Cancel* and *Delete* —
there's no *Keep anyway* button. The download bar's hidden *Keep anyway* item has also been removed in some Edge
releases. You have a few options.

**Download via PowerShell** (bypasses Edge's SmartScreen entirely):

```powershell
$url = "https://github.com/thegreystone/diskspace/releases/latest/download/diskspace-0.2.4-windows-x86_64-setup.exe"
$out = "$env:USERPROFILE\Downloads\diskspace-setup.exe"
Invoke-WebRequest -Uri $url -OutFile $out
Unblock-File $out         # clears the "downloaded from internet" mark so the OS doesn't re-warn
Start-Process $out         # runs the installer (UAC prompt will appear)
```

Substitute the version in the URL for the release you want, or use `curl.exe -L -o $out $url` if you prefer. Edit
`$url` for the standalone `.exe` if you'd rather skip the installer.

**Alternative: download with another browser.** Firefox and Chrome show different warning UX that still includes a
*Keep / Save anyway* path.

**Last resort: temporarily disable SmartScreen for downloads.** `edge://settings/privacy` → *Security* section →
toggle *Microsoft Defender SmartScreen* off, download, toggle it back on. Heavy-handed but works on locked-down Edge
configurations.

After the file is on disk, opening it triggers a separate OS-level *"Windows protected your PC"* dialog — that one
**does** have a *More info → Run anyway* path. Approve the UAC prompt afterward. *Publisher: Unknown* is expected
until we sign the binary.

Want to help the next person? On the SmartScreen dialog there's a *"Report this app as safe"* link — enough such
reports build the file's reputation in SmartScreen and the warnings ease off on their own. Long-term fix is a
code-signing cert; not done yet.

## Preferences

Right-click anywhere on the picker or in the empty space of an open disk view and choose **Preferences…** to set
startup defaults:

| Setting               | What it controls                                                  |
|-----------------------|-------------------------------------------------------------------|
| Default visualization | Sunburst (radial) or Squarified Treemap on each newly opened tab. |
| Default size unit     | Decimal (GB) or Binary (GiB) — applied at process start.          |
| Default coloring      | Coloring mode used by each newly opened tab.                      |

The `V` / `U` / `C` shortcuts still toggle the same things in-session without touching the persisted defaults.

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
macOS bulk scanner (`getattrlistbulk` with `ATTR_FILE_ALLOCSIZE`), which on APFS gives per-file allocation accounting
that doesn't double-count blocks shared between clones (Xcode SDKs, simulator runtimes, `cp -c` artifacts). Scanned
totals come out very close to what `df` and "About This Mac → Storage" report — usually within a percent or two.
The JVM dev runner doesn't have those bindings and falls back to Java NIO's `unix:blocks × 512`, which sums each
clone's full block count. There, totals can sit 20–30% above actual used space on a developer machine — a known
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
