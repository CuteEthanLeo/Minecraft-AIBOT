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
            default -> generateHouse(size, material, origin);
        };
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
