#!/usr/bin/env python3
# Portable replacement for the Windows-only nv_xxd.exe.
# Usage: embed_xxd.py <input> <output.h> <basename>
# Emits: unsigned char ___<basename>[] = { 0x.., ... };
import re
import sys

src, dst, name = sys.argv[1:4]
name = re.sub(r'[^A-Za-z0-9_]', '_', name)
data = open(src, 'rb').read()
with open(dst, 'w') as f:
    f.write('unsigned char ___%s[] = {\n' % name)
    for i in range(0, len(data), 16):
        f.write('  ' + ', '.join('0x%02x' % b for b in data[i:i + 16]) + ',\n')
    f.write('};\n')
