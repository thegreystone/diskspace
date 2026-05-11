#!/usr/bin/env bash
# Workaround for gluonhq/substrate#1318:
# Recent Oracle GraalVM and GraalVM Community 21.x ship their static libraries
# under a `glibc/` (and `musl/`) subdirectory of `lib/svm/clibraries/linux-amd64/`
# and `lib/static/linux-amd64/`. Substrate 0.0.68 (and the pinned
# gluonfx-maven-plugin 1.0.27) still expects them flat in the parent directory,
# so `mvn -Pnative gluonfx:build` fails the `link` step with:
#   Missing library libjvm.a not in linkpath …/lib/svm/clibraries/linux-amd64
# Until the upstream fix (PR #1319) lands and a fixed Substrate is released,
# this script bridges the layout difference by symlinking every glibc/*.a into
# its parent dir. Idempotent: `ln -sf` overwrites; re-running after a GraalVM
# upgrade refreshes the links.
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

patched_any=0
for parent in \
    "$ROOT/lib/svm/clibraries/linux-amd64" \
    "$ROOT/lib/static/linux-amd64"; do
    if [[ -d "$parent/glibc" ]]; then
        for src in "$parent/glibc"/*.a; do
            [[ -e "$src" ]] || continue
            ln -sf "glibc/$(basename "$src")" "$parent/$(basename "$src")"
            patched_any=1
        done
    fi
done

if [[ "$patched_any" -eq 1 ]]; then
    echo "patch-graalvm-static-libs-linux: symlinked glibc/*.a into parent dirs under $ROOT"
else
    echo "patch-graalvm-static-libs-linux: no glibc/ subdir found under $ROOT/lib/... — nothing to do"
fi
