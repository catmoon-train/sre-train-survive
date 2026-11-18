package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.quest.QuestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts only placements vanilla accepted, never failed previews or client prediction. */
@Mixin(BlockItem.class)
public class QuestPlaceMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void habi$questPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) return;
        Level level = context.getLevel();
        if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player) || !ModuleConfig.isEnabled(ModuleId.QUEST)) return;
        BlockPos pos = context.getClickedPos();
        QuestManager.get().onBlockPlaced(player, level.getBlockState(pos));
    }
}
