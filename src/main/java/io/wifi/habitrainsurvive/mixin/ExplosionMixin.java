package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.chestshop.ChestShopManager;
import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.town.Town;
import io.wifi.habitrainsurvive.town.TownManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private Level level;

    @Shadow
    public abstract List<BlockPos> getToBlow();

    @Inject(method = "finalizeExplosion", at = @At("HEAD"))
    private void habi$protectClaimsTownsAndShopsFromExplosion(boolean spawnParticles, CallbackInfo ci) {
        if (this.level.isClientSide()) return;
        String dim = this.level.dimension().location().toString();
        var worldPolicy = io.wifi.habitrainsurvive.hub.HubProtectionListener.policy(this.level);
        if (worldPolicy != null && !worldPolicy.explosions) { this.getToBlow().clear(); return; }
        this.getToBlow().removeIf(pos -> {
            // 1. 永远保护箱子商店容器不被炸毁
            if (ModuleConfig.isEnabled(ModuleId.CHEST_SHOP) && ChestShopManager.get().getShopAt(dim, pos) != null) {
                return true;
            }
            // 2. 保护未开启爆炸允许的私人领地
            if (ModuleConfig.isEnabled(ModuleId.CLAIM)) {
                Claim claim = ClaimManager.get().getClaimAt(dim, pos);
                if (claim != null && !claim.getFlag(ClaimFlag.EXPLOSION)) {
                    return true;
                }
            }
            // 3. 保护未开启爆炸允许的城镇区块
            if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
                Town town = TownManager.get().getTownAt(dim, pos);
                return town != null && !town.explosionEnabled;
            }
            return false;
        });
    }
}
