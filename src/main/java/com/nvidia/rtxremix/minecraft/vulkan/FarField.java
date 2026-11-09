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
import net.minecraft.world.level.EmptyBlockGetter;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Far-field environment (prototype, default OFF): terrain beyond the path-traced radius is voxelised on a worker thread into a
 * sparse voxel DAG, ray marched by a Vulkan compute shader into an equirect RGBA16F image over the procedural sky, read back
 * and published to Remix as the dome light texture (the same dome the atmospheric sky uses, so Remix lights the near field with
 * it and DLSS / frame generation are unaffected). Re-rendered only when the camera moved, the far data changed or the sky changed.
 *
 * <p>Enabled by a file {@code rtxremix-farfield} in the game directory (or -Drtxremix.farfield=...) with lines
 * {@code radius=6} (path-traced radius in chunks), {@code width=2048} (equirect width, height = width/2), {@code fog=4000}
 * (aerial perspective distance in blocks), {@code hideNear=1} (debug: keep captured terrain beyond the radius hidden so the far
 * field is visible), {@code sunBoost=1}. Vulkan backend only. Render thread only unless noted.
 */
public final class FarField {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarField.class);
    private static final int SKY_W = 256, SKY_H = 128;

    private static volatile FarField instance;
    private static boolean configRead;
    private static boolean enabled;
    private static int radius = 6, width = 2048, hideNear = 0;
    private static float fog = 4000f, sunBoost = 1f;

    public static boolean isEnabled() {
        readConfig();
        return enabled;
    }

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
                    case "radius" -> radius = Integer.parseInt(v);
                    case "width" -> width = Integer.parseInt(v);
                    case "fog" -> fog = Float.parseFloat(v);
                    case "hideNear" -> hideNear = Integer.parseInt(v);
                    case "sunBoost" -> sunBoost = Float.parseFloat(v);
                    default -> LOGGER.warn("FarField: unknown key {}", k);
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("FarField: bad value {}", line);
            }
        }
        LOGGER.info("FarField enabled: radius={} chunks width={} fog={} hideNear={} sunBoost={}", radius, width, fog, hideNear, sunBoost);
    }

    /** The prototype's single instance, created on first use; null when disabled or not on the Vulkan backend. */
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

    /** Debug cut: true when the captured terrain of this column must stay hidden because the far field draws it. */
    public static boolean cutsColumn(int cx, int cz) {
        readConfig();
        if (!enabled || hideNear == 0) return false;
        FarField f = instance;
        if (f == null) return false;
        return Math.max(Math.abs(cx - f.camChunkX), Math.abs(cz - f.camChunkZ)) >= radius;
    }

    public static void shutdown() {
        FarField f = instance;
        instance = null;
        if (f != null) f.close();
    }

    // ---------------------------------------------------------------------------------------------------------

    private record Build(FarFieldDag.Result dag, int originCx, int originCz, int minY, int chunks, int centerCx, int centerCz,
                         double voxelMs, double dagMs) {}

    private final FarFieldGpu gpu;
    private final int[] palette = new int[FarFieldGpu.PALETTE_WORDS];
    private final ByteBuffer skyCopy = ByteBuffer.allocateDirect(SKY_W * SKY_H * 8).order(ByteOrder.nativeOrder());
    private final ByteBuffer push = ByteBuffer.allocateDirect(FarFieldGpu.PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final AtomicReference<Build> built = new AtomicReference<>();
    private volatile boolean building;
    private Thread worker;

    private int camChunkX = Integer.MIN_VALUE, camChunkZ = Integer.MIN_VALUE;
    private Build current;
    private boolean skyDirty, hasSky, dagDirty;
    private int builtChunks, builtCenterX, builtCenterZ;
    private long lastBuildMs, lastRenderMs, lastCountMs;
    private Vec3 lastRenderCam;
    private boolean skyUploaded;
    private int frames;

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
        for (int id = 0; id < 64; id++) {
            MapColor c = MapColor.byId(id);
            palette[id + 1] = c == null ? 0 : (c.col & 0xFFFFFF);
        }
        patch.width = width;
        patch.height = width / 2;
        patch.rowPitch = width * 8;
        patch.format = TextureFormat.RGBA16_SFLOAT;
        entry.patches = new TexturePatch[]{patch};
        update.entries = new TextureBatchEntry[]{entry};
        LOGGER.info("FarField: Vulkan pipeline ready, {}x{} RGBA16F ({} MiB)", width, width / 2, (long) width * width / 2 * 8 >> 20);
    }

    /** Remix handle of the far-field dome texture, 0 until the first render finished. */
    public long textureHandle() {
        return texture != null && texture.isAlive() ? texture.getHandle() : 0L;
    }

    /** Offers the freshly generated procedural sky (256x128 RGBA16F, rows top first); copied. */
    public void offerSky(ByteBuffer pixels) {
        ByteBuffer src = pixels.duplicate();
        src.position(0).limit(Math.min(src.capacity(), skyCopy.capacity()));
        skyCopy.clear();
        skyCopy.put(src).flip();
        hasSky = true;
        skyDirty = true;
    }

    public void tick(RemixAPI api, ClientLevel level, Vec3 cam, SkyLightState sky, float[] ambientMean) {
        frames++;
        lastSky = sky;
        lastAmbient = ambientMean;
        camChunkX = Math.floorDiv((int) Math.floor(cam.x), 16);
        camChunkZ = Math.floorDiv((int) Math.floor(cam.z), 16);
        long now = System.currentTimeMillis();

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

        // 2. new DAG from the worker
        Build b = built.get();
        if (b != null && !gpu.inFlight() && b != current) {
            gpu.uploadDag(b.dag.nodes(), b.dag.nodeCount() * 8);
            current = b;
            dagDirty = true;
            builtChunks = b.chunks;
            builtCenterX = b.centerCx;
            builtCenterZ = b.centerCz;
        }

        // 3. start a rebuild when the camera moved or more far chunks arrived
        if (!building && now - lastBuildMs > 1500) {
            boolean need = current == null && built.get() == null;
            if (!need) {
                int moved = Math.max(Math.abs(camChunkX - builtCenterX), Math.abs(camChunkZ - builtCenterZ));
                if (moved >= Math.max(2, radius / 2)) need = true;
                if (!need && now - lastCountMs > 1000) {
                    lastCountMs = now;
                    if (countFarChunks(level) > builtChunks + Math.max(8, builtChunks / 10)) need = true;
                }
            }
            if (need) startBuild(level);
        }

        // 4. render when something changed
        if (current != null && !gpu.inFlight() && hasSky && now - lastRenderMs > 1000) {
            boolean moved = lastRenderCam == null || lastRenderCam.distanceToSqr(cam) > Math.pow(Math.max(4.0, radius * 16 / 12.0), 2);
            if (dagDirty || moved || skyDirty) {
                render(cam);
                lastRenderMs = now;
                lastRenderCam = cam;
                dagDirty = false;
                skyDirty = false;
            }
        }
    }

    private int countFarChunks(ClientLevel level) {
        int max = Minecraft.getInstance().options.renderDistance().get() + 1;
        int n = 0;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) >= radius - 1
                        && level.getChunkSource().getChunkNow(camChunkX + dx, camChunkZ + dz) != null) n++;
            }
        }
        return n;
    }

    private void startBuild(ClientLevel level) {
        int max = Minecraft.getInstance().options.renderDistance().get() + 1;
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < radius - 1) continue;
                LevelChunk c = level.getChunkSource().getChunkNow(camChunkX + dx, camChunkZ + dz);
                if (c != null) chunks.add(c);
            }
        }
        int minSectionY = level.getMinSectionY();
        int minY = level.getMinY();
        int cx = camChunkX, cz = camChunkZ;
        building = true;
        lastBuildMs = System.currentTimeMillis();
        worker = new Thread(() -> {
            try {
                built.set(voxelize(chunks, cx, cz, max, minSectionY, minY));
            } catch (Throwable t) {
                LOGGER.error("FarField build failed", t);
            } finally {
                building = false;
            }
        }, "RemixFarFieldBuild");
        worker.setDaemon(true);
        worker.start();
    }

    private static Build voxelize(List<LevelChunk> chunks, int cx, int cz, int maxChunks, int minSectionY, int minY) {
        long t0 = System.nanoTime();
        int levels = 9; // 512 blocks covers the world height
        while ((1 << levels) < (2 * maxChunks + 4) * 16) levels++;
        int sectionsAcross = (1 << levels) >> 4;
        int originCx = cx - sectionsAcross / 2, originCz = cz - sectionsAcross / 2;
        IdentityHashMap<BlockState, Byte> materials = new IdentityHashMap<>();
        Map<Long, byte[]> sections = new HashMap<>();
        for (LevelChunk chunk : chunks) {
            int sx = chunk.getPos().x() - originCx, sz = chunk.getPos().z() - originCz;
            if (sx < 0 || sz < 0 || sx >= sectionsAcross || sz >= sectionsAcross) continue;
            LevelChunkSection[] secs = chunk.getSections();
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection sec = secs[i];
                if (sec == null || sec.hasOnlyAir()) continue;
                byte[] data = new byte[4096];
                boolean uniform = true;
                byte first = 0;
                int solid = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = sec.getBlockState(x, y, z);
                            Byte m = materials.get(st);
                            if (m == null) {
                                m = material(st);
                                materials.put(st, m);
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
                int sy = i; // section index == offset from the world's minimum section
                sections.put(FarFieldDag.key(sx, sy, sz), uniform ? new byte[]{first} : data);
            }
        }
        long t1 = System.nanoTime();
        FarFieldDag.Result dag = FarFieldDag.build(sections, levels);
        long t2 = System.nanoTime();
        LOGGER.info("FarField build: {} chunks, {} sections, {} solid voxels -> {} DAG nodes ({} KiB), cube {} blocks; voxelize {} ms, DAG {} ms",
                chunks.size(), dag.solidSections(), dag.nonEmptyVoxels(), dag.uniqueNodes(), dag.nodeCount() * 32L >> 10, 1 << levels,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        return new Build(dag, originCx, originCz, minY, chunks.size(), cx, cz, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
    }

    private static byte material(BlockState st) {
        if (st.isAir()) return 0;
        MapColor color = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (color == MapColor.NONE) return 0;
        if (st.getFluidState().isEmpty()
                && st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return 0;
        return (byte) (color.id + 1);
    }

    private void render(Vec3 cam) {
        FarFieldGpu.Buf aux = gpu.aux();
        aux.mapped.clear();
        aux.mapped.asIntBuffer().put(palette);
        aux.mapped.position(FarFieldGpu.PALETTE_WORDS * 4);
        skyCopy.rewind();
        aux.mapped.put(skyCopy);
        aux.mapped.clear();
        gpu.vmaFlush(aux);

        Build b = current;
        float cubeSize = 1 << b.dag.levels();
        double ox = b.originCx * 16.0, oz = b.originCz * 16.0;
        boolean day = lastSky.sunPosition()[1] > -0.05f;
        float[] dir = day ? lastSky.sunPosition() : lastSky.moonPosition();
        float[] rad = day ? lastSky.sunDirectRadiance() : lastSky.moonDirectRadiance();
        float r = day ? lastSky.sunAngularRadius() : lastSky.moonAngularRadius();
        float e = r * r * sunBoost;
        push.clear();
        push.putFloat((float) (cam.x - ox)).putFloat((float) (cam.y - b.minY)).putFloat((float) (cam.z - oz)).putFloat(cubeSize);
        push.putInt(b.dag.root()).putInt(b.dag.levels()).putInt(gpu.width()).putInt(gpu.height());
        push.putFloat(dir[0]).putFloat(dir[1]).putFloat(dir[2]).putFloat(0f);
        push.putFloat(rad[0] * e).putFloat(rad[1] * e).putFloat(rad[2] * e).putFloat(0f);
        push.putFloat(lastAmbient[0]).putFloat(lastAmbient[1]).putFloat(lastAmbient[2]).putFloat(fog);
        push.putInt(SKY_W).putInt(SKY_H).putInt(0).putInt(0);
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
        gpu.close();
        if (texture != null && !texture.isDeleted()) texture.destroy();
    }
}
