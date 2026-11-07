util_src = files([
  'util_aftermath.cpp',
  'util_aftermath.h',
  'util_env.cpp',
  'util_string.cpp',
  'util_fps_limiter.cpp',
  'util_flush.cpp',
  'util_gdi.cpp',
  'util_luid.cpp',
  'util_matrix.cpp',
  'util_monitor.cpp',
  'util_sentry.cpp',

  'thread.cpp',
  
  'util_shared_res.cpp',
  
  'com/com_guid.cpp',
  'com/com_private_data.cpp',

  'config/config.cpp',
  
  'log/metrics.cpp',
  'log/log.cpp',
  'log/log_debug.cpp',
  
  'sha1/sha1.c',
  'sha1/sha1_util.cpp',

  'sync/sync_recursive.cpp',
  'xxHash/xxhash.c',
  'xxHash/xxhash.h',

  'util_memoization.h',

  'util_messagechannel.cpp',
  'util_messagechannel.h',

  'util_singleton.h',

  'util_struct_hash.h',

  'util_fastops.cpp',
  'util_fastops.h',

  'util_fast_cache.h',
  
  'util_filesys.h',
  'util_filesys.cpp',

  'util_local_data.h',
  'util_local_data.cpp',

  'util_per_game_config.h',

  'util_crash_report.h',

  'util_threadpool.h',
  'util_atomic_queue.h',

  'util_renderprocessor.h',
  
  'util_version.h',
  'util_remix_api_version.h',

  'util_global_time.cpp',
  'util_global_time.h',
])

if host_machine.system() == 'windows'
  util_src += files(['util_window.cpp'])
else
  util_src += files(['linux/win32_compat.cpp', 'linux/aftermath_stub.cpp', 'linux/nvapi_stub.cpp', 'linux/reflex_stub.cpp', 'linux/nrc_stub.cpp', 'linux/xess_stub.cpp', 'linux/window_stub.cpp'])
endif

util_lib = static_library('util', util_src,
  dependencies        : [ sentry_dep, aftermath_dep, nvapi_dep ],
  include_directories : [ dxvk_include_path ],
  override_options    : ['cpp_std='+dxvk_cpp_std])

util_dep = declare_dependency(
  link_with           : [ util_lib ],
  dependencies        : [ sentry_dep, aftermath_dep ])
