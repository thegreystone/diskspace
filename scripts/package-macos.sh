#!/usr/bin/env bash
# Wraps the GraalVM Native Image executable in DiskSpace.app and writes a compressed DMG.
# Native Image produces a bare executable, so the .app bundle, icon, Info.plist, signing,
# and DMG are assembled here.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)

app_name="DiskSpace"
executable_name="DiskSpace"
executable="$repo_root/target/native/$executable_name"
version=""
output_dir="$repo_root/target"
dmg_name=""
sign_identity="-"
entitlements=""
skip_dmg=false

usage() {
    cat <<'USAGE'
Usage: package-macos.sh [options] [VERSION]

  --executable PATH          Native Image executable (default target/native/DiskSpace)
  --version VERSION          Bundle version without leading v (default read from pom.xml)
  --output-dir DIR           Directory for DiskSpace.app and DMG (default target)
  --dmg-name NAME            DMG filename (default diskspace-<version>-macos-<arch>.dmg)
  --sign-identity IDENTITY   codesign identity (default '-' for ad-hoc signing)
  --entitlements PATH        Entitlements plist passed when signing with a Developer ID
  --no-dmg                   Only build and sign DiskSpace.app; skip creating the DMG
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --executable) executable="$2"; shift 2 ;;
        --version) version="$2"; shift 2 ;;
        --output-dir) output_dir="$2"; shift 2 ;;
        --dmg-name) dmg_name="$2"; shift 2 ;;
        --sign-identity) sign_identity="$2"; shift 2 ;;
        --entitlements) entitlements="$2"; shift 2 ;;
        --no-dmg) skip_dmg=true; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)
            if [[ -z "$version" ]]; then
                version="$1"
                shift
            else
                echo "Unexpected argument: $1" >&2; usage >&2; exit 2
            fi
            ;;
    esac
done

if [[ -z "$version" ]]; then
    version=$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' "$repo_root/pom.xml" | head -n 1)
fi
if [[ -z "$version" ]]; then
    echo "Could not determine version; pass --version VERSION" >&2
    exit 1
fi

if [[ ! -x "$executable" ]]; then
    echo "Native executable not found or not executable: $executable" >&2
    exit 1
fi

case "$(uname -m)" in
    arm64) arch="aarch64" ;;
    x86_64) arch="x86_64" ;;
    *) arch="$(uname -m)" ;;
esac
if [[ -z "$dmg_name" ]]; then
    dmg_name="diskspace-$version-macos-$arch.dmg"
fi

mkdir -p "$output_dir"
bundle="$output_dir/$app_name.app"
rm -rf "$bundle"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources"

cp "$executable" "$bundle/Contents/MacOS/$executable_name"
chmod +x "$bundle/Contents/MacOS/$executable_name"
cp "$repo_root/src/macos/assets/icon.icns" "$bundle/Contents/Resources/icon.icns"
sed "s/__VERSION__/$version/g" "$repo_root/src/macos/Info.plist" > "$bundle/Contents/Info.plist"
printf 'APPL????' > "$bundle/Contents/PkgInfo"

if [[ "$sign_identity" == "-" ]]; then
    # Local / dispatch builds carry no Developer ID. An ad-hoc signature is still required
    # for the bundle to launch on Apple Silicon.
    codesign --force --sign - --timestamp=none "$bundle"
else
    sign_args=(--force --deep --options runtime --sign "$sign_identity")
    if [[ -n "$entitlements" ]]; then
        # Required under Hardened Runtime so OpenJFX's extracted .dylib libraries
        # (~/.openjfx/cache) can be dlopen'd without Team ID mismatch.
        sign_args+=(--entitlements "$entitlements")
    fi
    codesign "${sign_args[@]}" "$bundle"
fi
codesign --verify --deep --strict "$bundle"
echo "Assembled $bundle"

if [[ "$skip_dmg" == true ]]; then
    exit 0
fi

dmg_path="$output_dir/$dmg_name"
rm -f "$dmg_path"
dmg_root=$(mktemp -d "${TMPDIR:-/tmp}/diskspace-dmg.XXXXXX")
trap 'rm -rf "$dmg_root"' EXIT
cp -R "$bundle" "$dmg_root/"
ln -s /Applications "$dmg_root/Applications"

# hdiutil's auto-sizing for -srcfolder can underestimate APFS container overhead on
# macOS 15 and fail with "No space left on device" while copying the native image.
# Size the image explicitly from apparent file sizes with headroom; UDZO compresses
# the unused blocks away.
content_bytes=$(find "$dmg_root" -type f -exec stat -f '%z' {} + | awk '{sum += $1} END {print sum + 0}')
dmg_size_mb=$(( (content_bytes + 1048575) / 1048576 + 64 ))

hdiutil create -volname "$app_name" -srcfolder "$dmg_root" -size "${dmg_size_mb}m" -ov -format UDZO "$dmg_path"

if [[ "$sign_identity" != "-" ]]; then
    codesign --force --sign "$sign_identity" "$dmg_path"
fi

echo "Wrote $dmg_path"
