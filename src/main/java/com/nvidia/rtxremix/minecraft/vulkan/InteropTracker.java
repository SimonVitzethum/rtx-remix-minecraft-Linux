/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/**
 * The bookkeeping of the Remix interop that does not touch Vulkan: when an exported image has to be
 * imported again, which timeline value a copy has to wait for, and the client-done counter.
 *
 * <p>On Linux the runtime reports the file descriptor of an exported image once and repeats the same
 * number afterwards (the import consumes the descriptor), so "the handle changed or the size changed"
 * is the only signal that a new image exists.
 */
final class InteropTracker {
    /** Identity of an imported image: the reported handle plus the geometry it was created with. */
    record ImageKey(long handle, int width, int height, int format) {
        boolean usable() {
            return handle != 0 && handle != -1L && width > 0 && height > 0;
        }
    }

    private ImageKey current;
    private long clientDoneValue;
    private long lastWaitValue = -1;

    /**
     * Returns true when {@code reported} describes an image different from the imported one, so the caller
     * must destroy the old import and import the new one. An unusable key (no handle yet) never asks for an import.
     */
    boolean needsImport(ImageKey reported) {
        if (!reported.usable()) {
            return false;
        }
        return !reported.equals(current);
    }

    /** Whether an image has to be torn down because Remix stopped reporting it. */
    boolean needsRelease(ImageKey reported) {
        return current != null && !reported.usable();
    }

    void imported(ImageKey key) {
        current = key;
        lastWaitValue = -1;
    }

    void released() {
        current = null;
        lastWaitValue = -1;
    }

    ImageKey current() {
        return current;
    }

    /**
     * Registers the dxvk-done value a GPU wait is about to use. Waiting twice on the same value is legal for a timeline
     * semaphore, the result only says whether this value was already waited on in this import.
     *
     * @throws IllegalStateException when the value moved backwards, which means the semaphore was replaced
     */
    boolean registerWait(long value) {
        if (value < lastWaitValue) {
            throw new IllegalStateException("Vulkan-done semaphore value moved backwards: " + value + " after " + lastWaitValue);
        }
        boolean fresh = value != lastWaitValue;
        lastWaitValue = value;
        return fresh;
    }

    /** The next value the client signals; Remix's present waits for the value passed in PresentInfo. */
    long nextClientDone() {
        return ++clientDoneValue;
    }

    long clientDoneValue() {
        return clientDoneValue;
    }

    /** True when a copy between two images of different size needs a scaling blit instead of a plain copy. */
    static boolean needsBlit(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        return srcWidth != dstWidth || srcHeight != dstHeight;
    }

    /**
     * Picks the lowest memory type that the image allows and that has all wanted property flags.
     *
     * @param typeBits memoryTypeBits of the image requirements
     * @param typeFlags propertyFlags per memory type index
     * @return the index, or -1
     */
    static int pickMemoryType(int typeBits, int[] typeFlags, int wanted) {
        for (int i = 0; i < typeFlags.length; i++) {
            if ((typeBits & (1 << i)) != 0 && (typeFlags[i] & wanted) == wanted) {
                return i;
            }
        }
        return -1;
    }
}
