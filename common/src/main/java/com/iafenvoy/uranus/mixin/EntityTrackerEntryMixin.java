package com.iafenvoy.uranus.mixin;

import com.iafenvoy.uranus.event.EntityEvents;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public abstract class EntityTrackerEntryMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendPairingData", at = @At("TAIL"))
    private void onStartTracking(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> consumer, CallbackInfo ci) {
        EntityEvents.START_TRACKING_TAIL.invoker().onTrackingStart(this.entity, player);
    }
}
