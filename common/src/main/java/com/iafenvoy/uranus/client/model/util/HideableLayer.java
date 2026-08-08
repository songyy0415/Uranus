package com.iafenvoy.uranus.client.model.util;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Migrated to the 26.2 render-state architecture (P2.3-D1).
 *
 * <p>Migration notes (old API → new API, breaking change, reason):
 * <ul>
 *   <li>{@code <T extends Entity, M extends EntityModel<T>, C extends RenderLayer<T, M>>} →
 *       {@code <S extends EntityRenderState, M extends EntityModel<? super S>, C extends RenderLayer<S, M>>}.
 *       Layers are driven from the render state, never from the live entity.</li>
 *   <li>{@code render(PoseStack, MultiBufferSource, int, T, float, float, float, float, float, float)} →
 *       {@code submit(PoseStack, SubmitNodeCollector, int, S, float yRot, float xRot)}.
 *       {@code MultiBufferSource} is deleted in 26.2; geometry is deferred via the
 *       {@link SubmitNodeCollector}. The wrapper simply forwards to the wrapped layer when not
 *       hidden — no {@code submitModel} call is needed here because the wrapped layer is an
 *       independent {@link RenderLayer} with its own {@code submit}.</li>
 * </ul>
 */
public class HideableLayer<S extends EntityRenderState, M extends EntityModel<? super S>, C extends RenderLayer<S, M>> extends RenderLayer<S, M> {

    final C layerRenderer;
    public boolean hidden;

    public HideableLayer(C layerRenderer, RenderLayerParent<S, M> entityRendererIn) {
        super(entityRendererIn);
        this.hidden = false;
        this.layerRenderer = layerRenderer;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, S state, float yRot, float xRot) {
        if (!this.hidden)
            this.layerRenderer.submit(poseStack, submitNodeCollector, lightCoords, state, yRot, xRot);
    }
}
