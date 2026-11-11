/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Captures the particle sprite atlas and builds parallel LabPBR atlas textures
 * plus a single atlas-wide {@link MaterialInfoOpaqueEXT} that carries its own
 * alpha test rather than draw-call alpha state. Blending is chosen per instance
 * by {@link RemixParticleRenderer} through {@link InstanceInfoBlendEXT}, so one
 * material serves both the alpha-tested and the blended particle passes.
 *
 * <p>Mirrors {@link BlockTextureManager#buildAtlasMaterials} but operates on
 * the particle atlas ({@code SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE}).
 *
 * <p>TERRAIN_SHEET particles reuse materials from {@link BlockTextureManager}
 * rather than creating new ones here.
 */
public class ParticleAtlasManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleAtlasManager.class);

    private final RemixRenderer renderer;
    private final Identifier atlasId;
    /** Prepended to a sprite id's path to get the texture the LabPBR variants sit next to. */
    private final String spritePathPrefix;
    private final String name;

    private final List<RemixTexture> managedTextures = new ArrayList<>();
    private final List<RemixMaterial> managedMaterials = new ArrayList<>();

    private RemixMaterial material;

    private boolean initialized;
    private boolean initFailed;

    public ParticleAtlasManager(RemixRenderer renderer) {
        this(renderer, TextureAtlas.LOCATION_PARTICLES, "particle/", "particle");
    }

    /**
     * Manager for another atlas that particles draw from, the item atlas of item break
     * particles. Sprite ids of the item atlas already name their texture ({@code item/apple}),
     * so its prefix is empty.
     */
    public ParticleAtlasManager(RemixRenderer renderer, Identifier atlasId, String spritePathPrefix, String name) {
        this.renderer = renderer;
        this.atlasId = atlasId;
        this.spritePathPrefix = spritePathPrefix;
        this.name = name;
    }

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            RemixAPI api = renderer.getRemixApi();
            Minecraft client = Minecraft.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            AbstractTexture tex = client.getTextureManager()
                    .getTexture(atlasId);
            if (!(tex instanceof TextureAtlas atlas)) return;

            SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
            int atlasW = acc.getWidth();
            int atlasH = acc.getHeight();
            if (atlasW == 0 || atlasH == 0) {
                LOGGER.warn("{} atlas dimensions are zero, deferring init", name);
                return;
            }

            Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();
            if (sprites == null || sprites.isEmpty()) {
                LOGGER.warn("{} atlas has no sprites, deferring init", name);
                return;
            }

            byte[] albedoRGBA = BlockTextureManager.stitchAtlasRGBA(atlas);

            int pixels = atlasW * atlasH;
            byte[] normalAtlas    = new byte[pixels * 4];
            byte[] heightAtlas    = new byte[pixels];
            byte[] roughnessAtlas = new byte[pixels];
            byte[] metallicAtlas  = new byte[pixels];
            byte[] emissiveAtlas  = new byte[pixels * 4];

            Arrays.fill(heightAtlas, (byte) 255);
            Arrays.fill(roughnessAtlas, (byte) 180);
            for (int i = 0; i < pixels; i++) {
                int idx4 = i * 4;
                normalAtlas[idx4]     = (byte) 128;
                normalAtlas[idx4 + 1] = (byte) 128;
                normalAtlas[idx4 + 2] = (byte) 255;
                normalAtlas[idx4 + 3] = (byte) 255;
            }

            boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false;
            ResourceManager rm = client.getResourceManager();
            LabPBRConverter.VariantCache labPBRVariants = LabPBRConverter.buildVariantCache(rm);

            for (var entry : sprites.entrySet()) {
                Identifier spriteId = entry.getKey();
                TextureAtlasSprite sprite = entry.getValue();
                AtlasSpriteRegion region = AtlasSpriteRegion.from(sprite, atlasW, atlasH);
                int pixX = region.x(), pixY = region.y();
                int pixW = region.width();
                int pixH = region.height();

                Identifier texId = Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
                        spritePathPrefix + spriteId.getPath());
                NativeImage nImg = labPBRVariants.loadVariant(texId, "_n");
                NativeImage sImg = labPBRVariants.loadVariant(texId, "_s");
                try {
                    if (nImg != null) {
                        LabPBRConverter.blitNormal(normalAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                        LabPBRConverter.blitHeight(heightAtlas, atlasW, nImg, albedoRGBA, pixX, pixY, pixW, pixH);
                        LabPBRConverter.applyAO(albedoRGBA, atlasW, nImg, pixX, pixY, pixW, pixH);
                        hasAnyNormal = true;
                        hasAnyHeight = true;
                    }
                    if (sImg != null) {
                        LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                        LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                    }
                    if (sImg != null && LabPBRConverter.hasEmissiveData(sImg)) {
                        LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sImg,
                                pixX, pixY, pixW, pixH, 0);
                        hasAnyEmissive = true;
                    }
                } finally {
                    if (nImg != null) nImg.close();
                    if (sImg != null) sImg.close();
                }
            }

            RemixTexture albedoTex = uploadTexture(name + "_atlas_albedo",
                    albedoRGBA, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);

            RemixTexture normalTex = hasAnyNormal
                    ? uploadTexture(name + "_atlas_normal", normalAtlas, atlasW, atlasH,
                            TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX) : null;
            RemixTexture heightTex = hasAnyHeight
                    ? uploadTexture(name + "_atlas_height", heightAtlas, atlasW, atlasH,
                            TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP) : null;
            RemixTexture roughnessTex = uploadTexture(name + "_atlas_roughness",
                    roughnessAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            RemixTexture metallicTex = uploadTexture(name + "_atlas_metallic",
                    metallicAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            RemixTexture emissiveTex = hasAnyEmissive
                    ? uploadTexture(name + "_atlas_emissive", emissiveAtlas, atlasW, atlasH,
                            TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;

            if (albedoTex == null) {
                LOGGER.error("Failed to upload {} atlas albedo, aborting", name);
                initFailed = true;
                return;
            }

            material = buildOpaqueMaterial(albedoTex, normalTex, heightTex,
                    roughnessTex, metallicTex, emissiveTex);

            if (material == null) {
                LOGGER.error("Failed to create {} atlas material(s)", name);
                initFailed = true;
                return;
            }

            initialized = true;
            LOGGER.info("{} atlas initialized ({}x{}, {} sprites, normal={}, height={}, emissive={})",
                    name, atlasW, atlasH, sprites.size(), hasAnyNormal, hasAnyHeight, hasAnyEmissive);

        } catch (Exception e) {
            LOGGER.error("{} atlas init failed", name, e);
            initFailed = true;
        }
    }

    private RemixMaterial buildOpaqueMaterial(RemixTexture albedo, RemixTexture normal,
                                              RemixTexture height, RemixTexture roughness,
                                              RemixTexture metallic, RemixTexture emissive) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(1f)
                .metallicConstant(0f)
                .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                .useDrawCallAlphaState(false)
                .alphaTestType(4)
                .alphaReferenceValue(10);
        if (roughness != null) ob.roughnessTexture(roughness.getHandle());
        if (metallic != null)  ob.metallicTexture(metallic.getHandle());
        if (height != null)    ob.heightTexture(height.getHandle()).displaceIn(0.02f);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(albedo.getHandle())
                .filterMode(0);
        if (normal != null)   mb.normalTexture(normal.getHandle());
        if (emissive != null) mb.emissiveTexture(emissive.getHandle()).emissiveIntensity(40.0f);

        return registerMaterial(mb.opaqueExt(ob.build()).build());
    }

    public RemixMaterial getMaterial()  { return material; }
    public boolean isInitialized()     { return initialized; }

    private RemixTexture uploadTexture(String name, byte[] data, int w, int h, int fmt, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            RemixTexture tex = new RemixTexture(w, h, fmt, purpose, buf);
            // The texture owns the buffer from here: RemixAssetBase disposes it after a
            // successful create and on the create failure path alike, so releasing it
            // here as well would release it twice.
            RemixTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload particle texture '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private RemixMaterial registerMaterial(MaterialInfo matInfo) {
        try {
            RemixMaterial mat = new RemixMaterial(matInfo);
            RemixMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create particle material: {}", e.getMessage());
            return null;
        }
    }

    public void cleanup() {
        for (RemixMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();

        for (RemixTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();

        material = null;
        initialized = false;
        initFailed = false;
    }
}
