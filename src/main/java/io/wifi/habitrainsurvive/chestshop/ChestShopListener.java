package io.wifi.habitrainsurvive.chestshop;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ChestShopListener {
    public static void register() {
        // 左键点击商店容器 -> 选中商店并弹出快捷交互面板与GUI
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CHEST_SHOP) || world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            String dim = sp.serverLevel().dimension().location().toString();
            ChestShop shop = ChestShopManager.get().getShopAt(dim, pos);
            if (shop != null) {
                if (sp.getMainHandItem().is(Items.WOODEN_HOE)) return InteractionResult.PASS;
                ChestShopManager.get().setLastSelectedShop(sp.getUUID(), shop.getPosKey());
                ChestShopGui.open(sp, shop);
                return InteractionResult.SUCCESS;
            }
            if (!sp.getMainHandItem().isEmpty() && !sp.getMainHandItem().is(Items.WOODEN_HOE)
                    && ChestShopManager.supported(world.getBlockEntity(pos))) {
                ChestShopGui.confirmCreate(sp, pos);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        // 右键点击商店容器 -> 非店主直接打开商店购买/出售GUI界面并禁止偷开箱子
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CHEST_SHOP) || world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            String dim = sp.serverLevel().dimension().location().toString();
            ChestShop shop = ChestShopManager.get().getShopAt(dim, pos);
            if (shop != null) {
                ChestShopManager.get().setLastSelectedShop(sp.getUUID(), shop.getPosKey());
                if (!shop.canManage(sp.getUUID()) && !sp.hasPermissions(2)) {
                    ChestShopGui.open(sp, shop);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 破坏保护 -> 防止商店箱子被非店主破坏
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CHEST_SHOP) || world.isClientSide() || !(player instanceof ServerPlayer sp)) return true;
            String dim = sp.serverLevel().dimension().location().toString();
            ChestShop shop = ChestShopManager.get().getShopAt(dim, pos);
            if (shop != null) {
                if (!shop.ownerUuid.equals(sp.getUUID()) && !sp.hasPermissions(2)) {
                    MessageUtil.sendError(sp, "箱子商店", "您不能破坏属于 " + shop.ownerName + " 的箱子商店！");
                    return false;
                }
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CHEST_SHOP) || !(player instanceof ServerPlayer sp)) return;
            ChestShop shop = ChestShopManager.get().getShopAt(world.dimension().location().toString(), pos);
            if (shop != null) {
                ChestShopManager.get().removeShop(shop.getPosKey());
                MessageUtil.sendInfo(sp, "箱子商店", "箱子已被破坏，商店记录已同步移除。");
            }
        });
    }

    public static void showShopCard(ServerPlayer player, ChestShop shop) {
        ItemStack template = ItemSerializer.deserialize(shop.serializedItem, player.server.registryAccess());
        Container container = ChestShopManager.get().resolveContainer(player, shop);
        int stock = shop.adminInfinite ? 999999 : ChestShopManager.get().countStock(container, template);

        player.sendSystemMessage(Component.literal("┏━━━ 箱子商店详情 [" + (shop.adminInfinite ? "系统无限商店" : shop.ownerName) + "] ━━━┓").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("┃ 商品: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(shop.itemDisplayName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" (库存: " + (shop.adminInfinite ? "无限" : stock) + ")").withStyle(ChatFormatting.WHITE)));
        if (shop.buyPrice > 0) {
            player.sendSystemMessage(Component.literal("┃ 售价: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(EconomyManager.get().format(shop.buyPrice) + "/件 ").withStyle(ChatFormatting.GREEN))
                    .append(MessageUtil.commandButton("买1个", "/qs buy 1", "点击购买 1 个", ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("买16个", "/qs buy 16", "点击购买 16 个", ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("买64个", "/qs buy 64", "点击购买 64 个", ChatFormatting.AQUA)));
        }
        if (shop.sellPrice > 0) {
            player.sendSystemMessage(Component.literal("┃ 收购: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(EconomyManager.get().format(shop.sellPrice) + "/件 ").withStyle(ChatFormatting.YELLOW))
                    .append(MessageUtil.commandButton("卖1个", "/qs sell 1", "点击出售 1 个", ChatFormatting.GOLD))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("卖16个", "/qs sell 16", "点击出售 16 个", ChatFormatting.GOLD))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("卖64个", "/qs sell 64", "点击出售 64 个", ChatFormatting.GOLD)));
        }
        player.sendSystemMessage(Component.literal("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛").withStyle(ChatFormatting.GOLD));
    }
}
