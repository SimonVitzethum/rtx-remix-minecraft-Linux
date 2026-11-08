/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Integer LOD tunables from a system property, or from {@code rtxremix-lod.properties} in the
 * game directory (same keys), for launchers that do not pass JVM arguments through. The system
 * property wins.
 */
public final class LodOverrides {
    private static final Properties FILE = load();

    private LodOverrides() {
    }

    private static Properties load() {
        Properties properties = new Properties();
        Path path = Path.of("rtxremix-lod.properties");
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            } catch (IOException | RuntimeException ignored) {
                // A bad override file must never stop the game.
            }
        }
        return properties;
    }

    public static int get(String key, int fallback) {
        Integer fromProperty = Integer.getInteger(key);
        if (fromProperty != null) {
            return fromProperty;
        }
        try {
            String fromFile = FILE.getProperty(key);
            return fromFile == null ? fallback : Integer.parseInt(fromFile.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
