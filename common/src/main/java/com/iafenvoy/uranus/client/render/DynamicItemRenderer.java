package com.iafenvoy.uranus.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;

@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface DynamicItemRenderer {
    Map<Item, DynamicItemRenderer> RENDERERS = new HashMap<>();

    void render(ItemStack var1, ItemDisplayContext var2, PoseStack var3, MultiBufferSource var4, int var5, int var6);
}
