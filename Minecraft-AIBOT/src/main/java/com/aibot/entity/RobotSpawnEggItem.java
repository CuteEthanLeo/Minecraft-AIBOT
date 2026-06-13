package com.aibot.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Custom spawn egg that enforces one-robot-per-world rule.
 * Prevents spawning a second robot if one already exists in the world.
 */
public class RobotSpawnEggItem extends SpawnEggItem {

    public RobotSpawnEggItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (!world.isClient() && world instanceof ServerWorld serverWorld) {
            // Check if a robot already exists in this world
            var existing = serverWorld.getEntitiesByType(
                net.minecraft.util.TypeFilter.instanceOf(RobotEntity.class),
                r -> r.isAlive()
            );
            if (!existing.isEmpty()) {
                // Robot already exists — warn and don't spawn
                if (context.getPlayer() != null) {
                    context.getPlayer().sendMessage(
                        Text.literal("§c这个世界已经有一个机器人了！无法生成第二个"), true);
                }
                return ActionResult.FAIL;
            }
        }
        return super.useOnBlock(context);
    }
}
