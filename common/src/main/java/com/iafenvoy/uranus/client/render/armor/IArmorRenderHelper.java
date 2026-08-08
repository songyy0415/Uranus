package com.iafenvoy.uranus.client.render.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemStack;

/**
 * Migrated to the 26.2 render-state architecture (P2.3-C3-C1).
 *
 * <p>Migration notes (old API → new API, breaking change, reason):
 * <ul>
 *   <li>{@code renderPart(PoseStack, MultiBufferSource, int, ItemStack, Model, Identifier)} →
 *       {@code renderPart(PoseStack, SubmitNodeCollector, S, int, ItemStack, Model<? super S>, RenderType)}.
 *       {@code MultiBufferSource} is deleted in 26.2 — geometry is deferred to the GPU via the
 *       {@link SubmitNodeCollector}, so there is no {@code VertexConsumer} to hand out and
 *       {@code Model.renderToBuffer} is never called here (it is final and only invoked by the
 *       deferred {@code ModelFeatureRenderer.prepareModel} step).</li>
 *   <li>{@code ItemRenderer.getArmorFoilBuffer(..., texture, hasFoil)} → the caller supplies a
 *       {@link RenderType}; the texture is data-driven (Equippable asset → equipment JSON → PNG,
 *       C3-B), so the old {@code Identifier} SPI parameter is not re-introduced. Glint is a separate
 *       {@code RenderTypes.armorEntityGlint()} submit after the cutout one, one per model.</li>
 *   <li>{@code translateToChest(PoseStack, PlayerModel<AbstractClientPlayer>, AbstractClientPlayer)} →
 *       {@code translateToChest(PoseStack, HumanoidModel<S>, S)}. {@code PlayerModel} is no longer
 *       generic and is bound to {@code AvatarRenderState}; the helper is driven from the render
 *       state, never from the live entity — {@code player.isCrouching()}, the model's {@code riding}
 *       flag and {@code player.isSwimming()} become {@code state.isCrouching},
 *       {@code state.isPassenger} and {@code state.isVisuallySwimming}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public interface IArmorRenderHelper {
    // From net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer

    /**
     * Helper method for rendering a specific armor model, comes after setting visibility.
     *
     * <p>This primarily handles applying glint and the given {@link RenderType}.
     *
     * @param matrices   the matrix stack
     * @param collector  the submit node collector (replaces the deleted {@code MultiBufferSource})
     * @param state      the render state the model is animated from
     * @param light      packed lightmap coordinates
     * @param stack      the item stack of the armor item
     * @param model      the model to be rendered
     * @param renderType the render type to apply (armor texture render type supplied by the caller)
     */
    static <S extends EntityRenderState> void renderPart(PoseStack matrices, SubmitNodeCollector collector, S state, int light, ItemStack stack, Model<? super S> model, RenderType renderType) {
        collector.order(0).submitModel(model, state, matrices, renderType, light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        if (stack.hasFoil())
            collector.order(1).submitModel(model, state, matrices, RenderTypes.armorEntityGlint(), light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
    }

    // From trinkets
    static <S extends HumanoidRenderState> void translateToChest(PoseStack matrices, HumanoidModel<S> model, S state) {
        if (state.isCrouching && !state.isPassenger && !state.isVisuallySwimming) {
            matrices.translate(0.0F, 0.2F, 0.0F);
            matrices.mulPose(Axis.XP.rotation(model.body.xRot));
        }
        matrices.mulPose(Axis.YP.rotation(model.body.yRot));
        matrices.translate(0.0F, 0.4F, -0.16F);
    }
}
