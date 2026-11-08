/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import com.nvidia.rtxremix.RemixBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/**
 * Forwards GLFW input to the Linux runtime while the Remix overlay owns input. Windows reads its own
 * raw input, but the Linux runtime has no window of its own to listen on.
 */
public final class LinuxOverlayInput {
    public static final boolean ENABLED = System.getProperty("os.name", "").toLowerCase().contains("linux");

    private static final int MOUSE_MOVE = 0;
    private static final int MOUSE_BUTTON = 1;
    private static final int MOUSE_WHEEL = 2;
    private static final int KEY = 3;
    private static final int CHAR = 4;
    private static final int FOCUS = 5;

    private LinuxOverlayInput() {
    }

    public static void cursor(double x, double y) {
        Window window = MinecraftClient.getInstance().getWindow();
        double sx = window.getWidth() > 0 ? (double) window.getFramebufferWidth() / window.getWidth() : 1.0;
        double sy = window.getHeight() > 0 ? (double) window.getFramebufferHeight() / window.getHeight() : 1.0;
        RemixBridge.linuxInput(MOUSE_MOVE, 0, (float) (x * sx), (float) (y * sy));
    }

    public static void mouseButton(int button, int action) {
        RemixBridge.linuxInput(MOUSE_BUTTON, button, action == GLFW.GLFW_RELEASE ? 0f : 1f, 0f);
    }

    public static void scroll(double horizontal, double vertical) {
        RemixBridge.linuxInput(MOUSE_WHEEL, 0, (float) horizontal, (float) vertical);
    }

    public static void key(int glfwKey, int action) {
        int vk = toVirtualKey(glfwKey);
        if (vk != 0) {
            RemixBridge.linuxInput(KEY, vk, action == GLFW.GLFW_RELEASE ? 0f : 1f, 0f);
        }
    }

    public static void character(int codePoint) {
        RemixBridge.linuxInput(CHAR, codePoint, 0f, 0f);
    }

    public static void focus(boolean focused) {
        RemixBridge.linuxInput(FOCUS, focused ? 1 : 0, 0f, 0f);
    }

    /** Maps a GLFW key to the Windows virtual key numbering the runtime expects, or 0. */
    static int toVirtualKey(int k) {
        if (k >= GLFW.GLFW_KEY_A && k <= GLFW.GLFW_KEY_Z) {
            return k;
        }
        if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) {
            return k;
        }
        if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F12) {
            return 0x70 + (k - GLFW.GLFW_KEY_F1);
        }
        if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) {
            return 0x60 + (k - GLFW.GLFW_KEY_KP_0);
        }
        switch (k) {
            case GLFW.GLFW_KEY_SPACE: return 0x20;
            case GLFW.GLFW_KEY_APOSTROPHE: return 0xDE;
            case GLFW.GLFW_KEY_COMMA: return 0xBC;
            case GLFW.GLFW_KEY_MINUS: return 0xBD;
            case GLFW.GLFW_KEY_PERIOD: return 0xBE;
            case GLFW.GLFW_KEY_SLASH: return 0xBF;
            case GLFW.GLFW_KEY_SEMICOLON: return 0xBA;
            case GLFW.GLFW_KEY_EQUAL: return 0xBB;
            case GLFW.GLFW_KEY_LEFT_BRACKET: return 0xDB;
            case GLFW.GLFW_KEY_BACKSLASH: return 0xDC;
            case GLFW.GLFW_KEY_RIGHT_BRACKET: return 0xDD;
            case GLFW.GLFW_KEY_GRAVE_ACCENT: return 0xC0;
            case GLFW.GLFW_KEY_ESCAPE: return 0x1B;
            case GLFW.GLFW_KEY_ENTER: return 0x0D;
            case GLFW.GLFW_KEY_KP_ENTER: return 0x0D;
            case GLFW.GLFW_KEY_TAB: return 0x09;
            case GLFW.GLFW_KEY_BACKSPACE: return 0x08;
            case GLFW.GLFW_KEY_INSERT: return 0x2D;
            case GLFW.GLFW_KEY_DELETE: return 0x2E;
            case GLFW.GLFW_KEY_RIGHT: return 0x27;
            case GLFW.GLFW_KEY_LEFT: return 0x25;
            case GLFW.GLFW_KEY_DOWN: return 0x28;
            case GLFW.GLFW_KEY_UP: return 0x26;
            case GLFW.GLFW_KEY_PAGE_UP: return 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN: return 0x22;
            case GLFW.GLFW_KEY_HOME: return 0x24;
            case GLFW.GLFW_KEY_END: return 0x23;
            case GLFW.GLFW_KEY_CAPS_LOCK: return 0x14;
            case GLFW.GLFW_KEY_SCROLL_LOCK: return 0x91;
            case GLFW.GLFW_KEY_NUM_LOCK: return 0x90;
            case GLFW.GLFW_KEY_PRINT_SCREEN: return 0x2C;
            case GLFW.GLFW_KEY_PAUSE: return 0x13;
            case GLFW.GLFW_KEY_KP_DECIMAL: return 0x6E;
            case GLFW.GLFW_KEY_KP_DIVIDE: return 0x6F;
            case GLFW.GLFW_KEY_KP_MULTIPLY: return 0x6A;
            case GLFW.GLFW_KEY_KP_SUBTRACT: return 0x6D;
            case GLFW.GLFW_KEY_KP_ADD: return 0x6B;
            case GLFW.GLFW_KEY_LEFT_SHIFT: return 0xA0;
            case GLFW.GLFW_KEY_LEFT_CONTROL: return 0xA2;
            case GLFW.GLFW_KEY_LEFT_ALT: return 0xA4;
            case GLFW.GLFW_KEY_LEFT_SUPER: return 0x5B;
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return 0xA1;
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return 0xA3;
            case GLFW.GLFW_KEY_RIGHT_ALT: return 0xA5;
            case GLFW.GLFW_KEY_RIGHT_SUPER: return 0x5C;
            case GLFW.GLFW_KEY_MENU: return 0x5D;
            default: return 0;
        }
    }
}
