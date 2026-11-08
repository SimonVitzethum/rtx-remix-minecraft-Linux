/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.RemixBridge;
import net.minecraft.client.Minecraft;

/**
 * Forwards SDL input to the Linux runtime while the Remix overlay owns input. Windows reads its own
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
        Window window = Minecraft.getInstance().getWindow();
        double sx = window.getScreenWidth() > 0 ? (double) window.getWidth() / window.getScreenWidth() : 1.0;
        double sy = window.getScreenHeight() > 0 ? (double) window.getHeight() / window.getScreenHeight() : 1.0;
        RemixBridge.linuxInput(MOUSE_MOVE, 0, (float) (x * sx), (float) (y * sy));
    }

    /** Forwards a Minecraft mouse button (1 left, 2 middle, 3 right, 4 and 5 side) as the runtime numbers it. */
    public static void mouseButton(int button, int action) {
        int runtimeButton = switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> button - 1;
        };
        RemixBridge.linuxInput(MOUSE_BUTTON, runtimeButton, action == InputConstants.RELEASE ? 0f : 1f, 0f);
    }

    public static void scroll(double horizontal, double vertical) {
        RemixBridge.linuxInput(MOUSE_WHEEL, 0, (float) horizontal, (float) vertical);
    }

    public static void key(int scancode, int action) {
        int vk = toVirtualKey(scancode);
        if (vk != 0) {
            RemixBridge.linuxInput(KEY, vk, action == InputConstants.RELEASE ? 0f : 1f, 0f);
        }
    }

    public static void character(int codePoint) {
        RemixBridge.linuxInput(CHAR, codePoint, 0f, 0f);
    }

    public static void focus(boolean focused) {
        RemixBridge.linuxInput(FOCUS, focused ? 1 : 0, 0f, 0f);
    }

    /** Maps an SDL scancode (as {@code KeyEvent.key()} carries it) to the Windows virtual key the runtime expects, or 0. */
    static int toVirtualKey(int sc) {
        if (sc >= InputConstants.KEY_A && sc <= InputConstants.KEY_Z) {
            return 'A' + (sc - InputConstants.KEY_A);
        }
        if (sc >= InputConstants.KEY_1 && sc <= InputConstants.KEY_9) {
            return '1' + (sc - InputConstants.KEY_1);
        }
        if (sc == InputConstants.KEY_0) {
            return '0';
        }
        if (sc >= InputConstants.KEY_F1 && sc <= InputConstants.KEY_F12) {
            return 0x70 + (sc - InputConstants.KEY_F1);
        }
        if (sc >= InputConstants.KEY_F13 && sc <= InputConstants.KEY_F24) {
            return 0x7C + (sc - InputConstants.KEY_F13);
        }
        if (sc >= InputConstants.KEY_NUMPAD1 && sc <= InputConstants.KEY_NUMPAD9) {
            return 0x61 + (sc - InputConstants.KEY_NUMPAD1);
        }
        switch (sc) {
            case InputConstants.KEY_NUMPAD0: return 0x60;
            case InputConstants.KEY_SPACE: return 0x20;
            case InputConstants.KEY_APOSTROPHE: return 0xDE;
            case InputConstants.KEY_COMMA: return 0xBC;
            case InputConstants.KEY_MINUS: return 0xBD;
            case InputConstants.KEY_PERIOD: return 0xBE;
            case InputConstants.KEY_SLASH: return 0xBF;
            case InputConstants.KEY_SEMICOLON: return 0xBA;
            case InputConstants.KEY_EQUALS: return 0xBB;
            case InputConstants.KEY_LBRACKET: return 0xDB;
            case InputConstants.KEY_BACKSLASH: return 0xDC;
            case InputConstants.KEY_RBRACKET: return 0xDD;
            case InputConstants.KEY_GRAVE: return 0xC0;
            case 100: return 0xE2; // SDL_SCANCODE_NONUSBACKSLASH
            case InputConstants.KEY_ESCAPE: return 0x1B;
            case InputConstants.KEY_RETURN: return 0x0D;
            case InputConstants.KEY_NUMPADENTER: return 0x0D;
            case InputConstants.KEY_TAB: return 0x09;
            case InputConstants.KEY_BACKSPACE: return 0x08;
            case InputConstants.KEY_INSERT: return 0x2D;
            case InputConstants.KEY_DELETE: return 0x2E;
            case InputConstants.KEY_RIGHT: return 0x27;
            case InputConstants.KEY_LEFT: return 0x25;
            case InputConstants.KEY_DOWN: return 0x28;
            case InputConstants.KEY_UP: return 0x26;
            case InputConstants.KEY_PAGEUP: return 0x21;
            case InputConstants.KEY_PAGEDOWN: return 0x22;
            case InputConstants.KEY_HOME: return 0x24;
            case InputConstants.KEY_END: return 0x23;
            case InputConstants.KEY_CAPSLOCK: return 0x14;
            case InputConstants.KEY_SCROLLLOCK: return 0x91;
            case InputConstants.KEY_NUMLOCK: return 0x90;
            case InputConstants.KEY_PRINTSCREEN: return 0x2C;
            case InputConstants.KEY_PAUSE: return 0x13;
            case 99: return 0x6E; // SDL_SCANCODE_KP_PERIOD
            case 84: return 0x6F; // SDL_SCANCODE_KP_DIVIDE
            case InputConstants.KEY_MULTIPLY: return 0x6A;
            case 86: return 0x6D; // SDL_SCANCODE_KP_MINUS
            case InputConstants.KEY_ADD: return 0x6B;
            case InputConstants.KEY_LSHIFT: return 0xA0;
            case InputConstants.KEY_LCONTROL: return 0xA2;
            case InputConstants.KEY_LALT: return 0xA4;
            case InputConstants.KEY_LGUI: return 0x5B;
            case InputConstants.KEY_RSHIFT: return 0xA1;
            case InputConstants.KEY_RCONTROL: return 0xA3;
            case InputConstants.KEY_RALT: return 0xA5;
            case InputConstants.KEY_RGUI: return 0x5C;
            case 101: return 0x5D; // SDL_SCANCODE_APPLICATION (menu)
            default: return 0;
        }
    }
}
