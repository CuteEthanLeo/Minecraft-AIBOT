package com.aibot.entity;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates block-by-block build plans for structures.
 * Each structure is a list of BuildSteps (position + block state).
 *
 * All positions are relative to the origin (robot's starting position).
 */
public class HouseBuilder {

    /** A single block to place in the world. */
    public record BuildStep(BlockPos pos, BlockState state) {}

    // ======================================================================
    //  Public API
    // ======================================================================

    /**
     * Generate a structure build plan.
     *
     * @param structure Type: "house", "wall", "tower", "hut"
     * @param size      Size: "small", "medium", "large"
     * @param material  Material: "oak", "stone", "birch", or null for default
     * @param origin    The starting position (robot's feet position)
     * @return List of BuildSteps to execute in order
     */
    public static List<BuildStep> generate(String structure, String size, String material, BlockPos origin) {
        if (origin == null) return List.of();

        return switch (structure != null ? structure.toLowerCase() : "house") {
            case "wall" -> generateWall(size, material, origin);
            case "tower" -> generateTower(size, material, origin);
            case "hut" -> generateHut(material, origin);
            case "bridge" -> generateBridge(size, material, origin);
            case "stairs" -> generateStairs(size, material, origin);
            case "platform" -> generatePlatform(size, material, origin);
            case "shelter" -> generateShelter(material, origin);
            case "road" -> generateRoad(size, material, origin);
            case "fountain" -> generateFountain(size, material, origin);
            case "statue" -> generateStatue(size, material, origin);
            case "pyramid" -> generatePyramid(size, material, origin);
            case "pool" -> generatePool(size, material, origin);
            case "garden" -> generateGarden(size, material, origin);
            case "pillar" -> generatePillar(size, material, origin);
            case "arch" -> generateArch(size, material, origin);
            case "farm" -> generateFarm(size, material, origin);
            // Unknown/unsupported structure — return empty list so caller can fall back
            // to custom generation or command-based building
            default -> {
                System.out.println("[AIBot] Unknown structure type: " + structure + " — falling back to custom generation");
                yield List.of();
            }
        };
    }

    /**
     * Generates a custom build based on a freeform description.
     * Analyzes the description for shape keywords and builds accordingly.
     * Returns empty list if no pattern matches — caller should fall back to command-based building.
     */
    public static List<BuildStep> generateCustom(String description, String size, String material, BlockPos origin) {
        if (description == null || origin == null) return List.of();
        String desc = description.toLowerCase();

        // Try to match description keywords to known shapes
        if (desc.contains("statue") || desc.contains("雕塑") || desc.contains("雕像") || desc.contains("人像")) {
            return generateStatue(size, material, origin);
        }
        if (desc.contains("pyramid") || desc.contains("金字塔")) {
            return generatePyramid(size, material, origin);
        }
        if (desc.contains("fountain") || desc.contains("喷泉") || desc.contains("水池")) {
            return generateFountain(size, material, origin);
        }
        if (desc.contains("pool") || desc.contains("游泳池") || desc.contains("池塘")) {
            return generatePool(size, material, origin);
        }
        if (desc.contains("pillar") || desc.contains("柱子") || desc.contains("柱")) {
            return generatePillar(size, material, origin);
        }
        if (desc.contains("arch") || desc.contains("拱门") || desc.contains("门")) {
            return generateArch(size, material, origin);
        }
        if (desc.contains("farm") || desc.contains("农场") || desc.contains("田")) {
            return generateFarm(size, material, origin);
        }
        if (desc.contains("garden") || desc.contains("花园")) {
            return generateGarden(size, material, origin);
        }
        if (desc.contains("wall") || desc.contains("墙")) {
            return generateWall(size, material, origin);
        }
        if (desc.contains("tower") || desc.contains("塔")) {
            return generateTower(size, material, origin);
        }

        // No match — return empty to signal fallback to command-based building
        return List.of();
    }

    // ======================================================================
    //  House (5x5 / 7x7 / 9x9 with walls, roof, door)
    // ======================================================================

    private static List<BuildStep> generateHouse(String size, String material, BlockPos origin) {
        int floorSize = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 7;
            case "large" -> 9;
            default -> 5;
        };
        int height = 4;

        Block floorBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);
        Block wallBlock = getBlock(material, "log", Blocks.OAK_LOG);
        Block roofBlock = getBlock(material, "stairs", Blocks.OAK_STAIRS);

        List<BuildStep> steps = new ArrayList<>();

        // Floor: solid square
        for (int x = 0; x < floorSize; x++) {
            for (int z = 0; z < floorSize; z++) {
                addStep(steps, origin, x, 0, z, floorBlock);
            }
        }

        // Walls: 4 blocks tall with door gap on front face (z = 0)
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < floorSize; x++) {
                for (int z = 0; z < floorSize; z++) {
                    if (x == 0 || x == floorSize - 1 || z == 0 || z == floorSize - 1) {
                        // Door gap: front center (z=0, y=1-2)
                        if (z == 0 && y <= 2 && x >= floorSize / 2 - 1 && x <= floorSize / 2) continue;
                        addStep(steps, origin, x, y, z, wallBlock);
                    }
                }
            }
        }

        // Roof: stair-like sloped layers
        for (int y = height + 1; y <= height + 3; y++) {
            int inset = y - (height + 1);
            for (int x = inset; x < floorSize - inset; x++) {
                for (int z = inset; z < floorSize - inset; z++) {
                    if (x == inset || x == floorSize - inset - 1 || z == inset || z == floorSize - inset - 1) {
                        addStep(steps, origin, x, y, z, roofBlock);
                    }
                }
            }
        }

        return steps;
    }

    // ======================================================================
    //  Wall (straight line of blocks)
    // ======================================================================

    private static List<BuildStep> generateWall(String size, String material, BlockPos origin) {
        int length = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 15;
            case "large" -> 25;
            default -> 10;
        };
        int height = 4;

        Block wallBlock = getBlock(material, "log", Blocks.OAK_LOG);

        List<BuildStep> steps = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            for (int y = 1; y <= height; y++) {
                addStep(steps, origin, x, y, 0, wallBlock);
            }
        }
        return steps;
    }

    // ======================================================================
    //  Tower (square tower with interior hollow)
    // ======================================================================

    private static List<BuildStep> generateTower(String size, String material, BlockPos origin) {
        int floorSize = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 5;
            case "large" -> 7;
            default -> 3;
        };
        int height = 6;

        Block floorBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);
        Block wallBlock = getBlock(material, "log", Blocks.OAK_LOG);

        List<BuildStep> steps = new ArrayList<>();

        // Floor
        for (int x = 0; x < floorSize; x++) {
            for (int z = 0; z < floorSize; z++) {
                addStep(steps, origin, x, 0, z, floorBlock);
            }
        }

        // Walls (hollow interior — only outer ring)
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < floorSize; x++) {
                for (int z = 0; z < floorSize; z++) {
                    if (x == 0 || x == floorSize - 1 || z == 0 || z == floorSize - 1) {
                        // Door gap at ground level
                        if (y <= 2 && x >= floorSize / 2 - 1 && x <= floorSize / 2 && z == 0) continue;
                        addStep(steps, origin, x, y, z, wallBlock);
                    }
                }
            }
        }

        return steps;
    }

    // ======================================================================
    //  Hut (tiny 3x3 shelter)
    // ======================================================================

    private static List<BuildStep> generateHut(String material, BlockPos origin) {
        Block wallBlock = getBlock(material, "log", Blocks.OAK_LOG);
        Block roofBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);

        List<BuildStep> steps = new ArrayList<>();

        // Floor
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                addStep(steps, origin, x, 0, z, wallBlock);
            }
        }

        // Walls with door
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    if (x == 0 || x == 2 || z == 0 || z == 2) {
                        if (z == 0 && y <= 2 && x == 1) continue; // door
                        addStep(steps, origin, x, y, z, wallBlock);
                    }
                }
            }
        }

        // Roof
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                addStep(steps, origin, x, 4, z, roofBlock);
            }
        }

        return steps;
    }

    // ======================================================================
    //  Bridge (path with railings, width depends on size)
    // ======================================================================

    private static List<BuildStep> generateBridge(String size, String material, BlockPos origin) {
        int length = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 15;
            case "large" -> 25;
            default -> 10;
        };
        int width = 3;
        Block floorBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);
        Block railBlock = getBlock(material, "log", Blocks.OAK_FENCE);

        List<BuildStep> steps = new ArrayList<>();
        // Floor
        for (int x = 0; x < length; x++) {
            for (int z = 0; z < width; z++) {
                addStep(steps, origin, x, 0, z, floorBlock);
            }
        }
        // Railings on sides
        for (int x = 0; x < length; x++) {
            addStep(steps, origin, x, 1, 0, railBlock);
            addStep(steps, origin, x, 1, width - 1, railBlock);
        }
        return steps;
    }

    // ======================================================================
    //  Stairs (ascending staircase)
    // ======================================================================

    private static List<BuildStep> generateStairs(String size, String material, BlockPos origin) {
        int steps_count = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 12;
            case "large" -> 20;
            default -> 8;
        };
        Block stepBlock = getBlock(material, "stairs", Blocks.OAK_STAIRS);
        // Actually use full blocks for simplicity (stairs are directional and complex)
        Block useBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);

        List<BuildStep> steps = new ArrayList<>();
        for (int i = 0; i < steps_count; i++) {
            // Each step: a 3-wide platform going up 1 block per step
            for (int z = -1; z <= 1; z++) {
                addStep(steps, origin, i, i, z, useBlock);
            }
            // Support pillar under each step
            if (i > 0) {
                addStep(steps, origin, i, 0, -1, useBlock);
                addStep(steps, origin, i, 0, 1, useBlock);
            }
        }
        return steps;
    }

    // ======================================================================
    //  Platform (flat raised surface)
    // ======================================================================

    private static List<BuildStep> generatePlatform(String size, String material, BlockPos origin) {
        int dim = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 7;
            case "large" -> 11;
            default -> 5;
        };
        Block floorBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);
        Block pillarBlock = getBlock(material, "log", Blocks.OAK_LOG);

        List<BuildStep> steps = new ArrayList<>();
        // Pillars at corners and edges
        for (int x = 0; x < dim; x += (dim > 5 ? 3 : 2)) {
            for (int z = 0; z < dim; z += (dim > 5 ? 3 : 2)) {
                for (int y = 1; y <= 3; y++) {
                    addStep(steps, origin, x, 3 - y, z, pillarBlock);
                }
            }
        }
        // Floor on top
        for (int x = 0; x < dim; x++) {
            for (int z = 0; z < dim; z++) {
                addStep(steps, origin, x, 3, z, floorBlock);
            }
        }
        return steps;
    }

    // ======================================================================
    //  Shelter (simple 3x3 roofed, even simpler than hut)
    // ======================================================================

    private static List<BuildStep> generateShelter(String material, BlockPos origin) {
        Block wallBlock = getBlock(material, "log", Blocks.OAK_LOG);
        Block roofBlock = getBlock(material, "planks", Blocks.OAK_PLANKS);

        List<BuildStep> steps = new ArrayList<>();
        // 4 corner pillars
        int[][] corners = {{0,0}, {0,3}, {3,0}, {3,3}};
        for (int[] c : corners) {
            for (int y = 1; y <= 3; y++) {
                addStep(steps, origin, c[0], y, c[1], wallBlock);
            }
        }
        // Roof
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                addStep(steps, origin, x, 3, z, roofBlock);
            }
        }
        return steps;
    }

    // ======================================================================
    //  Road (flat ground path)
    // ======================================================================

    private static List<BuildStep> generateRoad(String size, String material, BlockPos origin) {
        int length = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 30;
            case "large" -> 50;
            default -> 15;
        };
        int width = 3;
        Block roadBlock = getBlock(material, "planks", Blocks.STONE);

        List<BuildStep> steps = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            for (int z = 0; z < width; z++) {
                addStep(steps, origin, x, 0, z, roadBlock);
            }
        }
        return steps;
    }

    // ======================================================================
    //  Fountain (water feature with decorative ring)
    // ======================================================================

    private static List<BuildStep> generateFountain(String size, String material, BlockPos origin) {
        int radius = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 3;
            case "large" -> 5;
            default -> 2;
        };
        Block rimBlock = getBlock(material, "log", Blocks.STONE_BRICKS);
        Block waterBlock = Blocks.WATER;

        List<BuildStep> steps = new ArrayList<>();
        // Basin: circular rim
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist <= radius && dist > radius - 1) {
                    // Rim
                    for (int y = 0; y < 2; y++) {
                        addStep(steps, origin, x, y, z, rimBlock);
                    }
                } else if (dist <= radius - 1) {
                    // Interior: bottom slab
                    addStep(steps, origin, x, -1, z, rimBlock);
                    // Water on top
                    addStep(steps, origin, x, 0, z, waterBlock);
                }
            }
        }
        // Central pillar with water source on top
        addStep(steps, origin, 0, 0, 0, rimBlock);
        addStep(steps, origin, 0, 1, 0, rimBlock);
        addStep(steps, origin, 0, 2, 0, waterBlock);
        return steps;
    }

    // ======================================================================
    //  Statue (humanoid figure — simplified)
    // ======================================================================

    private static List<BuildStep> generateStatue(String size, String material, BlockPos origin) {
        int scale = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 2;
            case "large" -> 3;
            default -> 1;
        };
        Block bodyBlock = getBlock(material, "log", Blocks.STONE);
        Block headBlock = getBlock(material, "planks", Blocks.STONE_BRICKS);

        List<BuildStep> steps = new ArrayList<>();
        int bx = 0, bz = 0;
        int baseY = 0;

        // Base platform
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                addStep(steps, origin, x, baseY, z, bodyBlock);
            }
        }

        // Legs (2 pillars)
        for (int y = 1; y <= 2 * scale; y++) {
            addStep(steps, origin, bx - 1, y, bz, bodyBlock);
            addStep(steps, origin, bx + 1, y, bz, bodyBlock);
        }

        // Body (2-wide pillar up to shoulder)
        int bodyTop = 2 * scale + 1;
        for (int y = bodyTop; y <= bodyTop + 2 * scale; y++) {
            addStep(steps, origin, bx, y, bz, bodyBlock);
            if (scale > 1) {
                addStep(steps, origin, bx, y, bz + 1, bodyBlock);
            }
        }

        // Arms (horizontal at shoulder height)
        int shoulderY = bodyTop + 2 * scale;
        for (int dx = -2; dx <= 2; dx++) {
            if (Math.abs(dx) >= 1) {
                addStep(steps, origin, dx, shoulderY, bz, bodyBlock);
            }
        }

        // Head
        int headY = shoulderY + 1;
        addStep(steps, origin, bx, headY, bz, headBlock);
        if (scale > 1) {
            addStep(steps, origin, bx, headY + 1, bz, headBlock);
            addStep(steps, origin, bx + 1, headY, bz, headBlock);
            addStep(steps, origin, bx - 1, headY, bz, headBlock);
            addStep(steps, origin, bx, headY, bz + 1, headBlock);
            addStep(steps, origin, bx, headY, bz - 1, headBlock);
        }

        return steps;
    }

    // ======================================================================
    //  Pyramid (square base → apex)
    // ======================================================================

    private static List<BuildStep> generatePyramid(String size, String material, BlockPos origin) {
        int baseSize = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 9;
            case "large" -> 15;
            default -> 5;
        };
        Block block = getBlock(material, "log", Blocks.SANDSTONE);

        List<BuildStep> steps = new ArrayList<>();
        int half = baseSize / 2;
        for (int y = 0; y <= half; y++) {
            int layerSize = baseSize - y * 2;
            int offset = y;
            for (int x = 0; x < layerSize; x++) {
                for (int z = 0; z < layerSize; z++) {
                    // Only place blocks on the perimeter of each layer
                    if (x == 0 || x == layerSize - 1 || z == 0 || z == layerSize - 1 || y == 0) {
                        addStep(steps, origin, x + offset, y, z + offset, block);
                    }
                }
            }
        }
        return steps;
    }

    // ======================================================================
    //  Pool (water-filled rectangular basin)
    // ======================================================================

    private static List<BuildStep> generatePool(String size, String material, BlockPos origin) {
        int dim = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 7;
            case "large" -> 11;
            default -> 5;
        };
        Block rimBlock = getBlock(material, "log", Blocks.STONE_BRICKS);
        Block waterBlock = Blocks.WATER;

        List<BuildStep> steps = new ArrayList<>();
        for (int x = -1; x <= dim; x++) {
            for (int z = -1; z <= dim; z++) {
                boolean isEdge = x == -1 || x == dim || z == -1 || z == dim;
                if (isEdge) {
                    // Rim
                    for (int y = -1; y < 1; y++) {
                        addStep(steps, origin, x, y, z, rimBlock);
                    }
                } else {
                    // Interior: floor + water
                    addStep(steps, origin, x, -1, z, rimBlock);
                    addStep(steps, origin, x, 0, z, waterBlock);
                }
            }
        }
        return steps;
    }

    // ======================================================================
    //  Garden (small fenced area with flowers)
    // ======================================================================

    private static List<BuildStep> generateGarden(String size, String material, BlockPos origin) {
        int dim = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 7;
            case "large" -> 11;
            default -> 5;
        };
        Block fenceBlock = Blocks.OAK_FENCE;
        Block grassBlock = Blocks.GRASS_BLOCK;
        Block flowerBlock = Blocks.POPPY;

        List<BuildStep> steps = new ArrayList<>();
        for (int x = 0; x < dim; x++) {
            for (int z = 0; z < dim; z++) {
                boolean isEdge = x == 0 || x == dim - 1 || z == 0 || z == dim - 1;
                if (isEdge) {
                    // Fence posts at corners, fence between
                    addStep(steps, origin, x, 0, z, grassBlock);
                    addStep(steps, origin, x, 1, z, fenceBlock);
                } else {
                    addStep(steps, origin, x, 0, z, grassBlock);
                    // Scatter flowers randomly (deterministic by position)
                    if ((x + z) % 3 == 0) {
                        addStep(steps, origin, x, 1, z, flowerBlock);
                    }
                }
            }
        }
        return steps;
    }

    // ======================================================================
    //  Pillar (tall single-column structure)
    // ======================================================================

    private static List<BuildStep> generatePillar(String size, String material, BlockPos origin) {
        int height = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 12;
            case "large" -> 20;
            default -> 6;
        };
        Block pillarBlock = getBlock(material, "log", Blocks.STONE_BRICKS);
        Block topBlock = getBlock(material, "planks", Blocks.GLOWSTONE);

        List<BuildStep> steps = new ArrayList<>();
        // Base (2x2)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                addStep(steps, origin, x, 0, z, pillarBlock);
            }
        }
        // Shaft
        for (int y = 1; y < height; y++) {
            addStep(steps, origin, 0, y, 0, pillarBlock);
        }
        // Top decoration
        addStep(steps, origin, 0, height, 0, topBlock);
        return steps;
    }

    // ======================================================================
    //  Arch (free-standing archway)
    // ======================================================================

    private static List<BuildStep> generateArch(String size, String material, BlockPos origin) {
        int archHeight = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 7;
            case "large" -> 11;
            default -> 5;
        };
        int archWidth = archHeight / 2 + 1;
        Block archBlock = getBlock(material, "log", Blocks.STONE_BRICKS);

        List<BuildStep> steps = new ArrayList<>();
        int centerX = archWidth;

        // Two side pillars
        for (int y = 0; y < archHeight; y++) {
            addStep(steps, origin, 0, y, 0, archBlock);
            addStep(steps, origin, centerX * 2, y, 0, archBlock);
        }
        // Arch curve (semi-circle top)
        for (int x = 1; x < centerX * 2; x++) {
            double dx = x - centerX;
            int curveY = archHeight - (int) Math.round(Math.sqrt(Math.max(0, centerX * centerX - dx * dx)));
            curveY = Math.max(archHeight - centerX, curveY);
            addStep(steps, origin, x, curveY, 0, archBlock);
            // Extra thickness
            addStep(steps, origin, x, curveY, 1, archBlock);
        }
        return steps;
    }

    // ======================================================================
    //  Farm (tilled soil with water channels)
    // ======================================================================

    private static List<BuildStep> generateFarm(String size, String material, BlockPos origin) {
        int farmSize = switch (size != null ? size.toLowerCase() : "small") {
            case "medium" -> 9;
            case "large" -> 15;
            default -> 5;
        };
        Block fenceBlock = Blocks.OAK_FENCE;
        Block dirtBlock = Blocks.DIRT;
        Block waterBlock = Blocks.WATER;

        List<BuildStep> steps = new ArrayList<>();
        for (int x = 0; x < farmSize; x++) {
            for (int z = 0; z < farmSize; z++) {
                boolean isEdge = x == 0 || x == farmSize - 1 || z == 0 || z == farmSize - 1;
                boolean isWaterChannel = (x == farmSize / 2 || z == farmSize / 2) && !isEdge;
                if (isEdge) {
                    addStep(steps, origin, x, 0, z, dirtBlock);
                    addStep(steps, origin, x, 1, z, fenceBlock);
                } else if (isWaterChannel) {
                    addStep(steps, origin, x, 0, z, waterBlock);
                } else {
                    addStep(steps, origin, x, 0, z, Blocks.FARMLAND);
                }
            }
        }
        return steps;
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    private static void addStep(List<BuildStep> steps, BlockPos origin, int x, int y, int z, Block block) {
        steps.add(new BuildStep(origin.add(x, y, z), block.getDefaultState()));
    }

    /** Map material + type to a block, with sensible defaults. */
    private static Block getBlock(String material, String type, Block defaultBlock) {
        if (material == null) return defaultBlock;

        return switch (material.toLowerCase()) {
            case "stone" -> switch (type) {
                case "planks" -> Blocks.STONE_BRICKS;
                case "log" -> Blocks.STONE;
                case "stairs" -> Blocks.STONE_BRICK_STAIRS;
                default -> defaultBlock;
            };
            case "birch" -> switch (type) {
                case "planks" -> Blocks.BIRCH_PLANKS;
                case "log" -> Blocks.BIRCH_LOG;
                case "stairs" -> Blocks.BIRCH_STAIRS;
                default -> defaultBlock;
            };
            case "spruce" -> switch (type) {
                case "planks" -> Blocks.SPRUCE_PLANKS;
                case "log" -> Blocks.SPRUCE_LOG;
                case "stairs" -> Blocks.SPRUCE_STAIRS;
                default -> defaultBlock;
            };
            case "dark_oak" -> switch (type) {
                case "planks" -> Blocks.DARK_OAK_PLANKS;
                case "log" -> Blocks.DARK_OAK_LOG;
                case "stairs" -> Blocks.DARK_OAK_STAIRS;
                default -> defaultBlock;
            };
            case "acacia" -> switch (type) {
                case "planks" -> Blocks.ACACIA_PLANKS;
                case "log" -> Blocks.ACACIA_LOG;
                case "stairs" -> Blocks.ACACIA_STAIRS;
                default -> defaultBlock;
            };
            case "cobblestone" -> switch (type) {
                case "planks", "log", "stairs" -> Blocks.COBBLESTONE;
                default -> defaultBlock;
            };
            // Oak (default)
            default -> switch (type) {
                case "planks" -> Blocks.OAK_PLANKS;
                case "log" -> Blocks.OAK_LOG;
                case "stairs" -> Blocks.OAK_STAIRS;
                default -> defaultBlock;
            };
        };
    }
}
