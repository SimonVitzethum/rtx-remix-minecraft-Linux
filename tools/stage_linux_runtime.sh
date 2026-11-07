#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Stages the natively built Linux Remix runtime into remix-runtime/linux64/, where `syncRuntimeSdk` and `packageJar`
# pick it up (-Parch=linux64). Nothing here needs Proton or Wine.
#
# Usage: tools/stage_linux_runtime.sh [dxvk-remix checkout] [dxvk-deps directory]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dxvk="${1:-$root/../dxvk-remix}"
deps="${2:-$root/../dxvk-deps}"
out="$root/remix-runtime/linux64"

runtime="$dxvk/_CompLinuxRelease/src/d3d9/libremixapi.so"
nrd="$deps/NRD/build_linux/libNRD.so"
usd="$deps/usd-install"
dlss="$dxvk/submodules/dlss/lib/Linux_x86_64/rel"

for f in "$runtime" "$nrd" "$usd/lib"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$out"
mkdir -p "$out"
cp "$runtime" "$out/libremixapi.so"
strip --strip-unneeded "$out/libremixapi.so" 2>/dev/null || true
cp "$nrd" "$out/libNRD.so"

# OpenUSD, built without Python and imaging. The runtime finds the plugin descriptors in usd/ beside the library.
cp -a "$usd"/lib/libusd_*.so "$out/"
mkdir -p "$out/usd"
cp -a "$usd"/lib/usd/. "$out/usd/"

# DLSS super resolution and ray reconstruction. NGX discovers them in the runtime directory.
cp -L "$dlss"/libnvidia-ngx-dlss*.so.* "$out/"

# oneTBB is a runtime dependency of OpenUSD.
tbb="$(ldconfig -p | awk '/libtbb\.so\.12 /{print $NF; exit}')"
[ -n "$tbb" ] && cp -L "$tbb" "$out/"

# Every library finds its siblings through $ORIGIN, so the directory can move anywhere.
if command -v patchelf >/dev/null; then
    for lib in "$out"/*.so*; do patchelf --set-rpath '$ORIGIN' "$lib" 2>/dev/null || true; done
fi

echo "staged $(ls "$out" | wc -l) entries in $out"
