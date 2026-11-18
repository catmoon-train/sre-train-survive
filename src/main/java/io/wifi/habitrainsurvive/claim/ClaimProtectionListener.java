package io.wifi.habitrainsurvive.claim;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.security.CombatTagManager;
import io.wifi.habitrainsurvive.town.Town;
import io.wifi.habitrainsurvive.town.TownManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ClaimProtectionListener {
    public static void register() {
        // 1. 木锄头 (WOODEN_HOE) 左键设置领地对角点 #1
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp && sp.getMainHandItem().is(Items.WOODEN_HOE)) {
                ClaimManager.get().setPos1(sp, pos);
                MessageUtil.sendSuccess(sp, "木锄圈地", "已设置领地对角点 #1: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        // 2. 木锄头潜行+右键空中呼出领地控制面板 GUI + 末影珍珠/流体桶防潜入拦截
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack held = player.getItemInHand(hand);
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResultHolder.pass(held);
            }
            if (hand == InteractionHand.MAIN_HAND && held.is(Items.WOODEN_HOE) && sp.isShiftKeyDown()) {
                Claim standing = ClaimManager.get().getClaimAt(sp.serverLevel().dimension().location().toString(), sp.blockPosition());
                if (standing != null) {
                    ClaimControlPanelGui.openClaimDashboard(sp, standing);
                } else {
                    ClaimControlPanelGui.openMainSelector(sp);
                }
                return InteractionResultHolder.success(held);
            }

            if (!sp.hasPermissions(2)) {
                String dim = sp.serverLevel().dimension().location().toString();
                Claim claim = ClaimManager.get().getClaimAt(dim, sp.blockPosition());
                if (claim != null) {
                    if ((held.is(Items.ENDER_PEARL) || held.is(Items.CHORUS_FRUIT)) && !claim.canUseEnderPearl(sp.getUUID())) {
                        MessageUtil.sendError(sp, "领地防护", "领地 [" + claim.name + "] 禁止非信任成员使用末影珍珠或紫颂果！");
                        return InteractionResultHolder.fail(held);
                    }
                    if (held.getItem() instanceof BucketItem && !claim.canPlace(sp.getUUID())) {
                        MessageUtil.sendError(sp, "领地防护", "您没有权限在领地 [" + claim.name + "] 内倾倒或舀取流体桶！");
                        return InteractionResultHolder.fail(held);
                    }
                }
            }
            return InteractionResultHolder.pass(held);
        });

        // 3. 方块破坏保护 (领地 + 城镇)
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return true;
            if (sp.hasPermissions(2)) return true;
            String dim = sp.serverLevel().dimension().location().toString();

            if (ModuleConfig.isEnabled(ModuleId.CLAIM)) {
                Claim claim = ClaimManager.get().getClaimAt(dim, pos);
                if (claim != null && !claim.canBreak(sp.getUUID())) {
                    MessageUtil.sendError(sp, "领地防护", "您没有权限在 [" + claim.name + "] 领地内破坏方块！");
                    return false;
                }
            }

            if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
                Town town = TownManager.get().getTownAt(dim, pos);
                if (town != null && !town.canBuild(dim, sp.getUUID(), pos)) {
                    MessageUtil.sendError(sp, "城镇保护", "您不是城镇 [" + town.name + "] 的授权居民，无法破坏此区域方块！");
                    return false;
                }
            }
            return true;
        });

        // 4. 方块交互与放置保护 + 木锄头 (WOODEN_HOE) 右键选点 #2 / 潜行右键呼出控制面板
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            ItemStack held = sp.getItemInHand(hand);

            if (ModuleConfig.isEnabled(ModuleId.CLAIM) && hand == InteractionHand.MAIN_HAND && held.is(Items.WOODEN_HOE)) {
                if (sp.isShiftKeyDown()) {
                    Claim standing = ClaimManager.get().getClaimAt(sp.serverLevel().dimension().location().toString(), pos);
                    if (standing != null) {
                        ClaimControlPanelGui.openClaimDashboard(sp, standing);
                    } else {
                        ClaimControlPanelGui.openMainSelector(sp);
                    }
                    return InteractionResult.SUCCESS;
                }
                ClaimManager.get().setPos2(sp, pos);
                MessageUtil.sendSuccess(sp, "木锄圈地", "已设置领地对角点 #2: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
                return InteractionResult.SUCCESS;
            }

            if (sp.hasPermissions(2)) return InteractionResult.PASS;
            String dim = sp.serverLevel().dimension().location().toString();
            BlockPos targetPos = (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem)
                    ? pos.relative(hitResult.getDirection()) : pos;

            Claim claim = ModuleConfig.isEnabled(ModuleId.CLAIM) ? ClaimManager.get().getClaimAt(dim, targetPos) : null;
            if (claim != null) {
                BlockState state = world.getBlockState(pos);
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof Container) {
                    if (!claim.canOpenContainer(sp.getUUID())) {
                        MessageUtil.sendError(sp, "领地防护", "您没有权限打开 [" + claim.name + "] 领地内的容器！");
                        return InteractionResult.FAIL;
                    }
                } else if (state.getBlock() instanceof CraftingTableBlock
                        || state.getBlock() instanceof AnvilBlock
                        || state.getBlock() instanceof EnchantingTableBlock
                        || state.getBlock() instanceof BedBlock) {
                    if (!claim.isTrustedMember(sp.getUUID()) && !claim.getFlag(ClaimFlag.WORKSTATION)) {
                        MessageUtil.sendError(sp, "领地防护", "领地 [" + claim.name + "] 未对访客开放功能工作站使用权！");
                        return InteractionResult.FAIL;
                    }
                } else if (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem || held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) {
                    if (!claim.canPlace(sp.getUUID())) {
                        MessageUtil.sendError(sp, "领地防护", "您没有权限在 [" + claim.name + "] 领地内放置方块或点火！");
                        return InteractionResult.FAIL;
                    }
                } else {
                    if (!claim.canInteract(sp.getUUID())) {
                        MessageUtil.sendError(sp, "领地防护", "您没有权限与 [" + claim.name + "] 领地内的红石或门禁交互！");
                        return InteractionResult.FAIL;
                    }
                }
            }

            if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
                Town town = TownManager.get().getTownAt(dim, targetPos);
                if (town != null) {
                    boolean isPlaceOrModify = held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem
                            || held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE);
                    boolean allowed = isPlaceOrModify
                            ? town.canBuild(dim, sp.getUUID(), targetPos)
                            : town.canInteract(dim, sp.getUUID(), targetPos);
                    if (!allowed) {
                        MessageUtil.sendError(sp, "城镇保护", "您没有权限在城镇 [" + town.name + "] 内交互或建造！");
                        return InteractionResult.FAIL;
                    }
                }
            }

            return InteractionResult.PASS;
        });

        // 5. 实体攻击保护 (PVP、生物保护与战斗状态标记)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            String dim = sp.serverLevel().dimension().location().toString();
            Claim claim = ModuleConfig.isEnabled(ModuleId.CLAIM) ? ClaimManager.get().getClaimAt(dim, entity.blockPosition()) : null;
            if (claim != null && !sp.hasPermissions(2)) {
                if (entity instanceof ServerPlayer) {
                    if (!claim.getFlag(ClaimFlag.PVP)) {
                        MessageUtil.sendError(sp, "领地防护", "领地 [" + claim.name + "] 禁止 PVP！");
                        return InteractionResult.FAIL;
                    }
                } else {
                    if (!claim.canBreak(sp.getUUID()) && !claim.getFlag(ClaimFlag.MOB_DAMAGE)) {
                        MessageUtil.sendError(sp, "领地防护", "领地 [" + claim.name + "] 禁止伤害生物！");
                        return InteractionResult.FAIL;
                    }
                }
            }
            if (ModuleConfig.isEnabled(ModuleId.TOWN) && !sp.hasPermissions(2)) {
                Town town = TownManager.get().getTownAt(dim, entity.blockPosition());
                if (town != null) {
                    if (entity instanceof ServerPlayer victim) {
                        if (!town.pvpEnabled) {
                            MessageUtil.sendError(sp, "城镇保护", "城镇 [" + town.name + "] 内禁止 PVP！");
                            return InteractionResult.FAIL;
                        }
                        if (!town.friendlyFire && TownManager.get().areFriendly(sp.getUUID(), victim.getUUID())) {
                            MessageUtil.sendError(sp, "城镇保护", "城镇 [" + town.name + "] 已关闭同城/同盟友军伤害！");
                            return InteractionResult.FAIL;
                        }
                    } else if (!town.canBuild(dim, sp.getUUID(), entity.blockPosition())) {
                        MessageUtil.sendError(sp, "城镇保护", "您没有权限在城镇 [" + town.name + "] 内攻击生物！");
                        return InteractionResult.FAIL;
                    }
                }
            }
            if (entity instanceof ServerPlayer victim && !sp.getUUID().equals(victim.getUUID())) {
                CombatTagManager.get().tagCombat(sp, victim);
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (sp.hasPermissions(2)) return InteractionResult.PASS;
            String dim = sp.serverLevel().dimension().location().toString();
            if (ModuleConfig.isEnabled(ModuleId.CLAIM)) {
                Claim claim = ClaimManager.get().getClaimAt(dim, entity.blockPosition());
                if (claim != null && !claim.canInteract(sp.getUUID())) {
                    MessageUtil.sendError(sp, "领地防护", "您没有权限与 [" + claim.name + "] 领地内的实体交互！");
                    return InteractionResult.FAIL;
                }
            }
            if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
                Town town = TownManager.get().getTownAt(dim, entity.blockPosition());
                if (town != null && !town.canInteract(dim, sp.getUUID(), entity.blockPosition())) {
                    MessageUtil.sendError(sp, "城镇保护", "您没有权限与城镇 [" + town.name + "] 内的实体交互！");
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });
    }
}
