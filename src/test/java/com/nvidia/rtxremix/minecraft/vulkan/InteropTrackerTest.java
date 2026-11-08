/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteropTrackerTest {
    private static InteropTracker.ImageKey key(long handle, int w, int h) {
        return new InteropTracker.ImageKey(handle, w, h, 37);
    }

    @Test
    void importsOnlyWhenTheReportedImageChanges() {
        InteropTracker tracker = new InteropTracker();
        assertFalse(tracker.needsImport(key(0, 0, 0)), "no image reported yet");
        InteropTracker.ImageKey first = key(42, 1280, 720);
        assertTrue(tracker.needsImport(first));
        tracker.imported(first);
        // The runtime repeats the same (already consumed) descriptor number on later calls.
        assertFalse(tracker.needsImport(key(42, 1280, 720)));
        assertTrue(tracker.needsImport(key(43, 1920, 1080)), "resize reports a new descriptor");
        assertTrue(tracker.needsImport(key(42, 1920, 1080)), "same descriptor but another size");
    }

    @Test
    void releasesWhenTheImageDisappears() {
        InteropTracker tracker = new InteropTracker();
        assertFalse(tracker.needsRelease(key(0, 0, 0)));
        tracker.imported(key(7, 64, 64));
        assertTrue(tracker.needsRelease(key(0, 0, 0)));
        assertTrue(tracker.needsRelease(key(-1L, 64, 64)));
        tracker.released();
        assertFalse(tracker.needsRelease(key(0, 0, 0)));
    }

    @Test
    void waitValuesMayRepeatButNotMoveBackwards() {
        InteropTracker tracker = new InteropTracker();
        assertTrue(tracker.registerWait(3));
        assertFalse(tracker.registerWait(3));
        assertTrue(tracker.registerWait(4));
        assertThrows(IllegalStateException.class, () -> tracker.registerWait(2));
    }

    @Test
    void clientDoneCounterCountsUp() {
        InteropTracker tracker = new InteropTracker();
        assertEquals(0, tracker.clientDoneValue());
        assertEquals(1, tracker.nextClientDone());
        assertEquals(2, tracker.nextClientDone());
        assertEquals(2, tracker.clientDoneValue());
    }

    @Test
    void picksDeviceLocalMemoryTypeAllowedByTheImage() {
        int deviceLocal = 1;
        int[] flags = {0, deviceLocal, deviceLocal | 2};
        assertEquals(1, InteropTracker.pickMemoryType(0b110, flags, deviceLocal));
        assertEquals(2, InteropTracker.pickMemoryType(0b100, flags, deviceLocal));
        assertEquals(-1, InteropTracker.pickMemoryType(0b001, flags, deviceLocal));
    }

    @Test
    void blitOnlyWhenSizesDiffer() {
        assertFalse(InteropTracker.needsBlit(10, 20, 10, 20));
        assertTrue(InteropTracker.needsBlit(10, 20, 11, 20));
    }
}
