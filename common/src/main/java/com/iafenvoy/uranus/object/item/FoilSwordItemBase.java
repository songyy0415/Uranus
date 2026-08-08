package com.iafenvoy.uranus.object.item;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;

@Deprecated(forRemoval = true)
public class FoilSwordItemBase extends SwordItemBase {
    public FoilSwordItemBase(Tier toolMaterial, Properties settings) {
        super(toolMaterial, settings);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean isFoil(ItemStack itemtack) {
        return true;
    }
}
