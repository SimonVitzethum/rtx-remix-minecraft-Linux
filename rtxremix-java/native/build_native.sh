#!/bin/bash
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# build_native.sh: Compile remixbridge.so (Linux / ELF) using g++/clang.
#
# Usage: build_native.sh [x64|arm64]   (default: x64)
# Requires: a C++17 compiler, JAVA_HOME pointing to a 64-bit JDK 21, and the
# Linux Vulkan/OpenGL development headers (libgl1-mesa-dev).
#
# Output goes to build/native/{linux64|linuxarm64}/ under the repository root.
# This is the POSIX counterpart of build_native.bat; the JNI bridge is built
# from the same remixbridge.cpp source, which is guarded for both platforms.

set -euo pipefail

ARCH="${1:-x64}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$ARCH" in
    x64)   OUT_ARCH=linux64;   ;;
    arm64) OUT_ARCH=linuxarm64; ;;
    *) echo "ERROR: Unknown architecture: $ARCH. Use x64 or arm64." >&2; exit 1; ;;
esac

if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME is not set." >&2
    exit 1
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: jni.h not found in $JAVA_HOME/include" >&2
    exit 1
fi

REMIX_INCLUDE="${REMIX_INCLUDE:-$SRC/include}"
if [ ! -f "$REMIX_INCLUDE/remix_c.h" ]; then
    echo "ERROR: remix_c.h not found in $REMIX_INCLUDE" >&2
    exit 1
fi
# POSIX stand-in for <windows.h> compiling the copied remix_c.h on Linux.
LINUX_INCLUDE="$SRC/include_linux"

OUT_DIR="$ROOT/build/native/$OUT_ARCH"
mkdir -p "$OUT_DIR"

if [ "$ARCH" = "arm64" ]; then
    if command -v aarch64-linux-gnu-g++ >/dev/null 2>&1; then
        CXX=aarch64-linux-gnu-g++
    else
        echo "ERROR: aarch64-linux-gnu-g++ not found (needed for arm64)." >&2
        exit 1
    fi
else
    CXX="${CXX:-g++}"
fi

echo "Compiling remixbridge.so for $OUT_ARCH using $CXX ..."
"$CXX" -std=c++17 -O2 -fPIC -shared \
    -fvisibility=hidden \
    -DREMIX_WINAPI_NO_INCLUDE \
    -DREMIX_WINAPI_NO_LIBRARY_LOADER \
    -DREMIX_ALLOW_X86 \
    -DREMIXAPI= \
    -DREMIXAPI_CALL= \
    -D__stdcall= \
    -I"$JAVA_HOME/include" \
    -I"$JAVA_HOME/include/linux" \
    -I"$REMIX_INCLUDE" \
    -I"$LINUX_INCLUDE" \
    "$SRC/remixbridge.cpp" \
    -o "$OUT_DIR/remixbridge.so" \
    -lGL -ldl -lpthread

echo "remixbridge.so built successfully: $OUT_DIR/remixbridge.so"
