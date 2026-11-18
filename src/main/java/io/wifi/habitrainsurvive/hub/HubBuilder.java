package io.wifi.habitrainsurvive.hub;

import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Deterministic, resumable 97x97 lobby. No writes after its completion marker. */
public final class HubBuilder {
    private record Placement(BlockPos pos, BlockState state) {}
    private final List<Placement> plan = new ArrayList<>();
    public HubBuilder() {
        for (int x = -48; x <= 48; x++) for (int z = -48; z <= 48; z++) {
            put(x, 78, z, Blocks.BEDROCK); put(x, 79, z, Blocks.STONE_BRICKS);
            int r = Math.max(Math.abs(x), Math.abs(z));
            boolean road = Math.abs(x) <= 3 || Math.abs(z) <= 3;
            boolean plaza = x * x + z * z <= 190;
            Block surface = r == 48 || r == 44 ? Blocks.POLISHED_DEEPSLATE : road ? (Math.abs(x) == 3 || Math.abs(z) == 3 ? Blocks.CHISELED_STONE_BRICKS : Blocks.SMOOTH_QUARTZ)
                    : plaza ? ((x + z) % 4 == 0 ? Blocks.SEA_LANTERN : Blocks.QUARTZ_BRICKS) : Blocks.GRASS_BLOCK;
            put(x, 80, z, surface);
            if (r == 48) put(x, 81, z, Blocks.STONE_BRICK_WALL);
        }
        for (int x : new int[]{-38, -20, 20, 38}) for (int z : new int[]{-38, -20, 20, 38}) {
            for (int y = 81; y <= 85; y++) put(x, y, z, Blocks.OAK_LOG);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                put(x + dx, 85, z + dz, Blocks.OAK_LEAVES); put(x + dx, 86, z + dz, Blocks.OAK_LEAVES);
            }
            put(x, 87, z, Blocks.OAK_LEAVES);
        }
        for (int i : new int[]{-40, -24, -10, 10, 24, 40}) for (int sign : new int[]{-1, 1}) {
            lamp(i, sign * 5); lamp(sign * 5, i);
        }
        kiosk(0, -28, Blocks.DIAMOND_BLOCK); kiosk(0, 28, Blocks.AMETHYST_BLOCK);
        kiosk(-28, 0, Blocks.EMERALD_BLOCK); kiosk(28, 0, Blocks.GOLD_BLOCK);
        for (int x = -4; x <= 4; x++) for (int z = 7; z <= 15; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z - 11));
            put(x, 80, z, Blocks.SEA_LANTERN);
            if (edge == 4) put(x, 81, z, Blocks.QUARTZ_BLOCK);
            else put(x, 81, z, Blocks.WATER);
        }
        for (int y = 81; y <= 84; y++) put(0, y, 11, Blocks.QUARTZ_PILLAR);
        put(0, 85, 11, Blocks.SEA_LANTERN);
        for (int x : new int[]{-12, 12}) for (int z : new int[]{-18, 18}) {
            put(x, 81, z, Blocks.SMOOTH_STONE_SLAB); put(x + 1, 81, z, Blocks.SMOOTH_STONE_SLAB);
            put(x, 81, z + 2, Blocks.FLOWERING_AZALEA);
        }
    }
    private void put(int x, int y, int z, Block block) { plan.add(new Placement(new BlockPos(x, y, z), block.defaultBlockState())); }
    private void lamp(int x, int z) { for (int y = 81; y <= 84; y++) put(x, y, z, Blocks.SPRUCE_FENCE); put(x, 85, z, Blocks.SEA_LANTERN); }
    private void kiosk(int x, int z, Block icon) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) put(x + dx, 80, z + dz, Blocks.POLISHED_ANDESITE);
        for (int dx : new int[]{-4, 4}) for (int dz : new int[]{-4, 4}) for (int y = 81; y <= 85; y++) put(x + dx, y, z + dz, Blocks.QUARTZ_PILLAR);
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) put(x + dx, 86, z + dz, Blocks.DARK_PRISMARINE);
        put(x, 81, z, Blocks.QUARTZ_BLOCK); put(x, 82, z, icon); put(x, 83, z, Blocks.OAK_SIGN);
    }
    public void tick(int budget) {
        WorldManager manager = WorldManager.get(); ServerLevel level = manager.level(WorldManager.HUB);
        WorldState.Runtime state = manager.runtime(WorldManager.HUB);
        if (level == null || state.hubBuilt || state.imported) return;
        if (state.hubBuildCursor == 0) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            level.setBlock(new BlockPos(x, 80, z), Blocks.SMOOTH_QUARTZ.defaultBlockState(), 2);
        int end = Math.min(plan.size(), state.hubBuildCursor + budget);
        for (; state.hubBuildCursor < end; state.hubBuildCursor++) {
            Placement p = plan.get(state.hubBuildCursor); level.setBlock(p.pos, p.state, 2);
        }
        if (state.hubBuildCursor >= plan.size()) {
            sign(level, 0, -28, "§b生存世界", "§7永久保存 / 可圈地", "§f右键下方钻石块", "§e开始你的旅程");
            sign(level, 0, 28, "§d资源世界", "§7周期重置 / 勿定居", "§f右键下方紫晶块", "§e寻找新的矿脉");
            sign(level, -28, 0, "§a贸易中心", "§7官方商店 / 玩家交易", "§f右键下方绿宝石块", "§e打开官方商城");
            sign(level, 28, 0, "§6服务器菜单", "§7领地 / 城镇 / 指南", "§f右键下方金块", "§e也可输入 /menu");
            level.getChunkSource().save(true);
            state.hubBuilt = true; manager.save();
            HabiTrainSurvive.LOGGER.info("独立主城广场建造完成：97x97，{} 个方块放置步骤。", plan.size());
        }
    }
    private void sign(ServerLevel level, int x, int z, String... lines) {
        if (level.getBlockEntity(new BlockPos(x, 83, z)) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
            sign.setText(text, true); sign.setWaxed(true); sign.setChanged();
        }
    }
}
