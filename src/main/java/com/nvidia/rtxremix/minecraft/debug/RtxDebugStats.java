/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.debug;

import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The "RTX" section of the F3 overlay: rendered (real) frame rate, presented frame rate including DLSS frame
 * generation, internal render resolution, DLSS mode and the frame time of the rendered frame. Counters come from
 * the Remix runtime ({@code remixapi_linux_GetFrameStats}); {@link #onPresent} runs once per Minecraft frame on
 * both graphics backends.
 */
public final class RtxDebugStats implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("rtxremix", "rtx_stats");
    private static final String[] QUALITY = {"Ultra Performance", "Performance", "Balanced", "Quality", "Auto", "DLAA (full res)"};
    private static final String[] UPSCALER = {"off", "DLSS", "NIS", "TAAU", "XeSS", "DLSS RR"};

    private static final long WINDOW_NANOS = 500_000_000L;
    private static long lastPresent;
    private static long lastFrameNanos;
    private static long windowStart;
    private static int windowFrames;
    private static long windowStartRendered;
    private static long windowStartPresented;
    private static double renderedFps;
    private static double presentedFps;
    private static double avgFrameMs;
    private static boolean settingsInitialised;
    private static long configReadAt;
    private static String qualityText = "?";
    private static boolean dlfgEnabled;
    private static int dlfgFrames = 1;
    private static final long[] stats = new long[8];
    private static boolean haveRuntimeStats;

    private RtxDebugStats() {
    }

    public static void register() {
        DebugScreenEntries.register(ID, new RtxDebugStats());
    }

    /** Called after every Present on the render thread. */
    public static void onPresent() {
        long now = System.nanoTime();
        if (lastPresent != 0) {
            lastFrameNanos = now - lastPresent;
        }
        lastPresent = now;
        windowFrames++;
        if (windowStart == 0) {
            windowStart = now;
            return;
        }
        long elapsed = now - windowStart;
        if (elapsed >= WINDOW_NANOS) {
            haveRuntimeStats = RemixBridge.linuxFrameStats(stats);
            double seconds = elapsed / 1.0e9;
            avgFrameMs = elapsed / 1.0e6 / windowFrames;
            if (haveRuntimeStats) {
                renderedFps = (stats[0] - windowStartRendered) / seconds;
                presentedFps = (stats[1] - windowStartPresented) / seconds;
                windowStartRendered = stats[0];
                windowStartPresented = stats[1];
            } else {
                renderedFps = windowFrames / seconds;
                presentedFps = renderedFps;
            }
            windowStart = now;
            windowFrames = 0;
            if (now - configReadAt > 1_000_000_000L) {
                configReadAt = now;
                readConfig();
            }
        }
    }

    private static void readConfig() {
        try {
            var api = RtxRemixMod.renderer.getRemixApi();
            int q = Integer.parseInt(api.getConfigVariableResolved("rtx.qualityDLSS").strip());
            qualityText = q >= 0 && q < QUALITY.length ? QUALITY[q] : Integer.toString(q);
            dlfgEnabled = Boolean.parseBoolean(api.getConfigVariableResolved("rtx.dlfg.enable").strip());
            dlfgFrames = Integer.parseInt(api.getConfigVariableResolved("rtx.dlfg.maxInterpolatedFrames").strip()) + 1;
        } catch (Exception e) {
            qualityText = "?";
        }
    }

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk,
                        @Nullable LevelChunk serverChunk) {
        Locale l = Locale.ROOT;
        double lastMs = lastFrameNanos / 1.0e6;
        String mult = presentedFps > renderedFps * 1.05 && renderedFps > 0
                ? String.format(l, " (frame generation x%.1f)", presentedFps / renderedFps)
                : (dlfgEnabled ? " (frame generation x" + dlfgFrames + " pending)" : " (no frame generation)");
        String line1 = String.format(l, "[RTX] rendered %.0f fps (%.1f ms/frame, last %.1f ms) | presented %.0f fps%s",
                renderedFps, avgFrameMs, lastMs, presentedFps, mult);
        String line2;
        if (haveRuntimeStats && stats[3] > 0) {
            int up = (int) stats[7];
            String mode = up >= 0 && up < UPSCALER.length ? UPSCALER[up] : "?";
            boolean dlss = up == 1 || up == 5;
            line2 = String.format(l, "[RTX] internal %dx%d -> %dx%d | upscaler %s%s | ray reconstruction %s",
                    stats[3], stats[4], stats[5], stats[6], mode, dlss ? " " + qualityText : "", up == 5 ? "on" : "off");
        } else {
            line2 = "[RTX] internal resolution: n/a (runtime without frame statistics) | DLSS " + qualityText;
        }
        displayer.addToGroup(ID, java.util.List.of(line1, line2));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    /** Shows the entry in the overlay by default, once, unless the player already chose a status for it. */
    public static void enableByDefault(Minecraft mc) {
        if (settingsInitialised || mc.debugEntries == null) {
            return;
        }
        settingsInitialised = true;
        java.nio.file.Path marker = mc.gameDirectory.toPath().resolve("rtxremix-f3-initialised");
        if (java.nio.file.Files.exists(marker)) {
            return;
        }
        if (mc.debugEntries.getStatus(ID) == DebugScreenEntryStatus.NEVER) {
            mc.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
        }
        try {
            java.nio.file.Files.writeString(marker, "The RTX section of the F3 overlay was switched on once; change it in the F3+F6 debug options.\n");
        } catch (java.io.IOException ignored) {
        }
    }
}
