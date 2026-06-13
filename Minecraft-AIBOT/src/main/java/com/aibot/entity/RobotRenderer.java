package com.aibot.entity;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.util.Identifier;

/**
 * Robot entity renderer adapted for Minecraft 1.21.2+ Render State Split.
 */
public class RobotRenderer extends MobEntityRenderer<RobotEntity, RobotEntityRenderState, RobotModel> {

    // Uses the Steve texture from RobotModel
    private static final Identifier TEXTURE = RobotModel.TEXTURE;

    public RobotRenderer(EntityRendererFactory.Context context) {
        super(context, new RobotModel(context.getPart(RobotModel.LAYER)), 0.5f);
    }

    @Override
    public RobotEntityRenderState createRenderState() {
        return new RobotEntityRenderState();
    }

    @Override
    public void updateRenderState(RobotEntity entity, RobotEntityRenderState state, float partialTick) {
        super.updateRenderState(entity, state, partialTick);
    }

    @Override
    public Identifier getTexture(RobotEntityRenderState state) {
        return TEXTURE;
    }
}
