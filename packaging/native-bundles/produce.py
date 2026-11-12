#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Audit and seal already compiled runtimes; this tool never downloads or compiles."""

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

PLATFORMS = ("linux64", "linuxarm64", "win64", "arm64")
# These remain system prerequisites. Other dependencies must be in the bundle.
LINUX_SYSTEM = {"libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1",
                "libgcc_s.so.1", "libstdc++.so.6", "ld-linux-x86-64.so.2", "ld-linux-aarch64.so.1",
                "libGL.so.1", "libGLX.so.0", "libOpenGL.so.0", "libvulkan.so.1", "libX11.so.6",
                "libXext.so.6", "libxcb.so.1", "libwayland-client.so.0", "libnvidia-ml.so.1"}
WINDOWS_SYSTEM = {s + ".dll" for s in (
    "kernel32", "ntdll", "user32", "gdi32", "advapi32", "ole32", "oleaut32", "shell32",
    "shlwapi", "version", "ws2_32", "winmm", "winhttp", "wininet", "crypt32", "bcrypt",
    "secur32", "setupapi", "psapi", "dbghelp", "dwmapi", "comdlg32", "comctl32", "imm32",
    "uxtheme", "opengl32", "vulkan-1", "dxgi", "d3d11", "d3d12", "d3d9", "msvcrt",
    "ucrtbase", "powrprof", "cfgmgr32", "wintrust", "winspool.drv", "hid", "propsys")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def bridge_hash(root):
    value = hashlib.sha256()
    for name in (root / "packaging/native-bundles/source-files.txt").read_text().splitlines():
        if not name:
            continue
        value.update((name + "\n" + sha(root / "rtxremix-java/native" / name) + "\n").encode())
    return value.hexdigest()


def command(args):
    # Large symbol tables go to disk first and have a bounded parser input.
    with tempfile.TemporaryFile() as out:
        subprocess.run(args, check=True, stdout=out, stderr=subprocess.PIPE, timeout=90)
        require(out.tell() <= 32 * 1024 * 1024, "Oversized binary audit output")
        out.seek(0)
        return out.read().decode("utf-8", errors="strict")


def revision(root):
    return command(["git", "-C", str(root), "rev-parse", "HEAD"]).strip()


def safe_name(name):
    require(len(name) <= 1024, "Native path too long")
    for part in name.split("/"):
        require(re.fullmatch(r"[A-Za-z0-9_@+.-]{1,240}", part) and part not in (".", "..")
                and not part.endswith(".")
                and not re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])([.].*)?", part),
                f"Unsafe native path: {name}")
    return name


def machine(path, platform):
    with path.open("rb") as f:
        h = f.read(4096)
    arm = platform in ("linuxarm64", "arm64")
    if platform.startswith("linux"):
        require(len(h) >= 64 and h[:6] == b"\x7fELF\x02\x01"
                and struct.unpack_from("<H", h, 18)[0] == (183 if arm else 62), f"Wrong ELF machine: {path}")
    else:
        require(len(h) >= 64 and h[:2] == b"MZ", f"Not PE: {path}")
        offset = struct.unpack_from("<I", h, 60)[0]
        require(offset <= len(h) - 26 and h[offset:offset+4] == b"PE\0\0"
                and struct.unpack_from("<H", h, offset+4)[0] == (0xaa64 if arm else 0x8664)
                and struct.unpack_from("<H", h, offset+24)[0] == 0x20b,
                f"Wrong PE machine (ARM64EC prohibited): {path}")


def symbols(path, platform, readobj):
    machine(path, platform)
    linux = platform.startswith("linux")
    args = [readobj, "--dyn-symbols", "--needed-libs", "--notes"] if linux else [readobj, "--coff-exports", "--coff-imports"]
    text = command([*args, str(path)])
    if linux:
        sections = re.findall(r"Symbol \{(.*?)\n  \}", text, re.S)
        exports = sorted({re.search(r"Name: (\S+)", s)[1].split("@")[0] for s in sections
                          if re.search(r"Name: (\S+)", s) and "Section: Undefined" not in s})
        needed = re.search(r"NeededLibraries \[(.*?)\]", text, re.S)
        imports = needed[1].split() if needed else []
        require(not re.search(r"x86-64-v[234]|x86 ISA needed:.*(?:AVX|SSE4)", text, re.I),
                f"Non-baseline x86 CPU requirements: {path}")
        versions = re.findall(r"\b(GLIBC|GLIBCXX|CXXABI)_([0-9.]+)", text)
        maximum = {}
        for prefix, version in versions:
            maximum[prefix] = max(maximum.get(prefix, (0,)), tuple(map(int, version.split("."))))
        return {"exports": exports, "imports": sorted(imports),
                "versions": {k: ".".join(map(str, v)) for k, v in maximum.items()}}
    exports = sorted(re.findall(r"Export \{\s+Ordinal:.*?\s+Name: (\S+)", text, re.S))
    imports = sorted(re.findall(r"Import \{\s+Name: (\S+)", text))
    return {"exports": exports, "imports": imports, "versions": {}}


def properties(values):
    return "".join(f"{k}={str(v).replace(chr(92), chr(92)*2)}\n" for k, v in sorted(values.items())).encode("ascii")


def produce(args):
    root = args.mod_source.resolve()
    runtime = args.runtime_source.resolve()
    payload = args.payload.resolve()
    require(payload.is_dir(), "Staged runtime payload is missing")
    copied = root / "rtxremix-java/native/include/remix_c.h"
    canonical = runtime / "public/include/remix/remix_c.h"
    require(sha(copied) == sha(canonical), "Copied C API header differs from the runtime source")
    header = copied.read_text()
    version = ".".join(re.search(rf"#define REMIXAPI_VERSION_{key}\s+(\d+)\b", header)[1]
                       for key in ("MAJOR", "MINOR", "PATCH"))
    body = re.search(r"typedef\s+struct\s+remixapi_Interface\s*\{(.*?)\}\s*remixapi_Interface\s*;", header, re.S)[1]
    body = re.sub(r"/\*.*?\*/|//[^\n]*", "", body, flags=re.S)
    pointers = len(re.findall(r"PFN_\w+\s+\w+\s*;", body))
    require(not re.sub(r"PFN_\w+\s+\w+\s*;", "", body).strip() and pointers > 0, "Unknown C interface layout")
    # A producer must compare its own build result, rather than relabel a cached runtime.
    runtime_name = "libremixapi.so" if args.platform.startswith("linux") else "d3d9.dll"
    bridge_name = "remixbridge.so" if args.platform.startswith("linux") else "remixbridge.dll"
    require(sha(payload / runtime_name) == sha(args.built_runtime), "Staged runtime is not the supplied build result")
    require(sha(payload / bridge_name) == sha(args.built_bridge), "Staged JNI bridge is not the supplied build result")
    provenance = json.loads(args.provenance.read_text())
    source = revision(runtime)
    require(provenance["runtimeSource"] == source and provenance["headerSha256"] == sha(canonical)
            and provenance["runtimeSha256"] == sha(args.built_runtime)
            and provenance["bridgeSourceSha256"] == bridge_hash(root)
            and provenance["bridgeSha256"] == sha(args.built_bridge), "Build provenance does not match its inputs/outputs")
    require(provenance["platform"] == args.platform and provenance["api"] == version and provenance["interfacePointers"] == pointers
            and provenance["apiCompatibilityTest"] == "passed", "Producer API compatibility gate has not passed")
    require(provenance["cpuBaseline"] == ("armv8-a" if "arm64" in args.platform else "x86-64"), "Incorrect CPU baseline")
    require(not re.search(r"-march=native|-mtune=native|-march=x86-64-v[234]|/arch:AVX", provenance["compilerCommands"], re.I),
            "Host-specific CPU flags in producer build")
    files = {}
    names = set()
    for path in sorted(payload.rglob("*")):
        require(not path.is_symlink(), f"Materialize symlinks without touching the shared SDK: {path}")
        if path.is_dir():
            continue
        require(path.is_file() and path.stat().st_size > 0, f"Special/empty runtime payload: {path}")
        name = safe_name(path.relative_to(payload).as_posix())
        require(name.lower() not in names and name not in ("bundle.properties", "inventory.properties", "audit.json", "jni-signatures.properties"),
                f"Reserved/duplicate native path: {name}")
        require(not re.search(r"(?i)(python[^/]*\.(dll|so)(\.[0-9]+)*|\.(pyd|pyc|pdb|a|lib|o|obj))$", name), f"Development/Python payload prohibited: {name}")
        names.add(name.lower())
        files[name] = path
    # All redistributed license texts are inventories, including the JNI miniz dependency.
    license_files = sorted(n for n in files if n.startswith("licenses/"))
    require(license_files and any(sha(files[n]) == sha(root / "rtxremix-java/native/third_party/miniz/LICENSE")
                                  for n in license_files), "Missing runtime or miniz licenses")
    assignments = json.loads(args.license_inventory.read_text())
    audit = {"schema": 1, "platform": args.platform, "provenance": provenance, "binaries": {}}
    all_exports = set()
    for name, path in files.items():
        if re.search(r"(?i)(\.so(\.[0-9]+)*|\.dll|\.exe)$", name):
            require(name in assignments and isinstance(assignments[name], list) and assignments[name]
                    and all(license in license_files for license in assignments[name]),
                    f"Missing/invalid per-binary license assignment: {name}")
            data = symbols(path, args.platform, args.llvm_readobj)
            audit["binaries"][name] = data
            if name == bridge_name:
                all_exports.update(data["exports"])
            if name == runtime_name:
                require("remixapi_InitializeLibrary" in data["exports"], "Runtime lacks the Remix C API export")
    expected = set()
    signatures = {}
    for generated in args.jni_headers.glob("*.h"):
        text = generated.read_text()
        for signature, export in re.findall(r"Signature:\s*(\S+)\s*\*/\s*JNIEXPORT\s+\w+\s+JNICALL\s+(\w+)", text):
            expected.add(export)
            signatures[export] = signature
    require(expected and "Java_com_nvidia_rtxremix_VoxelDag_overlaySurface" in expected, "Missing current generated JNI headers")
    require(all_exports >= expected, f"Missing JNI exports: {sorted(expected - all_exports)}")
    audit["jniSignatures"] = dict(sorted(signatures.items()))
    for name, data in audit["binaries"].items():
        for dependency in data["imports"]:
            system = dependency in LINUX_SYSTEM if args.platform.startswith("linux") else (
                dependency.lower() in WINDOWS_SYSTEM or dependency.lower().startswith(("api-ms-win-", "ext-ms-win-")))
            require(system or dependency.lower() in {Path(n).name.lower() for n in files}, f"Unbundled dependency {dependency} of {name}")
        if args.platform.startswith("linux"):
            actual = data["versions"].get("GLIBC", "0")
            require(tuple(map(int, actual.split("."))) <= tuple(map(int, args.minimum_glibc.split("."))),
                    f"{name} requires glibc {actual}, above declared {args.minimum_glibc}")
    metadata = {"schema": 1, "platform": args.platform, "api": version, "bridgeAbi": "source-v1", "interfacePointers": pointers,
                "runtimeSource": source, "bridgeSource": revision(root), "headerSha256": sha(copied),
                "bridgeSourceSha256": bridge_hash(root), "minimumOs": ("glibc>=" + args.minimum_glibc
                    if args.platform.startswith("linux") else args.minimum_windows),
                "minimumVulkan": args.minimum_vulkan, "minimumDriver": args.minimum_driver}
    generated = {"audit.json": (json.dumps(audit, sort_keys=True, indent=2) + "\n").encode(),
                 "bundle.properties": properties(metadata), "jni-signatures.properties": properties(signatures)}
    inventory = {"file." + name: f"{path.stat().st_size}:{sha(path)}" for name, path in files.items()}
    for name in ("audit.json", "jni-signatures.properties"):
        inventory["file." + name] = f"{len(generated[name])}:{hashlib.sha256(generated[name]).hexdigest()}"
    inventory.update({f"license.{i}": name for i, name in enumerate(license_files)})
    inventory.update({"license." + name: ",".join(licenses) for name, licenses in assignments.items()})
    generated["inventory.properties"] = properties(inventory)
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / (args.platform + ".zip")
    temporary = archive.with_suffix(".part")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=False) as out:
            for name in sorted(set(files) | set(generated)):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                with out.open(info, "w") as target:
                    if name in generated:
                        target.write(generated[name])
                    else:
                        with files[name].open("rb") as source_file:
                            shutil.copyfileobj(source_file, target, length=65536)
        require(temporary.stat().st_size <= 1024**3, "Archive exceeds installer limit")
        unpacked = sum(p.stat().st_size for p in files.values()) + sum(len(v) for v in generated.values())
        require(unpacked <= 3 * 1024**3, "Unpacked archive exceeds installer limit")
        temporary.replace(archive)
        pin = {**metadata, "url": args.url, "sha256": sha(archive), "bytes": archive.stat().st_size, "unpackedBytes": unpacked}
        (args.output / (args.platform + ".pin.properties")).write_bytes(properties(pin))
        print(json.dumps({"archive": str(archive), "sha256": pin["sha256"], "bytes": pin["bytes"], "url": args.url}))
    finally:
        temporary.unlink(missing_ok=True)


def freeze(args):
    # Pins are parsed narrowly: producer properties contain ASCII scalar values only.
    pins = {}
    for platform in PLATFORMS:
        pin = dict(line.split("=", 1) for line in (args.pins / (platform + ".pin.properties")).read_text().splitlines() if line)
        require(pin["platform"] == platform, "Wrong platform pin")
        require(pin["url"].startswith("https://") and "/latest/" not in pin["url"], "Need a real immutable HTTPS asset URL")
        archive = args.pins / (platform + ".zip")
        require(sha(archive) == pin["sha256"] and archive.stat().st_size == int(pin["bytes"]), "Bundle changed since producer audit")
        pins[platform] = pin
    common = ("api", "bridgeAbi", "interfacePointers", "runtimeSource", "bridgeSource", "headerSha256", "bridgeSourceSha256")
    require(all(len({pin[k] for pin in pins.values()}) == 1 for k in common), "All four bundles must share the frozen source/ABI")
    manifest = {"schema": 1, "freeze": "final", "platforms": ",".join(PLATFORMS), **{k: pins["linux64"][k] for k in common}}
    for platform, pin in pins.items():
        manifest[platform + ".status"] = "available"
        for key in ("url", "sha256", "bytes", "unpackedBytes", "minimumOs", "minimumVulkan", "minimumDriver"):
            manifest[platform + "." + key] = pin[key]
    args.manifest.write_bytes(properties(manifest))
    print("Manifest prepared. Verify the real HTTPS assets and run ./gradlew build before publication.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    seal = sub.add_parser("produce")
    seal.add_argument("--platform", choices=PLATFORMS, required=True)
    for name in ("mod-source", "runtime-source", "payload", "built-runtime", "built-bridge", "provenance", "jni-headers", "license-inventory", "output"):
        seal.add_argument("--" + name, type=Path, required=True)
    seal.add_argument("--llvm-readobj", default="llvm-readobj")
    seal.add_argument("--minimum-glibc", default="2.44")
    seal.add_argument("--minimum-windows", default="Windows-11")
    seal.add_argument("--minimum-vulkan", required=True)
    seal.add_argument("--minimum-driver", required=True)
    seal.add_argument("--url", required=True)
    seal.set_defaults(run=produce)
    combine = sub.add_parser("freeze")
    combine.add_argument("--pins", type=Path, required=True)
    combine.add_argument("--manifest", type=Path, required=True)
    combine.set_defaults(run=freeze)
    args = parser.parse_args()
    try:
        args.run(args)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Native producer blocked: {error}\n")


if __name__ == "__main__":
    main()
