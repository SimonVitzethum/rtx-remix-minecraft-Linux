/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
import com.nvidia.rtxremix.VoxelDag;
import java.nio.file.Path;

/** CPU-only JNI loader probe. It never loads/initializes a Remix runtime or Vulkan device. */
class NativeBridgeProbe {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Pass the absolute JNI bridge path");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        long[] metrics = VoxelDag.metrics();
        if (metrics == null || metrics.length < 16 || metrics[3] != 0) {
            throw new AssertionError("Unexpected idle VoxelDag metrics ABI");
        }
        System.out.println("CPU-only JNI load passed: " + System.getProperty("os.name") + " / "
                + System.getProperty("os.arch") + "; metrics=" + metrics.length);
    }
}
