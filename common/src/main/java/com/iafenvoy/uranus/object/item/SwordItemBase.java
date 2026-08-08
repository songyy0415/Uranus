package com.iafenvoy.uranus.object.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.Level;

@Deprecated(forRemoval = true)
public class SwordItemBase extends Item implements ISwingable {
    public SwordItemBase(ToolMaterial toolMaterial, Properties settings) {
        super(settings.sword(toolMaterial, 3.0F, -2.4F));
    }

    @Override
    public boolean onEntitySwing(ItemStack itemtack, Entity entity) {
        return onSwingHand(itemtack, entity.level(), entity.getX(), entity.getY(), entity.getZ());
    }

    @Override
    public boolean onSwingHand(ItemStack itemtack, Level world, double x, double y, double z) {
        return false;
    }
}
