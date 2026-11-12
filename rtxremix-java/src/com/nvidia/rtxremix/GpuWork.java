/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix;

/**
 * Compute/transfer work on the runtime's Vulkan device and ordinary graphics queue.
 * The runtime owns allocations, queue submission, dependencies and actual fence retirement.
 * Client-owned pipelines, descriptor pools and queries must survive every referencing work list.
 */
public final class GpuWork {
    public static final int BUFFER = 1, IMAGE_2D = 2, COMMAND_LIST = 3;
    public static final int DEVICE = 0, UPLOAD = 1, READBACK = 2;
    public static final int IDLE = 0, QUEUED = 1, IN_FLIGHT = 2, COMPLETE = 3, FAILED = 4;
    public static final long SHADER_INT64 = 1, BUFFER_DEVICE_ADDRESS = 2;

    private GpuWork() {}

    /** Borrowed Vulkan1.2 core context. Never destroy the device or acquire/submit its queues. */
    public static final class Context {
        public final long instance, physicalDevice, device, features, budgetSource, maxStorageBufferRange;
        public final int apiVersion, queueFamily;
        public final Memory memory;
        private Context(long instance, long physicalDevice, long device, int apiVersion, int queueFamily,
                        long features, long budgetSource, long maxStorageBufferRange, Memory memory) {
            this.instance = instance;
            this.physicalDevice = physicalDevice;
            this.device = device;
            this.apiVersion = apiVersion;
            this.queueFamily = queueFamily;
            this.features = features;
            this.budgetSource = budgetSource;
            this.maxStorageBufferRange = maxStorageBufferRange;
            this.memory = memory;
        }
    }

    /** Physical process budget plus the shared runtime resource ledger. All capacities are bytes. */
    public static final class Memory {
        public final long deviceBudget, deviceUsage, hostBudget, hostUsage, deviceHeadroom, hostHeadroom;
        public final long deviceReserved, hostReserved, deviceCommitted, hostCommitted, deviceAvailable, hostAvailable;
        public final long devicePeak, hostPeak, rejected, queryNanos;
        private Memory(long[] values) {
            deviceBudget = values[8];
            deviceUsage = values[9];
            hostBudget = values[10];
            hostUsage = values[11];
            deviceHeadroom = values[12];
            hostHeadroom = values[13];
            deviceReserved = values[14];
            hostReserved = values[15];
            deviceCommitted = values[16];
            hostCommitted = values[17];
            deviceAvailable = values[18];
            hostAvailable = values[19];
            devicePeak = values[20];
            hostPeak = values[21];
            rejected = values[22];
            queryNanos = values[23];
        }
    }

    public static Context context() {
        long[] result = new long[24];
        check(RemixBridge.getGpuContext(result));
        Memory memory = new Memory(result);
        return new Context(result[0], result[1], result[2], (int) result[3], (int) result[4], result[5], result[6], result[7], memory);
    }

    public static final class Status {
        public final int state, error;
        public final long serial;
        private Status(int state, int error, long serial) {
            this.state = state; this.error = error; this.serial = serial;
        }
        public boolean retired() { return state == IDLE || state == COMPLETE || state == FAILED; }
        public void checkCompletion() {
            if (!retired()) throw new IllegalStateException("GPU work has not retired");
            check(error);
        }
    }

    /**
     * Registers a buffer, a 2D image, or a resettable secondary compute command list.
     * Returns null only for temporary physical memory admission pressure. All other failures throw.
     * Use core Vulkan buffer usage and format values. Zero every field unused by the chosen kind.
     */
    public static Resource tryCreate(int kind, int memory, long bytes, int usage, int width, int height, int format) {
        long[] result = new long[10];
        int code = RemixBridge.createGpuResource(kind, memory, bytes, usage, width, height, format, result);
        if (code == ErrorCode.RESOURCE_BUSY) return null;
        check(code);
        return new Resource(kind, result);
    }

    public static final class Resource implements AutoCloseable {
        private final int kind;
        private long id;
        public final long buffer, bufferOffset, bytes, deviceAddress, mapped;
        public final long image, imageView, commandBuffer, texture;

        private Resource(int kind, long[] values) {
            this.kind = kind;
            id = values[0];
            buffer = values[1]; bufferOffset = values[2]; bytes = values[3];
            deviceAddress = values[4]; mapped = values[5]; image = values[6];
            imageView = values[7]; commandBuffer = values[8]; texture = values[9];
        }

        public long id() {
            if (id == 0) throw new IllegalStateException("GPU resource is closed");
            return id;
        }

        /** Nonblocking. QUEUED includes the interval before the runtime's CS thread records work. */
        public Status status() {
            requireCommands();
            long[] result = new long[3];
            check(RemixBridge.getGpuWorkStatus(id(), result));
            return new Status((int) result[0], (int) result[1], result[2]);
        }

        /**
         * Submit an ended secondary command buffer. Include all resources reached through descriptors
         * or device addresses. Write implies read. Returns false only while this list is already busy.
         * Do not record, map-write, or destroy client pipeline/descriptor/query objects until retirement.
         */
        public boolean submit(long[] reads, long[] writes) {
            requireCommands();
            if (reads == null || writes == null) throw new IllegalArgumentException("Resource lists must be explicit");
            int code = RemixBridge.submitGpuWork(id(), reads, writes);
            if (code == ErrorCode.RESOURCE_BUSY) return false;
            check(code);
            return true;
        }

        private void requireCommands() {
            if (kind != COMMAND_LIST) throw new IllegalStateException("This resource is not a command list");
        }

        /** Drops client ownership. Native references retain submitted buffers until actual retirement. */
        @Override public void close() {
            if (id != 0) {
                check(RemixBridge.destroyGpuResource(id));
                id = 0;
            }
        }
    }

    private static void check(int code) {
        if (code != ErrorCode.SUCCESS) throw new RemixException("GPU work failed", code);
    }
}
