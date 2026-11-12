/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shared admission ledger for transient, persistent and retiring GPU resources. A measurement includes the entire
 * process; the ledger reconciles it with owned allocations so delayed driver accounting cannot spend the same
 * headroom twice. Reservations cover replacements before allocation. Committed leases live until actual destruction
 * after their last fence, including old allocations during growth. There is no subsystem byte ceiling.
 */
final class MemoryBudget {
    record Snapshot(long deviceBudget, long deviceUsage, long hostBudget, long hostUsage,
                    long deviceHeadroom, long hostHeadroom, String source) {
        Snapshot {
            if (deviceBudget < 0 || deviceUsage < 0 || hostBudget < 0 || hostUsage < 0
                    || deviceHeadroom < 0 || hostHeadroom < 0) {
                throw new IllegalArgumentException("Negative memory measurement");
            }
            Objects.requireNonNull(source);
        }
    }

    record State(long deviceAvailable, long hostAvailable, long deviceReserved, long hostReserved,
                 long deviceCommitted, long hostCommitted, long devicePeak, long hostPeak,
                 long rejections, String source) {}

    final class Reservation implements AutoCloseable {
        private final long deviceBytes, hostBytes;
        private boolean committed, closed;

        private Reservation(long deviceBytes, long hostBytes) {
            this.deviceBytes = deviceBytes;
            this.hostBytes = hostBytes;
        }

        /** Commits an allocation already covered by this reservation; its byte ownership is unchanged. */
        void commit() {
            synchronized (MemoryBudget.this) {
                if (closed || committed) throw new IllegalStateException("Memory reservation is not pending");
                deviceReserved -= deviceBytes;
                hostReserved -= hostBytes;
                deviceCommitted = Math.addExact(deviceCommitted, deviceBytes);
                hostCommitted = Math.addExact(hostCommitted, hostBytes);
                committed = true;
            }
        }

        /** Releases a failed reservation, or an allocation only after it has actually been destroyed. */
        @Override public void close() {
            synchronized (MemoryBudget.this) {
                if (closed) throw new IllegalStateException("Memory reservation already released");
                if (committed) {
                    deviceCommitted -= deviceBytes;
                    hostCommitted -= hostBytes;
                } else {
                    deviceReserved -= deviceBytes;
                    hostReserved -= hostBytes;
                }
                closed = true;
            }
        }
    }

    private final Supplier<Snapshot> measurement;
    private final LongSupplier clock;
    private final long pressureHoldNanos;
    private long deviceReserved, hostReserved, deviceCommitted, hostCommitted;
    private long devicePeak, hostPeak, rejections;
    private long otherDevice, otherHost, lastDeviceHigh, lastHostHigh;
    private boolean measured;

    MemoryBudget(Supplier<Snapshot> measurement) {
        this(measurement, System::nanoTime, 30_000_000_000L);
    }

    MemoryBudget(Supplier<Snapshot> measurement, LongSupplier clock, long pressureHoldNanos) {
        this.measurement = Objects.requireNonNull(measurement);
        this.clock = Objects.requireNonNull(clock);
        if (pressureHoldNanos < 0) throw new IllegalArgumentException("Negative memory pressure hold");
        this.pressureHoldNanos = pressureHoldNanos;
    }

    /**
     * Reserves new capacity without releasing old or in-flight leases. Null means defer/retire work and try again
     * when the owner has new completion or budget information. Device-local memory on a unified heap reserves both
     * domains. The caller must account for alignment and all simultaneously live allocations before committing.
     */
    synchronized Reservation reserve(long deviceBytes, long hostBytes) {
        if (deviceBytes < 0 || hostBytes < 0 || (deviceBytes == 0 && hostBytes == 0)) {
            throw new IllegalArgumentException("Invalid memory reservation size");
        }
        State state = state();
        if (deviceBytes > state.deviceAvailable || hostBytes > state.hostAvailable) {
            rejections++;
            return null;
        }
        deviceReserved = Math.addExact(deviceReserved, deviceBytes);
        hostReserved = Math.addExact(hostReserved, hostBytes);
        devicePeak = Math.max(devicePeak, Math.addExact(deviceReserved, deviceCommitted));
        hostPeak = Math.max(hostPeak, Math.addExact(hostReserved, hostCommitted));
        return new Reservation(deviceBytes, hostBytes);
    }

    synchronized State state() {
        Snapshot snapshot = Objects.requireNonNull(measurement.get());
        long now = clock.getAsLong();
        long observedDevice = Math.max(0, snapshot.deviceUsage - deviceCommitted);
        long observedHost = Math.max(0, snapshot.hostUsage - hostCommitted);
        if (!measured || observedDevice >= otherDevice || now - lastDeviceHigh >= pressureHoldNanos) {
            otherDevice = observedDevice;
            lastDeviceHigh = now;
        }
        if (!measured || observedHost >= otherHost || now - lastHostHigh >= pressureHoldNanos) {
            otherHost = observedHost;
            lastHostHigh = now;
        }
        measured = true;
        long availableDevice = available(snapshot.deviceBudget, snapshot.deviceUsage, snapshot.deviceHeadroom,
                otherDevice, deviceCommitted, deviceReserved);
        long availableHost = available(snapshot.hostBudget, snapshot.hostUsage, snapshot.hostHeadroom,
                otherHost, hostCommitted, hostReserved);
        return new State(availableDevice, availableHost, deviceReserved, hostReserved, deviceCommitted,
                hostCommitted, devicePeak, hostPeak, rejections, snapshot.source);
    }

    private static long available(long budget, long observedUsage, long headroom,
                                  long otherUsage, long committed, long reserved) {
        long trackedUsage = otherUsage > Long.MAX_VALUE - committed ? Long.MAX_VALUE : otherUsage + committed;
        long remaining = Math.max(0, budget - Math.min(budget, Math.max(observedUsage, trackedUsage)));
        remaining -= Math.min(remaining, headroom);
        return remaining - Math.min(remaining, reserved);
    }

    /** Chooses geometric capacity within a device/index limit; available-memory admission remains a separate lease. */
    static long capacity(long current, long required, long minimum, long hardwareLimit) {
        if (current < 0 || required < 0 || minimum <= 0 || hardwareLimit < minimum || current > hardwareLimit) {
            throw new IllegalArgumentException("Invalid capacity or hardware limit");
        }
        if (required > hardwareLimit) throw new IllegalArgumentException("Memory request exceeds device/index addressing limit");
        if (required <= current) return current;
        long result = Math.max(current, minimum);
        while (result < required) result = result > hardwareLimit / 2 ? hardwareLimit : result * 2;
        return result;
    }
}
