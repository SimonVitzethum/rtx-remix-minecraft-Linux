# Keep the variant parser as the single source of generated output names.
rtx_shader_sources = files('../shaders/rtx/shader_sources.txt')
rtx_shader_manifest = configure_file(
  output: 'rtx_shader_outputs.txt',
  command: [python_interpreter, shader_compile_script,
    '-input', rtx_shader_source_directory, '-output', rtx_shader_output_directory,
    '-source-list', rtx_shader_sources, '-list-outputs',
    '-depfile', '@DEPFILE@', '-dep-target', 'rtx_shader_outputs.txt'],
  depfile: 'rtx_shader_manifest.d',
  capture: true
)
rtx_shader_outputs = run_command(python_interpreter, '-c',
  'import pathlib,sys; print(pathlib.Path(sys.argv[1]).read_text(), end="")',
  rtx_shader_manifest, check: true).stdout().strip().split('\n')

rtx_shaders = custom_target(
  'rtx_shaders',
  build_by_default: true,
  output: rtx_shader_outputs,
  depfile: 'rtx_shaders.d',
  depend_files: [rtx_shader_sources, files('../../../scripts-common/compile_shaders.py',
    '../../../scripts-common/shader_xxd.py', '../../../scripts-common/depfile.py')],
  command: [python_interpreter] + rtx_shaders_command_arguments +
    ['-source-list', rtx_shader_sources, '-depfile', '@DEPFILE@', '-dep-target', '@OUTPUT0@']
)
rtx_shader_headers = []
foreach i : range(rtx_shader_outputs.length())
  if rtx_shader_outputs[i].endswith('.h')
    rtx_shader_headers += rtx_shaders[i]
  endif
endforeach
