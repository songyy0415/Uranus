package com.iafenvoy.uranus.client.render.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Migrated to the 26.2 render-state architecture (P2.3-C3-A).
 *
 * <p>Migration notes (old API → new API, breaking change, reason):
 * <ul>
 *   <li>{@code extends HumanoidModel<LivingEntity>} → {@code extends HumanoidModel<HumanoidRenderState>}.
 *       26.2 models are animated from the render state, never from the live entity.</li>
 *   <li>{@code setupAnim(Entity, float, float, float, float, float)} →
 *       {@code setupAnim(HumanoidRenderState)}; armor stand poses are read from the
 *       {@link ArmorStandRenderState} public {@code Rotations} fields via the record
 *       accessors {@code x()/y()/z()} ({@code getX()/getY()/getZ()} no longer exist).</li>
 *   <li>{@code hat.copyFrom(head)} removed — in 26.2 {@code hat} is a child of {@code head}
 *       ({@code HumanoidModel} constructor: {@code this.hat = this.head.getChild("hat")}),
 *       so the hat inherits the head transform automatically.</li>
 *   <li>{@code render(...)} / {@code renderHelmet/...} took {@code MultiBufferSource} +
 *       {@code Identifier texture} → now {@code SubmitNodeCollector} + {@code HumanoidRenderState}
 *       + {@code RenderType}. There is no {@code VertexConsumer} to hand out at submit time;
 *       geometry is deferred to {@code ModelFeatureRenderer.prepareModel}, which calls
 *       {@code setupAnim(state)} then the final {@code Model.renderToBuffer}. The render type is
 *       supplied by the caller (the equipment texture derivation is C3-B work).</li>
 *   <li>Glint is no longer merged into a single buffer via
 *       {@code ItemRenderer.getArmorFoilBuffer}; it is a separate
 *       {@code RenderTypes.armorEntityGlint()} submit.</li>
 *   <li>{@code INNER_MODEL_OFFSET}/{@code OUTER_MODEL_OFFSET} kept for subclass offset math;
 *       inner/outer selection itself is now expressed by {@code LayerType}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class ArmorModelBase extends HumanoidModel<HumanoidRenderState> {
    protected static final float INNER_MODEL_OFFSET = 0.38F;
    protected static final float OUTER_MODEL_OFFSET = 0.45F;

    public ArmorModelBase(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(HumanoidRenderState state) {
        super.setupAnim(state);
        if (state instanceof ArmorStandRenderState armorStandState) {
            this.head.xRot = (float) (Math.PI / 180.0) * armorStandState.headPose.x();
            this.head.yRot = (float) (Math.PI / 180.0) * armorStandState.headPose.y();
            this.head.zRot = (float) (Math.PI / 180.0) * armorStandState.headPose.z();
            this.body.xRot = (float) (Math.PI / 180.0) * armorStandState.bodyPose.x();
            this.body.yRot = (float) (Math.PI / 180.0) * armorStandState.bodyPose.y();
            this.body.zRot = (float) (Math.PI / 180.0) * armorStandState.bodyPose.z();
            this.leftArm.xRot = (float) (Math.PI / 180.0) * armorStandState.leftArmPose.x();
            this.leftArm.yRot = (float) (Math.PI / 180.0) * armorStandState.leftArmPose.y();
            this.leftArm.zRot = (float) (Math.PI / 180.0) * armorStandState.leftArmPose.z();
            this.rightArm.xRot = (float) (Math.PI / 180.0) * armorStandState.rightArmPose.x();
            this.rightArm.yRot = (float) (Math.PI / 180.0) * armorStandState.rightArmPose.y();
            this.rightArm.zRot = (float) (Math.PI / 180.0) * armorStandState.rightArmPose.z();
            this.leftLeg.xRot = (float) (Math.PI / 180.0) * armorStandState.leftLegPose.x();
            this.leftLeg.yRot = (float) (Math.PI / 180.0) * armorStandState.leftLegPose.y();
            this.leftLeg.zRot = (float) (Math.PI / 180.0) * armorStandState.leftLegPose.z();
            this.rightLeg.xRot = (float) (Math.PI / 180.0) * armorStandState.rightLegPose.x();
            this.rightLeg.yRot = (float) (Math.PI / 180.0) * armorStandState.rightLegPose.y();
            this.rightLeg.zRot = (float) (Math.PI / 180.0) * armorStandState.rightLegPose.z();
        }
    }

    public void render(EquipmentSlot slot, PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        switch (slot) {
            case HEAD -> this.renderHelmet(poseStack, collector, state, lightCoords, stack, renderType);
            case CHEST -> this.renderChestplate(poseStack, collector, state, lightCoords, stack, renderType);
            case LEGS -> this.renderLeggings(poseStack, collector, state, lightCoords, stack, renderType);
            case FEET -> this.renderBoots(poseStack, collector, state, lightCoords, stack, renderType);
        }
    }

    public void renderHelmet(PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        this.setArmorPartVisibility(EquipmentSlot.HEAD);
        this.submitArmor(poseStack, collector, state, lightCoords, stack, renderType);
    }

    public void renderChestplate(PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        this.setArmorPartVisibility(EquipmentSlot.CHEST);
        this.submitArmor(poseStack, collector, state, lightCoords, stack, renderType);
    }

    public void renderLeggings(PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        this.setArmorPartVisibility(EquipmentSlot.LEGS);
        this.submitArmor(poseStack, collector, state, lightCoords, stack, renderType);
    }

    public void renderBoots(PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        this.setArmorPartVisibility(EquipmentSlot.FEET);
        this.submitArmor(poseStack, collector, state, lightCoords, stack, renderType);
    }

    protected void setArmorPartVisibility(EquipmentSlot slot) {
        this.head.visible = slot == EquipmentSlot.HEAD;
        this.hat.visible = slot == EquipmentSlot.HEAD;
        this.body.visible = slot == EquipmentSlot.CHEST;
        this.leftArm.visible = slot == EquipmentSlot.CHEST;
        this.rightArm.visible = slot == EquipmentSlot.CHEST;
        this.leftLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
        this.rightLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    protected void submitArmor(PoseStack poseStack, SubmitNodeCollector collector, HumanoidRenderState state, int lightCoords, ItemStack stack, RenderType renderType) {
        collector.order(0).submitModel(this, state, poseStack, renderType, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        if (stack.hasFoil())
            collector.order(1).submitModel(this, state, poseStack, RenderTypes.armorEntityGlint(), lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
    }
}
