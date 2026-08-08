package com.iafenvoy.uranus.mixin;

import com.iafenvoy.uranus.client.render.armor.IArmorRendererBase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorFeatureRendererMixin<T extends LivingEntity, M extends HumanoidModel<T>, A extends HumanoidModel<T>> extends RenderLayer<T, M> {
    public ArmorFeatureRendererMixin(RenderLayerParent<T, M> context) {
        super(context);
    }

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void onRenderArmor(PoseStack matrices, MultiBufferSource vertexConsumers, T entity, EquipmentSlot armorSlot, int light, A model, CallbackInfo ci) {
        ItemStack stack = entity.getItemBySlot(armorSlot);
        @SuppressWarnings("unchecked")
        IArmorRendererBase<T> renderer = (IArmorRendererBase<T>) IArmorRendererBase.RENDERERS.get(stack.getItem());
        if (renderer != null) {
            renderer.render(matrices, vertexConsumers, entity, armorSlot, light, stack, this.getParentModel());
            ci.cancel();
        }
    }
}
