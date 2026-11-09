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
 * conf KEY VALUE          Remix config variable (SetConfigVariable), e.g. conf rtx.qualityDLSS 2
 * cmd TEXT...             chat command without the slash (sendCommand), e.g. cmd tp @s 0 100 0 90 0
 * look YAW PITCH          set the local player's view direction (degrees)
 * freecam                  toggle the freecam (like the key; the real key is tested with "key f4")
 * fcaim DIST UP ANGLE  freecam DIST blocks from the player (ANGLE degrees around, UP higher) looking at it
 * fcpose X Y Z YAW PITCH  place the freecam
 * fb on|off [VARIANT [KEYRAD BACKRAD EVDROP]]   fullbright (variant 1..3) and tunables
 * diag                     log client/server loaded chunk counts and view distances
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
    private static final String FILE_NAME = "rtxremix-test-script.txt";

    private record Step(double time, String[] words) {
    }

    private static List<Step> steps;
    private static boolean loaded;
    private static boolean menuMode;
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
            // Launchers such as Prism do not pass the environment through and rewrite their JVM
            // arguments, so a file in the game directory is a third way to arm the hook.
            Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(FILE_NAME);
            path = Files.exists(file) ? file.toString() : null;
        }
        if (path == null || path.isBlank()) {
            return;
        }
        steps = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of(path.trim()))) {
                line = line.strip();
                if (line.equals("# menu")) {
                    menuMode = true;
                }
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
        // Steps named "@menu" in the script file header (first line "# menu") may run before a world exists.
        if (!menuMode && (mc.level == null || mc.player == null)) {
            return;
        }
        if (mc.player != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
            mc.player.respawn(); // a world saved while dead (test damage) must not block the script
            return;
        }
        if (!menuMode && startNanos < 0 && mc.gui.screen() != null) {
            return; // the clock starts once the world is playable (loading screen gone)
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

    private static void run(Minecraft mc, String[] w) throws Exception {
        Window window = mc.getWindow();
        long h = window.handle();
        String desc = String.join(" ", w);
        switch (w[0]) {
            case "open" -> RtxRemixMod.openRemixMenu(w.length > 1 && w[1].startsWith("g")
                    ? RemixInputGate.Menu.GRAPHICS_SETTINGS : RemixInputGate.Menu.DEVELOPER);
            case "conf" -> {
                RtxRemixMod.renderer.getRemixApi().setConfigVariable(w[1], w[2]);
                LOGGER.info("conf {} = {}", w[1], w[2]);
            }
            case "get" -> LOGGER.info("get {} = {}", w[1],
                    RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(w[1]));
            case "size" -> org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(h, Integer.parseInt(w[1]), Integer.parseInt(w[2]));
            case "dumpblocks" -> BlockGridTest.dump(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(w[1]));
            case "grid" -> BlockGridTest.grid(mc, net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(w[1]),
                    Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]));
            case "freecam" -> com.nvidia.rtxremix.minecraft.camera.Freecam.toggleForTest(mc);
            case "fcpose" -> com.nvidia.rtxremix.minecraft.camera.Freecam.setPose(Double.parseDouble(w[1]),
                    Double.parseDouble(w[2]), Double.parseDouble(w[3]), Float.parseFloat(w[4]), Float.parseFloat(w[5]));
            case "fcaim" -> {
                // freecam DIST blocks away from the player at ANGLE degrees around it, UP blocks higher, looking at the player
                double dist = Double.parseDouble(w[1]);
                double up = Double.parseDouble(w[2]);
                double ang = Math.toRadians(Double.parseDouble(w[3]));
                net.minecraft.world.phys.Vec3 pe = mc.player.getEyePosition();
                double cx = pe.x + Math.sin(ang) * dist;
                double cz = pe.z + Math.cos(ang) * dist;
                double cy = pe.y + up;
                double dx = pe.x - cx;
                double dz = pe.z - cz;
                double dy = pe.y - 0.5 - cy;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                com.nvidia.rtxremix.minecraft.camera.Freecam.setPose(cx, cy, cz, yaw, pitch);
            }
            case "fb" -> {
                com.nvidia.rtxremix.minecraft.light.Fullbright.setEnabled(w[1].equals("on"));
                if (w.length > 2) com.nvidia.rtxremix.minecraft.light.Fullbright.variant = Integer.parseInt(w[2]);
                if (w.length > 3) com.nvidia.rtxremix.minecraft.light.Fullbright.keyRadiance = Float.parseFloat(w[3]);
                if (w.length > 4) com.nvidia.rtxremix.minecraft.light.Fullbright.backRadiance = Float.parseFloat(w[4]);
                if (w.length > 5) com.nvidia.rtxremix.minecraft.light.Fullbright.evMinDrop = Float.parseFloat(w[5]);
            }
            case "shot" -> {
                LOGGER.info("shot marker {}", w.length > 1 ? w[1] : "");
                // The external screenshot tool acknowledges with a file; the render thread waits (max 30 s) so the picture is of this moment.
                Path ack = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("shot-ack-" + (w.length > 1 ? w[1] : ""));
                if (w.length > 1 && Boolean.getBoolean("rtxremix.test.shotAck") || Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("shot-ack-enable"))) {
                    for (int i = 0; i < 300 && !Files.exists(ack); i++) {
                        Thread.sleep(100);
                    }
                }
            }
            case "cmd" -> mc.player.connection.sendCommand(String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length)));
            case "scmd" -> {
                // server-side command with full permission (test worlds without cheats): scmd execute as @p run tp @s 0 120 0
                net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
                String text = String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length));
                if (server != null) {
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), text));
                }
            }
            case "look" -> {
                float yaw = Float.parseFloat(w[1]);
                float pitch = Float.parseFloat(w[2]);
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.yRotO = yaw;
                mc.player.xRotO = pitch;
            }
            case "diag" -> {
                var srv = mc.getSingleplayerServer();
                LOGGER.info("diag: client loaded chunks={} effectiveRD={} optionRD={} serverViewDistance={} serverLoaded={}",
                        mc.level.getChunkSource().getLoadedChunksCount(), mc.options.getEffectiveRenderDistance(),
                        mc.options.renderDistance().get(),
                        srv == null ? -1 : srv.getPlayerList().getViewDistance(),
                        srv == null ? -1 : srv.overworld().getChunkSource().getLoadedChunksCount());
            }
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
            case "kdown" -> mc.keyboardHandler.keyPress(h, 1, new KeyEvent(scancode(w[1]), 0, 0));
            case "kup" -> mc.keyboardHandler.keyPress(h, 0, new KeyEvent(scancode(w[1]), 0, 0));
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
