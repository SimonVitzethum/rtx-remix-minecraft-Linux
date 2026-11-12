/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#include <iostream>
#include <string>

#include "../../../src/dxvk/rtx_render/rtx_asset_data_manager.h"
#include "../../../src/dxvk/rtx_render/rtx_file_watch.h"

int main(int argc, char** argv) {
  if (argc > 1 && std::string(argv[1]) == "watch-first") {
    dxvk::FileWatch::get();
  }
  dxvk::AssetDataManager::get();
  dxvk::FileWatch::get();
  std::cout << "Constructed production asset manager and watcher; checking process-exit destruction" << std::endl;
  return 0;
}
