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
    public static float keyRadiance = 90.0f;
    public static float keyRadius = 0.9f;
    public static float keyUp = 1.4f;
    public static float backRadiance = 40.0f;
    public static float backRadius = 1.2f;
    public static float backDistance = 4.0f;
    public static float evMinDrop = 2.0f;
    public static float exposureGain = 0.6f;
    public static float shadowLift = 1.0f;
    public static float nightAmbientAdd = 6.0f;

    private static boolean enabled;
    private static float intensity = DEFAULT_INTENSITY;
    private static float level;
    private static long lastNanos;

    private RemixLight key;
    private RemixLight back;
    private float appliedLevel = -1.0f;

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
            if ((presses & 1) == 1 && !client.options.keyDebugModifier.isDown()) {
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
        float target = enabled ? intensity : 0.0f;
        float step = dt * Math.max(intensity, 1.0f) / FADE_SECONDS;
        level = level < target ? Math.min(target, level + step) : Math.max(target, level - step);
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
        // Smoothstep makes the fade perceptually even.
        float s = Math.min(level, 1.0f);
        float e = s * s * (3.0f - 2.0f * s) * Math.max(level, 1.0f);
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

    private void applyExposure(RemixRenderer renderer, RemixAPI api) {
        if (Math.abs(level - appliedLevel) < 0.004f) {
            return;
        }
        boolean first = appliedLevel < 0.0f;
        appliedLevel = level;
        if (first && level == 0.0f) {
            return;
        }
        PostProcessingSettings post = renderer.getPostProcessingSettings();
        if (post == null) {
            return;
        }
        float l = variant == 2 ? 0.0f : level;
        {
            LOGGER.info("level {} variant {}", level, variant);
        }
        try {
            api.setConfigVariable("rtx.autoExposure.evMinValue", Float.toString(post.getLowLightAdaptation() - evMinDrop * l));
            api.setConfigVariable("rtx.localtonemap.exposure", Float.toString(post.getExposure() * (1.0f + exposureGain * l)));
            api.setConfigVariable("rtx.localtonemap.shadows", Float.toString(post.getShadowLevel() + shadowLift * l));
        } catch (RemixException ex) {
            LOGGER.error("Fullbright exposure failed: {}", ex.getMessage());
        }
    }

    public void shutdown() {
        destroyLights();
    }
}
