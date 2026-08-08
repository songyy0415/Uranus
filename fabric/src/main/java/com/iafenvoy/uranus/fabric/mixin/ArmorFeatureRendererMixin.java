package com.iafenvoy.uranus.fabric.mixin;

import com.iafenvoy.uranus.client.render.armor.IArmorTextureProvider;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorFeatureRendererMixin<T extends LivingEntity, M extends HumanoidModel<T>, A extends HumanoidModel<T>> extends RenderLayer<T, M> {
    @Shadow
    protected abstract boolean usesInnerModel(EquipmentSlot slot);

    public ArmorFeatureRendererMixin(RenderLayerParent<T, M> context) {
        super(context);
    }

    @Deprecated(forRemoval = true)
    @ModifyExpressionValue(method = "renderArmorPiece", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ArmorMaterial$Layer;texture(Z)Lnet/minecraft/resources/ResourceLocation;"))
    private ResourceLocation pushTexture(ResourceLocation original,
                                   @Local(ordinal = 0) ItemStack itemStack,
                                   @Local(ordinal = 0, argsOnly = true) T entity,
                                   @Local(ordinal = 0, argsOnly = true) EquipmentSlot armorSlot,
                                   @Local(ordinal = 0) ArmorMaterial.Layer layer
    ) {
        if (itemStack.getItem() instanceof IArmorTextureProvider provider)
            return provider.getArmorTexture(itemStack, entity, armorSlot, layer, this.usesInnerModel(armorSlot));
        return original;
    }
}
