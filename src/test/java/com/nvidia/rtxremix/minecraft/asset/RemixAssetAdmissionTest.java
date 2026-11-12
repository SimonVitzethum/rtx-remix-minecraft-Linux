/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.ErrorCode;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemixAssetAdmissionTest {
    @Test void temporaryDenialPreservesInputForLaterAdmission() {
        Asset asset = new Asset(ErrorCode.RESOURCE_BUSY);
        assertThrows(RemixException.class, () -> asset.create(null, 1, true));
        assertEquals(0, asset.disposals);
        asset.error = 0;
        asset.create(null, 2, true);
        assertEquals(1, asset.disposals);
        assertEquals(16, asset.getHandle());
        asset.destroy();
    }

    @Test void internalFailureDisposesInputAndIsNotTurnedIntoAdmissionDenial() {
        Asset asset = new Asset(ErrorCode.GENERAL_FAILURE);
        RemixException error = assertThrows(RemixException.class, () -> asset.create(null, 1, true));
        assertEquals(ErrorCode.GENERAL_FAILURE, error.getErrorCode());
        assertEquals(1, asset.disposals);
        asset.destroy();
    }

    private static final class Asset extends RemixAssetBase {
        int error, disposals;
        Asset(int error) { this.error = error; }
        @Override protected long doCreate(RemixAPI api, long hash) {
            if (error != 0) throw new RemixException("test", error);
            return 16;
        }
        @Override protected void doDestroy(RemixAPI api, long handle) {}
        @Override protected void doDisposeData() { disposals++; }
        @Override protected String typeName() { return "AdmissionTestAsset"; }
    }
}
