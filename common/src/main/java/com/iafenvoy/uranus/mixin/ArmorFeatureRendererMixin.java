package com.iafenvoy.uranus.mixin;

import com.iafenvoy.uranus.client.render.armor.IArmorRendererBase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migrated to the 26.2 render-state / Equipment Asset architecture (P2.3-C3-C2, Design A).
 *
 * <p>Migration notes (old API → new API, breaking change, reason):
 * <ul>
 *   <li>{@code <T extends LivingEntity, M extends HumanoidModel<T>, A extends HumanoidModel<T>>}
 *       → pinned {@code RenderLayer<HumanoidRenderState, HumanoidModel<HumanoidRenderState>>}.
 *       {@code LivingEntity} is gone from the client render path — 26.2 layers are driven from the
 *       render state, so the mixin is pinned to concrete types and {@code getParentModel()} returns
 *       exactly the {@code HumanoidModel<HumanoidRenderState>} the SPI needs. No casts, no
 *       {@code @SuppressWarnings("unchecked")}.</li>
 *   <li>{@code renderArmorPiece(PoseStack, MultiBufferSource, T, EquipmentSlot, int, A)} →
 *       {@code renderArmorPiece(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, HumanoidRenderState)}
 *       (javap-verified descriptor, all six data params are plain method args — no
 *       {@code @Local}/{@code LocalCapture} needed). {@code MultiBufferSource} is deleted in 26.2;
 *       geometry is deferred via the {@link SubmitNodeCollector}.</li>
 *   <li>The old handler looked the item up via {@code entity.getItemBySlot(...)} and rendered the
 *       custom model itself. Now the {@code ItemStack} and render state arrive as arguments, and
 *       all rendering (texture, dye, overlay, trim, glint) is delegated to the vanilla
 *       {@link EquipmentLayerRenderer#renderLayers} — this mixin only supplies the custom armor
 *       model (via {@link IArmorRendererBase#getHumanoidArmorModel}) and its part visibility. It
 *       never builds a {@code RenderType} or re-implements the Equipment Asset lookup.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorFeatureRendererMixin extends RenderLayer<HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    @Shadow
    private EquipmentLayerRenderer equipmentRenderer;

    public ArmorFeatureRendererMixin(RenderLayerParent<HumanoidRenderState, HumanoidModel<HumanoidRenderState>> context) {
        super(context);
    }

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void onRenderArmor(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
        IArmorRendererBase<HumanoidRenderState> renderer = IArmorRendererBase.RENDERERS.get(stack.getItem());
        if (renderer != null) {
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.assetId().isPresent() && equippable.slot() == slot) {
                HumanoidModel<HumanoidRenderState> armorModel = renderer.getHumanoidArmorModel(state, stack, slot, this.getParentModel());
                armorModel.head.visible = slot == EquipmentSlot.HEAD;
                armorModel.hat.visible = slot == EquipmentSlot.HEAD;
                armorModel.body.visible = slot == EquipmentSlot.CHEST;
                armorModel.leftArm.visible = slot == EquipmentSlot.CHEST;
                armorModel.rightArm.visible = slot == EquipmentSlot.CHEST;
                armorModel.leftLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
                armorModel.rightLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
                EquipmentClientInfo.LayerType layerType = state.isBaby && state.entityType != EntityTypes.ARMOR_STAND
                        ? EquipmentClientInfo.LayerType.HUMANOID_BABY
                        : (slot == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID);
                this.equipmentRenderer.renderLayers(layerType, equippable.assetId().orElseThrow(), armorModel, state, stack, poseStack, collector, light, state.outlineColor);
                ci.cancel();
            }
        }
    }
}
