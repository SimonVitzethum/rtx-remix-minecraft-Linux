/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GPU-resident far terrain rendered into the Vulkan environment dome. Java owns spatial/revision metadata and
 * bounded change messages; native workspaces build one column at a time, upload it, and release their node storage.
 * Disk DAG pages and received surfaces supply reloads without a retained Java or native scene mirror.
 *
 * <p>Loaded gameplay and existing Bobby chunks supply exact voxels outside the retained native radius. Unvisited
 * integrated terrain uses stateless density/biome samples without chunk tickets. The renderer never raises normal
 * render or simulation distance. Every live-world read, GPU operation and publication belongs to the client thread;
 * workers perform procedural sampling, bounded native builds and disk I/O. Configuration remains in the mod settings
 * and the optional rtxremix-farfield file; radius=0 follows the actual Minecraft render distance.
 */
public final class FarField {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarField.class);
    private static final int SKY_W = 256, SKY_H = 128;
    public static final int MAX_DISTANCE_CHUNKS = 512;
    public static final int MAX_RADIUS_CHUNKS = 64;

    private static volatile FarField instance;
    /** Serializes disk ownership across world swaps and off/on cycles without waiting on the render thread. */
    private static CompletableFuture<Void> previousCacheFlush = CompletableFuture.completedFuture(null);
    private static boolean configRead;
    private static volatile boolean enabled;
    private static volatile int radius = 0, distance = 256;
    /** Multiplayer chunk cache (surface columns of every chunk received, per server) and its disk limit. */
    private static volatile boolean cacheOn = true;
    private static volatile int cacheMb = 256;
    private static int width = 2048, cut = 1, lodThreads = 2, buildThreads = 3;
    private static volatile boolean fogOn = false, shadowsOn = true;
    private static float fogDist = 4000f, sunBoost = 1f;

    public static boolean isEnabled() {
        readConfig();
        return enabled;
    }

    // ---- settings accessors (RtxSettingsFactory) ----
    public static boolean getEnabledSetting() { readConfig(); return enabled; }
    public static void setEnabledSetting(boolean on) {
        readConfig();
        if (enabled == on) return;
        enabled = on;
        if (!on) shutdown();
    }
    public static int getRadiusSetting() { readConfig(); return radius; }
    public static void setRadiusSetting(int chunks) { radius = Math.clamp(chunks, 0, MAX_RADIUS_CHUNKS); }
    public static int getDistanceSetting() { readConfig(); return distance; }
    public static void setDistanceSetting(int chunks) { distance = Math.clamp(chunks, 16, MAX_DISTANCE_CHUNKS); }
    public static boolean getCacheSetting() { readConfig(); return cacheOn; }
    public static void setCacheSetting(boolean on) { cacheOn = on; }
    public static int getCacheMbSetting() { readConfig(); return cacheMb; }
    public static void setCacheMbSetting(int mb) { cacheMb = Math.clamp(mb, 16, 4096); }
    public static boolean getFogSetting() { readConfig(); return fogOn; }
    public static void setFogSetting(boolean on) { fogOn = on; }
    public static boolean getShadowsSetting() { readConfig(); return shadowsOn; }
    public static void setShadowsSetting(boolean on) { shadowsOn = on; }

    private static void readConfig() {
        if (configRead) return;
        configRead = true;
        String text = System.getProperty("rtxremix.farfield");
        if (text == null) {
            try {
                Path file = FabricLoader.getInstance().getGameDir().resolve("rtxremix-farfield");
                if (Files.isRegularFile(file)) text = Files.readString(file);
            } catch (IOException | RuntimeException e) {
                // no config file
            }
        }
        if (text == null) return;
        enabled = true;
        for (String line : text.split("[\\s,;]+")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).strip(), v = line.substring(eq + 1).strip();
            try {
                switch (k) {
                    case "radius" -> radius = Math.clamp(Integer.parseInt(v), 0, MAX_RADIUS_CHUNKS);
                    case "distance" -> distance = Math.clamp(Integer.parseInt(v), 16, MAX_DISTANCE_CHUNKS);
                    case "width" -> width = Integer.parseInt(v);
                    case "cache" -> cacheOn = !v.equals("0");
                    case "cacheMb" -> cacheMb = Math.clamp(Integer.parseInt(v), 16, 4096);
                    case "fog" -> fogOn = !v.equals("0");
                    case "fogDist" -> fogDist = Float.parseFloat(v);
                    case "shadows" -> shadowsOn = !v.equals("0");
                    case "cut", "hideNear" -> cut = Integer.parseInt(v);
                    case "sunBoost" -> sunBoost = Float.parseFloat(v);
                    case "lodThreads" -> lodThreads = Integer.parseInt(v);
                    case "buildThreads" -> buildThreads = Integer.parseInt(v);
                    default -> LOGGER.warn("FarField: unknown key {}", k);
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("FarField: bad value {}", line);
            }
        }
        LOGGER.info("FarField enabled: radius={} (0 = render distance) distance={} chunks width={} fog={} shadows={} cut={}",
                radius, distance, width, fogOn, shadowsOn, cut);
    }

    /** The single instance, created on first use; null when disabled or not on the Vulkan backend. */
    public static FarField get() {
        if (!isEnabled()) return null;
        FarField f = instance;
        if (f == null) {
            if (!VulkanBackendSupport.isActive()) {
                LOGGER.warn("FarField needs the Vulkan backend; disabled");
                enabled = false;
                return null;
            }
            try {
                f = new FarField();
                instance = f;
            } catch (RuntimeException | Error e) {
                LOGGER.error("FarField init failed", e);
                enabled = false;
                return null;
            }
        }
        return f;
    }

    /** True when the captured terrain of this column must stay hidden: the far field draws it (beyond the path-traced radius). */
    public static boolean cutsColumn(int cx, int cz) {
        if (!enabled || cut == 0) return false;
        FarField f = instance;
        if (f == null || f.current == null) return false;
        return Math.max(Math.abs(cx - f.camChunkX), Math.abs(cz - f.camChunkZ)) >= f.lastRadius;
    }

    public static void shutdown() {
        FarField f = instance;
        instance = null;
        if (f != null) f.close();
    }

    private record Subtree(GpuDagAllocator.Page page, int[] roots, int nodes, FarFieldCoverage.Sources sources) {}
    private record Build(int root, int levels, int originSx, int originSz, int minY, long session,
                         int generation, FarFieldCoverage coverage, java.util.Set<GpuDagAllocator.Page> pages,
                         GpuDagAllocator.Page directory) {}
    private static final class ColumnIn {
        final byte[] cls = new byte[256];
        final long[] revisions = new long[256];
        int loaded;
        long signature() {
            long hash = 0;
            for (int i = 0; i < 256; i++) if (cls[i] != 0) {
                hash = hash * 1000003L + i * 4L + cls[i] + (cls[i] == 2 ? revisions[i] * 0x9E3779B9L : 0);
            }
            return hash;
        }
    }
    private record Desired(long key, int sx, int sz, int lg, long signature, long sourceRevision, long palette,
                           int colourGeneration, ColumnIn input) {}
    private enum Stage { WORKER, CAPTURE, FINISHED }
    private final class Job {
        final Desired desired;
        final long jobSession = session;
        final ClientLevel level;
        final FarTerrainSource source;
        final int bottom = minY, slabs = (worldHeight + 255) / 256;
        final FarBuildCache.Ticket ticket;
        final FarFieldCoverage.Sources sources;
        long handle;
        int nodes, nextChunk, nextSection;
        int[] roots;
        Stage stage = Stage.WORKER;
        Throwable failure;
        boolean fromDisk;
        Job(Desired desired, ClientLevel level, FarBuildCache.Ticket ticket) {
            this.desired = desired; this.level = level; this.ticket = ticket; source = FarField.this.source;
            int near = 0, exact = 0;
            if (desired.input != null) for (byte cls : desired.input.cls) { if (cls == 1) near++; if (cls == 2) exact++; }
            // Live/Bobby provenance is assigned when the input is inspected on the owner thread.
            int bobby = 0;
            if (desired.input != null) for (int i = 0; i < 256; i++) {
                if (desired.input.cls[i] == 2 && BobbyCompat.isFake(chunk(level, desired.sx * 16 + (i & 15), desired.sz * 16 + (i >> 4)))) bobby++;
            }
            int surface = 0;
            for (int i = 0; i < 256; i++) {
                if ((desired.input == null || desired.input.cls[i] != 2) && (source.hasProcedural()
                        || source.receivedChunk(desired.sx * 16 + (i & 15), desired.sz * 16 + (i >> 4)))) surface++;
            }
            sources = new FarFieldCoverage.Sources(near, exact - bobby, bobby, surface);
        }
    }
    private record Event(Job job, Stage stage, Throwable failure) {}
    private static final class Upload {
        final Job job;
        final GpuDagAllocator.Page page;
        int nextWord = 8, sentWords;
        Upload(Job job, GpuDagAllocator.Page page) { this.job = job; this.page = page; }
    }

    private final FarChunkMessages chunkMessages = new FarChunkMessages();
    private final FarFieldGpu gpu;
    private final GpuDagStorage storage;
    private final ByteBuffer skyCopy = ByteBuffer.allocateDirect(SKY_W * SKY_H * 8).order(ByteOrder.nativeOrder());
    private final ByteBuffer push = ByteBuffer.allocateDirect(FarFieldGpu.PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer exactMessage = DirectBufferPool.acquire(8192).order(ByteOrder.nativeOrder());
    private final FarBuildCache<Subtree> cache = new FarBuildCache<>(2, this::retire);
    private final java.util.Set<GpuDagAllocator.Page> retired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<Job> jobs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.concurrent.ArrayBlockingQueue<Event> events = new java.util.concurrent.ArrayBlockingQueue<>(2);
    private final Object completionLock = new Object();
    private final java.util.concurrent.ThreadPoolExecutor pool;
    private final HashMap<Long, Long> recorded = new HashMap<>();
    private final java.util.ArrayDeque<Long> recordQueue = new java.util.ArrayDeque<>();
    private final java.util.Set<Long> recordQueued = new java.util.HashSet<>();
    private Object levelKey;
    private volatile long session;
    private volatile boolean closed;
    private FarTerrainSource source;
    private int minY, worldHeight, epoch, mergedEpoch = -1;
    private int camChunkX, camChunkZ, lastRadius = 6, buildCursor;
    private int paletteGeneration = -1;
    private long paletteIdentity;
    private long scheduled, lastPlanMs, lastStatMs, lastRenderMs, staleCaptures, deferredRecords;
    private long[] wantedKeys = new long[0];
    private java.util.List<Desired> desired = java.util.List.of();
    private boolean wantedChanged, skyDirty, hasSky, dagDirty;
    private int levels, originSx, originSz;
    private Build current, directoryUpload, renderedBuild, publishedBuild;
    private Upload uploading;
    private Vec3 lastRenderCam, publishedRenderCam;
    private SkyLightState lastSky;
    private float[] lastAmbient = {0.1f, 0.1f, 0.1f};
    private long renders, readerDeferredFrames, uploadDeferredFrames;
    private RemixTexture texture;
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo update = new TexturePatchInfo();

    private FarField() {
        gpu = new FarFieldGpu(width, width / 2);
        storage = gpu.storage();
        pool = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "RemixNativeDagBuild");
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
        });
        patch.width = width; patch.height = width / 2; patch.rowPitch = width * 8; patch.format = TextureFormat.RGBA16_SFLOAT;
        entry.patches = new TexturePatch[]{patch}; update.entries = new TextureBatchEntry[]{entry};
    }

    public long textureHandle() { return texture != null && texture.isAlive() ? texture.getHandle() : 0; }

    public void offerSky(ByteBuffer pixels) {
        ByteBuffer view = pixels.duplicate();
        view.position(0).limit(Math.min(view.capacity(), skyCopy.capacity()));
        if (hasSky && skyCopy.limit() == view.remaining() && skyCopy.mismatch(view) < 0) return;
        skyCopy.clear(); skyCopy.put(view).flip(); hasSky = true; skyDirty = true;
    }

    private static int ptRadius() {
        return radius > 0 ? radius : Math.max(2, Minecraft.getInstance().options.renderDistance().get());
    }

    public void tick(RemixAPI api, ClientLevel level, Vec3 cam, SkyLightState sky, float[] ambient,
                     com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer timer) {
        lastSky = sky; lastAmbient = ambient;
        camChunkX = Math.floorDiv((int) Math.floor(cam.x), 16); camChunkZ = Math.floorDiv((int) Math.floor(cam.z), 16);
        lastRadius = ptRadius();
        if (levelKey != level) newLevel(level);
        ByteBuffer pixels = gpu.poll();
        if (pixels != null && renderedBuild != null && renderedBuild.session == session) {
            publish(api, pixels); publishedBuild = renderedBuild; publishedRenderCam = lastRenderCam; renders++;
            if (renders <= 5 || renders % 20 == 0 || Boolean.getBoolean("rtxremix.farfield.diagnostics")) {
                LOGGER.info("FarField render #{}: session {} generation {}, gpu {} ms (wall to fence {} ms), dag {} KiB", renders,
                        renderedBuild.session, renderedBuild.generation, String.format("%.2f", gpu.lastGpuMs()),
                        String.format("%.2f", gpu.lastFlightMs()), storage.liveBytes() >> 10);
            }
        }
        boolean copyReady = storage.poll();
        if (!jobs.isEmpty()) {
            if (gpu.inFlight()) readerDeferredFrames++;
            if (storage.copying()) uploadDeferredFrames++;
        }
        drainEvents();
        if (copyReady && !gpu.inFlight()) advanceUploads();
        int accepted = cache.drain();
        epoch += accepted;
        long now = System.currentTimeMillis();
        if (now - lastPlanMs > 500) { lastPlanMs = now; plan(level, cam); }
        captureSlice();
        recordSlice(level);
        // Directory commits take priority over further page uploads; replaced pages cannot accumulate indefinitely.
        if (!gpu.inFlight() && !storage.copying() && uploading == null && directoryUpload == null
                && epoch > 0 && (wantedChanged || epoch != mergedEpoch)) buildDirectory();
        if (directoryUpload == null && uploading == null && !gpu.inFlight() && !storage.copying()) beginUpload();
        admit(level);
        if (current != null && !gpu.inFlight() && !storage.copying() && hasSky && now - lastRenderMs > 1000) {
            double threshold = Math.max(8, lastRadius * 16.0 / 12);
            if (dagDirty || skyDirty || lastRenderCam == null || lastRenderCam.distanceToSqr(cam) > threshold * threshold) {
                render(cam); lastRenderMs = now; lastRenderCam = cam; dagDirty = false; skyDirty = false;
            }
        }
        if (now - lastStatMs > 10000) {
            lastStatMs = now;
            LOGGER.info("FarField progress: native jobs {}, GPU pages {}, stale captures {}, record backlog {}, deferred admissions {}",
                    jobs.size(), storage.livePages(), staleCaptures, recordQueue.size(), deferredRecords);
            if (Boolean.getBoolean("rtxremix.farfield.diagnostics")) LOGGER.info("FarField coverage: {}", diagnostics());
        }
        storage.recordFrame(timer);
    }

    private void newLevel(ClientLevel level) {
        levelKey = level; session++;
        cache.reset(); desired = java.util.List.of(); wantedKeys = new long[0]; wantedChanged = true;
        epoch = 0; mergedEpoch = -1; lastPlanMs = 0; lastRenderCam = null;
        // A submitted reader/copy keeps its allocations alive until the regular fence path sees completion.
        if (current != null) { retired.addAll(current.pages); if (current.directory != null) retired.add(current.directory); }
        current = null; renderedBuild = null; publishedBuild = null; publishedRenderCam = null;
        if (texture != null && !texture.isDeleted()) texture.destroy(); texture = null;
        if (source != null) { source.close(); previousCacheFlush = source.flushed(); }
        source = null; recorded.clear(); recordQueue.clear(); recordQueued.clear();
        minY = level.getMinY(); worldHeight = level.getHeight();
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        try {
            Path root = FabricLoader.getInstance().getGameDir();
            if (server == null) {
                var connection = client.getCurrentServer();
                String serverName = (connection == null ? "unknown" : connection.ip).replaceAll("[^A-Za-z0-9._-]", "_");
                Path received = root.resolve("rtxremix-farcache").resolve(serverName)
                        .resolve(level.dimension().identifier().toString().replace(':', '_').replace('/', '_'));
                source = new FarTerrainSource(received, received.resolve("stream"), null, cacheOn, cacheMb, previousCacheFlush);
            } else {
                ServerLevel world = server.getLevel(level.dimension());
                if (world == null) return;
                String name = server.getWorldData().getLevelName().replaceAll("[^A-Za-z0-9._-]", "_");
                Path generated = root.resolve("rtxremix-farlod").resolve(name + "-" + Long.toHexString(world.getSeed()))
                        .resolve(level.dimension().identifier().toString().replace(':', '_').replace('/', '_'));
                String identity = java.util.UUID.nameUUIDFromBytes(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                source = new FarTerrainSource(generated.resolve("received").resolve(identity), generated,
                        new FarTerrainSampler(world), true, cacheMb, previousCacheFlush);
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot open disk-backed far terrain", failure); }
    }

    private static long subKey(int x, int z) { return FarTerrainSource.key(x, z); }

    private void plan(ClientLevel level, Vec3 camera) {
        int generation = com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current();
        if (generation != paletteGeneration) {
            paletteIdentity = FarTerrainSampler.paletteIdentity();
            paletteGeneration = generation;
        }
        HashMap<Long, ColumnIn> loaded = new HashMap<>();
        int reach = Minecraft.getInstance().options.renderDistance().get() + 2;
        for (int z = -reach; z <= reach; z++) for (int x = -reach; x <= reach; x++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(camChunkX + x, camChunkZ + z);
            if (chunk != null) addLoaded(loaded, chunk);
        }
        BobbyCompat.forEachFakeChunk(level, chunk -> addLoaded(loaded, chunk));
        int extent = Math.max(distance, lastRadius + 8);
        int sx = Math.floorDiv((int) Math.floor(camera.x), 256), sz = Math.floorDiv((int) Math.floor(camera.z), 256);
        int span = (extent * 16 + 255) / 256 + 1;
        int nextLevels = 9;
        while ((1 << nextLevels) < (2 * span + 2) * 256) nextLevels++;
        int nextOriginX = sx - (1 << (nextLevels - 9)), nextOriginZ = sz - (1 << (nextLevels - 9));
        var candidates = new ArrayList<Desired>();
        for (int z = sz - span; z <= sz + span; z++) for (int x = sx - span; x <= sx + span; x++) {
            int d = Math.max(Math.abs(x * 16 + 8 - camChunkX), Math.abs(z * 16 + 8 - camChunkZ));
            if (d - 8 > extent) continue;
            int lg = d < 32 ? 1 : d < 64 ? 2 : d < 192 ? 3 : 4;
            long key = subKey(x, z), revision = source == null ? 0 : source.revision(x, z);
            ColumnIn input = loaded.get(key);
            long signature = (lg + 2L) * 0x100000001B3L ^ (input == null ? 0 : input.signature())
                    ^ revision * 0x9E3779B97F4A7C15L ^ paletteIdentity ^ generation;
            cache.observe(key, signature);
            candidates.add(new Desired(key, x, z, lg, signature, revision, paletteIdentity, generation, input));
        }
        candidates.sort(java.util.Comparator.<Desired>comparingInt(value -> Math.max(Math.abs(value.sx * 16 + 8 - camChunkX),
                Math.abs(value.sz * 16 + 8 - camChunkZ))).thenComparingLong(Desired::key));
        long[] wanted = new long[candidates.size()];
        var keep = new java.util.HashSet<Long>();
        for (int i = 0; i < wanted.length; i++) { wanted[i] = candidates.get(i).key; keep.add(wanted[i]); }
        wantedChanged |= levels != nextLevels || originSx != nextOriginX || originSz != nextOriginZ
                || !java.util.Arrays.equals(wantedKeys, wanted);
        wantedKeys = wanted; desired = candidates; levels = nextLevels; originSx = nextOriginX; originSz = nextOriginZ;
        cache.retain(keep);
        // No LevelChunk is retained in the recorder. Removed chunks are sampled synchronously by the unload hook.
        recorded.keySet().removeIf(key -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow((int) (key >> 32), (int) (long) key);
            return chunk == null || BobbyCompat.isFake(chunk);
        });
        if (source != null) { source.setCenter((int) camera.x, (int) camera.z); source.trim((extent + 64) * 16); }
    }

    private void addLoaded(Map<Long, ColumnIn> loaded, LevelChunk chunk) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        long key = subKey(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16));
        ColumnIn column = loaded.computeIfAbsent(key, ignored -> new ColumnIn());
        int slot = Math.floorMod(cz, 16) * 16 + Math.floorMod(cx, 16);
        if (column.cls[slot] != 0 && BobbyCompat.isFake(chunk)) return;
        if (column.cls[slot] == 0) column.loaded++;
        column.cls[slot] = (byte) (Math.max(Math.abs(cx - camChunkX), Math.abs(cz - camChunkZ)) < lastRadius - 1 ? 1 : 2);
        long revision = FarChunkMessages.signature(chunk);
        column.revisions[slot] = revision;
        if (!BobbyCompat.isFake(chunk) && source != null && !java.util.Objects.equals(recorded.get(subKey(cx, cz)), revision)
                && recordQueued.add(subKey(cx, cz))) {
            if (recordQueue.size() < 1024) recordQueue.addLast(subKey(cx, cz));
            else { recordQueued.remove(subKey(cx, cz)); deferredRecords++; }
        }
    }

    private static LevelChunk chunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk != null ? chunk : BobbyCompat.fakeChunk(level, cx, cz);
    }

    private boolean valid(Desired desired, ClientLevel level, long jobSession, FarTerrainSource jobSource) {
        if (jobSession != session || jobSource != source || jobSource.revision(desired.sx, desired.sz) != desired.sourceRevision
                || desired.colourGeneration != com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current()) return false;
        if (desired.input != null) for (int i = 0; i < 256; i++) {
            if (desired.input.cls[i] != 2) continue;
            LevelChunk chunk = chunk(level, desired.sx * 16 + (i & 15), desired.sz * 16 + (i >> 4));
            if (chunk == null || FarChunkMessages.signature(chunk) != desired.input.revisions[i]) return false;
        }
        return true;
    }

    private void admit(ClientLevel level) {
        if (source == null || !cache.hasCapacity() || desired.isEmpty()) return;
        Desired selected = null;
        // Alternate responsive edits with a rotating scan so moving/edited near columns cannot starve initial far coverage.
        if ((scheduled & 1) == 0) for (Desired candidate : desired) {
            if (cache.get(candidate.key) != null && cache.needsBuild(candidate.key)) { selected = candidate; break; }
        }
        if (selected == null) for (int i = 0; i < desired.size(); i++) {
            int index = Math.floorMod(buildCursor + i, desired.size());
            Desired candidate = desired.get(index);
            if (cache.needsBuild(candidate.key) && (candidate.input != null || source.hasData(candidate.sx, candidate.sz))) {
                selected = candidate; buildCursor = (index + 1) % desired.size(); break;
            }
        }
        if (selected == null) return;
        Desired selection = selected;
        FarTerrainSource jobSource = source;
        long jobSession = session;
        var ticket = cache.begin(selected.key, selected.signature, () -> valid(selection, level, jobSession, jobSource));
        if (ticket == null) return;
        Job job = new Job(selected, level, ticket);
        jobs.add(job); scheduled++; jobSource.acquire();
        execute(job, () -> {
            job.handle = job.source.open(selection.sx, selection.sz, selection.lg, selection.signature);
            if (job.handle != 0) { job.fromDisk = true; finish(job); return Stage.FINISHED; }
            job.handle = com.nvidia.rtxremix.VoxelDag.create(selection.lg, job.slabs);
            job.source.surface(job.handle, selection.sx, selection.sz, selection.lg, job.bottom,
                    selection.palette, selection.colourGeneration);
            return Stage.CAPTURE;
        });
    }

    @FunctionalInterface private interface Work { Stage run() throws IOException; }
    private void execute(Job job, Work work) {
        pool.execute(() -> {
            Stage stage = Stage.FINISHED;
            Throwable failure = null;
            try { if (job.jobSession == session && !closed) stage = work.run(); }
            catch (Throwable problem) { failure = problem; }
            synchronized (completionLock) {
                if (closed) { releaseJob(job); return; }
                if (!events.offer(new Event(job, stage, failure))) throw new IllegalStateException("Native DAG completion overflow");
            }
        });
    }

    private void drainEvents() {
        Event event;
        while ((event = events.poll()) != null) {
            Job job = event.job;
            if (job.jobSession != session || event.failure != null || job.handle == 0) {
                if (event.failure != null && !(event.failure instanceof java.util.concurrent.CancellationException)) {
                    LOGGER.error("Far-field native job failed at {},{}", job.desired.sx, job.desired.sz, event.failure);
                }
                cache.finish(job.ticket, null); releaseJob(job); jobs.remove(job);
            } else job.stage = event.stage;
        }
    }

    private void captureSlice() {
        long deadline = System.nanoTime() + 2_000_000;
        int budget = 64;
        for (Job job : java.util.List.copyOf(jobs)) {
            if (job.stage != Stage.CAPTURE) continue;
            if (!valid(job.desired, job.level, job.jobSession, job.source)) {
                staleCaptures++; cache.finish(job.ticket, null); releaseJob(job); jobs.remove(job); continue;
            }
            ColumnIn input = job.desired.input;
            while (job.nextChunk < 256 && budget > 0 && System.nanoTime() < deadline) {
                int slot = job.nextChunk;
                if (input == null || input.cls[slot] != 2) { job.nextChunk++; continue; }
                LevelChunk chunk = chunk(job.level, job.desired.sx * 16 + (slot & 15), job.desired.sz * 16 + (slot >> 4));
                chunkMessages.exact(chunk, job.nextSection, exactMessage);
                com.nvidia.rtxremix.VoxelDag.exact(job.handle, slot & 15, job.nextSection, slot >> 4, exactMessage);
                budget--;
                if (++job.nextSection == job.slabs * 16) { job.nextSection = 0; job.nextChunk++; }
            }
            if (job.nextChunk == 256) {
                job.stage = Stage.WORKER;
                execute(job, () -> { finish(job); return Stage.FINISHED; });
            }
            if (budget <= 0 || System.nanoTime() >= deadline) break;
        }
    }

    private void finish(Job job) {
        job.nodes = com.nvidia.rtxremix.VoxelDag.finish(job.handle);
        job.roots = new int[job.slabs];
        for (int i = 0; i < job.slabs; i++) job.roots[i] = com.nvidia.rtxremix.VoxelDag.root(job.handle, i);
        if (!job.fromDisk) job.source.save(job.handle, job.desired.sx, job.desired.sz, job.desired.lg, job.desired.signature);
    }

    private void beginUpload() {
        for (Job job : java.util.List.copyOf(jobs)) {
            if (job.stage != Stage.FINISHED) continue;
            if (!valid(job.desired, job.level, job.jobSession, job.source)) {
                cache.finish(job.ticket, null); releaseJob(job); jobs.remove(job); continue;
            }
            if (job.nodes <= 1) { complete(job, null); continue; }
            uploading = new Upload(job, storage.allocate(job.nodes - 1));
            copyNextSlice(); return;
        }
    }

    private void copyNextSlice() {
        Upload upload = uploading;
        upload.sentWords = Math.min(GpuDagStorage.STAGING_BYTES / 4, upload.job.nodes * 8 - upload.nextWord);
        storage.upload(upload.page, upload.job.handle, upload.nextWord, upload.sentWords);
    }

    private void advanceUploads() {
        if (directoryUpload != null) {
            Build next = directoryUpload; directoryUpload = null;
            if (next.session == session) {
                if (current != null && current.directory != null) retired.add(current.directory);
                current = next; dagDirty = true;
            } else { retired.add(next.directory); retired.addAll(next.pages); }
            collectRetired();
        }
        if (uploading == null) { collectRetired(); return; }
        Upload upload = uploading;
        upload.nextWord += upload.sentWords;
        if (upload.nextWord >= upload.job.nodes * 8) {
            complete(upload.job, upload.page); uploading = null;
        } else copyNextSlice();
    }

    private void complete(Job job, GpuDagAllocator.Page page) {
        int offset = page == null ? 0 : page.referenceOffset();
        for (int i = 0; i < job.roots.length; i++) {
            int reference = job.roots[i];
            if (reference != 0 && (reference & FarFieldDag.LEAF) == 0) job.roots[i] += offset;
        }
        cache.finish(job.ticket, new Subtree(page, job.roots, job.nodes, job.sources));
        releaseJob(job); jobs.remove(job);
    }

    private void releaseJob(Job job) {
        if (job.handle != 0) { com.nvidia.rtxremix.VoxelDag.destroy(job.handle); job.handle = 0; }
        job.source.release();
    }

    private void retire(Subtree subtree) { if (subtree.page != null) retired.add(subtree.page); }

    private void collectRetired() {
        if (gpu.inFlight() || storage.copying()) return;
        var iterator = retired.iterator();
        while (iterator.hasNext()) {
            var page = iterator.next();
            if (current != null && (page == current.directory || current.pages.contains(page))) continue;
            if (directoryUpload != null && (page == directoryUpload.directory || directoryUpload.pages.contains(page))) continue;
            storage.release(page); iterator.remove();
        }
    }

    private void buildDirectory() {
        HashMap<Long, int[]> roots = new HashMap<>();
        HashMap<Long, FarFieldCoverage.Source> coverage = new HashMap<>();
        java.util.Set<GpuDagAllocator.Page> pages = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (long key : wantedKeys) {
            var value = cache.get(key);
            if (value == null) continue;
            Subtree subtree = value.data();
            roots.put(key, subtree.roots);
            if (subtree.page != null) pages.add(subtree.page);
            boolean geometry = !allZero(subtree.roots);
            coverage.put(key, new FarFieldCoverage.Source(value.signature(), geometry, subtree.sources));
        }
        // The top tree is a small address directory, not a mirror of subtree nodes.
        GpuDagTopology.Result topology = GpuDagTopology.build(roots, levels, originSx, originSz, (worldHeight + 255) / 256);
        int count = topology.nodes().length / 8 - 1;
        GpuDagAllocator.Page directory = count > 0 ? storage.allocate(count) : null;
        int root = topology.root();
        if (directory == null && storage.buffer() == 0) {
            directory = storage.allocate(1);
            storage.upload(directory, java.nio.IntBuffer.wrap(new int[8]));
        } else if (directory != null) {
            int[] nodes = topology.nodesAt(directory.referenceOffset());
            root = topology.rootAt(directory.referenceOffset());
            storage.upload(directory, java.nio.IntBuffer.wrap(nodes, 8, nodes.length - 8));
        }
        directoryUpload = new Build(root, levels, originSx, originSz, minY, session, epoch,
                new FarFieldCoverage(coverage), java.util.Set.copyOf(pages), directory);
        mergedEpoch = epoch; wantedChanged = false;
        if (!storage.copying()) advanceUploads();
    }

    private static boolean allZero(int[] values) { for (int value : values) if (value != 0) return false; return true; }

    public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        FarField field = instance;
        if (field != null && field.levelKey == level && !BobbyCompat.isFake(chunk)) {
            if (!field.record(chunk)) LOGGER.warn("FarField: bounded surface record queue full at unload of {}; last disk state retained", chunk.getPos());
            field.recorded.remove(subKey(chunk.getPos().x(), chunk.getPos().z()));
        }
    }

    private boolean record(LevelChunk chunk) {
        if (source == null || !source.canRecord()) return false;
        long signature = FarChunkMessages.signature(chunk), key = subKey(chunk.getPos().x(), chunk.getPos().z());
        if (java.util.Objects.equals(recorded.get(key), signature)) return true;
        ByteBuffer message = DirectBufferPool.acquire(1024).order(ByteOrder.nativeOrder());
        try { chunkMessages.surface(chunk, message); }
        catch (RuntimeException | Error failure) { DirectBufferPool.release(message); throw failure; }
        if (!source.record(chunk.getPos().x(), chunk.getPos().z(), message)) { DirectBufferPool.release(message); return false; }
        recorded.put(key, signature); return true;
    }

    private void recordSlice(ClientLevel level) {
        if (source == null) return;
        long deadline = System.nanoTime() + 2_000_000;
        int budget = 8;
        while (!recordQueue.isEmpty() && source.canRecord() && budget-- > 0 && System.nanoTime() < deadline) {
            long key = recordQueue.removeFirst(); recordQueued.remove(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow((int) (key >> 32), (int) key);
            if (chunk != null && !BobbyCompat.isFake(chunk)) record(chunk);
        }
    }

    public static String diagnostics() {
        FarField f = instance;
        String settings = "enabled=" + enabled + " settingRadius=" + radius + " distance=" + distance + " cut=" + cut;
        if (f == null) return settings + " instance=none";
        HashMap<Long, FarFieldCoverage.Source> cached = new HashMap<>();
        for (long key : f.wantedKeys) {
            var value = f.cache.get(key);
            if (value != null) cached.put(key, new FarFieldCoverage.Source(value.signature(), !allZero(value.data().roots), value.data().sources));
        }
        long retiredBytes = f.retired.stream().mapToLong(page -> page.nodes * 32L).sum();
        return settings + " session=" + f.session + " radius=" + f.lastRadius + " plannedDistance=" + Math.max(distance, f.lastRadius + 8)
                + " planTimeMs=" + f.lastPlanMs + " renderSubmitTimeMs=" + f.lastRenderMs + " publishedRenders=" + f.renders
                + " cameraChunk=" + f.camChunkX + "," + f.camChunkZ + " wanted=" + f.wantedKeys.length
                + " wantedRanges=" + FarFieldCoverage.ranges(f.wantedKeys) + " cache=" + new FarFieldCoverage(cached).summarize(f.wantedKeys, f.cache::matches)
                + " current=" + f.describe(f.current) + " uploading=" + f.describeUpload()
                + " submitted=" + f.describe(f.renderedBuild) + " submittedCamera=" + f.lastRenderCam
                + " published=" + f.describe(f.publishedBuild) + " publishedCamera=" + f.publishedRenderCam
                + " nativeDag=" + java.util.Arrays.toString(com.nvidia.rtxremix.VoxelDag.metrics())
                + " gpuAllocatedBytes=" + f.storage.deviceBytes() + " gpuPeakBytes=" + f.storage.peakDeviceBytes()
                + " gpuUsedBytes=" + f.storage.liveBytes() + " stagingBytes=" + f.storage.stagingBytes()
                + " uploadInFlight=" + f.storage.copying() + " retiredBytes=" + retiredBytes
                + " residentJavaSources=0 nativeMirrorBytes=0 nativeTransientJobs=" + f.jobs.size()
                + " sourceTransientMessageBytes=" + (f.source == null ? 0 : f.source.transientMessageBytes())
                + " exactMessageBytes=" + f.exactMessage.capacity()
                + " readerDeferredFrames=" + f.readerDeferredFrames + " uploadDeferredFrames=" + f.uploadDeferredFrames
                + " uploadStats=" + f.storage.diagnostics();
    }

    private String describe(Build build) {
        if (build == null) return "none";
        return "{session=" + build.session + " generation=" + build.generation + " origin=" + build.originSx + "," + build.originSz
                + " cubeBlocks=" + (1 << build.levels) + " coverage=" + build.coverage.summarize(wantedKeys, cache::matches) + "}";
    }

    private String describeUpload() {
        if (directoryUpload != null) return describe(directoryUpload);
        if (uploading == null) return "none";
        Job job = uploading.job;
        var selected = new FarFieldCoverage(Map.of(job.desired.key, new FarFieldCoverage.Source(job.desired.signature,
                !allZero(job.roots), job.sources)));
        return "{session=" + job.jobSession + " stage=subtree wordsSubmitted=" + (uploading.nextWord - 8 + uploading.sentWords)
                + " wordsTotal=" + (job.nodes * 8 - 8) + " coverage=" + selected.summarize(wantedKeys, cache::matches) + "}";
    }

    static final int KIND_PLAIN = 0, KIND_GRASS = 1, KIND_FOLIAGE = 2, KIND_WATER = 3;

    /** Block state to (kind << 24 | rgb), 0 = not visible from afar. */
    static int classify(BlockState st) {
        if (st.isAir()) return 0;
        MapColor color = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (color == MapColor.NONE) return 0;
        boolean fluid = !st.getFluidState().isEmpty();
        if (!fluid && st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return 0;
        int kind = KIND_PLAIN;
        if (st.getFluidState().is(FluidTags.WATER)) kind = KIND_WATER;
        else if (st.is(Blocks.GRASS_BLOCK)) kind = KIND_GRASS;
        else if (st.getBlock() instanceof LeavesBlock && color == MapColor.PLANT) kind = KIND_FOLIAGE;
        return 0x80000000 | (kind << 24) | (color.col & 0xFFFFFF);
    }

    private void render(Vec3 cam) {
        FarFieldGpu.Buf aux = gpu.aux();
        aux.mapped.clear();
        aux.mapped.position(FarFieldGpu.PALETTE_WORDS * 4);
        skyCopy.rewind();
        aux.mapped.put(skyCopy);
        aux.mapped.clear();
        gpu.vmaFlush(aux);

        Build b = current;
        float cubeSize = 1 << b.levels;
        double ox = b.originSx * 256.0, oz = b.originSz * 256.0;
        boolean day = lastSky.sunPosition()[1] > -0.05f;
        float[] dir = day ? lastSky.sunPosition() : lastSky.moonPosition();
        float[] rad = day ? lastSky.sunDirectRadiance() : lastSky.moonDirectRadiance();
        float r = day ? lastSky.sunAngularRadius() : lastSky.moonAngularRadius();
        float e = r * r * sunBoost;
        push.clear();
        push.putFloat((float) (cam.x - ox)).putFloat((float) (cam.y - b.minY)).putFloat((float) (cam.z - oz)).putFloat(cubeSize);
        push.putInt(b.root).putInt(b.levels).putInt(gpu.width()).putInt(gpu.height());
        push.putFloat(dir[0]).putFloat(dir[1]).putFloat(dir[2]).putFloat(0f);
        push.putFloat(rad[0] * e).putFloat(rad[1] * e).putFloat(rad[2] * e).putFloat(0f);
        push.putFloat(lastAmbient[0]).putFloat(lastAmbient[1]).putFloat(lastAmbient[2]).putFloat(fogOn ? fogDist : 0f);
        push.putInt(SKY_W).putInt(SKY_H).putInt(shadowsOn ? 1 : 0).putInt(0);
        push.flip();
        gpu.dispatch(push);
        renderedBuild = b;
    }

    private void publish(RemixAPI api, ByteBuffer pixels) {
        int bytes = width * (width / 2) * 8;
        pixels.position(0).limit(bytes);
        if (texture == null || !texture.isAlive()) {
            if (texture != null && !texture.isDeleted()) texture.destroy();
            ByteBuffer owned = DirectBufferPool.acquire(bytes);
            owned.put(pixels);
            owned.flip();
            texture = RemixTextureStore.get().create(new RemixTexture(width, width / 2, TextureFormat.RGBA16_SFLOAT, 0, owned));
        } else {
            patch.pixelData = pixels;
            entry.handle = texture.getHandle();
            api.updateTextureFromMemory(update);
        }
    }

    private void close() {
        synchronized (completionLock) {
            closed = true; session++;
            if (source != null) { source.close(); previousCacheFlush = source.flushed(); }
            // Running workers observe closed and release their transient handles themselves.
            pool.shutdown();
            for (Job job : java.util.List.copyOf(jobs)) if (job.stage != Stage.WORKER) { releaseJob(job); jobs.remove(job); }
            Event event;
            while ((event = events.poll()) != null) { releaseJob(event.job); jobs.remove(event.job); }
        }
        gpu.close();
        DirectBufferPool.release(exactMessage);
        if (texture != null && !texture.isDeleted()) texture.destroy();
    }
}
