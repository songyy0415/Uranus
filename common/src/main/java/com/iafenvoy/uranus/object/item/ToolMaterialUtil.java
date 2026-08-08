package com.iafenvoy.uranus.object.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.block.Block;

public class ToolMaterialUtil {
    public static ToolMaterial of(int uses, float speed, float attackDamageBonus, int enchantmentLevel, TagKey<Item> repairItems) {
        return of(TagKey.create(Registries.BLOCK, Identifier.parse("")), uses, speed, attackDamageBonus, enchantmentLevel, repairItems);
    }

    public static ToolMaterial of(TagKey<Block> inverseTag, int uses, float speed, float attackDamageBonus, int enchantmentLevel, TagKey<Item> repairItems) {
        return new ToolMaterial(inverseTag, uses, speed, attackDamageBonus, enchantmentLevel, repairItems);
    }
}
