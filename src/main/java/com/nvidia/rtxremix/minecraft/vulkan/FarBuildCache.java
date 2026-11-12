/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BooleanSupplier;

/**
 * Owner-thread cache with bounded worker handoff. A request identity survives neither a content change nor a session reset;
 * even A -> B -> A cannot publish an intervening build. Old published values stay available until their replacement is ready.
 * Only {@link #finish} runs on workers; validation and publication always run on the owner thread.
 */
final class FarBuildCache<V> {
    private record Request(long signature, long observedNanos) {}
    record Value<V>(long signature, V data) {}
    record Ticket(long key, Object request, BooleanSupplier valid) {}
    private record Done<V>(Ticket ticket, V value) {}

    private final int capacity;
    private final ArrayBlockingQueue<Done<V>> completed;
    private final Map<Long, Request> wanted = new HashMap<>();
    private final Map<Long, Ticket> busy = new HashMap<>();
    private final Map<Long, Value<V>> values = new HashMap<>();
    private int pending;
    private long published, publicationNanos, maxPublicationNanos;

    FarBuildCache(int capacity) {
        this.capacity = capacity;
        completed = new ArrayBlockingQueue<>(capacity);
    }

    void observe(long key, long signature) {
        Request old = wanted.get(key);
        if (old == null || old.signature != signature) wanted.put(key, new Request(signature, System.nanoTime()));
    }

    boolean needsBuild(long key) {
        Request request = wanted.get(key);
        Value<V> value = values.get(key);
        return request != null && (value == null || value.signature != request.signature) && !busy.containsKey(key);
    }

    Ticket begin(long key, long signature, BooleanSupplier valid) {
        if (pending >= capacity || !needsBuild(key) || wanted.get(key).signature != signature) return null;
        Ticket ticket = new Ticket(key, wanted.get(key), valid);
        busy.put(key, ticket);
        pending++;
        return ticket;
    }

    /** Exactly once for every begun ticket, including failed jobs (null value). Never drops a completion. */
    void finish(Ticket ticket, V value) {
        if (!completed.offer(new Done<>(ticket, value))) throw new IllegalStateException("Far-field completion overflow");
    }

    int drain() {
        int published = 0;
        Done<V> done;
        while ((done = completed.poll()) != null) {
            pending--;
            Ticket ticket = done.ticket;
            busy.remove(ticket.key, ticket);
            if (done.value != null && wanted.get(ticket.key) == ticket.request && ticket.valid.getAsBoolean()) {
                values.put(ticket.key, new Value<>(((Request) ticket.request).signature, done.value));
                published++;
                this.published++;
                long latency = System.nanoTime() - ((Request) ticket.request).observedNanos;
                publicationNanos += latency;
                maxPublicationNanos = Math.max(maxPublicationNanos, latency);
            }
        }
        return published;
    }

    Value<V> get(long key) { return values.get(key); }
    boolean matches(long key, long signature) {
        Request request = wanted.get(key);
        return request != null && request.signature == signature;
    }
    int size() { return values.size(); }
    double meanPublicationMs() { return publicationNanos / 1e6 / Math.max(1, published); }
    double maxPublicationMs() { return maxPublicationNanos / 1e6; }
    int pending() { return pending; }
    boolean hasCapacity() { return pending < capacity; }

    void retain(Set<Long> keys) {
        wanted.keySet().retainAll(keys);
        values.keySet().retainAll(keys);
    }

    void reset() {
        wanted.clear();
        values.clear();
        busy.clear();
        // Running old-session jobs still own slots until their completions are drained.
    }
}
