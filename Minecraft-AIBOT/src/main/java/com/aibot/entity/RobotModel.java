package com.aibot.entity;

import net.minecraft.client.model.*;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelPartNames;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Robot entity model — Steve player appearance with standard biped animations.
 * Y coordinate convention (standard for BipedEntityModel):
 *   Head: y=-8 to 0, Body: y=0 to 12, Arms: y=0 to 12, Legs: y=12 to 24
 * After scale(-1,-1,1) + translate(0,-1.501,0) in the render pipeline,
 * lower Y values appear higher on screen, so head (-8) renders at top.
 */
public class RobotModel extends EntityModel<RobotEntityRenderState> {

    public static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("aibot", "robot"), "main");

    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public RobotModel(ModelPart root) {
        super(root);
        this.head = root.getChild(EntityModelPartNames.HEAD);
        this.body = root.getChild(EntityModelPartNames.BODY);
        this.rightArm = root.getChild(EntityModelPartNames.RIGHT_ARM);
        this.leftArm = root.getChild(EntityModelPartNames.LEFT_ARM);
        this.rightLeg = root.getChild(EntityModelPartNames.RIGHT_LEG);
        this.leftLeg = root.getChild(EntityModelPartNames.LEFT_LEG);
    }

    /**
     * Standard Steve player model data — 64x64 texture.
     * Coordinates match BipedEntityModel conventions.
     */
    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData root = modelData.getRoot();

        // Head: 8x8x8 at pivot (0, 0, 0)
        // Cuboid extends from y=-8 to y=0 (absolute)
        ModelPartData headData = root.addChild(EntityModelPartNames.HEAD,
            ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            ModelTransform.origin(0.0F, 0.0F, 0.0F)
        );

        // Hat overlay: child of head, UV (32,0), dilation +0.5 (Steve hat layer)
        headData.addChild("hat",
            ModelPartBuilder.create().uv(32, 0).cuboid(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new Dilation(0.5F)),
            ModelTransform.NONE
        );

        // Body: 8x12x4 at pivot (0, 0, 0), extends from y=0 to y=12
        root.addChild(EntityModelPartNames.BODY,
            ModelPartBuilder.create().uv(16, 16).cuboid(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F),
            ModelTransform.origin(0.0F, 0.0F, 0.0F)
        );

        // Right Arm: 4x12x4 at pivot (-5, 2, 0), extends from y=-2 to y=10 → absolute y=0 to y=12
        root.addChild(EntityModelPartNames.RIGHT_ARM,
            ModelPartBuilder.create().uv(40, 16).cuboid(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            ModelTransform.origin(-5.0F, 2.0F, 0.0F)
        );

        // Left Arm: 4x12x4 mirrored at pivot (5, 2, 0)
        root.addChild(EntityModelPartNames.LEFT_ARM,
            ModelPartBuilder.create().uv(40, 16).mirrored().cuboid(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            ModelTransform.origin(5.0F, 2.0F, 0.0F)
        );

        // Right Leg: 4x12x4 at pivot (-1.9, 12, 0), extends from y=0 to y=12 → absolute y=12 to y=24
        root.addChild(EntityModelPartNames.RIGHT_LEG,
            ModelPartBuilder.create().uv(0, 16).cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            ModelTransform.origin(-1.9F, 12.0F, 0.0F)
        );

        // Left Leg: 4x12x4 at pivot (1.9, 12, 0)
        root.addChild(EntityModelPartNames.LEFT_LEG,
            ModelPartBuilder.create().uv(16, 48).mirrored().cuboid(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            ModelTransform.origin(1.9F, 12.0F, 0.0F)
        );

        return TexturedModelData.of(modelData, 64, 64);
    }

    @Override
    public void setAngles(RobotEntityRenderState state) {
        if (state == null) return;

        // Head follows look direction (relativeHeadYaw is the head-body difference,
        // so the head rotates independently of body yaw)
        this.head.yaw = state.relativeHeadYaw * MathHelper.RADIANS_PER_DEGREE;
        this.head.pitch = state.pitch * MathHelper.RADIANS_PER_DEGREE;

        // NOTE: body yaw is NOT set here — the renderer's setupTransforms()
        // already rotates the entire model by bodyYaw. Setting it again would
        // double-rotate and make the body appear tilted.

        // Walking animation
        float limbSwing = state.limbSwingAnimationProgress;
        float limbSwingAmount = state.limbSwingAmplitude;
        float speed = 0.6662F;

        // Use limbAmplitudeInverse to scale animation (matching BipedEntityModel behavior)
        float inverse = state.limbAmplitudeInverse > 0.001F ? state.limbAmplitudeInverse : 1.0F;

        this.rightLeg.pitch = MathHelper.cos(limbSwing * speed) * 1.4F * limbSwingAmount / inverse;
        this.leftLeg.pitch  = MathHelper.cos(limbSwing * speed + MathHelper.PI) * 1.4F * limbSwingAmount / inverse;
        this.rightArm.pitch = MathHelper.cos(limbSwing * speed + MathHelper.PI) * 1.4F * limbSwingAmount / inverse;
        this.leftArm.pitch  = MathHelper.cos(limbSwing * speed) * 1.4F * limbSwingAmount / inverse;

        // Leg default rotations (subtle outward angle like BipedEntityModel)
        this.rightLeg.yaw = 0.005F;
        this.leftLeg.yaw = -0.005F;
        this.rightLeg.roll = 0.005F;
        this.leftLeg.roll = -0.005F;
    }
}
