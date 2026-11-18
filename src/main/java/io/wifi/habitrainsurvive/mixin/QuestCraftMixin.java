package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.quest.QuestManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A crafted item is both "obtained" and "crafted"; both triggers stay independent in the JSON. */
@Mixin(ResultSlot.class)
public class QuestCraftMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void habi$questCrafted(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer) || stack == null || stack.isEmpty() || !ModuleConfig.isEnabled(ModuleId.QUEST)) return;
        QuestManager.get().onItemCrafted(serverPlayer, stack);
        QuestManager.get().onItemObtained(serverPlayer, stack);
    }
}
