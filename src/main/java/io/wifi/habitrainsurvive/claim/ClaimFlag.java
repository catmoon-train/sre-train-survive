package io.wifi.habitrainsurvive.claim;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ClaimFlag {
    BLOCK_BREAK(false, "方块破坏", "是否允许非信任访客破坏方块", Items.IRON_PICKAXE),
    BLOCK_PLACE(false, "方块放置", "是否允许非信任访客放置方块", Items.GRASS_BLOCK),
    INTERACT(false, "门与红石交互", "是否允许访客使用门、按钮、拉杆等", Items.OAK_DOOR),
    CONTAINER(false, "容器打开权限", "是否允许访客打开箱子、木桶、熔炉等", Items.CHEST),
    WORKSTATION(true, "功能方块使用", "是否允许访客使用工作台、附魔台、铁砧", Items.CRAFTING_TABLE),
    PVP(false, "玩家PVP伤害", "是否允许在领地内进行玩家间对战", Items.DIAMOND_SWORD),
    EXPLOSION(false, "爆炸破坏允许", "是否允许TNT/苦力怕/凋灵炸毁领地方块", Items.TNT),
    FIRE_SPREAD(false, "火焰蔓延与引燃", "是否允许领地内火焰蔓延烧毁方块", Items.FLINT_AND_STEEL),
    PISTON(false, "外部活塞推拉", "是否允许领地外活塞推拉领地内方块", Items.PISTON),
    FLUID_FLOW(false, "外部流体涌入", "是否允许领地外的岩浆或水流入领地", Items.LAVA_BUCKET),
    MOB_DAMAGE(true, "攻击生物允许", "是否允许访客攻击领地内的生物", Items.BOW),
    MOB_GRIEF(false, "怪物破坏方块", "是否允许末影人搬方块或怪物破坏", Items.ENDER_EYE),
    CROP_TRAMPLE(false, "农田踩踏允许", "是否允许实体跳踩退化领地内耕地", Items.WHEAT),
    ITEM_PICKUP(true, "访客拾取掉落物", "是否允许非成员拾取领地地面掉落物", Items.HOPPER),
    ENDER_PEARL(false, "末影珍珠穿墙", "是否允许非成员投掷末影珍珠进入领地", Items.ENDER_PEARL),
    FLY(false, "领地内飞行权限", "是否允许领地受信任成员在领地内飞行", Items.FEATHER),
    ENTER(true, "允许访客进入", "是否允许非黑名单访客踏入领地边界", Items.OAK_FENCE_GATE),
    TELEPORT(true, "允许公共传送", "是否允许其他玩家通过 /claim tp 传送", Items.ENDER_CHEST);

    private final boolean defaultValue;
    private final String title;
    private final String description;
    private final Item icon;

    ClaimFlag(boolean defaultValue, String title, String description, Item icon) {
        this.defaultValue = defaultValue;
        this.title = title;
        this.description = description;
        this.icon = icon;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Item getIcon() {
        return icon;
    }
}
