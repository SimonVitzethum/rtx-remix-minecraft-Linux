/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include <array>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <map>
#include <set>
#include <string>
#include <vector>

#include <sys/inotify.h>
#include <unistd.h>

namespace dxvk {

  // Single-threaded event collector. The owner serializes registration and draining.
  class LinuxDirectoryWatch {
  public:
    LinuxDirectoryWatch() : m_fd(inotify_init1(IN_NONBLOCK | IN_CLOEXEC)) { }
    ~LinuxDirectoryWatch() {
      if (m_fd >= 0) {
        close(m_fd);
      }
    }
    LinuxDirectoryWatch(const LinuxDirectoryWatch&) = delete;
    LinuxDirectoryWatch& operator=(const LinuxDirectoryWatch&) = delete;

    bool valid() const { return m_fd >= 0; }

    bool addDirectory(const std::filesystem::path& directory) {
      std::error_code error;
      const auto path = std::filesystem::canonical(directory, error);
      if (error || !valid() || !std::filesystem::is_directory(path, error)) {
        return false;
      }
      for (const auto& root : m_roots) {
        if (within(path, root)) {
          return true;
        }
      }
      const bool complete = addTree(path);
      if (complete) {
        m_roots.insert(path);
      }
      return complete;
    }

    void clear() {
      // A fresh descriptor also discards queued IN_IGNORED events for old watch ids.
      if (m_fd >= 0) {
        close(m_fd);
      }
      m_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
      m_directories.clear();
      m_files.clear();
      m_roots.clear();
    }

    std::vector<std::filesystem::path> takeChanges() {
      std::set<std::filesystem::path> changed;
      alignas(inotify_event) std::array<char, 64 * 1024> buffer;
      bool overflow = false;
      while (valid()) {
        const auto count = read(m_fd, buffer.data(), buffer.size());
        if (count < 0 && errno == EINTR) {
          continue;
        }
        if (count <= 0) {
          break;
        }
        for (size_t offset = 0; offset + sizeof(inotify_event) <= size_t(count);) {
          const auto* event = reinterpret_cast<const inotify_event*>(buffer.data() + offset);
          const size_t bytes = sizeof(*event) + event->len;
          if (bytes > size_t(count) - offset) {
            break;
          }
          offset += bytes;
          if (event->mask & IN_Q_OVERFLOW) {
            overflow = true;
            continue;
          }
          const auto found = m_directories.find(event->wd);
          if (found == m_directories.end()) {
            continue;
          }
          const auto directory = found->second;
          const auto path = event->len
            ? directory / std::string(event->name, strnlen(event->name, event->len)) : directory;
          if (event->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED)) {
            removeTree(directory, changed);
          } else if (event->mask & IN_ISDIR) {
            if (event->mask & (IN_DELETE | IN_MOVED_FROM)) {
              removeTree(path, changed);
            } else if (event->mask & (IN_CREATE | IN_MOVED_TO)) {
              addTree(path);
              // A populated directory may be moved in before its watches are installed.
              for (const auto& file : m_files) {
                if (within(file, path)) {
                  changed.insert(file);
                }
              }
            }
          } else {
            if (event->mask & (IN_CLOSE_WRITE | IN_MOVED_TO | IN_DELETE | IN_MOVED_FROM)) {
              changed.insert(path);
            }
            if (event->mask & (IN_DELETE | IN_MOVED_FROM)) {
              m_files.erase(path);
            } else {
              m_files.insert(path);
            }
          }
        }
      }
      if (overflow) {
        // Include old names as well, so lost deletes invalidate dependent assets.
        changed.insert(m_files.begin(), m_files.end());
        const auto roots = m_roots;
        clear();
        for (const auto& root : roots) {
          addDirectory(root);
        }
        changed.insert(m_files.begin(), m_files.end());
      }
      return { changed.begin(), changed.end() };
    }

  private:
    static bool within(const std::filesystem::path& file, const std::filesystem::path& directory) {
      const auto prefix = directory.string() + '/';
      return file == directory || file.string().compare(0, prefix.size(), prefix) == 0;
    }

    bool addWatch(const std::filesystem::path& directory) {
      const int wd = inotify_add_watch(m_fd, directory.c_str(), IN_CLOSE_WRITE | IN_MOVED_TO | IN_MOVED_FROM
        | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE_SELF | IN_ONLYDIR | IN_DONT_FOLLOW);
      if (wd < 0) {
        return false;
      }
      m_directories[wd] = directory;
      return true;
    }

    bool addTree(const std::filesystem::path& directory) {
      bool complete = addWatch(directory);
      std::error_code error;
      auto iterator = std::filesystem::recursive_directory_iterator(directory,
        std::filesystem::directory_options::skip_permission_denied, error);
      const auto end = std::filesystem::recursive_directory_iterator();
      while (!error && iterator != end) {
        const auto& entry = *iterator;
        if (entry.is_directory(error) && !entry.is_symlink(error)) {
          complete = addWatch(entry.path()) && complete;
        } else if (entry.is_regular_file(error)) {
          m_files.insert(entry.path());
        }
        iterator.increment(error);
      }
      return complete && !error;
    }

    void removeTree(const std::filesystem::path& directory, std::set<std::filesystem::path>& changed) {
      for (auto it = m_files.begin(); it != m_files.end();) {
        if (within(*it, directory)) {
          changed.insert(*it);
          it = m_files.erase(it);
        } else {
          ++it;
        }
      }
      for (auto it = m_directories.begin(); it != m_directories.end();) {
        if (within(it->second, directory)) {
          inotify_rm_watch(m_fd, it->first);
          it = m_directories.erase(it);
        } else {
          ++it;
        }
      }
    }

    int m_fd;
    std::map<int, std::filesystem::path> m_directories;
    std::set<std::filesystem::path> m_files;
    std::set<std::filesystem::path> m_roots;
  };
}
