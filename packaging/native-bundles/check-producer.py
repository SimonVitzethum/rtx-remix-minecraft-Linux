#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Inspect actual producer outputs without asserting that an API or GPU test ran."""
import argparse
import json
from pathlib import Path
import re

from produce import require, sha, symbols


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=('linux64', 'linuxarm64', 'win64', 'arm64'), required=True)
    for name in ('runtime', 'bridge', 'jni-headers', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--llvm-readobj', default='llvm-readobj')
    args = parser.parse_args()
    expected = {}
    for header in args.jni_headers.glob('*.h'):
        for descriptor, export in re.findall(r'Signature:\s*(\S+)\s*\*/\s*JNIEXPORT\s+\w+\s+JNICALL\s+(\w+)', header.read_text()):
            expected[export] = descriptor
    require(expected and 'Java_com_nvidia_rtxremix_VoxelDag_overlaySurface' in expected,
            'Current generated JNI descriptors including overlaySurface are required')
    bridge = symbols(args.bridge, args.platform, args.llvm_readobj)
    runtime = symbols(args.runtime, args.platform, args.llvm_readobj)
    require(set(bridge['exports']) >= set(expected), f'Missing JNI exports: {set(expected) - set(bridge["exports"])}')
    require('remixapi_InitializeLibrary' in runtime['exports'], 'Own runtime lacks C API initialization')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        'platform': args.platform,
        'runtimeSha256': sha(args.runtime), 'bridgeSha256': sha(args.bridge),
        'runtime': runtime, 'bridge': bridge, 'jniSignatures': expected,
        'apiCompatibilityTest': 'not-run', 'gpuTest': 'not-run',
    }, indent=2, sort_keys=True) + '\n')
    print(f'Producer machine/export audit passed: {args.platform}; {len(expected)} JNI descriptors')


if __name__ == '__main__':
    main()
