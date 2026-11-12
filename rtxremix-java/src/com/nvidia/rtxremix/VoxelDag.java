/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Bounded CPU workspace for building a sparse RGB565 voxel DAG before a GPU upload. This service has no Minecraft
 * dependencies and no Vulkan objects. Load the JNI bridge before use. Handles are explicit, transient ownership:
 * pair every successful {@link #create} with {@link #destroy}, including cancellation and failed uploads.
 *
 * <p>Inputs and output slices are direct buffers in native byte order; their positions are ignored. Native methods
 * copy inputs before returning and never retain their addresses. A handle belongs to one caller at a time. Finished
 * handles remain readable until destruction, but reject further input. Admission and overflow fail explicitly;
 * the service never reduces detail to fit. No whole-scene arrays cross this interface.
 */
public final class VoxelDag {
    private VoxelDag() {}

    /** Creates one 256x256 column workspace: horizontal cell size 2^lg blocks, 1..16 vertical 256-block slabs. */
    public static native long create(int lg, int slabs);

    /**
     * Copies count row-major surface cells at firstCell into the workspace. Each direct-buffer uint32 stores
     * signed int16 height above the column's minimum Y in the high half and RGB565 in the low half. Height
     * Short.MIN_VALUE denotes no surface; integer surface boundaries are preserved at every horizontal cell size.
     */
    public static native void surface(long handle, int firstCell, int count, ByteBuffer packed);

    /**
     * Immediately compresses one exact section into the workspace. cx/cz are column-local chunk coordinates 0..15;
     * sy counts 16-block sections from minimum Y. The direct buffer contains 4096 native-order uint16 RGB565 values
     * indexed by (y<<8)|(z<<4)|x, with zero for air. Exact sections replace the procedural surface in that section.
     */
    public static native void exact(long handle, int cx, int sy, int cz, ByteBuffer voxels);

    /** Seals all height slabs and returns the total node count, including reserved empty node zero. */
    public static native int finish(long handle);

    /** Returns a finished slab's local root reference: zero empty, bit31 uniform RGB565 leaf, otherwise node index. */
    public static native int root(long handle, int slab);

    /**
     * Copies count uint32 words starting at firstWord into a direct buffer, adding globalNodeOffset to nonzero
     * non-leaf references. A node is eight words; node zero's eight reserved words stay zero. The caller bounds
     * the slice and uploads it before reusing its staging memory. This never creates a retained native DAG mirror.
     */
    public static native void copy(long handle, int firstWord, int count, int globalNodeOffset, ByteBuffer destination);

    /** Releases the workspace, including all nodes, hash tables and input storage. */
    public static native void destroy(long handle);

    /**
     * Eight byte/count metrics: global capacity, reserved current, reserved peak, active handles, finished output
     * current, finished output peak, I/O scratch current, I/O scratch peak. Reserved includes node capacity,
     * hash capacity and bounded workspace inputs.
     */
    public static native long[] metrics();

    /** Writes a finished workspace as a compact disk DAG; the temporary file is atomically renamed. */
    public static native void save(long handle, String path);

    /** Opens a compact disk DAG without retaining its node payload in RAM; copy reads bounded file slices. */
    public static native long open(String path);

    /** Overlays received FCC version 1 surfaces on a building column using bounded streaming region decoding. */
    public static native void overlay(long handle, String receivedRootPath, int sx, int sz, int minY);

    /**
     * Atomically updates one FCC region's chunk record from 256 direct-buffer packed uint32 cells. Heights in
     * this message are absolute signed world Y, unlike surface's column-relative heights. The buffer is 1024 bytes.
     */
    public static native void recordSurface(String receivedRootPath, int cx, int cz, ByteBuffer packed);
}
