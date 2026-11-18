package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.quest.QuestManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts pickups only when vanilla consumed the item entity. The stack is captured before the
 * pickup because a successful pickup empties or discards it before the tail injection runs.
 */
@Mixin(ItemEntity.class)
public class QuestPickupMixin {
    @Unique
    private ItemStack habi$questPickupSnapshot = ItemStack.EMPTY;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void habi$questPickupHead(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer) || !ModuleConfig.isEnabled(ModuleId.QUEST)) return;
        habi$questPickupSnapshot = ((ItemEntity) (Object) this).getItem().copy();
    }

    @Inject(method = "playerTouch", at = @At("TAIL"))
    private void habi$questPickupTail(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        ItemStack snapshot = habi$questPickupSnapshot;
        habi$questPickupSnapshot = ItemStack.EMPTY;
        if (snapshot.isEmpty() || !((ItemEntity) (Object) this).isRemoved()) return;
        QuestManager.get().onItemObtained(serverPlayer, snapshot);
    }
}
