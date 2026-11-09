/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test hook helpers: {@code dumpblocks} lists non-full-cube blocks, {@code grid} places a labelled block grid. */
final class BlockGridTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("RemixBlockGrid");

    private BlockGridTest() {
    }

    /** Writes one line per block of the registry: id, state count, render shape, full-cube flag, light, and a sample of non-default state strings. */
    static void dump(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState d = block.defaultBlockState();
            boolean full = d.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                    && d.isSolidRender();
            boolean fullShape = d.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).equals(net.minecraft.world.phys.shapes.Shapes.block());
            StringBuilder props = new StringBuilder();
            for (Property<?> p : block.getStateDefinition().getProperties()) {
                props.append(p.getName()).append('=').append(p.getPossibleValues().size()).append(' ');
            }
            lines.add(BuiltInRegistries.BLOCK.getKey(block) + "\t" + block.getStateDefinition().getPossibleStates().size()
                    + "\t" + d.getRenderShape() + "\tsolidRender=" + d.isSolidRender() + "\tfullShape=" + fullShape
                    + "\tfullCollision=" + full + "\tlight=" + d.getLightEmission() + "\tprops: " + props);
        }
        Files.write(file, lines);
        LOGGER.info("dumpblocks: {} blocks -> {}", lines.size(), file);
    }

    /**
     * Places the states listed in a file (one per line, vanilla block state syntax with optional NBT, '#' comments)
     * on a grid: x grows to the east, rows go from north (row 0) to south, spacing 2. The first line may be
     * "!all" (also place the boolean-flipped state). Returns after scheduling on the server thread.
     */
    static void grid(Minecraft mc, Path file, int ox, int oy, int oz, int cols) throws IOException, CommandSyntaxException {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("grid: no integrated server");
            return;
        }
        List<String> entries = new ArrayList<>();
        for (String l : Files.readAllLines(file)) {
            l = l.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
                entries.add(l);
            }
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int rows = (entries.size() + cols - 1) / cols;
            // clear + platform + backdrop
            for (int x = ox - 2; x <= ox + cols * 2 + 1; x++) {
                for (int z = oz - 3; z <= oz + rows * 2 + 1; z++) {
                    for (int y = oy; y <= oy + 4; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                    level.setBlock(new BlockPos(x, oy - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState(), 2 | 16);
                }
            }
            int i = 0;
            for (String e : entries) {
                int col = i % cols;
                int row = i / cols;
                int x = ox + col * 2;
                int z = oz + row * 2;
                i++;
                try {
                    var res = BlockStateParser.parseForBlock(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), e, true);
                    BlockPos pos = new BlockPos(x, oy, z);
                    // dark floor with a light pad under each block: cutouts and translucency show against both
                    level.setBlock(pos.below(), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState(), 2 | 16);
                    level.setBlock(pos, res.blockState(), 2 | 16);
                    if (res.nbt() != null) {
                        var be = level.getBlockEntity(pos);
                        if (be != null) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "data merge block " + x + " " + oy + " " + z + " " + res.nbt());
                        }
                    }
                    LOGGER.info("grid[{},{}] ({},{},{}) {}", row, col, x, oy, z, e);
                } catch (Exception ex) {
                    LOGGER.warn("grid: cannot place '{}': {}", e, ex.toString());
                }
            }
            LOGGER.info("grid: placed {} entries in {} rows", entries.size(), rows);
        });
    }

    /** All states of a block that differ in one property, for listing. */
    static String flipped(BlockState s) {
        BlockState o = s;
        for (Property<?> p : s.getProperties()) {
            if (p instanceof BooleanProperty bp) {
                o = o.setValue(bp, !o.getValue(bp));
            }
        }
        return o.toString();
    }
}
