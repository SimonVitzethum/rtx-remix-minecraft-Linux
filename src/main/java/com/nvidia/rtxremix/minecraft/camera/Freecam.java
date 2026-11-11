/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detached free camera (default key F4).
 *
 * <p>The camera is a pose override, not an entity: {@code FreecamCameraMixin} replaces the pose of
 * Minecraft's {@link Camera} right after vanilla aligned it with the player, so everything that is
 * derived from the camera afterwards (cull frustum, section visibility and build order, the pose the
 * mod hands to Remix, light/entity culling distances, far-field centre, underwater test) follows the
 * freecam without further hooks. {@code FreecamOptionsMixin} makes the game report third-person
 * view while the freecam is active, which is what makes vanilla (and the mod's own first-person
 * checks) draw the player body and skip the first-person hands. Player input, mouse look,
 * attack/use/pick and the scroll wheel are redirected to the freecam by the other mixins.
 *
 * <p>Chunk capture and the far-field centre follow the camera (they already take their position from
 * the camera): what matters for the picture is what lies around the viewpoint, and the client only
 * has chunks within the server view distance of the player, so the freecam sees terrain as far as
 * the client has it and nothing is requested from the server for it.
 *
 * <p>The freecam ends by pressing the key again, on any damage to the player (health drops or the
 * hurt timer starts), on death, respawn and leaving the world.
 */
public final class Freecam {
    private static final Logger LOGGER = LoggerFactory.getLogger("Freecam");
    private static final double DEFAULT_SPEED = 10.0;
    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 400.0;
    private static final double SPRINT_FACTOR = 4.0;
    private static final double SCROLL_FACTOR = 1.2;

    private static KeyMapping toggleKey;
    private static boolean active;
    private static double x;
    private static double y;
    private static double z;
    private static float yaw;
    private static float pitch;
    private static double speed = DEFAULT_SPEED;
    private static long lastFrameNanos;
    private static LocalPlayer owner;
    private static float lastHealth;
    private static int lastHurtTime;

    private Freecam() {
    }

    public static void register(KeyMapping.Category category) {
        // F4 is also vanilla's F3+F4 game mode switcher and spectator-effects key. The switcher
        // consumes its key press (no click is produced for F4 then) and tick() ignores the click
        // while F3 is held, so both keep working.
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.rtxremix.freecam", InputConstants.Type.KEYBOARD, InputConstants.KEY_F4, category));
        ClientTickEvents.START_CLIENT_TICK.register(Freecam::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rtxremix", "freecam_hint"),
                (graphics, delta) -> {
                    if (!active) {
                        return;
                    }
                    Minecraft mc = Minecraft.getInstance();
                    Component text = Component.translatable("rtxremix.freecam.hud",
                            toggleKey.getTranslatedKeyMessage(), String.format(Locale.ROOT, "%.1f", speed));
                    graphics.centeredText(mc.font, text, graphics.guiWidth() / 2, 6, 0xFFFFFFFF);
                });
    }

    public static boolean isActive() {
        return active;
    }

    public static double speed() {
        return speed;
    }

    public static Vec3d position() {
        return new Vec3d(x, y, z);
    }

    public record Vec3d(double x, double y, double z) {
    }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        boolean worldReady = player != null && mc.level != null;
        while (toggleKey.consumeClick()) {
            if (!worldReady || mc.options.keyDebugModifier.isDown() || mc.gui.screen() != null) {
                continue;
            }
            if (active) {
                stop("key");
            } else {
                start(mc);
            }
        }
        if (!active) {
            return;
        }
        if (!worldReady || player != owner || player.isDeadOrDying()) {
            stop("world/player changed");
            return;
        }
        float health = player.getHealth();
        int hurt = player.hurtTime;
        if (health < lastHealth - 1.0e-3f || (hurt > lastHurtTime)) {
            stop("damage");
            return;
        }
        lastHealth = health;
        lastHurtTime = hurt;
    }

    private static void start(Minecraft mc) {
        Camera camera = mc.gameRenderer.mainCamera();
        LocalPlayer player = mc.player;
        x = camera.position().x;
        y = camera.position().y;
        z = camera.position().z;
        yaw = camera.yRot();
        pitch = camera.xRot();
        owner = player;
        lastHealth = player.getHealth();
        lastHurtTime = player.hurtTime;
        lastFrameNanos = System.nanoTime();
        // Cancelling future interactions must also finish the one already in progress.
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
            if (player.isUsingItem()) {
                mc.gameMode.releaseUsingItem(player);
            }
        }
        active = true;
        LOGGER.info("Freecam on at {} {} {}", x, y, z);
    }

    public static void stop(String reason) {
        if (!active) {
            return;
        }
        active = false;
        owner = null;
        LOGGER.info("Freecam off ({})", reason);
    }

    /** Test hook: places the freecam (relative moves are made with the keys in real use). */
    public static void setPose(double nx, double ny, double nz, float nyaw, float npitch) {
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = Mth.clamp(npitch, -90.0f, 90.0f);
    }

    public static void toggleForTest(Minecraft mc) {
        if (active) {
            stop("test");
        } else if (mc.player != null && mc.level != null) {
            start(mc);
        }
    }

    /** Mouse look, called instead of {@code LocalPlayer.turn} while active. */
    public static void turn(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90.0f, 90.0f);
    }

    /** Scroll wheel: speed. */
    public static void scroll(double amount) {
        speed = Mth.clamp(speed * Math.pow(SCROLL_FACTOR, amount), MIN_SPEED, MAX_SPEED);
    }

    /** Whether the world is interactive right now (no screen, not paused, mouse captured). */
    private static boolean inputLive(Minecraft mc) {
        return mc.gui.screen() == null && !mc.isPaused() && mc.mouseHandler.isMouseGrabbed();
    }

    /** Called by the camera mixin once per camera update (render frame): moves and applies the pose. */
    public static void applyTo(CameraPoseSetter setter) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (inputLive(mc)) {
            move(mc, dt);
        }
        setter.set(x, y, z, yaw, pitch);
    }

    @FunctionalInterface
    public interface CameraPoseSetter {
        void set(double x, double y, double z, float yaw, float pitch);
    }

    private static void move(Minecraft mc, double dt) {
        double forward = axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
        double strafe = axis(mc.options.keyRight.isDown(), mc.options.keyLeft.isDown());
        double vertical = axis(mc.options.keyJump.isDown(), mc.options.keyShift.isDown());
        if (forward == 0 && strafe == 0 && vertical == 0) {
            return;
        }
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cp = Math.cos(pitchRad);
        double fx = -Math.sin(yawRad) * cp;
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * cp;
        double rx = -Math.cos(yawRad);
        double rz = -Math.sin(yawRad);
        double dx = fx * forward + rx * strafe;
        double dy = fy * forward + vertical;
        double dz = fz * forward + rz * strafe;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6) {
            return;
        }
        double step = speed * (mc.options.keySprint.isDown() ? SPRINT_FACTOR : 1.0) * dt / length;
        x += dx * step;
        y += dy * step;
        z += dz * step;
    }

    private static double axis(boolean positive, boolean negative) {
        return (positive ? 1.0 : 0.0) - (negative ? 1.0 : 0.0);
    }

    /** What {@code Options.getCameraType} reports while the freecam is on. */
    public static CameraType overrideCameraType() {
        return CameraType.THIRD_PERSON_BACK;
    }
}
