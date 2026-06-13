package com.aibot.entity;

import com.aibot.Registry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;

/**
 * Screen handler for the robot's inventory.
 * Provides a 27-slot inventory (3 rows of 9) plus the player's hotbar and inventory.
 */
public class RobotScreenHandler extends ScreenHandler {

    private final Inventory robotInventory;
    private final RobotEntity robot;

    // Slot indices for shift-click logic
    // Actual layout: robot inv 0-26, player hotbar 27-35, player main 36-62
    private static final int ROBOT_INV_START = 0;
    private static final int ROBOT_INV_END = 26;
    private static final int PLAYER_HOTBAR_START = 27;
    private static final int PLAYER_HOTBAR_END = 35;
    private static final int PLAYER_MAIN_START = 36;
    private static final int PLAYER_MAIN_END = 62;
    private static final int PLAYER_ALL_START = PLAYER_HOTBAR_START; // 27 — first player-owned slot
    private static final int PLAYER_ALL_END = PLAYER_MAIN_END;       // 62 — last player-owned slot

    public RobotScreenHandler(int syncId, PlayerInventory playerInventory, Inventory robotInventory, RobotEntity robot) {
        super(Registry.ROBOT_SCREEN_HANDLER, syncId);
        this.robotInventory = robotInventory;
        this.robot = robot;
        robotInventory.onOpen(playerInventory.player);

        // Robot inventory (3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(robotInventory, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }

        // Player inventory (3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 85 + row * 18));
            }
        }

        // Player hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 143));
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);

        if (slot != null && slot.hasStack()) {
            ItemStack originalStack = slot.getStack();
            newStack = originalStack.copy();

            if (slotIndex <= ROBOT_INV_END) {
                // Move from robot inventory to player inventory
                if (!this.insertItem(originalStack, PLAYER_ALL_START, PLAYER_ALL_END + 1, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Move from player inventory to robot inventory
                if (!this.insertItem(originalStack, ROBOT_INV_START, ROBOT_INV_END + 1, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (originalStack.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            } else {
                slot.markDirty();
            }
        }

        return newStack;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return this.robotInventory.canPlayerUse(player) && (robot == null || robot.isAlive());
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.robotInventory.onClose(player);
    }

    public RobotEntity getRobot() {
        return robot;
    }
}
