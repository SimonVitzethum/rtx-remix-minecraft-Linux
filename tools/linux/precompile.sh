#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Compiles every Remix shader and pipeline in a separate process before the game starts, so Minecraft never waits
# on "Compiling shaders". The runtime writes remix-pipeline.cache beside libremixapi.so, and the game loads it.
# The compile is skipped while that cache is newer than the runtime library and the game's rtx.conf.
#
# Usage: precompile.sh [game directory, default: current directory] [--force]
# Prism Launcher: set the instance's pre-launch command to
#   bash "$INST_MC_DIR/remix-sdk/precompile.sh" "$INST_MC_DIR"
set -euo pipefail

game="${1:-$PWD}"
force="${2:-}"
sdk="$game/remix-sdk"
cache="$sdk/remix-pipeline.cache"
tool="$sdk/remix-precompile"
timeout_seconds="${REMIX_PRECOMPILE_TIMEOUT:-900}"

if [ ! -x "$tool" ] && [ -f "$tool" ]; then
    chmod +x "$tool"
fi
if [ ! -x "$tool" ]; then
    echo "precompile: $tool not found, skipping" >&2
    exit 0
fi

if [ "$force" != "--force" ] && [ -f "$cache" ] && [ "$cache" -nt "$sdk/libremixapi.so" ] \
    && { [ ! -f "$game/rtx.conf" ] || [ "$cache" -nt "$game/rtx.conf" ]; }; then
    echo "precompile: pipeline cache is current, nothing to do"
    exit 0
fi

echo "precompile: compiling shaders in $game"
cd "$game"
# The runtime is built against its own sibling libraries.
export LD_LIBRARY_PATH="$sdk${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
nice -n 5 timeout "$timeout_seconds" "$tool" "$sdk/libremixapi.so" "$timeout_seconds" || {
    status=$?
    echo "precompile: finished with status $status, the game will compile the rest itself" >&2
    exit 0
}
