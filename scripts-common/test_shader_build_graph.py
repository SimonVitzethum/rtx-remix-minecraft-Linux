"""Exercise the production Meson shader graph with real Slang and C++ compilers."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SLANG = Path(os.environ.get('REMIX_TEST_SLANGC', REPO.parent / 'dxvk-tools/slang-2026/bin/slangc'))


@unittest.skipUnless(SLANG.is_file() and shutil.which('meson') and shutil.which('ninja'), 'Meson, Ninja and Slang required')
class ShaderBuildGraphTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='remix-shader-graph-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'src/dxvk/shaders/rtx'
        self.source.mkdir(parents=True)
        graph = self.root / 'src/dxvk/rtx_shaders'
        graph.mkdir()
        shutil.copyfile(REPO / 'src/dxvk/rtx_shaders/meson.build', graph / 'meson.build')
        (self.root / 'scripts-common').symlink_to(REPO / 'scripts-common', target_is_directory=True)
        self.registry = self.source / 'shader_sources.txt'
        self.registry.write_text('probe.comp.slang\n')
        self.include = self.source / 'value.slangh'
        self.include.write_text('#define VALUE 17\n')
        self.shader = self.source / 'probe.comp.slang'
        self.shader.write_text('//!variant-matrix probe\n//!> axis mode a MODE=0\n//!> axis mode b MODE=1\n//!end-variants\n'
                               '#include "value.slangh"\nRWStructuredBuffer<uint> result;\n'
                               '[shader("compute")] [numthreads(1,1,1)] void main(uint3 id : SV_DispatchThreadID) { result[id.x] = VALUE + MODE; }\n')
        (self.root / 'meson.build').write_text("""project('shader-graph', 'cpp', default_options: ['cpp_std=c++17', 'optimization=3'])
python_interpreter = find_program('python3')
shader_compile_script = meson.project_source_root() / 'scripts-common/compile_shaders.py'
rtx_shader_source_directory = meson.project_source_root() / 'src/dxvk/shaders/rtx'
rtx_shader_output_directory = meson.project_build_root() / 'src/dxvk/rtx_shaders'
rtx_shaders_command_arguments = [shader_compile_script, '-slangc', '""" + str(SLANG) + """',
  '-glslang', find_program('glslangValidator').full_path(), '-spirvval', find_program('spirv-val').full_path(),
  '-input', rtx_shader_source_directory, '-output', rtx_shader_output_directory, '-parallel', '-jobs', '2']
subdir('src/dxvk/rtx_shaders')
executable('probe', ['main.cpp', 'fallback.cpp', 'unrelated.cpp'] + rtx_shader_headers,
  include_directories: include_directories('src/dxvk'), cpp_args: ['-DREMIX_LINUX_NATIVE=1'])
""")
        (self.root / 'main.cpp').write_text('#include <cstdint>\n#include <cstddef>\n#include <cstdio>\n'
            '#include <rtx_shaders/probe_variants.h>\nbool fallback(const void*, size_t);\n'
            'int main() { return reinterpret_cast<uintptr_t>(probe_a) % 4 || !fallback(probe_a, sizeof(probe_a)) || '
            'std::fwrite(probe_a, 1, probe_a_sizeInBytes, stdout) != probe_a_sizeInBytes; }\n')
        (self.root / 'fallback.cpp').write_text('#include <cstdint>\n#include <cstddef>\n#include <cstring>\n'
            '#undef REMIX_LINUX_NATIVE\n#include <rtx_shaders/probe_variants.h>\n'
            'bool fallback(const void* bytes, size_t size) { return size == sizeof(probe_a) && !std::memcmp(bytes, probe_a, size); }\n')
        (self.root / 'unrelated.cpp').write_text('int unrelated() { return 1; }\n')
        self.build = self.root / 'build'
        self.run_command(['meson', 'setup', str(self.build), str(self.root)])

    def run_command(self, command):
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end='', flush=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        return result.stdout

    def build_once_and_check(self):
        self.run_command(['ninja', '-C', str(self.build), '-j1'])
        binary = (self.build / 'src/dxvk/rtx_shaders/probe_a.spv').read_bytes()
        self.assertEqual(subprocess.check_output([str(self.build / 'probe')]), binary)
        self.assertIn('no work to do', self.run_command(['ninja', '-C', str(self.build), '-j1']))
        return binary

    def test_legacy_undeclared_outputs_reproduce_stale_link(self):
        (self.root / 'src/dxvk/rtx_shaders/meson.build').write_text("rtx_shaders = custom_target('rtx_shaders', output: 'marker.h', build_by_default: true, command: [python_interpreter] + rtx_shaders_command_arguments)\nrtx_shader_headers = [rtx_shaders]\n")
        self.run_command(['ninja', '-C', str(self.build), '-j1'])
        first = subprocess.check_output([str(self.build / 'probe')])
        self.include.write_text('#define VALUE 29\n')
        self.run_command(['ninja', '-C', str(self.build), '-j1'])
        current = (self.build / 'src/dxvk/rtx_shaders/probe_a.spv').read_bytes()
        linked = subprocess.check_output([str(self.build / 'probe')])
        self.assertNotEqual(first, current)
        self.assertEqual(first, linked)
        self.assertNotEqual(current, linked)

    def test_incremental_shader_include_matrix_missing_output_and_new_source(self):
        first = self.build_once_and_check()
        unrelated = self.build / 'probe.p/unrelated.cpp.o'
        unrelated_mtime = unrelated.stat().st_mtime_ns
        self.include.write_text('#define VALUE 29\n')
        second = self.build_once_and_check()
        self.assertNotEqual(first, second)
        self.shader.write_text(self.shader.read_text().replace('VALUE + MODE', 'VALUE * 2 + MODE'))
        third = self.build_once_and_check()
        self.assertNotEqual(second, third)
        (self.build / 'src/dxvk/rtx_shaders/probe_a.h').unlink()
        self.assertEqual(third, self.build_once_and_check())
        (self.build / 'src/dxvk/rtx_shaders/probe_b.spv').unlink()
        self.assertEqual(third, self.build_once_and_check())
        self.shader.write_text(self.shader.read_text().replace('//!> axis mode b MODE=1', '//!> axis mode b MODE=1\n//!> axis mode c MODE=2'))
        self.assertEqual(third, self.build_once_and_check())
        self.assertTrue((self.build / 'src/dxvk/rtx_shaders/probe_c.h').is_file())
        (self.source / 'new.comp.slang').write_text('RWStructuredBuffer<uint> result;\n[shader("compute")] [numthreads(1,1,1)] void main(uint3 id : SV_DispatchThreadID) { result[id.x] = 31; }\n')
        self.registry.write_text(self.registry.read_text() + 'new.comp.slang\n')
        self.assertEqual(third, self.build_once_and_check())
        self.assertTrue((self.build / 'src/dxvk/rtx_shaders/new.h').is_file())
        self.assertEqual(unrelated_mtime, unrelated.stat().st_mtime_ns)


if __name__ == '__main__':
    unittest.main()
