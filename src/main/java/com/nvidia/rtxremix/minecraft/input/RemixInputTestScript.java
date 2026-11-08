/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless test hook for the Remix menu input path. Only active when the JVM property
 * {@code rtxremix.test.script} (or the environment variable {@code RTXREMIX_TEST_SCRIPT}) names a
 * file. Each line is {@code <seconds> <command> [args]}, with the time counted from the first
 * frame in a world. The commands call Minecraft's own SDL handlers, so the mixins and the gate
 * see exactly what real input would produce.
 *
 * <pre>
 * open [dev|graphics]      RtxRemixMod.openRemixMenu
 * close                    close the menu like Alt+X does
 * altx                     Alt+X chord through KeyboardHandler.keyPress (toggles the developer menu)
 * move X Y                 cursor in window coordinates; "c" is the window centre; X and Y may be fractions like 0.5
 * click [left|right]       press and release at the current position
 * down / up [button]       press or release only
 * scroll DY                wheel
 * char C                   one character (text input)
 * key NAME                 press and release of esc, enter, tab, a..z, f1..f12, backspace, up, down, left, right
 * </pre>
 */
public final class RemixInputTestScript {
    private static final Logger LOGGER = LoggerFactory.getLogger("RemixInputTest");
    private static final String PROPERTY = "rtxremix.test.script";
    private static final String ENVIRONMENT = "RTXREMIX_TEST_SCRIPT";

    private record Step(double time, String[] words) {
    }

    private static List<Step> steps;
    private static boolean loaded;
    private static int next;
    private static long startNanos = -1L;
    private static double cursorX;
    private static double cursorY;

    private RemixInputTestScript() {
    }

    private static void load() {
        loaded = true;
        String path = System.getProperty(PROPERTY, System.getenv(ENVIRONMENT));
        if (path == null || path.isBlank()) {
            return;
        }
        steps = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of(path.trim()))) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] words = line.split("\\s+");
                steps.add(new Step(Double.parseDouble(words[0]), java.util.Arrays.copyOfRange(words, 1, words.length)));
            }
            LOGGER.info("Input test script {} loaded: {} steps", path, steps.size());
        } catch (Exception e) {
            LOGGER.error("Input test script {} failed to load", path, e);
            steps = null;
        }
    }

    /** Runs due steps. Call once per frame on the render thread. */
    public static void tick() {
        if (!loaded) {
            load();
        }
        if (steps == null || next >= steps.size()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        long now = System.nanoTime();
        if (startNanos < 0) {
            startNanos = now;
        }
        double elapsed = (now - startNanos) / 1.0e9;
        while (next < steps.size() && steps.get(next).time <= elapsed) {
            Step step = steps.get(next++);
            try {
                run(mc, step.words);
            } catch (Exception e) {
                LOGGER.error("Step failed: {}", String.join(" ", step.words), e);
            }
        }
    }

    private static void run(Minecraft mc, String[] w) {
        Window window = mc.getWindow();
        long h = window.handle();
        String desc = String.join(" ", w);
        switch (w[0]) {
            case "open" -> RtxRemixMod.openRemixMenu(w.length > 1 && w[1].startsWith("g")
                    ? RemixInputGate.Menu.GRAPHICS_SETTINGS : RemixInputGate.Menu.DEVELOPER);
            case "close" -> {
                if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
                    RtxRemixMod.toggleRemixMenu();
                }
            }
            case "altx" -> {
                mc.keyboardHandler.keyPress(h, 1, new KeyEvent(InputConstants.KEY_LALT, 0x40000000 | 0xE2, 0x100));
                mc.keyboardHandler.keyPress(h, 1, new KeyEvent(InputConstants.KEY_X, 'x', 0x100));
                mc.keyboardHandler.keyPress(h, 0, new KeyEvent(InputConstants.KEY_X, 'x', 0x100));
                mc.keyboardHandler.keyPress(h, 0, new KeyEvent(InputConstants.KEY_LALT, 0x40000000 | 0xE2, 0));
            }
            case "move" -> {
                cursorX = coordinate(w[1], window.getScreenWidth());
                cursorY = coordinate(w[2], window.getScreenHeight());
                mc.mouseHandler.onMove(h, cursorX, cursorY, 0.0, 0.0);
            }
            case "click" -> {
                int button = button(w);
                mc.mouseHandler.onButton(h, new MouseButtonInfo(button, 0), 1);
                mc.mouseHandler.onButton(h, new MouseButtonInfo(button, 0), 0);
            }
            case "down" -> mc.mouseHandler.onButton(h, new MouseButtonInfo(button(w), 0), 1);
            case "up" -> mc.mouseHandler.onButton(h, new MouseButtonInfo(button(w), 0), 0);
            case "scroll" -> mc.mouseHandler.onScroll(h, 0.0, Double.parseDouble(w[1]));
            case "char" -> mc.keyboardHandler.charTyped(h, new CharacterEvent(w[1].codePointAt(0)));
            case "key" -> {
                int sc = scancode(w[1]);
                mc.keyboardHandler.keyPress(h, 1, new KeyEvent(sc, 0, 0));
                mc.keyboardHandler.keyPress(h, 0, new KeyEvent(sc, 0, 0));
            }
            default -> {
                LOGGER.warn("Unknown step: {}", desc);
                return;
            }
        }
        LOGGER.info("t={}s step '{}' done: captureOpen={} cursorGrabbed={} screen={} cursor=({},{}) window={}x{} fb={}x{}",
                String.format("%.1f", (System.nanoTime() - startNanos) / 1.0e9), desc,
                RtxRemixMod.getRemixInputGate().isCaptureSessionOpen(), mc.mouseHandler.isMouseGrabbed(),
                mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(),
                cursorX, cursorY, window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight());
    }

    private static double coordinate(String word, int extent) {
        if (word.equals("c")) {
            return extent / 2.0;
        }
        double v = Double.parseDouble(word);
        return v <= 1.0 ? v * extent : v;
    }

    private static int button(String[] w) {
        if (w.length > 1) {
            if (w[1].startsWith("r")) {
                return InputConstants.MOUSE_BUTTON_RIGHT;
            }
            if (w[1].startsWith("m")) {
                return InputConstants.MOUSE_BUTTON_MIDDLE;
            }
        }
        return InputConstants.MOUSE_BUTTON_LEFT;
    }

    private static int scancode(String name) {
        String n = name.toLowerCase();
        if (n.length() == 1 && n.charAt(0) >= 'a' && n.charAt(0) <= 'z') {
            return InputConstants.KEY_A + (n.charAt(0) - 'a');
        }
        if (n.matches("f([1-9]|1[0-2])")) {
            return InputConstants.KEY_F1 + Integer.parseInt(n.substring(1)) - 1;
        }
        return switch (n) {
            case "esc", "escape" -> InputConstants.KEY_ESCAPE;
            case "enter" -> InputConstants.KEY_RETURN;
            case "tab" -> InputConstants.KEY_TAB;
            case "backspace" -> InputConstants.KEY_BACKSPACE;
            case "up" -> InputConstants.KEY_UP;
            case "down" -> InputConstants.KEY_DOWN;
            case "left" -> InputConstants.KEY_LEFT;
            case "right" -> InputConstants.KEY_RIGHT;
            default -> throw new IllegalArgumentException("unknown key " + name);
        };
    }
}
