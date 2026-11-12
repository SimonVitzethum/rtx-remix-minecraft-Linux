/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Actual bridge exports, without initializing a Vulkan device or loading a runtime. */
@EnabledIfEnvironmentVariable(named = "RTX_REMIX_BRIDGE_TEST_LIBRARY", matches = ".+")
class GpuWorkNativeTest {
    @BeforeAll static void load() { System.load(System.getenv("RTX_REMIX_BRIDGE_TEST_LIBRARY")); }

    @Test void allGpuWorkExportsFailClosedBeforeRuntimeInitialization() {
        assertEquals(RemixAPI.makeVersion(RemixAPI.VERSION_MAJOR, RemixAPI.VERSION_MINOR, RemixAPI.VERSION_PATCH),
                RemixBridge.bridgeContract());
        assertEquals(ErrorCode.NOT_INITIALIZED, RemixBridge.getGpuContext(new long[24]));
        assertEquals(ErrorCode.NOT_INITIALIZED, RemixBridge.createGpuResource(GpuWork.BUFFER,
                GpuWork.DEVICE, 4096, 32, 0, 0, 0, new long[10]));
        assertEquals(ErrorCode.NOT_INITIALIZED, RemixBridge.destroyGpuResource(1));
        assertEquals(ErrorCode.NOT_INITIALIZED, RemixBridge.submitGpuWork(1, new long[0], new long[0]));
        assertEquals(ErrorCode.NOT_INITIALIZED, RemixBridge.getGpuWorkStatus(1, new long[3]));
        assertEquals(ErrorCode.NOT_INITIALIZED, assertThrows(RemixException.class, GpuWork::context).getErrorCode());
    }
}
