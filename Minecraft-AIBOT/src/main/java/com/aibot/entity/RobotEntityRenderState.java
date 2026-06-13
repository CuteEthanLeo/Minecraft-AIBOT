package com.aibot.entity;

import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

/**
 * Render state for the AI Robot entity.
 * Holds per-frame data extracted from the entity for the renderer to use,
 * decoupling rendering from game logic (1.21.4+ render state system).
 */
public class RobotEntityRenderState extends BipedEntityRenderState {
    // All required animation fields are inherited from BipedEntityRenderState
    // which provides handSwingProgress, limb swing data, etc.
}
