package com.aibot.client;

import com.aibot.entity.RobotScreenHandler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Client-side GUI screen for the robot's inventory.
 * Displays 3 rows of robot inventory + player inventory + hotbar.
 */
public class RobotScreen extends HandledScreen<RobotScreenHandler> {

    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/gui/container/generic_54.png");
    private static final int ROWS = 3;

    public RobotScreen(RobotScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        // 3 rows × 18px + player inventory area
        this.backgroundHeight = 114 + ROWS * 18;
        this.playerInventoryTitleY = this.backgroundHeight - 94;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int i = (this.width - this.backgroundWidth) / 2;
        int j = (this.height - this.backgroundHeight) / 2;

        // Top section: robot inventory (3 rows of 9 slots)
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, i, j,
            0.0F, 0.0F, this.backgroundWidth, ROWS * 18 + 17, 256, 256);

        // Bottom section: player inventory + hotbar
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE,
            i, j + ROWS * 18 + 17, 0.0F, 126.0F, this.backgroundWidth, 96, 256, 256);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Draw the robot inventory title (top)
        context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
        // Draw the "Inventory" label (bottom)
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
    }
}
