package com.iafenvoy.uranus.client.model;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Tabula model animator, migrated to the 26.2 render-state architecture.
 *
 * <p>The live {@code Entity} is replaced by the renderer-extracted
 * {@link LivingEntityRenderState} — models must never read back from the Entity.
 * The animation inputs ({@code limbSwing/limbSwingAmount/ageInTicks/rotationYaw/rotationPitch})
 * are still passed explicitly, but {@link TabulaModel#setupAnim} now derives them from
 * the state fields ({@code walkAnimationPos/walkAnimationSpeed/ageInTicks/yRot/xRot})
 * instead of the old per-frame renderer parameters.
 */
@Environment(EnvType.CLIENT)
@FunctionalInterface
public interface ITabulaModelAnimator<T extends LivingEntityRenderState> {
    void setRotationAngles(TabulaModel<T> model, T state, float limbSwing, float limbSwingAmount, float ageInTicks, float rotationYaw, float rotationPitch, float scale);
}