#!/usr/bin/env bash
# Workaround for gluonhq/substrate#1318 and a related Substrate Linux link-path gap.
# Recent Oracle GraalVM and GraalVM Community 21.x ship their static libraries
# under `glibc/` (and `musl/`) subdirectories of:
#   lib/svm/clibraries/linux-amd64/      ← Substrate-VM C libs (libjvm.a, …)
#   lib/static/linux-amd64/              ← JDK static libs (libmanagement_ext.a, …)
# Substrate 0.0.68 (and the pinned gluonfx-maven-plugin 1.0.27) still expects
# the SVM libs flat in lib/svm/clibraries/linux-amd64/ at build start, so the
# build aborts with:
#   Missing library libjvm.a not in linkpath …/lib/svm/clibraries/linux-amd64
# And during the actual link, only `lib/svm/clibraries/linux-amd64` is on `-L`,
# so any pom-level `<arg>-l<jdkLib></arg>` (e.g. `-lmanagement_ext` for the JFR
# build) hits `ld: cannot find -l<jdkLib>` because the JDK static dir isn't on
# the search path.
#
# Until the upstream fix (PR #1319) lands and a fixed Substrate is released,
# this script:
#   1. symlinks lib/svm/clibraries/linux-amd64/glibc/*.a into the parent dir
#      (covers the pre-link existence check that wants libjvm.a flat)
#   2. symlinks lib/static/linux-amd64/glibc/*.a into the SVM clibraries dir
#      (puts JDK static libs on the `-L` Substrate already passes, so `-l…`
#       resolves at link time)
# Idempotent: `ln -sf` overwrites; re-running after a GraalVM upgrade refreshes.
#
# Linux-only — macOS and Windows GraalVM bundles don't have the glibc/ subdir.

set -euo pipefail

ROOT="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [[ -z "$ROOT" ]]; then
    echo "patch-graalvm-static-libs-linux: neither GRAALVM_HOME nor JAVA_HOME set" >&2
    exit 1
fi
if [[ ! -d "$ROOT" ]]; then
    echo "patch-graalvm-static-libs-linux: \$GRAALVM_HOME=$ROOT does not exist" >&2
    exit 1
fi

SVM_DIR="$ROOT/lib/svm/clibraries/linux-amd64"
STATIC_DIR="$ROOT/lib/static/linux-amd64"

patched_any=0

# 1. SVM clibraries: flat-layout symlinks within the same dir.
if [[ -d "$SVM_DIR/glibc" ]]; then
    for src in "$SVM_DIR/glibc"/*.a; do
        [[ -e "$src" ]] || continue
        ln -sf "glibc/$(basename "$src")" "$SVM_DIR/$(basename "$src")"
        patched_any=1
    done
fi

# 2. JDK static libs: link from lib/static/linux-amd64/glibc/ into SVM clibraries
# so `-l<name>` (with Substrate's hardcoded `-L .../lib/svm/clibraries/linux-amd64`)
# can find them. Resolve to the canonical path so the symlink works regardless
# of relative-path traversal.
if [[ -d "$STATIC_DIR/glibc" && -d "$SVM_DIR" ]]; then
    for src in "$STATIC_DIR/glibc"/*.a; do
        [[ -e "$src" ]] || continue
        target="$SVM_DIR/$(basename "$src")"
        # Don't clobber an existing real file or a same-dir glibc/ symlink from step 1.
        if [[ -L "$target" || ! -e "$target" ]]; then
            ln -sf "$src" "$target"
            patched_any=1
        fi
    done
fi

if [[ "$patched_any" -eq 1 ]]; then
    echo "patch-graalvm-static-libs-linux: symlinks refreshed under $ROOT"
else
    echo "patch-graalvm-static-libs-linux: no glibc/ subdir found — nothing to do"
fi
