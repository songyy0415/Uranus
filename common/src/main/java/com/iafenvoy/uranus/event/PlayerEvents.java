package com.iafenvoy.uranus.event;

import net.minecraft.world.entity.player.Player;

public class PlayerEvents {
    public static final Event<PlayerLoggedInOrOut> LOGGED_OUT = new com.iafenvoy.uranus.event.Event<>(callbacks -> player -> {
        for (PlayerLoggedInOrOut e : callbacks)
            e.handleConnection(player);
    });

    /**
     * Use this interface to handle players logging in and out.
     */
    @FunctionalInterface
    public interface PlayerLoggedInOrOut {
        void handleConnection(Player player);
    }
}
