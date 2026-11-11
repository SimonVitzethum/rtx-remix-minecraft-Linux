/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.settings.PostProcessingSettings;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fullbright: a path-traced night-vision / ambient fill, not a flat lightmap override.
 *
 * <p>Three parts, all scaled by one smoothed level (fade in/out over {@link #FADE_SECONDS}) times the user
 * intensity: (1) two soft fill spheres that travel with the camera (a key above the viewer and a weaker one
 * behind), so caves and rooms are lit with real shadows, contact darkening and PBR highlights instead of a
 * constant colour; (2) a lower auto-exposure floor and a gentle exposure/shadow lift, so dark scenes are
 * readable without blowing out lit ones; (3) a larger night sky ambient, so open ground at night is readable
 * too. Variants (see {@link #variant}) exist only to compare looks: 1 = exposure only, 2 = fill lights only,
 * 3 = all (default).
 */
public final class Fullbright {
    private static final Logger LOGGER = LoggerFactory.getLogger("Fullbright");
    public static final float FADE_SECONDS = 0.8f;
    public static final float DEFAULT_INTENSITY = 1.0f;
    public static final float MAX_INTENSITY = 2.0f;

    // Tunables (test hook "fb" may change them).
    public static int variant = 3;
    public static float keyRadiance = 100.0f;
    public static float keyRadius = 0.9f;
    public static float keyUp = 1.4f;
    public static float backRadiance = 50.0f;
    public static float backRadius = 1.2f;
    public static float backDistance = 4.0f;
    public static float evMinDrop = 2.0f;
    public static float exposureGain = 0.6f;
    public static float shadowLift = 1.0f;
    public static float nightAmbientAdd = 6.0f;

    private static boolean enabled;
    private static float intensity = DEFAULT_INTENSITY;
    private static float level;
    private static float blend;
    private static long lastNanos;

    private RemixLight key;
    private RemixLight back;
    private RemixAPI exposureApi;
    private long exposureLayer;
    private float appliedStrength = -1.0f;
    private float appliedEv = Float.NaN;
    private float appliedExposure = Float.NaN;
    private float appliedShadows = Float.NaN;

    /**
     * Default key B: vanilla has no plain-B binding (only F3+B, hitboxes, which consumes its key press), and
     * the mod's G and F8 are taken. Rebindable in Controls like every other binding.
     */
    public static void registerKey(KeyMapping.Category category) {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.rtxremix.fullbright", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            int presses = 0;
            while (key.consumeClick()) {
                presses++;
            }
            if ((presses & 1) == 1 && client.gui.screen() == null
                    && !client.options.keyDebugModifier.isDown()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.translatable(
                            enabled ? "rtxremix.fullbright.on" : "rtxremix.fullbright.off"));
                }
            }
        });
    }

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }
    public static float getIntensity() { return intensity; }
    public static void setIntensity(float v) { intensity = Mth.clamp(v, 0.0f, MAX_INTENSITY); }
    /** Current faded level (0..intensity); read by the sky ambient. */
    public static float level() { return level; }

    /** Extra night ambient multiplier for the sky renderer. */
    public static float nightAmbientBoost() {
        return variant == 2 ? 0.0f : level * nightAmbientAdd;
    }

    public void update(RemixRenderer renderer, Camera camera) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        blend = advanceBlend(blend, enabled, dt);
        level = blend * blend * (3.0f - 2.0f * blend) * intensity;
        RemixAPI api = renderer.getRemixApi();
        if (api == null) {
            return;
        }
        applyExposure(renderer, api);
        if (level <= 0.0f || variant == 1) {
            destroyLights();
            return;
        }
        try {
            drawLights(api, camera);
        } catch (RemixException e) {
            LOGGER.error("Fullbright light failed: {}", e.getMessage());
        }
    }

    private void drawLights(RemixAPI api, Camera camera) throws RemixException {
        Vec3 p = camera.position();
        Vector3fc fwd = camera.forwardVector();
        float e = level;
        key = put(key, p.x, p.y + keyUp, p.z, keyRadius, keyRadiance * e, api);
        back = put(back, p.x - fwd.x() * backDistance, p.y + 0.8 - fwd.y() * backDistance,
                p.z - fwd.z() * backDistance, backRadius, backRadiance * e, api);
    }

    private static RemixLight put(RemixLight light, double x, double y, double z, float radius, float radiance,
                                  RemixAPI api) throws RemixException {
        LightInfo info = LightInfo.builder()
                .radiance(radiance, radiance * 0.96f, radiance * 0.9f)
                .sphereExt(LightInfoSphereEXT.builder()
                        .position((float) x, (float) y, (float) z)
                        .radius(radius)
                        // Camera-locked emitters must not smear into the volumetric grid (see AGENTS.md).
                        .volumetricRadianceScale(0.0f)
                        .build())
                .build();
        if (light != null && light.isAlive()) {
            RemixLightStore.get().update(light, info);
        } else {
            light = new RemixLight(info, null);
            RemixLightStore.get().create(light);
        }
        light.draw(api);
        return light;
    }

    private void destroyLights() {
        if (key != null && !key.isDeleted()) key.destroy();
        if (back != null && !back.isDeleted()) back.destroy();
        key = null;
        back = null;
    }

    /** Normalized enable transition: every intensity takes the same 0.8 seconds. */
    static float advanceBlend(float current, boolean on, float seconds) {
        float step = Math.max(0.0f, seconds) / FADE_SECONDS;
        return on ? Math.min(1.0f, current + step) : Math.max(0.0f, current - step);
    }

    private void applyExposure(RemixRenderer renderer, RemixAPI api) {
        float strength = variant == 2 ? 0.0f : level / MAX_INTENSITY;
        if (strength == 0.0f && exposureLayer == 0) {
            return;
        }
        PostProcessingSettings post = renderer.getPostProcessingSettings();
        if (post == null) {
            return;
        }
        float ev = post.getLowLightAdaptation() - evMinDrop * MAX_INTENSITY;
        float exposure = post.getExposure() * (1.0f + exposureGain * MAX_INTENSITY);
        float shadows = post.getShadowLevel() + shadowLift * MAX_INTENSITY;
        try {
            if (exposureLayer == 0 || ev != appliedEv || exposure != appliedExposure || shadows != appliedShadows) {
                // Dynamic effects belong to a blendable layer, below explicit user overrides.
                // Acquire parses synchronously; the small generated file is not a shipped payload.
                Path conf = Files.createTempFile("rtxremix-fullbright-", ".conf");
                try {
                    Files.writeString(conf, "rtx.autoExposure.evMinValue = " + ev
                            + "\nrtx.localtonemap.exposure = " + exposure
                            + "\nrtx.localtonemap.shadows = " + shadows + "\n");
                    long replacement = api.acquireConfigLayer(conf.toString(), 12000.0f, 0.5f);
                    if (exposureLayer != 0) api.releaseConfigLayer(exposureLayer);
                    exposureLayer = replacement;
                    exposureApi = api;
                } finally {
                    Files.deleteIfExists(conf);
                }
                appliedEv = ev;
                appliedExposure = exposure;
                appliedShadows = shadows;
                appliedStrength = -1.0f;
            }
            if (strength != appliedStrength) {
                api.setConfigLayerBlending(exposureLayer, strength, 0.5f);
                appliedStrength = strength;
            }
        } catch (RemixException | IOException ex) {
            LOGGER.error("Fullbright exposure failed: {}", ex.getMessage());
        }
    }

    /** Releases world-owned native resources before the stores and runtime are destroyed. */
    public void shutdown() {
        destroyLights();
        if (exposureLayer != 0) {
            exposureApi.releaseConfigLayer(exposureLayer);
            exposureLayer = 0;
            exposureApi = null;
        }
        appliedStrength = -1.0f;
        blend = 0.0f;
        level = 0.0f;
        lastNanos = 0;
    }
}
