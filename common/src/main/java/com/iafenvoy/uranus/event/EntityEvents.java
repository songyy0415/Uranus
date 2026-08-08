package com.iafenvoy.uranus.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public class EntityEvents {
    public static final Event<JoinWorld> ON_JOIN_WORLD = new Event<>(callbacks -> (entity, world) -> {
        for (JoinWorld callback : callbacks)
            if (!callback.onJoinWorld(entity, world))
                return true;
        return false;
    });

    public static final Event<Tracking> START_TRACKING_TAIL = new Event<>(callbacks -> (entity, player) -> {
        for (Tracking callback : callbacks)
            callback.onTrackingStart(entity, player);
    });

    @FunctionalInterface
    public interface JoinWorld {
        boolean onJoinWorld(Entity entity, Level world);
    }

    @FunctionalInterface
    public interface Tracking {
        void onTrackingStart(Entity tracking, ServerPlayer player);
    }
}
