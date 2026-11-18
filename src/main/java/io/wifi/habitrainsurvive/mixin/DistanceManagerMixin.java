package io.wifi.habitrainsurvive.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    // A missing bucket must still run vanilla distance/ticket cleanup during dimension travel.
    // Do not catch the exception after teleportTo has already started removing the player.
    @Redirect(method = "removePlayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;", remap = false))
    private Object habi$missingPlayerBucket(Long2ObjectMap<ObjectSet<ServerPlayer>> players, long chunk) {
        ObjectSet<ServerPlayer> bucket = players.get(chunk);
        return bucket != null ? bucket : new ObjectOpenHashSet<ServerPlayer>();
    }
}
