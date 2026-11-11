"""Exercise generated shader bytes, ELF linkage and compiler-cache invalidation."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform.startswith('linux'), 'Native ELF shader embedding')
class ShaderEmbeddingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='remix shader ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.binary = self.root / 'shader "quoted".spv'
        self.header = self.root / 'probe.h'
        self.generator = Path(__file__).with_name('shader_xxd.py')

    def generate(self, data):
        self.binary.write_bytes(data)
        subprocess.run([sys.executable, str(self.generator), '-i', str(self.binary), '-o', str(self.header)], check=True)

    def compile(self, sources, output, cached=False):
        command = ['c++', '-std=c++17', '-O3', '-Wall', '-Wextra', '-Werror',
                   '--param=ggc-min-expand=5', '--param=ggc-min-heapsize=16384']
        if cached:
            command.insert(0, 'ccache')
        subprocess.run(command + [str(path) for path in sources] + ['-o', str(output)], check=True)

    def test_native_and_array_bytes_alignment_and_comdat(self):
        for data in (b'\x03\x02\x23\x07', bytes(range(37))):
            with self.subTest(size=len(data)):
                self.generate(data)
                expected = data + b'\0' * (-len(data) % 4)
                main = self.root / 'main.cpp'
                main.write_text('#include <cstdint>\n#include <cstddef>\n#include <cstring>\n'
                                '#define REMIX_LINUX_NATIVE 1\n#include "probe.h"\n'
                                'const uint32_t* other(); bool fallback(const void*);\n'
                                'int main() { const unsigned char expected[] = {' + ','.join(map(str, expected)) + '};\n'
                                'return sizeof(probe) != sizeof(expected) || probe_sizeInBytes != ' + str(len(data)) +
                                ' || reinterpret_cast<uintptr_t>(probe) % 4 || probe != other()'
                                ' || std::memcmp(probe, expected, sizeof(expected)) || !fallback(probe); }\n')
                other = self.root / 'other.cpp'
                other.write_text('#include <cstdint>\n#include <cstddef>\n#define REMIX_LINUX_NATIVE 1\n'
                                 '#include "probe.h"\nconst uint32_t* other() { return probe; }\n')
                fallback = self.root / 'fallback.cpp'
                fallback.write_text('#include <cstdint>\n#include <cstddef>\n#include <cstring>\n#include "probe.h"\n'
                                    'bool fallback(const void* p) { return !std::memcmp(p, probe, sizeof(probe)); }\n')
                output = self.root / 'probe'
                self.compile([main, other, fallback], output)
                subprocess.run([str(output)], check=True)

    @unittest.skipUnless(shutil.which('ccache'), 'ccache not installed')
    def test_same_size_binary_change_invalidates_cache(self):
        source = self.root / 'cached.cpp'
        source.write_text('#include <cstdint>\n#include <cstddef>\n#include <cstdio>\n'
                          '#define REMIX_LINUX_NATIVE 1\n#include "probe.h"\n'
                          'int main() { return std::fwrite(probe, 1, probe_sizeInBytes, stdout) != probe_sizeInBytes; }\n')
        output = self.root / 'cached'
        obj = self.root / 'cached.o'
        for data in (b'\x01\x02\x03\x04', b'\x05\x06\x07\x08', b'\x05\x06\x07\x08'):
            self.generate(data)
            subprocess.run(['ccache', 'c++', '-std=c++17', '-O3', '--param=ggc-min-expand=5',
                            '--param=ggc-min-heapsize=16384', '-c', str(source), '-o', str(obj)], check=True)
            self.compile([obj], output)
            self.assertEqual(subprocess.check_output([str(output)]), data)


if __name__ == '__main__':
    unittest.main()
