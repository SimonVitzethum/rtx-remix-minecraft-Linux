#!/usr/bin/env python3
"""Build inside resource-run.sh compile; output lives in the caller's scratch directory."""
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
slang = root.parent/'dxvk-tools/slang-2026/bin/slangc'
for name in ('cull','trace'):
    spv = out/(name+'.spv')
    subprocess.run([str(slang), str(Path(__file__).parent/(name+'.slang')), '-target','spirv','-profile','sm_6_6','-entry','main','-stage','compute','-o',str(spv)],check=True)
    subprocess.run(['spirv-val','--target-env','vulkan1.3',str(spv)],check=True)
    data=spv.read_bytes()
    words=[int.from_bytes(data[i:i+4],'little') for i in range(0,len(data),4)]
    (out/('rtx_cluster_selftest_'+name+'_spv.h')).write_text('static const uint32_t s17_'+name+'_spv[] = {'+','.join(hex(x) for x in words)+'};\n')
subprocess.run(['g++','-std=c++17','-O2','-I'+str(root/'include/vulkan/include'),'-I'+str(out),
    str(Path(__file__).parent/'main.cpp'),str(Path(__file__).parent/'rtx_cluster_selftest.cpp'),'-lvulkan','-o',str(out/'cluster_selftest')],check=True)
