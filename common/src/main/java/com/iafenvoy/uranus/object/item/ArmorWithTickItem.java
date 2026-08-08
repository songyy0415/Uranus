package com.iafenvoy.uranus.object.item;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.level.Level;

@Deprecated(forRemoval = true)
public abstract class ArmorWithTickItem extends Item {
    private static final List<ArmorWithTickItem> NEED_TO_TICK = new ArrayList<>();

    public ArmorWithTickItem(ArmorMaterial material, ArmorType slot, Properties settings) {
        super(settings.humanoidArmor(material, slot));
        NEED_TO_TICK.add(this);
    }

    public static void doTick(Level world, Player entity) {
        synchronized (NEED_TO_TICK) {
            for (ArmorWithTickItem item : NEED_TO_TICK)
                item.onArmorTick(world, entity);
        }
    }

    public abstract void onArmorTick(Level world, Player entity);
}
