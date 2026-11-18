package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.menu.MenuManager;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MenuHotkeyMixin {
    @Shadow public ServerPlayer player;
    @Unique private int habi$lastOpen = -20;

    @Unique private void habi$openMenu() {
        int now = player.server.getTickCount();
        if (now - habi$lastOpen >= 10) {
            habi$lastOpen = now;
            MenuManager.get().open(player, null);
        }
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;getAction()Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;", ordinal = 0), cancellable = true)
    private void habi$sneakSwap(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (ModuleConfig.isEnabled(ModuleId.MENU) && packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && player.isShiftKeyDown() && MenuManager.get().isSneakSwapEnabled()) {
            habi$openMenu();
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void habi$inventorySneakSwap(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
        if (ModuleConfig.isEnabled(ModuleId.MENU) && packet.getContainerId() == player.containerMenu.containerId && packet.getClickType() == ClickType.SWAP
                && packet.getButtonNum() == 40 && player.isShiftKeyDown() && MenuManager.get().isSneakSwapEnabled()) {
            habi$openMenu();
            MenuGui.resync(player);
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void habi$restoreMenuIcons(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        MenuGui.resync(player);
    }
}
