package com.iafenvoy.uranus.data;

import net.minecraft.world.inventory.ContainerData;

@Deprecated(forRemoval = true)
public class EntityPropertyDelegate implements ContainerData {
    public int entityId = -1;

    public EntityPropertyDelegate() {
    }

    public EntityPropertyDelegate(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public int get(int index) {
        return this.entityId;
    }

    @Override
    public void set(int index, int value) {
        this.entityId = value;
    }

    @Override
    public int getCount() {
        return 1;
    }
}
