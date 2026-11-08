#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Linux counterpart of tools/rtx.bat for the minecraft mod dev workflow.
#
# Usage: tools/linux/rtx.sh <subcommand> [args...]
#   launch      build the mod (package -Parch=linux64 -x test), install the jar into
#               the Prism instance, run the shader precompiler when the mod has
#               extracted remix-sdk, and start Minecraft through Prism Launcher.
#   precompile  run tools/linux/precompile.sh for the instance [--force].
#   instance    create the Prism instance only (does not build or launch).
#   status      report what is set up.
#
# Configuration (environment):
#   PRISM_DATA_DIR     Prism Launcher data directory (default: ~/.local/share/PrismLauncher)
#   RTX_INSTANCE       instance folder name (default: rtx-remix-26.3)
#   RTX_PRISM_PROFILE  Prism account profile name for launch; when unset, the account
#                      is read from accounts.json if exactly one account exists.
#   JAVA25_HOME        JDK 25 home; default: autodetect /usr/lib/jvm/*25*, else JAVA_HOME.
#   FABRIC_API_VERSION Fabric API version to fetch from Modrinth (default: 0.165.2+26.3)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PRISM_DATA_DIR="${PRISM_DATA_DIR:-$HOME/.local/share/PrismLauncher}"
RTX_INSTANCE="${RTX_INSTANCE:-rtx-remix-26.3}"
FABRIC_API_VERSION="${FABRIC_API_VERSION:-0.165.2+26.3}"

INST="$PRISM_DATA_DIR/instances/$RTX_INSTANCE"
MC="$INST/minecraft"
MODS="$MC/mods"
OLD_MODS="$INST/old-mods"
MC_VERSION=26.3
FABRIC_LOADER_VERSION=0.19.5

die() {
    printf 'rtx: %s\n' "$*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "required tool '$1' not found on PATH"
}

usage() {
    printf '%s\n' \
        'Usage: tools/linux/rtx.sh <subcommand> [args...]' \
        '  launch      build, install and start Minecraft through Prism Launcher' \
        '  precompile  run tools/linux/precompile.sh for the instance [--force]' \
        '  instance    create the Prism instance only' \
        '  status      report what is set up' \
        'Environment: PRISM_DATA_DIR, RTX_INSTANCE, RTX_PRISM_PROFILE, JAVA25_HOME,' \
        '            FABRIC_API_VERSION (see header comments)'
}

is_java_25() {
    local java="$1" out
    out="$("$java" -version 2>&1)" || return 1
    printf '%s\n' "$out" | grep -qE 'version "25([."]|$)'
}

java_version() {
    "$1" -version 2>&1 | sed -nE 's/.*version "([^"]+)".*/\1/p' | head -n 1
}

find_java_home() {
    local dir java
    if [ -n "${JAVA25_HOME:-}" ]; then
        java="$JAVA25_HOME/bin/java"
        [ -x "$java" ] || die "JAVA25_HOME=$JAVA25_HOME has no executable bin/java"
        is_java_25 "$java" || die "JAVA25_HOME=$JAVA25_HOME is not a JDK 25"
        printf '%s\n' "$JAVA25_HOME"
        return
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && is_java_25 "$JAVA_HOME/bin/java"; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi
    for dir in /usr/lib/jvm/*25*; do
        [ -d "$dir" ] || continue
        java="$dir/bin/java"
        [ -x "$java" ] || continue
        if is_java_25 "$java"; then
            printf '%s\n' "$dir"
            return
        fi
    done
    die "no JDK 25 found; install one and set JAVA25_HOME (or JAVA_HOME) to it"
}

detect_java() {
    JAVA25_HOME="$(find_java_home)"
    JAVA25_VERSION="$(java_version "$JAVA25_HOME/bin/java")"
    [ -n "$JAVA25_VERSION" ] || die "could not determine version of $JAVA25_HOME/bin/java"
}

resolve_profile() {
    local accounts count name
    if [ -n "${RTX_PRISM_PROFILE:-}" ]; then
        printf '%s\n' "$RTX_PRISM_PROFILE"
        return
    fi
    accounts="$PRISM_DATA_DIR/accounts.json"
    [ -f "$accounts" ] || die "no accounts.json at $accounts; sign in to Prism Launcher or set RTX_PRISM_PROFILE"
    require_cmd python3
    count="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["accounts"]))' "$accounts")"
    [ "$count" -eq 1 ] || die "Prism has $count accounts; set RTX_PRISM_PROFILE to choose one"
    name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accounts"][0]["profile"]["name"])' "$accounts")"
    printf '%s\n' "$name"
}

fetch_fabric_api() {
    local url
    [ -f "$MODS/fabric-api-$FABRIC_API_VERSION.jar" ] && return 0
    require_cmd curl
    require_cmd python3
    url="$(curl -fsS --retry 2 "https://api.modrinth.com/v2/project/fabric-api/version/$FABRIC_API_VERSION" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(f["url"] for f in d["files"] if f["primary"]))')" \
        || die "could not resolve Fabric API $FABRIC_API_VERSION on Modrinth"
    printf 'rtx: downloading Fabric API %s\n' "$FABRIC_API_VERSION"
    curl -fsS --retry 2 -o "$MODS/fabric-api-$FABRIC_API_VERSION.jar.tmp" "$url" \
        || die "failed to download Fabric API from $url"
    mv -f "$MODS/fabric-api-$FABRIC_API_VERSION.jar.tmp" "$MODS/fabric-api-$FABRIC_API_VERSION.jar"
}

ensure_instance() {
    local uuid
    if [ -d "$INST" ]; then
        return 0
    fi
    printf 'rtx: creating instance %s\n' "$RTX_INSTANCE"
    mkdir -p "$MODS" "$MC/logs"
    uuid="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
    cat > "$INST/mmc-pack.json" <<EOF
{
    "components": [
        {
            "cachedName": "LWJGL 3",
            "cachedVersion": "3.4.3",
            "cachedVolatile": true,
            "dependencyOnly": true,
            "uid": "org.lwjgl3",
            "version": "3.4.3"
        },
        {
            "cachedName": "Minecraft",
            "cachedRequires": [
                {
                    "suggests": "3.4.3",
                    "uid": "org.lwjgl3"
                }
            ],
            "cachedVersion": "$MC_VERSION",
            "important": true,
            "uid": "net.minecraft",
            "version": "$MC_VERSION"
        },
        {
            "cachedName": "Intermediary Mappings",
            "cachedRequires": [
                {
                    "equals": "$MC_VERSION",
                    "uid": "net.minecraft"
                }
            ],
            "cachedVersion": "$MC_VERSION",
            "cachedVolatile": true,
            "dependencyOnly": true,
            "uid": "net.fabricmc.intermediary",
            "version": "$MC_VERSION"
        },
        {
            "cachedName": "Fabric Loader",
            "cachedRequires": [
                {
                    "uid": "net.fabricmc.intermediary"
                }
            ],
            "cachedVersion": "$FABRIC_LOADER_VERSION",
            "uid": "net.fabricmc.fabric-loader",
            "version": "$FABRIC_LOADER_VERSION"
        }
    ],
    "formatVersion": 1
}
EOF
    cat > "$INST/instance.cfg" <<EOF
[General]
ConfigVersion=1.3
InstanceType=OneSix
JavaPath=$JAVA25_HOME/bin/java
JavaVersion=$JAVA25_VERSION
MaxMemAlloc=12288
MinMemAlloc=2048
OverrideCommands=false
OverrideJavaLocation=true
OverrideMemory=true
name=$RTX_INSTANCE
uuid=$uuid
EOF
    printf 'soundCategory_master:0.0\n' > "$MC/options.txt"
    fetch_fabric_api
    printf 'rtx: instance ready at %s\n' "$INST"
}

ensure_prelaunch() {
    local cfg="$INST/instance.cfg"
    [ -f "$MC/remix-sdk/precompile.sh" ] || return 0
    python3 - "$cfg" <<'PY'
import sys

path = sys.argv[1]
command = 'bash "$INST_MC_DIR/remix-sdk/precompile.sh" "$INST_MC_DIR"'
lines = open(path, encoding="utf-8").read().splitlines()
have_command = any(line.startswith("prelaunchCommand=") for line in lines)
out = []
section = ""
for line in lines:
    if line.startswith("["):
        section = line
    if section == "[General]" and line.startswith("OverrideCommands="):
        out.append("OverrideCommands=true")
        continue
    if section == "[General]" and line.startswith("prelaunchCommand="):
        out.append("prelaunchCommand=" + command)
        have_command = True
        continue
    out.append(line)
    if section == "[General]" and line.startswith("ConfigVersion=") and not have_command:
        out.append("prelaunchCommand=" + command)
        have_command = True
open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY
}

build() {
    local build_log
    [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ] || die "gradle wrapper jar missing at $ROOT/gradle/wrapper/gradle-wrapper.jar"
    build_log="${TMPDIR:-/tmp}/rtx-package-$RTX_INSTANCE.log"
    printf 'rtx: building with %s\n' "$JAVA25_HOME/bin/java"
    if ! (cd "$ROOT" && "$JAVA25_HOME/bin/java" -cp gradle/wrapper/gradle-wrapper.jar \
        org.gradle.wrapper.GradleWrapperMain package -Parch=linux64 -x test > "$build_log" 2>&1); then
        tail -n 30 "$build_log" >&2 || true
        die "gradle package failed; full log at $build_log"
    fi
}

install_mod() {
    local jar f
    mkdir -p "$OLD_MODS" "$MODS"
    for f in "$MODS"/rtx-remix-renderer*.jar; do
        [ -e "$f" ] || continue
        printf 'rtx: moving old mod %s\n' "$(basename "$f")"
        mv -f "$f" "$OLD_MODS/"
    done
    jar="$(find "$ROOT/build/distributions" -maxdepth 1 -type f -name '*linux64.jar' \
        -printf '%T@ %p\n' | sort -n | tail -n 1 | cut -d' ' -f2-)"
    printf 'rtx: installing %s\n' "$(basename "$jar")"
    cp "$jar" "$MODS/"
}

precompile_instance() {
    bash "$ROOT/tools/linux/precompile.sh" "$MC" "${@}"
}

sub_launch() {
    local profile
    require_cmd prismlauncher
    require_cmd curl
    require_cmd python3
    detect_java
    [ -f "$ROOT/remix-runtime/linux64/libremixapi.so" ] || die \
        "staged runtime missing at $ROOT/remix-runtime/linux64; run tools/stage_linux_runtime.sh first"
    ensure_instance
    build
    install_mod
    ensure_prelaunch
    if [ -f "$MC/remix-sdk/precompile.sh" ]; then
        precompile_instance
    fi
    profile="$(resolve_profile)"
    printf 'rtx: launching %s as %s\n' "$RTX_INSTANCE" "$profile"
    setsid prismlauncher -l "$RTX_INSTANCE" -a "$profile" "${@}" \
        >> "$INST/prism-console.log" 2>&1 < /dev/null &
    printf 'rtx: Prism started (pid %s); console at %s\n' "$!" "$INST/prism-console.log"
}

sub_precompile() {
    [ -d "$INST" ] || die "instance $RTX_INSTANCE does not exist; run 'rtx.sh instance' first"
    precompile_instance "$@"
}

sub_instance() {
    detect_java
    ensure_instance
}

sub_status() {
    local java prism
    if JAVA25_HOME="$(find_java_home 2>/dev/null)"; then
        java="$JAVA25_HOME/bin/java"
    else
        java="not found (set JAVA25_HOME or JAVA_HOME)"
    fi
    if command -v prismlauncher >/dev/null 2>&1; then
        prism="$(command -v prismlauncher)"
    else
        prism="not found (install Prism Launcher)"
    fi
    printf 'Prism executable: %s\n' "$prism"
    printf 'JDK 25: %s\n' "$java"
    printf 'Prism data dir: %s\n' "$PRISM_DATA_DIR"
    if [ -d "$INST" ]; then
        printf 'Instance: %s\n' "$INST"
        [ -d "$MODS" ] && printf '  mods: %s jars\n' "$(find "$MODS" -maxdepth 1 -type f -name '*.jar' | wc -l)"
        [ -f "$MODS/fabric-api-$FABRIC_API_VERSION.jar" ] && printf '  Fabric API %s: present\n' "$FABRIC_API_VERSION"
        [ -d "$OLD_MODS" ] && printf '  old-mods: %s files\n' "$(find "$OLD_MODS" -maxdepth 1 -type f | wc -l)"
        if [ -f "$MC/remix-sdk/precompile.sh" ]; then
            printf '  remix-sdk: present (precompile ready)\n'
        else
            printf '  remix-sdk: not yet extracted (first launch creates it)\n'
        fi
    else
        printf 'Instance: not created yet (run "rtx.sh instance")\n'
    fi
    if [ -f "$ROOT/remix-runtime/linux64/libremixapi.so" ]; then
        printf 'Staged runtime: %s\n' "$ROOT/remix-runtime/linux64"
    else
        printf 'Staged runtime: missing (run tools/stage_linux_runtime.sh)\n'
    fi
}

case "${1:-}" in
    launch) shift; sub_launch "$@" ;;
    precompile) shift; sub_precompile "$@" ;;
    instance) sub_instance ;;
    status) sub_status ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
esac
