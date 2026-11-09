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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Far-field environment (Vulkan only, default OFF, setting "Far field"): terrain beyond the path-traced radius is kept as a sparse
 * voxel DAG, ray marched by a Vulkan compute shader into an equirect RGBA16F image over the procedural sky, read back and published
 * to Remix as the dome light texture (the dome the atmospheric sky uses, so Remix lights the near field with it and DLSS / frame
 * generation are unaffected). Re-rendered only when the camera moved, the far data changed or the sky changed.
 *
 * <p>Data: the world is cut into 256 x 256 block columns ({@link FarSubtree}), each its own sub-DAG built on a worker thread and
 * cached (only columns whose input signature changed are rebuilt) and merged into one pool ({@link FarFieldDag}) by a merge task. A
 * column's input is, per 16 x 16 chunk: a client chunk inside the path-traced radius (hole, drawn by Remix), a client chunk
 * beyond it (exact 1-block voxels), or no chunk (LOD height + colour grid from the integrated server, {@link FarLodSource}, with
 * 4 / 8 / 16 block cells by distance). So the far field starts beyond whatever the client really has loaded.
 *
 * <p>Settings (mod settings screen, persisted) and the optional file {@code rtxremix-farfield} / -Drtxremix.farfield=... with lines
 * {@code radius=0} (path-traced chunks, 0 = vanilla render distance), {@code distance=256} (far field chunks, up to 512),
 * {@code fog=0|1} (aerial perspective, default off), {@code fogDist=4000}, {@code shadows=1}, {@code width=2048} (equirect width,
 * height = width/2), {@code cut=1} (hide captured terrain beyond the radius), {@code sunBoost=1}, {@code lodThreads=2},
 * {@code buildThreads=3}. Render thread only unless noted.
 */
public final class FarField {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarField.class);
    private static final int SKY_W = 256, SKY_H = 128;
    public static final int MAX_DISTANCE_CHUNKS = 512;
    public static final int MAX_RADIUS_CHUNKS = 64;

    private static volatile FarField instance;
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

    // ---------------------------------------------------------------------------------------------------------

    /** Merged DAG: {@code nodes[0 .. words)} valid. */
    private record Build(int[] nodes, int words, int root, int levels, int originSx, int originSz, int minY,
                         int subtrees, int nodeCount, double mergeMs) {}

    private record Entry(long sig, FarSubtree.Result res) {}

    /** Loaded client chunks of one column, filled on the render thread. */
    private static final class ColumnIn {
        final LevelChunk[] chunks = new LevelChunk[256];
        final byte[] cls = new byte[256]; // 1 = hole (inside the path-traced radius), 2 = exact
        long sig;
        int loaded;
    }

    private final FarFieldGpu gpu;
    private final ByteBuffer skyCopy = ByteBuffer.allocateDirect(SKY_W * SKY_H * 8).order(ByteOrder.nativeOrder());
    private final ByteBuffer push = ByteBuffer.allocateDirect(FarFieldGpu.PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final AtomicReference<Build> built = new AtomicReference<>();
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> inProgress = new ConcurrentHashMap<>();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger epoch = new AtomicInteger();
    private final AtomicLong taskNanos = new AtomicLong();
    private final AtomicInteger tasksDone = new AtomicInteger();
    private final java.util.concurrent.ThreadPoolExecutor pool;
    private volatile boolean merging;
    private int mergedEpoch = -1, mergedKeyHash;
    private long lastMergeMs, lastPlanMs, lastStatMs;

    private Object levelKey;
    private int lastFakeCount;
    private FarTileSource source;
    /** Loaded real chunks handed to the multiplayer cache (render thread): position -> chunk object recorded last. */
    private final HashMap<Long, LevelChunk> tracked = new HashMap<>();
    private int camChunkX = Integer.MIN_VALUE, camChunkZ = Integer.MIN_VALUE;
    private volatile int lastRadius = 6;
    private Build current, uploading;
    private boolean skyDirty, hasSky, dagDirty;
    private long lastRenderMs;
    private Vec3 lastRenderCam;
    private int minY, worldHeight;
    private long[] wantedKeys = new long[0];
    private int wantedLevels, wantedOriginSx, wantedOriginSz;
    private volatile boolean wantedChanged;

    private RemixTexture texture;
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo update = new TexturePatchInfo();
    private SkyLightState lastSky;
    private float[] lastAmbient = {0.1f, 0.1f, 0.1f};
    private double lastRenderGpuMs;
    private long renders;

    private FarField() {
        gpu = new FarFieldGpu(width, width / 2);
        pool = new java.util.concurrent.ThreadPoolExecutor(buildThreads, buildThreads, 0L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "RemixFarFieldBuild");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        patch.width = width;
        patch.height = width / 2;
        patch.rowPitch = width * 8;
        patch.format = TextureFormat.RGBA16_SFLOAT;
        entry.patches = new TexturePatch[]{patch};
        update.entries = new TextureBatchEntry[]{entry};
        LOGGER.info("FarField: Vulkan pipeline ready, {}x{} RGBA16F ({} MiB), {} build threads", width, width / 2,
                (long) width * width / 2 * 8 >> 20, buildThreads);
    }

    /** Remix handle of the far-field dome texture, 0 until the first render finished. */
    public long textureHandle() {
        return texture != null && texture.isAlive() ? texture.getHandle() : 0L;
    }

    /** Offers the freshly generated procedural sky (256x128 RGBA16F, rows top first); copied. */
    public void offerSky(ByteBuffer pixels) {
        ByteBuffer src = pixels.duplicate();
        src.position(0).limit(Math.min(src.capacity(), skyCopy.capacity()));
        boolean same = hasSky && skyCopy.limit() == src.remaining() && skyCopy.mismatch(src.duplicate()) < 0;
        if (same) return;
        skyCopy.clear();
        skyCopy.put(src).flip();
        hasSky = true;
        skyDirty = true;
    }

    /** The path-traced radius in chunks: the setting, or Minecraft's render distance when 0. */
    private static int ptRadius() {
        int r = radius;
        return r > 0 ? r : Math.max(2, Minecraft.getInstance().options.renderDistance().get());
    }

    public void tick(RemixAPI api, ClientLevel level, Vec3 cam, SkyLightState sky, float[] ambientMean) {
        lastSky = sky;
        lastAmbient = ambientMean;
        camChunkX = Math.floorDiv((int) Math.floor(cam.x), 16);
        camChunkZ = Math.floorDiv((int) Math.floor(cam.z), 16);
        lastRadius = ptRadius();
        long now = System.currentTimeMillis();
        if (levelKey != level) newLevel(level);

        // 1. finished readback -> Remix
        ByteBuffer pixels = gpu.poll();
        if (pixels != null) {
            publish(api, pixels);
            renders++;
            lastRenderGpuMs = gpu.lastGpuMs();
            if (renders <= 5 || renders % 20 == 0) {
                LOGGER.info("FarField render #{}: gpu {} ms (wall to fence {} ms), dag {} KiB", renders,
                        String.format("%.2f", lastRenderGpuMs), String.format("%.2f", gpu.lastFlightMs()), gpu.dagBytes() >> 10);
            }
        }

        // 2. new merged DAG -> GPU: sliced copy into the spare buffer (<= 1M words = 4 MiB per frame), swap while idle
        Build b = built.get();
        if (b != null && b != current) {
            if (uploading != b) {
                gpu.beginUpload(b.words);
                uploading = b;
            }
            boolean done = gpu.uploadSlice(b.nodes, 1 << 20);
            if (done && !gpu.inFlight()) {
                gpu.commitUpload();
                current = b;
                uploading = null;
                dagDirty = true;
            }
        }

        // 3. plan sub-DAG builds / merge
        if (now - lastPlanMs > 500) {
            lastPlanMs = now;
            plan(level, cam);
        }
        if (now - lastStatMs > 10_000 && source != null && (source.pending() > 0 || pendingTasks.get() > 0)) {
            lastStatMs = now;
            LOGGER.info("FarField progress: lod tiles generated {} (disk {}, pending {}), subtree tasks pending {}, cached subtrees {}",
                    source.generatedCount(), source.diskCount(), source.pending(), pendingTasks.get(), cache.size());
        }

        // 4. render when something changed
        if (current != null && !gpu.inFlight() && hasSky && now - lastRenderMs > 1000) {
            double thr = Math.max(8.0, lastRadius * 16 / 12.0);
            boolean moved = lastRenderCam == null || lastRenderCam.distanceToSqr(cam) > thr * thr;
            if (dagDirty || moved || skyDirty) {
                render(cam);
                lastRenderMs = now;
                lastRenderCam = cam;
                dagDirty = false;
                skyDirty = false;
            }
        }
    }

    private void newLevel(ClientLevel level) {
        levelKey = level;
        cache.clear();
        built.set(null);
        current = null;
        uploading = null;
        mergedEpoch = -1;
        if (source != null) source.close();
        source = null;
        minY = level.getMinY();
        worldHeight = level.getHeight();
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        tracked.clear();
        if (server == null) {
            if (!cacheOn) {
                LOGGER.info("FarField: no integrated server and the chunk cache is off, only loaded chunks");
                return;
            }
            try {
                var current = Minecraft.getInstance().getCurrentServer();
                String name = current == null ? "unknown" : current.ip;
                name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                Path dir = FabricLoader.getInstance().getGameDir().resolve("rtxremix-farcache").resolve(name)
                        .resolve(level.dimension().identifier().toString().replace(':', '_').replace('/', '_'));
                source = new FarCacheSource(dir, cacheMb, minY);
                LOGGER.info("FarField: multiplayer chunk cache {} (limit {} MB){}", dir, cacheMb,
                        BobbyCompat.isPresent() ? ", Bobby fake chunks are read as well" : "");
            } catch (RuntimeException | Error e) {
                LOGGER.error("FarField: chunk cache init failed", e);
            }
            return;
        }
        ServerLevel sl = server.getLevel(level.dimension());
        if (sl == null) return;
        try {
            String world = server.getWorldData().getLevelName().replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = FabricLoader.getInstance().getGameDir().resolve("rtxremix-farlod")
                    .resolve(world + "-" + Long.toHexString(sl.getSeed()))
                    .resolve(level.dimension().identifier().toString().replace(':', '_').replace('/', '_'));
            source = new FarLodSource(sl, dir, lodThreads);
            LOGGER.info("FarField: LOD source from the integrated server, cache {}", dir);
        } catch (RuntimeException | Error e) {
            LOGGER.error("FarField: LOD source init failed", e);
        }
    }

    private static long subKey(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }

    private void plan(ClientLevel level, Vec3 cam) {
        int R = lastRadius;
        int rd = Minecraft.getInstance().options.renderDistance().get();
        int D = Math.max(distance, R + 8);
        int csx = Math.floorDiv((int) Math.floor(cam.x), 256), csz = Math.floorDiv((int) Math.floor(cam.z), 256);
        int span = (D * 16 + 255) / 256 + 1;
        int levels = 9;
        while ((1 << levels) < (2 * span + 2) * 256) levels++;
        int cubeSub = 1 << (levels - 8);
        int originSx = csx - cubeSub / 2, originSz = csz - cubeSub / 2;
        if (source != null) source.setCenter((int) cam.x, (int) cam.z);

        // loaded client chunks (Bobby's fake chunks included), grouped per column; real ones are also offered to the multiplayer cache
        HashMap<Long, ColumnIn> loaded = new HashMap<>();
        int lm = rd + 2;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        int recordBudget = 128;
        for (int dx = -lm; dx <= lm; dx++) {
            for (int dz = -lm; dz <= lm; dz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(camChunkX + dx, camChunkZ + dz);
                if (c == null) continue;
                recordBudget = addLoaded(loaded, seen, c, camChunkX + dx, camChunkZ + dz, R, recordBudget);
            }
        }
        if (BobbyCompat.isPresent()) {
            List<LevelChunk> fakes = BobbyCompat.fakeChunks(level);
            if (Math.abs(fakes.size() - lastFakeCount) >= 25) {
                lastFakeCount = fakes.size();
                LOGGER.info("FarField: {} Bobby fake chunks", fakes.size());
            }
            for (LevelChunk c : fakes) {
                int cx = c.getPos().x(), cz = c.getPos().z();
                if (!seen.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) continue;
                addLoaded(loaded, seen, c, cx, cz, R, recordBudget);
            }
        }
        if (source instanceof FarCacheSource cs) {
            // chunks that left the client: record their last state once more
            var it = tracked.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!seen.contains(e.getKey()) && cs.record(e.getValue())) it.remove();
            }
        }

        // wanted columns, nearest first
        int total = (2 * span + 1) * (2 * span + 1);
        long[] order = new long[total];
        int cnt = 0;
        for (int sz = csz - span; sz <= csz + span; sz++) {
            for (int sx = csx - span; sx <= csx + span; sx++) {
                int dChunks = Math.max(Math.abs(sx * 16 + 8 - camChunkX), Math.abs(sz * 16 + 8 - camChunkZ));
                if (dChunks - 8 > D) continue;
                order[cnt++] = ((long) dChunks << 40) | ((long) (sx - csx + span) << 20) | (sz - csz + span);
            }
        }
        java.util.Arrays.sort(order, 0, cnt);
        long[] wanted = new long[cnt];
        int budget = buildThreads * 16 - pendingTasks.get();
        int slabs = (worldHeight + 255) / 256;
        for (int i = 0; i < cnt; i++) {
            long o = order[i];
            int dChunks = (int) (o >>> 40);
            int sx = (int) ((o >> 20) & 0xFFFFF) - span + csx, sz = (int) (o & 0xFFFFF) - span + csz;
            long key = subKey(sx, sz);
            wanted[i] = key;
            ColumnIn in = loaded.get(key);
            int lgUsed = -1;
            FarLodSource.Tile tile = null;
            if (source != null && (in == null || in.loaded < 256)) {
                int desired = dChunks < 64 ? 2 : dChunks < 192 ? 3 : 4;
                int tz;
                for (int lg = desired; lg <= FarLodSource.MAX_LG && tile == null; lg++) {
                    int tx0 = Math.floorDiv(sx * 256, FarLodSource.TILE_CELLS << lg);
                    tz = Math.floorDiv(sz * 256, FarLodSource.TILE_CELLS << lg);
                    if (lg == desired) tile = source.get(lg, tx0, tz);
                    else if (source.has(lg, tx0, tz)) tile = source.get(lg, tx0, tz);
                    if (tile != null) lgUsed = lg;
                }
                if (tile == null) {
                    source.get(FarLodSource.MAX_LG, Math.floorDiv(sx * 256, FarLodSource.TILE_CELLS << FarLodSource.MAX_LG),
                            Math.floorDiv(sz * 256, FarLodSource.TILE_CELLS << FarLodSource.MAX_LG));
                }
            }
            long sig = (lgUsed + 2L) * 0x100000001B3L ^ (in == null ? 0L : in.sig) ^ ((long) (in == null ? 0 : in.loaded) << 50)
                    ^ (tile == null ? 0L : 0x5555L + tile.version * 0x9E3779B97F4A7C15L);
            Entry e = cache.get(key);
            if (e != null && e.sig == sig) continue;
            if (budget <= 0 || inProgress.putIfAbsent(key, Boolean.TRUE) != null) continue;
            budget--;
            pendingTasks.incrementAndGet();
            final int fsx = sx, fsz = sz, flg = lgUsed;
            final FarLodSource.Tile ftile = tile;
            final ColumnIn fin = in;
            final long fsig = sig;
            final int fMinY = minY;
            pool.execute(() -> {
                long t0 = System.nanoTime();
                try {
                    FarSubtree.Result r = buildSubtree(fsx, fsz, flg, ftile, fin, fMinY, slabs);
                    cache.put(key, new Entry(fsig, r));
                    epoch.incrementAndGet();
                } catch (Throwable t) {
                    LOGGER.error("FarField subtree {},{} failed", fsx, fsz, t);
                    cache.put(key, new Entry(fsig, new FarSubtree.Result(new int[8], 1, new int[slabs], 0)));
                } finally {
                    inProgress.remove(key);
                    pendingTasks.decrementAndGet();
                    taskNanos.addAndGet(System.nanoTime() - t0);
                    tasksDone.incrementAndGet();
                }
            });
        }
        int keyHash = java.util.Arrays.hashCode(wanted) * 31 + levels * 7 + originSx * 13 + originSz;
        if (keyHash != mergedKeyHash) {
            wantedChanged = true;
            mergedKeyHash = keyHash;
            // drop cached columns no longer wanted
            java.util.HashSet<Long> keep = new java.util.HashSet<>(cnt * 2);
            for (long k : wanted) keep.add(k);
            cache.keySet().removeIf(k -> !keep.contains(k));
            if (source != null) source.trim((D + 64) * 16);
        }
        wantedKeys = wanted;
        wantedLevels = levels;
        wantedOriginSx = originSx;
        wantedOriginSz = originSz;

        long now = System.currentTimeMillis();
        if (!merging && (wantedChanged || epoch.get() != mergedEpoch) && now - lastMergeMs > 400 && epoch.get() > 0) {
            wantedChanged = false;
            mergedEpoch = epoch.get();
            lastMergeMs = now;
            merging = true;
            final long[] fw = wanted;
            final int fl = levels, fox = originSx, foz = originSz, fy = minY, fslabs = slabs;
            pool.execute(() -> {
                try {
                    built.set(merge(fw, fl, fox, foz, fy, fslabs));
                } catch (Throwable t) {
                    LOGGER.error("FarField merge failed", t);
                } finally {
                    merging = false;
                }
            });
        }
    }

    /** Adds one client chunk to its column; real chunks go to the multiplayer cache (at most {@code recordBudget} per call). */
    private int addLoaded(HashMap<Long, ColumnIn> loaded, java.util.Set<Long> seen, LevelChunk c, int cx, int cz, int R, int recordBudget) {
        long pk = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        seen.add(pk);
        long k = subKey(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16));
        ColumnIn in = loaded.computeIfAbsent(k, x -> new ColumnIn());
        int slot = (Math.floorMod(cz, 16) << 4) | Math.floorMod(cx, 16);
        int d = Math.max(Math.abs(cx - camChunkX), Math.abs(cz - camChunkZ));
        in.chunks[slot] = c;
        in.cls[slot] = (byte) (d < R - 1 ? 1 : 2);
        in.loaded++;
        in.sig = in.sig * 1000003L + slot * 4 + in.cls[slot] + 0x9E3779B9L * System.identityHashCode(c);
        if (source instanceof FarCacheSource cs && recordBudget > 0 && !BobbyCompat.isFake(c) && tracked.get(pk) != c) {
            if (cs.record(c)) {
                tracked.put(pk, c);
                recordBudget--;
            }
        }
        return recordBudget;
    }

    private Build merge(long[] wanted, int levels, int originSx, int originSz, int minY, int slabs) {
        long t0 = System.nanoTime();
        FarFieldDag dag = new FarFieldDag();
        HashMap<Long, int[]> roots = new HashMap<>();
        int present = 0;
        for (long k : wanted) {
            Entry e = cache.get(k);
            if (e == null || e.res.nodeCount() <= 1 && allZero(e.res.roots())) continue;
            int off = dag.append(e.res.nodes(), e.res.nodeCount());
            int[] r = new int[slabs];
            for (int i = 0; i < slabs; i++) {
                int v = e.res.roots()[i];
                r[i] = (v == 0 || (v & FarFieldDag.LEAF) != 0) ? v : v + off;
            }
            roots.put(k, r);
            present++;
        }
        int root = top(dag, roots, levels, 0, 0, 0, originSx, originSz, slabs);
        double ms = (System.nanoTime() - t0) / 1e6;
        int words = dag.nodeCount() * 8;
        long tasks = Math.max(1, tasksDone.get());
        LOGGER.info("FarField merge: {} subtrees, {} nodes ({} MiB), cube {} blocks, merge {} ms (avg subtree build {} ms over {} tasks)",
                present, dag.nodeCount(), (long) words * 4 >> 20, 1 << levels, String.format("%.1f", ms),
                String.format("%.1f", taskNanos.get() / 1e6 / tasks), tasks);
        return new Build(dag.rawNodes(), words, root, levels, originSx, originSz, minY, present, dag.nodeCount(), ms);
    }

    private static boolean allZero(int[] a) {
        for (int v : a) if (v != 0) return false;
        return true;
    }

    /** Top of the tree over sub-DAG roots; (ix, iy, iz) in 256-block cells relative to the cube origin. */
    private static int top(FarFieldDag dag, HashMap<Long, int[]> roots, int level, int ix, int iy, int iz, int osx, int osz, int slabs) {
        if (level == FarSubtree.SIZE_LEVEL) {
            if (iy >= slabs) return 0;
            int[] r = roots.get(subKey(osx + ix, osz + iz));
            return r == null ? 0 : r[iy];
        }
        int half = 1 << (level - 1 - FarSubtree.SIZE_LEVEL);
        int[] refs = new int[8];
        boolean any = false;
        for (int i = 0; i < 8; i++) {
            int cy = iy + ((i >> 1) & 1) * half;
            if (cy >= slabs) { refs[i] = 0; continue; }
            refs[i] = top(dag, roots, level - 1, ix + (i & 1) * half, cy, iz + ((i >> 2) & 1) * half, osx, osz, slabs);
            any |= refs[i] != 0;
        }
        return any ? dag.make(refs) : 0;
    }

    // ---- one 256 x 256 column on a worker thread ----

    private FarSubtree.Result buildSubtree(int sx, int sz, int lg, FarLodSource.Tile tile, ColumnIn in, int minY, int slabs) {
        int useLg = lg < 0 ? 4 : lg;
        int n = 256 >> useLg;
        short[] top = null, rgb = null;
        if (tile != null) {
            top = new short[n * n];
            rgb = new short[n * n];
            int tileBlocks = FarLodSource.TILE_CELLS << lg;
            int ox = (Math.floorMod(sx * 256, tileBlocks)) >> lg, oz = (Math.floorMod(sz * 256, tileBlocks)) >> lg;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int src = (oz + j) * FarLodSource.TILE_CELLS + ox + i;
                    short t = tile.top[src];
                    top[j * n + i] = t == FarLodSource.EMPTY ? t : (short) (t - minY);
                    rgb[j * n + i] = tile.rgb[src];
                }
            }
        }
        short[][][] exact = null;
        if (in != null) {
            exact = new short[256][][];
            int cells = Math.max(1, 16 >> useLg);
            for (int slot = 0; slot < 256; slot++) {
                if (in.cls[slot] == 0) continue;
                int cx = slot & 15, cz = slot >> 4;
                // holes (client chunks inside the path-traced radius) keep the coarse LOD terrain as a backdrop (seen only where the
                // path-traced chunk is not meshed yet); exact chunks replace it with their own voxels
                if (top != null && in.cls[slot] == 2) {
                    for (int j = 0; j < cells; j++) {
                        for (int i = 0; i < cells; i++) {
                            int ci = (cx * 16 >> useLg) + i, cj = (cz * 16 >> useLg) + j;
                            top[cj * n + ci] = FarLodSource.EMPTY;
                        }
                    }
                }
                if (in.cls[slot] == 2) {
                    try {
                        exact[slot] = voxelizeChunk(in.chunks[slot]);
                    } catch (RuntimeException e) {
                        exact[slot] = null; // chunk changed under us; the next signature change rebuilds it
                    }
                    if (exact[slot] == null) exact[slot] = new short[0][];
                }
            }
        }
        return FarSubtree.build(useLg, top, rgb, exact, slabs);
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

    /** 1-block RGB565 voxels of a client chunk: sections indexed from the world bottom, null = air, length 1 = uniform. */
    private static short[][] voxelizeChunk(LevelChunk chunk) {
        Biome biome = chunk.getNoiseBiome(chunk.getPos().getMiddleBlockX() >> 2, 64 >> 2, chunk.getPos().getMiddleBlockZ() >> 2).value();
        int grass = FarLodSource.scale(biome.getGrassColor(chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ()), 0.85f);
        int foliage = FarLodSource.scale(biome.getFoliageColor(), 0.8f);
        int water = FarLodSource.scale(biome.getWaterColor(), 0.85f);
        IdentityHashMap<BlockState, Short> cache = new IdentityHashMap<>();
        LevelChunkSection[] secs = chunk.getSections();
        short[][] out = new short[secs.length][];
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection sec = secs[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            short[] data = new short[4096];
            boolean uniform = true;
            short first = 0;
            int solid = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        Short m = cache.get(st);
                        if (m == null) {
                            int c = classify(st);
                            int rgb = c & 0xFFFFFF;
                            if (c != 0) {
                                switch ((c >> 24) & 0x7F) {
                                    case KIND_GRASS -> rgb = grass;
                                    case KIND_FOLIAGE -> rgb = foliage;
                                    case KIND_WATER -> rgb = water;
                                    default -> { }
                                }
                            }
                            m = c == 0 ? (short) 0 : FarLodSource.rgb565(rgb);
                            cache.put(st, m);
                        }
                        int idx = (y << 8) | (z << 4) | x;
                        data[idx] = m;
                        if (idx == 0) first = m;
                        else if (m != first) uniform = false;
                        if (m != 0) solid++;
                    }
                }
            }
            if (solid == 0) continue;
            out[i] = uniform ? new short[]{first} : data;
        }
        return out;
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
        pool.shutdownNow();
        if (source != null) source.close();
        gpu.close();
        if (texture != null && !texture.isDeleted()) texture.destroy();
    }
}
