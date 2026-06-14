package com.aibot.entity;

import com.aibot.ai.ActionTypes;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Executes AI-generated actions on the robot entity.
 * Provides the QueuedAction record and action queue processing logic.
 */
public class RobotAI {

    /**
     * A single queued action for the robot to execute.
     *
     * @param type     The type of action
     * @param target   Target entity description (for ATTACK, FOLLOW)
     * @param pos      Target position (for MINE, PLACE, GOTO)
     * @param message  Chat message (for CHAT)
     * @param itemName Item name (for EQUIP, CRAFT)
     * @param quantity Quantity (for MINE, COLLECT)
     */
    public record QueuedAction(
        ActionTypes type,
        String target,
        BlockPos pos,
        String message,
        String itemName,
        int quantity,
        String command,
        String structure,
        String size,
        String material,
        String description,
        java.util.List<String> commands
    ) {
        public QueuedAction(ActionTypes type) {
            this(type, null, null, null, null, 0, null, null, null, null, null, null);
        }

        public QueuedAction(ActionTypes type, String target) {
            this(type, target, null, null, null, 0, null, null, null, null, null, null);
        }

        public QueuedAction(ActionTypes type, BlockPos pos) {
            this(type, null, pos, null, null, 0, null, null, null, null, null, null);
        }

        public QueuedAction(ActionTypes type, String target, BlockPos pos) {
            this(type, target, pos, null, null, 0, null, null, null, null, null, null);
        }
    }

    /**
     * Converts parsed actions from the AI into queued actions ready for execution.
     */
    public static java.util.List<QueuedAction> fromParsedActions(List<com.aibot.ai.ParsedAction> parsed) {
        return parsed.stream().map(RobotAI::convert).toList();
    }

    private static QueuedAction convert(com.aibot.ai.ParsedAction parsed) {
        return new QueuedAction(
            parsed.type,
            parsed.target,
            parsed.position,
            parsed.message,
            parsed.itemName,
            parsed.quantity,
            parsed.command,
            parsed.structure,
            parsed.size,
            parsed.material,
            parsed.description,
            parsed.commands
        );
    }

    /**
     * Builds a scan report of the robot's surroundings for returning to the AI.
     */
    public static String buildScanReport(RobotEntity robot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scan report:\n");

        sb.append("Position: ").append(robot.getBlockPos().getX()).append(", ")
            .append(robot.getBlockPos().getY()).append(", ")
            .append(robot.getBlockPos().getZ()).append("\n");

        sb.append("Health: ").append(String.format("%.0f", robot.getHealth())).append("/")
            .append(String.format("%.0f", robot.getMaxHealth())).append("\n");

        // Nearby entities using getEntityWorld() and getOtherEntities
        var box = robot.getBoundingBox().expand(16);
        var entities = robot.getEntityWorld().getOtherEntities(robot, box, e -> true);
        if (!entities.isEmpty()) {
            sb.append("Nearby entities:\n");
            for (var e : entities.stream().limit(10).toList()) {
                sb.append("  - ").append(e.getName().getString())
                    .append(" (").append(e.getType().getName().getString()).append(")")
                    .append(" at ").append(String.format("%.0f", e.distanceTo(robot))).append("m\n");
            }
        }

        // Nearby blocks within 5 blocks
        sb.append("Nearby notable blocks:\n");
        BlockPos robotPos = robot.getBlockPos();
        int count = 0;
        for (int dx = -5; dx <= 5 && count < 10; dx++) {
            for (int dy = -2; dy <= 3 && count < 10; dy++) {
                for (int dz = -5; dz <= 5 && count < 10; dz++) {
                    BlockPos p = robotPos.add(dx, dy, dz);
                    var state = robot.getEntityWorld().getBlockState(p);
                    if (!state.isAir() && !state.isOf(net.minecraft.block.Blocks.STONE) && !state.isOf(net.minecraft.block.Blocks.DIRT) && !state.isOf(net.minecraft.block.Blocks.GRASS_BLOCK)) {
                        if (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.CRAFTING_TABLE) ||
                            state.isOf(net.minecraft.block.Blocks.FURNACE) || state.getBlock().getName().getString().toLowerCase().contains("ore") ||
                            state.isOf(net.minecraft.block.Blocks.BEDROCK) || state.isOf(net.minecraft.block.Blocks.WATER) ||
                            state.isOf(net.minecraft.block.Blocks.LAVA)) {
                            sb.append("  - ").append(state.getBlock().getName().getString())
                                .append(" at (").append(dx).append(", ").append(dy).append(", ").append(dz).append(")\n");
                            count++;
                        }
                    }
                }
            }
        }

        return sb.toString();
    }
}
