/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.joml.Vector3fc;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-block mesh emission for RTX Remix, designed to be driven from
 * {@link com.nvidia.rtxremix.minecraft.mixin.SectionBuilderMixin} redirects
 * that piggyback on MC's own block iteration loop.
 *
 * <p>Lifecycle per section:
 * <ol>
 *   <li>{@link #beginSection}: reset state, called at HEAD of build()</li>
 *   <li>{@link #processBlock}: opacity/masks/lights, called per block from the RenderSectionRegion.getBlockState redirect</li>
 *   <li>{@link #emitBlock}: block model quads for MODEL blocks, called from the same
 *       getBlockState redirect; vanilla's tesselateBlock never runs because captured
 *       blocks are reported invisible</li>
 *   <li>{@link #emitFluid}: fluid quads, called from renderFluid redirect</li>
 *   <li>{@link #finishSection}: build SectionBlockData, called at RETURN</li>
 * </ol>
 *
 * <p>One instance per worker thread via {@code ThreadLocal}.
 */
public final class BlockQuadEmitter {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final Direction[] ALL_DIRECTIONS = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadNamed =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidCheckPos = new BlockPos.MutableBlockPos();
    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();

    private static final int TINT_CACHE_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int WATER_COLOR_CACHE_SIZE = 4 * 2 * 4;
    private static final float WATER_CEILING_CULL_MIN_HEIGHT = 8.0f / 9.0f;
    private static final float WATER_HEIGHT_EPSILON = 0.0001f;
    private static final int WATER_TOP_CULL_RADIUS = 2;
    private static final int WATER_TOP_CULL_WIDTH = WATER_TOP_CULL_RADIUS * 2 + 1;
    private static final int WATER_TOP_CULL_CELL_COUNT = WATER_TOP_CULL_WIDTH * WATER_TOP_CULL_WIDTH;
    private static final int WATER_TOP_CACHE_WIDTH = SECTION_SIZE + WATER_TOP_CULL_RADIUS * 2;
    private static final int WATER_TOP_CACHE_CELL_COUNT =
            WATER_TOP_CACHE_WIDTH * SECTION_SIZE * WATER_TOP_CACHE_WIDTH;
    // BlockState render type is immutable; cache by raw state id to avoid a per-block virtual call.
    private static volatile RenderShape[] renderTypeByStateId = new RenderShape[0];

    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final int[] tintCache = new int[TINT_CACHE_SIZE];
    private final int[] waterColorCache = new int[WATER_COLOR_CACHE_SIZE];
    private final byte[] waterTopCellCache = new byte[WATER_TOP_CACHE_CELL_COUNT];
    private final int[] waterTopCullStack = new int[WATER_TOP_CULL_CELL_COUNT];
    private final FluidTopOcclusion.CellClassifier waterTopCellClassifier =
            this::classifyWaterTopCellAtOffset;
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    /**
     * Results of {@link Block#shouldRenderFace}, keyed like vanilla's own cache by (state,
     * neighbour state, side) and therefore position-independent to the same degree. Vanilla's
     * cache allocates its lookup key on every opaque-neighbour face; this direct-mapped table
     * of packed state ids allocates nothing on a hit and defers to vanilla on a miss.
     */
    private static final int FACE_CULL_CACHE_BITS = 13;
    private static final byte FACE_CULL_UNKNOWN = 0;
    private static final byte FACE_CULL_HIDDEN = 1;
    private static final byte FACE_CULL_DRAWN = 2;
    private final long[] faceCullKeys = new long[1 << FACE_CULL_CACHE_BITS];
    private final byte[] faceCullResults = new byte[1 << FACE_CULL_CACHE_BITS];

    /**
     * Model-randomisation seed for the block being emitted, mirroring vanilla's
     * {@code state.getSeed(pos)}. Set once per block in {@link #emitBlock}.
     *
     * <p>A multi-variant block picks its variant from this, so a constant makes
     * every instance identical. Where the variants are rotations of one model
     * that is not merely a repeated texture but misplaced geometry: a sea
     * pickle cluster rotates its pickles around the block.
     */
    private long modelRandomSeed = 42L;

    // Per-section state set by beginSection / processBlock
    /**
     * Whether {@link #beginSection} has run without a matching {@link #finishSection}.
     * Capture is gated on it, and a build that never reaches its RETURN inject leaves it
     * set, so the next build on this thread releases the writer's grown buffers before
     * it starts.
     */
    private boolean active;
    private BlockTextureManager texManager;
    private BlockStateModelSet blockModels;
    private FluidStateModelSet fluidModels;
    private BlockColors blockColors;
    private int baseX, baseY, baseZ;
    private long topMask, bottomMask, eastMask, westMask, southMask, northMask;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private int blockCount, fluidCount;
    private int tintValidMask;
    private int waterColorKnownMask;
    private FluidState waterTopCellCacheFluid;
    private BlockAndTintGetter waterTopCullWorld;
    private FluidState waterTopCullReferenceFluid;
    private int waterTopCullOriginX, waterTopCullOriginY, waterTopCullOriginZ;

    private BlockQuadEmitter() {}

    /** Returns the per-thread instance. */
    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    /** Returns the current per-thread instance without allocating one. */
    public static BlockQuadEmitter current() {
        return INSTANCE.get();
    }

    public static int getWaterTopOcclusionRadius() {
        return WATER_TOP_CULL_RADIUS;
    }

    // --- Section lifecycle (called from mixin) ---

    /**
     * Initialises emission state for a new section. Called from the
     * SectionBuilderMixin HEAD inject before MC's block loop starts.
     */
    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockStateModelSet models,
                             FluidStateModelSet fluids,
                             BlockColors colors) {
        if (!threadNamed.get()) {
            RemixAPI.tracyThreadName("Worker " + Thread.currentThread().getName());
            threadNamed.set(Boolean.TRUE);
        }
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.texManager = tex;
        this.blockModels = models;
        this.fluidModels = fluids;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();

        this.blockColors = colors;

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
        // Clear the water classification cache lazily on the first eligible
        // surface so sections without covered water pay no cache-reset cost.
        waterTopCellCacheFluid = null;
        waterColorKnownMask = 0;
        blockCount = 0;
        fluidCount = 0;
    }

    public boolean isActive() { return active; }
    public int getBlockCount() { return blockCount; }
    public int getFluidCount() { return fluidCount; }

    public static RenderShape getRenderTypeCached(BlockState state) {
        int id = Block.getId(state);
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            RenderShape renderType = cache[id];
            if (renderType != null) {
                return renderType;
            }
        }

        RenderShape renderType = state.getRenderShape();
        cache = ensureRenderTypeCacheCapacity(id);
        cache[id] = renderType;
        return renderType;
    }

    private static RenderShape[] ensureRenderTypeCacheCapacity(int id) {
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            return cache;
        }

        synchronized (BlockQuadEmitter.class) {
            cache = renderTypeByStateId;
            if (id >= cache.length) {
                int newSize = Math.max(id + 1, Math.max(1024, cache.length * 2));
                renderTypeByStateId = Arrays.copyOf(cache, newSize);
            }
            return renderTypeByStateId;
        }
    }

    /**
     * Captures opacity bitmap, boundary face masks, and light-emitting
     * blocks. Called once per block from the getBlockState redirect.
     */
    public void processBlock(BlockState state, BlockGetter world, BlockPos pos, boolean opaque) {
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());

        int bitIdx = (ly << 8) | (lz << 4) | lx;
        cacheLocalBlockState(bitIdx, state);

        long bit = 1L << (bitIdx & 63);
        int word = bitIdx >> 6;
        if (opaque) {
            opacity[word] |= bit;
        } else {
            opacity[word] &= ~bit;
        }

        if (!opaque && (ly == 0 || ly == 15 || lx == 0 || lx == 15 || lz == 0 || lz == 15)) {
            if (ly == 15) topMask    |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (ly == 0)  bottomMask |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (lx == 15) eastMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lx == 0)  westMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lz == 15) southMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
            if (lz == 0)  northMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
        }

        if (state.isAir()) return;
        if (!BlockLightRuleManager.hasMatchingRules(state)) return;

        // The sampler needs the same seed the mesh uses, so a rule reading
        // model geometry sees the variant this position actually draws.
        //
        // Lights resolve against the unoffset block position, and the sampler
        // reads the unoffset baked model, while emitBlock folds
        // state.getModelOffset into the geometry origin. No vanilla block
        // combines an AbstractBlock.OffsetType with a luminance, so the two
        // frames agree for every vanilla block; a modded or resource-pack rule
        // on an offset block would need the same offset passed into begin() and
        // into the LightEntry positions.
        modelSampling.begin(blockModels, state, state.getSeed(pos));
        List<LightEntry> resolvedLights = BlockLightRuleManager.resolve(state, pos, modelSampling);
        lights.addAll(resolvedLights);
    }

    /**
     * Emits block model quads for the current block. Called from the
     * getBlockState redirect for blocks vanilla would hand to tesselateBlock,
     * a call the build then skips by reporting the block invisible. Bypasses
     * FRAPI/Indigo and writes directly to the DirectQuadWriter.
     */
    public void emitBlock(BlockState state, BlockPos pos, BlockAndTintGetter world) {
        blockCount++;
        mutablePos.set(pos);
        tintValidMask = 0;

        // Vanilla derives model randomisation from the block position, in the
        // same BlockModelRenderer.render this emitter replaces. It also
        // re-seeds before every getQuads call, which the loops below match.
        modelRandomSeed = state.getSeed(pos);

        float lx = SectionPos.sectionRelative(pos.getX());
        float ly = SectionPos.sectionRelative(pos.getY());
        float lz = SectionPos.sectionRelative(pos.getZ());

        // Vanilla applies the per-position model offset (AbstractBlock.OffsetType
        // XZ/XYZ: flowers, saplings, grass, dripstone) inside
        // BlockModelRenderer.render, which this emitter replaces wholesale.
        // Applying it here is what keeps Remix geometry on the same spot as
        // vanilla's.
        //
        // Folded into the section-local origin rather than applied per vertex:
        // both model paths already add that origin to every position, so the
        // offset rides along for free. hasModelOffset() is a bare null check on
        // the block's offsetter, while getModelOffset() allocates a Vec3d, so
        // the gate keeps that allocation off a path that runs for every block in
        // every section build.
        //
        // This moves geometry only. Block lights are resolved in processBlock
        // against the unoffset grid position; see the note there before adding a
        // light rule to a block that offsets.
        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(pos);
            lx += (float) modelOffset.x;
            ly += (float) modelOffset.y;
            lz += (float) modelOffset.z;
        }

        BlockStateModel model = blockModels.get(state);
        blockLayerGlass = state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE);

        blockOffX = lx;
        blockOffY = ly;
        blockOffZ = lz;
        frapiState = state;
        frapiWorld = world;
        frapiTintInitialised = false;

        // Fabric's renderer emits every model kind (vanilla variants, multipart,
        // weighted, custom FRAPI models) as quads into this emitter. The cull test
        // lets a model drop whole groups early; every quad is culled again by its
        // cull face in onQuad, because a model may ignore the test.
        random.setSeed(modelRandomSeed);
        model.emitQuads(quadEmitter, world, mutablePos, state, random, cullTest);

        writer.flushBlock(texManager);
    }

    /**
     * Emits fluid quads for the current block. Called from the
     * renderFluid redirect.
     */
    public void emitFluid(BlockPos pos, BlockAndTintGetter world, FluidState fluidState) {
        fluidCount++;
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        cacheLocalFluidState(localIndex(lx, ly, lz), fluidState);
        emitFluidQuads(world, fluidState, lx, ly, lz);
    }

    /**
     * Finalises the section, producing a {@link SectionBlockData} with
     * all accumulated mesh/light/mask data. Deactivates the emitter.
     */
    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            finishScope.blockCount(blockCount).fluidCount(fluidCount);
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result = new SectionBlockData(
                    sectionPos, meshData, List.copyOf(lights), topMask, bottomMask,
                    eastMask, westMask, southMask, northMask,
                    Arrays.copyOf(opacity, opacity.length));
            lights.clear();
            return result;
        } finally {
            active = false;
        }
    }

    // --- Quad capture ---

    private BlockState frapiState;
    private BlockAndTintGetter frapiWorld;
    private boolean blockLayerGlass;
    private float blockOffX, blockOffY, blockOffZ;
    private List<BlockTintSource> frapiTintSources;
    private boolean frapiTintInitialised;
    private TextureAtlas blockAtlas;
    private SpriteFinder blockSpriteFinder;

    private final java.util.function.Predicate<Direction> cullTest = this::frapiCullTest;
    private final QuadEmitter quadEmitter = Renderer.get().quadEmitter(this::onQuad);

    private boolean frapiCullTest(Direction dir) {
        return dir != null && !shouldDrawBlockSide(frapiWorld, frapiState, dir);
    }

    private SpriteFinder blockSpriteFinder() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (atlas != blockAtlas) {
            blockAtlas = atlas;
            blockSpriteFinder = ((FabricTextureAtlas) atlas).spriteFinder();
        }
        return blockSpriteFinder;
    }

    /** Captures one quad a model emitted for the current block. */
    private void onQuad(MutableQuadView quad) {
        Direction cull = quad.cullFace();
        if (cull != null && !shouldDrawBlockSide(frapiWorld, frapiState, cull)) {
            return;
        }
        // Only the block atlas has materials in Remix; a block model painted from
        // another atlas has no sprite entry to bind. Vanilla's block models all use the
        // block atlas on 26.3; only a pack or mod model that samples the item atlas is
        // skipped here, since a section's UVs and its terrain material share one atlas.
        if (quad.atlas() != QuadAtlas.BLOCK) {
            return;
        }

        for (int v = 0; v < 4; v++) {
            tempPositions[v * 3]     = quad.x(v) + blockOffX;
            tempPositions[v * 3 + 1] = quad.y(v) + blockOffY;
            tempPositions[v * 3 + 2] = quad.z(v) + blockOffZ;
            tempUvs[v * 2]     = quad.u(v);
            tempUvs[v * 2 + 1] = quad.v(v);
        }

        Vector3fc fn = quad.faceNormal();
        float nx = fn.x(), ny = fn.y(), nz = fn.z();

        int color = quad.color(0);
        int tintIndex = quad.tintIndex();
        if (tintIndex >= 0) {
            color = tintFrapiColor(color, getBlockTint(tintIndex));
        }

        TextureAtlasSprite sprite = blockSpriteFinder().find(quad);
        Identifier spriteId = sprite.contents().name();
        String layerName = blockLayerGlass ? "glass" : layerName(quad.chunkLayer(), spriteId);
        // Redstone dust is modelled with force_translucent textures (flat, tinted, mostly transparent quads).
        // Remix's translucent material is refractive glass: the dust turned clear and, unpowered, invisible.
        // It is a decal, so it takes the alpha-tested layer; the coplanar overlay quad becomes a decal.
        if (spriteId.getPath().startsWith("block/redstone_dust")) {
            layerName = "cutout";
        }
        if (isWaterSprite(spriteId)) {
            emitBlockWaterQuad(nx, ny, nz, color, layerName);
            return;
        }
        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                layerName);
    }

    static String layerName(ChunkSectionLayer layer, Identifier spriteId) {
        // Portal sheets blend their animated RGBA over the world and enclose no glass medium.
        if (spriteId.equals(Identifier.withDefaultNamespace("block/nether_portal"))) {
            return "blended";
        }
        return switch (layer) {
            case SOLID -> "solid";
            case CUTOUT -> "cutout";
            case TRANSLUCENT -> "translucent";
        };
    }

    /**
     * The block tint of a quad's tint index, resolved the way
     * {@code ModelBlockRenderer} does: an index past the block's tint sources is
     * white. Sources are fetched once per block and results are cached per index.
     */
    private int getBlockTint(int tintIndex) {
        if (blockColors == null) return -1;
        if (!frapiTintInitialised) {
            frapiTintSources = blockColors.getTintSources(frapiState);
            frapiTintInitialised = true;
        }
        if (tintIndex >= frapiTintSources.size()) return -1;
        if (tintIndex >= TINT_CACHE_SIZE) {
            return frapiTintSources.get(tintIndex).colorInWorld(frapiState, frapiWorld, mutablePos);
        }
        int bit = 1 << tintIndex;
        if ((tintValidMask & bit) == 0) {
            tintCache[tintIndex] = frapiTintSources.get(tintIndex)
                    .colorInWorld(frapiState, frapiWorld, mutablePos);
            tintValidMask |= bit;
        }
        return tintCache[tintIndex];
    }

    private static final Identifier WATER_STILL_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW_SPRITE = Identifier.withDefaultNamespace("block/water_flow");

    /** A block-model face painted with the water texture: the cauldron's water level is the vanilla case. */
    static boolean isWaterSprite(Identifier spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    /**
     * Emits the staged quad in {@code tempPositions} as RTX water instead of an
     * atlas-textured face, so a cauldron's water matches the lake outside. The
     * water material tiles in world space, so the UVs come from the vertex
     * positions along the face's dominant axes, exactly as the fluid path maps
     * its top and side faces.
     */
    private void emitBlockWaterQuad(float nx, float ny, float nz, int color, String layerName) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u / tileSize;
            tempUvs[v * 2 + 1] = w / tileSize;
        }
        writer.writeFluidQuad(tempPositions, tempUvs, nx, ny, nz, color,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    // --- Fluid geometry ---

    private void emitFluidQuads(BlockAndTintGetter world, FluidState fluidState,
                                 int lx, int ly, int lz) {
        FluidModel fluidModel = fluidModels.get(fluidState);
        if (fluidModel == null) return;

        fluidPos.set(baseX + lx, baseY + ly, baseZ + lz);

        TextureAtlasSprite stillSpriteBaked = fluidModel.stillMaterial().sprite();
        TextureAtlasSprite flowSpriteBaked = fluidModel.flowingMaterial().sprite();

        boolean isLava = fluidState.is(FluidTags.LAVA);
        boolean isWater = !isLava;
        String layer = isLava ? "solid" : "translucent";
        int fluidColor = isWater
                ? getCachedWaterColor(fluidModel, world, fluidState, lx, ly, lz)
                : fluidTint(fluidModel, world, fluidState, lx, ly, lz);
        int r = (fluidColor >> 16) & 0xFF, g = (fluidColor >> 8) & 0xFF, b = fluidColor & 0xFF;
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        float h00 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz);
        float h10 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz);
        float h01 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz + 1);
        float h11 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz + 1);

        float tileSize = BlockTextureManager.getWaterTileSize();
        int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;

        TextureAtlasSprite stillSprite = stillSpriteBaked;
        TextureAtlasSprite flowSprite = flowSpriteBaked;

        Identifier stillId = stillSprite.contents().name();
        float su0 = stillSprite.getU0(), sv0 = stillSprite.getV0();
        float su1 = stillSprite.getU1(), sv1 = stillSprite.getV1();

        Identifier flowId = flowSprite.contents().name();
        float fu0 = flowSprite.getU0(), fv0 = flowSprite.getV0();
        float fu1 = flowSprite.getU1(), fv1 = flowSprite.getV1();

        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() + 1, fluidPos.getZ());
        FluidState aboveFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        boolean submerged = aboveFluid.getType().isSame(fluidState.getType());
        boolean waterTopCovered = false;
        if (isWater && !submerged && fluidState.isSource()) {
            BlockState aboveState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            float topCoverageHeight = Math.min(Math.min(h00, h10), Math.min(h01, h11));
            if (isFluidTopCoveredByCeiling(world, topCoverageHeight, fluidCheckPos, aboveState)) {
                waterTopCovered = classifyWaterTopExposure(world, fluidState, wx, wy, wz)
                        == FluidTopOcclusion.Exposure.HIDDEN;
            }
        }

        // Top face
        if (!submerged && !waterTopCovered) {
            setPos4(tempPositions, lx, ly + h00, lz, lx + 1, ly + h10, lz,
                    lx + 1, ly + h11, lz + 1, lx, ly + h01, lz + 1);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v0, u1, v0, u1, v1, u0, v1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
                setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                        lx + 1, ly + h10, lz, lx, ly + h00, lz);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer: a still surface shows the still sprite in
                // full; a moving one shows the centre quarter of the flowing sprite
                // (two tiles across, so a quarter is one tile) rotated to the flow.
                Vec3 flow = fluidState.getFlow(world, fluidPos);
                if (flow.x == 0.0 && flow.z == 0.0) {
                    setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
                    float sin = Mth.sin(angle) * 0.25f;
                    float cos = Mth.cos(angle) * 0.25f;
                    // Vanilla's corners in its order (0,0) (0,1) (1,1) (1,0), reordered to
                    // this emitter's (0,0) (1,0) (1,1) (0,1).
                    float c00u = flowSprite.getU(0.5f - cos - sin), c00v = flowSprite.getV(0.5f - cos + sin);
                    float c01u = flowSprite.getU(0.5f - cos + sin), c01v = flowSprite.getV(0.5f + cos + sin);
                    float c11u = flowSprite.getU(0.5f + cos + sin), c11v = flowSprite.getV(0.5f + cos - sin);
                    float c10u = flowSprite.getU(0.5f + cos - sin), c10v = flowSprite.getV(0.5f - cos - sin);
                    setUv4(tempUvs, c00u, c00v, c10u, c10v, c11u, c11v, c01u, c01v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    setUv4(tempUvs, c01u, c01v, c11u, c11v, c10u, c10v, c00u, c00v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                }
            }
        }

        // Bottom face
        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() - 1, fluidPos.getZ());
        FluidState belowFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        BlockState belowState = getCachedBlockState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        if (!belowFluid.getType().isSame(fluidState.getType()) && !belowState.isSolidRender()) {
            setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                    lx + 1, ly, lz, lx, ly, lz);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v1, u1, v1, u1, v0, u0, v0);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        stillId, su0, sv0, su1, sv1, layer, texManager);
            }
        }

        // Side faces
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            fluidCheckPos.set(
                fluidPos.getX() + dir.getStepX(),
                fluidPos.getY(),
                fluidPos.getZ() + dir.getStepZ());
            FluidState sideFluid = getCachedFluidState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            BlockState sideState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());

            if (sideFluid.getType().isSame(fluidState.getType())) continue;
            if (sideState.isSolidRender()) continue;

            float sideH0, sideH1;
            switch (dir) {
                case NORTH -> {
                    sideH0 = h10; sideH1 = h00;
                    setPos4(tempPositions, lx + 1, ly, lz, lx, ly, lz,
                            lx, ly + sideH1, lz, lx + 1, ly + sideH0, lz);
                }
                case SOUTH -> {
                    sideH0 = h01; sideH1 = h11;
                    setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                            lx + 1, ly + sideH1, lz + 1, lx, ly + sideH0, lz + 1);
                }
                case EAST -> {
                    sideH0 = h11; sideH1 = h10;
                    setPos4(tempPositions, lx + 1, ly, lz + 1, lx + 1, ly, lz,
                            lx + 1, ly + sideH1, lz, lx + 1, ly + sideH0, lz + 1);
                }
                case WEST -> {
                    sideH0 = h00; sideH1 = h01;
                    setPos4(tempPositions, lx, ly, lz, lx, ly, lz + 1,
                            lx, ly + sideH1, lz + 1, lx, ly + sideH0, lz);
                }
                default -> { continue; }
            }

            float fnx = dir.getStepX(), fny = 0, fnz = dir.getStepZ();
            if (isWater) {
                boolean xAxis = (dir == Direction.EAST || dir == Direction.WEST);
                float sU0, sU1, sV0, sV1;
                if (xAxis) {
                    sU0 = wz / tileSize; sU1 = (wz + 1) / tileSize;
                } else {
                    sU0 = wx / tileSize; sU1 = (wx + 1) / tileSize;
                }
                sV0 = wy / tileSize; sV1 = (wy + 1) / tileSize;
                setUv4(tempUvs, sU0, sV1, sU1, sV1, sU1, sV0, sU0, sV0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer side mapping: one tile is half the flowing
                // sprite, so U spans its left half and V runs from the fluid surface
                // ((1 - height) / 2) down to the half-way line. The full sprite over a
                // one-block face showed the texture at twice the density.
                float sideU0 = flowSprite.getU(0.0f);
                float sideU1 = flowSprite.getU(0.5f);
                float sideVBottom = flowSprite.getV(0.5f);
                float sideVTop0 = flowSprite.getV((1.0f - sideH0) * 0.5f);
                float sideVTop1 = flowSprite.getV((1.0f - sideH1) * 0.5f);
                setUv4(tempUvs, sideU0, sideVBottom, sideU1, sideVBottom, sideU1, sideVTop1, sideU0, sideVTop0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        flowId, fu0, fv0, fu1, fv1, layer, texManager);
            }
        }
    }

    static boolean isFluidTopCoveredByCeiling(BlockGetter world, float fluidHeight,
                                              BlockPos neighborPos, BlockState neighborState) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT
                || !neighborState.canOcclude()) {
            return false;
        }
        if (neighborState.isSolidRender()) {
            return true;
        }

        VoxelShape neighborShape = neighborState.getOcclusionShape();
        return isFluidSideCovered(Direction.UP, 1.0f, true, neighborShape);
    }

    static boolean isFluidTopCoveredByCeiling(float fluidHeight,
                                              boolean neighborOpaque, VoxelShape neighborShape) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT) return false;

        return isFluidSideCovered(Direction.UP, 1.0f, neighborOpaque, neighborShape);
    }

    static boolean isFluidSideCovered(Direction direction, float fluidHeight,
                                      boolean neighborOpaque, VoxelShape neighborShape) {
        if (!neighborOpaque) return false;

        VoxelShape fluidShape = Shapes.box(0.0, 0.0, 0.0, 1.0, fluidHeight, 1.0);
        return Shapes.blockOccludes(fluidShape, neighborShape, direction);
    }

    private FluidTopOcclusion.Exposure classifyWaterTopExposure(
            BlockAndTintGetter world, FluidState referenceFluid, int worldX, int worldY, int worldZ) {
        if (getCachedBlockState(world, worldX, worldY, worldZ).canOcclude()) {
            return FluidTopOcclusion.Exposure.EXPOSED;
        }

        if (waterTopCellCacheFluid == null
                || !waterTopCellCacheFluid.getType().isSame(referenceFluid.getType())) {
            Arrays.fill(waterTopCellCache, FluidTopOcclusion.UNKNOWN);
            waterTopCellCacheFluid = referenceFluid;
        }

        int originCacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (originCacheIndex >= 0) {
            // The caller already established that this still-fluid origin has
            // a covering ceiling, so do not repeat its shape comparison.
            waterTopCellCache[originCacheIndex] = FluidTopOcclusion.CONNECTED;
        }

        waterTopCullWorld = world;
        waterTopCullReferenceFluid = referenceFluid;
        waterTopCullOriginX = worldX;
        waterTopCullOriginY = worldY;
        waterTopCullOriginZ = worldZ;
        try {
            return FluidTopOcclusion.classifyConnectedOrigin(
                    WATER_TOP_CULL_RADIUS, waterTopCullStack, waterTopCellClassifier);
        } finally {
            waterTopCullWorld = null;
            waterTopCullReferenceFluid = null;
        }
    }

    private byte classifyWaterTopCellAtOffset(int xOffset, int zOffset) {
        int worldX = waterTopCullOriginX + xOffset;
        int worldY = waterTopCullOriginY;
        int worldZ = waterTopCullOriginZ + zOffset;
        int cacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (cacheIndex < 0) {
            return classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
        }

        byte classification = waterTopCellCache[cacheIndex];
        if (classification == FluidTopOcclusion.UNKNOWN) {
            classification = classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
            waterTopCellCache[cacheIndex] = classification;
        }
        return classification;
    }

    private int waterTopCellCacheIndex(int worldX, int worldY, int worldZ) {
        int x = worldX - baseX + WATER_TOP_CULL_RADIUS;
        int y = worldY - baseY;
        int z = worldZ - baseZ + WATER_TOP_CULL_RADIUS;
        if ((x | y | z) < 0
                || x >= WATER_TOP_CACHE_WIDTH
                || y >= SECTION_SIZE
                || z >= WATER_TOP_CACHE_WIDTH) {
            return -1;
        }
        return (y * WATER_TOP_CACHE_WIDTH + z) * WATER_TOP_CACHE_WIDTH + x;
    }

    private byte classifyWaterTopCell(BlockAndTintGetter world, FluidState referenceFluid,
                                      int worldX, int worldY, int worldZ) {
        BlockState state = getCachedBlockState(world, worldX, worldY, worldZ);
        if (state.canOcclude()) {
            return FluidTopOcclusion.BLOCKED;
        }

        FluidState fluid = getCachedFluidState(world, worldX, worldY, worldZ);
        if (!fluid.getType().isSame(referenceFluid.getType()) || !fluid.isSource()) {
            return FluidTopOcclusion.EXPOSED;
        }

        FluidState aboveFluid = getCachedFluidState(world, worldX, worldY + 1, worldZ);
        if (aboveFluid.getType().isSame(referenceFluid.getType())) {
            return FluidTopOcclusion.BLOCKED;
        }

        fluidCheckPos.set(worldX, worldY + 1, worldZ);
        BlockState aboveState = getCachedBlockState(world, worldX, worldY + 1, worldZ);
        return isFluidTopCoveredByCeiling(world, fluid.getOwnHeight(), fluidCheckPos, aboveState)
                ? FluidTopOcclusion.CONNECTED
                : FluidTopOcclusion.EXPOSED;
    }

    private float getFluidHeight(BlockAndTintGetter world, FluidState referenceFluid,
                                  int worldX, int worldY, int worldZ) {
        float totalHeight = 0;
        int count = 0;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                fluidCheckPos.set(worldX + dx, worldY + 1, worldZ + dz);
                FluidState aboveFluid = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (aboveFluid.getType().isSame(referenceFluid.getType())) {
                    return 1.0f;
                }

                fluidCheckPos.set(worldX + dx, worldY, worldZ + dz);
                FluidState fs = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (fs.getType().isSame(referenceFluid.getType())) {
                    float h = fs.getOwnHeight();
                    totalHeight += h;
                    count++;
                } else if (!getCachedBlockState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ()).isSolid()) {
                    count++;
                }
            }
        }

        return count > 0 ? totalHeight / count : 0.5f;
    }

    private int fluidTint(FluidModel model, BlockAndTintGetter world, FluidState fluidState,
                          int lx, int ly, int lz) {
        BlockTintSource source = model.tintSource();
        if (source == null) return -1;
        BlockState at = getCachedBlockState(world, baseX + lx, baseY + ly, baseZ + lz);
        return source.colorInWorld(at, world, fluidPos);
    }

    private int getCachedWaterColor(FluidModel model, BlockAndTintGetter world,
                                    FluidState fluidState, int lx, int ly, int lz) {
        int cell = ((ly >> 3) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        int bit = 1 << cell;
        if ((waterColorKnownMask & bit) == 0) {
            waterColorCache[cell] = fluidTint(model, world, fluidState, lx, ly, lz);
            waterColorKnownMask |= bit;
        }
        return waterColorCache[cell];
    }

    // --- Helpers ---

    private boolean shouldDrawBlockSide(BlockAndTintGetter world, BlockState state, Direction side) {
        int neighborX = mutablePos.getX() + side.getStepX();
        int neighborY = mutablePos.getY() + side.getStepY();
        int neighborZ = mutablePos.getZ() + side.getStepZ();
        BlockState neighbor = getCachedBlockState(world, neighborX, neighborY, neighborZ);
        long key = ((long) Block.getId(state) << 35)
                | ((long) Block.getId(neighbor) << 3)
                | side.ordinal();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CULL_CACHE_BITS));
        if (faceCullKeys[slot] == key && faceCullResults[slot] != FACE_CULL_UNKNOWN) {
            return faceCullResults[slot] == FACE_CULL_DRAWN;
        }

        neighborPos.set(neighborX, neighborY, neighborZ);
        boolean drawn = Block.shouldRenderFace(state, neighbor, side);
        faceCullKeys[slot] = key;
        faceCullResults[slot] = drawn ? FACE_CULL_DRAWN : FACE_CULL_HIDDEN;
        return drawn;
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        FluidState fluidState = getCachedBlockState(world, worldX, worldY, worldZ).getFluidState();
        cacheLocalFluidState(idx, fluidState);
        return fluidState;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private void cacheLocalFluidState(int idx, FluidState fluidState) {
        fluidStateCache[idx] = fluidState;
        fluidStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }


    /**
     * A FRAPI quad's vertex color under its block tint, as Indigo's
     * {@code ColorHelper.multiplyColor} computes it: each channel multiplied,
     * rounding down. The tint is treated as opaque, as Indigo's
     * {@code BlockRenderInfo.blockColor} makes it, so the vertex alpha is kept.
     * A block without a color provider has the tint -1, which is white and
     * changes nothing.
     */
    static int tintFrapiColor(int vertexColor, int tint) {
        int red = ((vertexColor >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 0xFF;
        int green = ((vertexColor >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 0xFF;
        int blue = (vertexColor & 0xFF) * (tint & 0xFF) / 0xFF;
        return (vertexColor & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }

    /**
     * The terrain layer a block's quads are bucketed under, by
     * {@link BlockTextureManager#layerType} name. A held block item asks the same
     * question, so it shades as its placed counterpart does.
     */
    public static String classifyBlockLayer(BlockState state) {
        // Vanilla draws clear glass as a cutout: the texture is alpha 0 between
        // the frame pixels and the GL path simply discards those. Remix can
        // refract through it instead, so clear glass takes its own layer and a
        // translucent material with the frame as a diffuse layer.
        // Stained glass is already translucent and keeps vanilla's layer.
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return "glass";
        if (state.is(Blocks.NETHER_PORTAL)) return "blended";
        // 26.3 has no per-block render type: the layer lives on each quad. A held
        // block has no quads in hand here, so judge by its model's material flags
        // and the transparency of its particle sprite.
        BlockStateModel model = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
        if (model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT)) return "translucent";
        if (model.particleMaterial().sprite().transparency().hasTransparent()) return "cutout";
        return "solid";
    }

    private static void setPos4(float[] out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3) {
        out[0] = x0; out[1] = y0; out[2] = z0;
        out[3] = x1; out[4] = y1; out[5] = z1;
        out[6] = x2; out[7] = y2; out[8] = z2;
        out[9] = x3; out[10] = y3; out[11] = z3;
    }

    private static void setUv4(float[] out,
                                float u0, float v0, float u1, float v1,
                                float u2, float v2, float u3, float v3) {
        out[0] = u0; out[1] = v0; out[2] = u1; out[3] = v1;
        out[4] = u2; out[5] = v2; out[6] = u3; out[7] = v3;
    }
}
