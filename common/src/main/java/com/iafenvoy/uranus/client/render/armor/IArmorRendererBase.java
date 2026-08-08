package com.iafenvoy.uranus.client.render.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import java.util.HashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Migrated to the 26.2 render-state architecture (P2.3-C3-A).
 *
 * <p>Migration notes (old API → new API, breaking change, reason):
 * <ul>
 *   <li>{@code <T extends LivingEntity>} → {@code <S extends HumanoidRenderState>}.
 *       Renderers are driven from the render state, never from the live entity.</li>
 *   <li>{@code getHumanoidArmorModel(LivingEntity, ...)} → {@code getHumanoidArmorModel(S, ...)}.</li>
 *   <li>Removed {@code getArmorTexture(ItemStack, Entity, EquipmentSlot)} — it read
 *       {@code ArmorItem.getMaterial().value().layers()} ({@code ArmorItem}/
 *       {@code ArmorMaterial.Layer} are deleted in 26.2). Texture selection is now data-driven
 *       (Equippable asset → equipment JSON, C3-B); the render type is supplied by the caller.</li>
 *   <li>{@code render(...)} took {@code MultiBufferSource} + {@code LivingEntity} →
 *       now {@code SubmitNodeCollector} + {@code HumanoidRenderState} + {@code RenderType}.
 *       {@code copyPropertiesTo} no longer exists — each armor model runs its own
 *       {@code setupAnim(state)} when {@code ModelFeatureRenderer.prepareModel} resolves the
 *       deferred submit. There is no {@code VertexConsumer} at submit time, so
 *       {@code model.renderToBuffer} is not called here ({@code Model.renderToBuffer} is final).</li>
 *   <li>Glint is a separate {@code RenderTypes.armorEntityGlint()} submit instead of
 *       {@code ItemRenderer.getArmorFoilBuffer}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public interface IArmorRendererBase<S extends HumanoidRenderState> {
    // Value type pinned to the concrete HumanoidRenderState (P2.3-C3-R §11/§B4, user-authorized
    // minimal correction): a wildcard would make type-safe invocation impossible for consumers
    // like ArmorFeatureRendererMixin, forcing an unchecked cast.
    HashMap<ItemLike, IArmorRendererBase<HumanoidRenderState>> RENDERERS = new HashMap<>();

    HumanoidModel<S> getHumanoidArmorModel(S state, ItemStack stack, EquipmentSlot slot, HumanoidModel<S> defaultModel);

    default void render(PoseStack poseStack, SubmitNodeCollector collector, S state, EquipmentSlot slot, int lightCoords, ItemStack stack, HumanoidModel<S> defaultModel, RenderType renderType) {
        HumanoidModel<S> armorModel = this.getHumanoidArmorModel(state, stack, slot, defaultModel);
        armorModel.head.visible = slot == EquipmentSlot.HEAD;
        armorModel.hat.visible = slot == EquipmentSlot.HEAD;
        armorModel.body.visible = slot == EquipmentSlot.CHEST;
        armorModel.leftArm.visible = slot == EquipmentSlot.CHEST;
        armorModel.rightArm.visible = slot == EquipmentSlot.CHEST;
        armorModel.leftLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
        armorModel.rightLeg.visible = slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
        collector.order(0).submitModel(armorModel, state, poseStack, renderType, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        if (stack.hasFoil())
            collector.order(1).submitModel(armorModel, state, poseStack, RenderTypes.armorEntityGlint(), lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
    }

    static void register(IArmorRendererBase<HumanoidRenderState> renderer, ItemLike... items) {
        Arrays.stream(items).forEach(x -> RENDERERS.put(x, renderer));
    }
}
