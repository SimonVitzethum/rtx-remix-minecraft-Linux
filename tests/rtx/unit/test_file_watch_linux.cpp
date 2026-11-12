/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <thread>

#include "../../../src/dxvk/rtx_render/rtx_file_watch_linux.h"

namespace fs = std::filesystem;

static void require(bool condition, const char* message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

static void expectChange(dxvk::LinuxDirectoryWatch& watch, const fs::path& path) {
  for (unsigned attempt = 0; attempt < 100; ++attempt) {
    for (const auto& changed : watch.takeChanges()) {
      if (changed == path) {
        return;
      }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
  throw std::runtime_error("Missing inotify event for " + path.string());
}

int main() {
  char pattern[] = "/tmp/rtx-native-watch-XXXXXX";
  const char* temporary = mkdtemp(pattern);
  if (!temporary) {
    return 1;
  }
  const fs::path root(temporary);
  try {
    dxvk::LinuxDirectoryWatch watch;
    require(watch.valid(), "inotify initialization");
    fs::create_directory(root / "nested");
    const auto texture = root / "nested/texture.dds";
    std::ofstream(texture) << "original";
    require(watch.addDirectory(root), "recursive registration");
    require(watch.addDirectory(root / "nested"), "idempotent child registration");
    require(!watch.addDirectory(root / "missing"), "missing directory rejected");
    require(watch.takeChanges().empty(), "registration must not synthesize changes");

    {
      std::ofstream writing(texture);
      writing << "new bytes" << std::flush;
      require(watch.takeChanges().empty(), "open writer must not trigger partial upload");
    }
    expectChange(watch, texture);

    const auto replacement = root / "replacement.tmp";
    std::ofstream(replacement) << "replacement bytes";
    fs::rename(replacement, texture);
    expectChange(watch, texture);
    std::ofstream(texture) << "write after atomic replacement";
    expectChange(watch, texture);

    fs::create_directory(root / "new-directory");
    const auto created = root / "new-directory/created.usda";
    std::ofstream(created) << "new asset before directory watch drains";
    expectChange(watch, created);
    std::ofstream(created) << "second edit";
    expectChange(watch, created);
    fs::remove(created);
    expectChange(watch, created);

    // Moving populated trees exercises watch removal and re-registration, not a per-file inode watch.
    const auto moved = root / "moved-directory";
    fs::rename(root / "nested", moved);
    expectChange(watch, moved / "texture.dds");
    std::ofstream(moved / "texture.dds") << "edit in moved subtree";
    expectChange(watch, moved / "texture.dds");

    watch.clear();
    std::ofstream(moved / "texture.dds") << "no longer watched";
    require(watch.takeChanges().empty(), "clear must detach registrations and queued events");
    require(watch.addDirectory(root), "register after clear");
    std::ofstream(moved / "texture.dds") << "watch restored";
    expectChange(watch, moved / "texture.dds");
    fs::remove_all(root);
    std::cout << "Linux file watch: close-write, atomic replacement, recursive creation/move/delete and reset passed\n";
  } catch (const std::exception& error) {
    fs::remove_all(root);
    std::cerr << error.what() << '\n';
    return 1;
  }
  return 0;
}
