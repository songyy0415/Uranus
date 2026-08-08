package com.iafenvoy.uranus.object.item;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import java.util.function.Function;

@Deprecated(forRemoval = true)
public class FoilItemBase extends ItemBase {
    public FoilItemBase(Function<Properties, Properties> properties) {
        super(properties);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean isFoil(ItemStack itemtack) {
        return true;
    }
}
