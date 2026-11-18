package io.wifi.habitrainsurvive.config;

public enum ModuleId {
    PERMISSION("permission", "权限系统", "权限组、节点与配额管理 (/perm)"),
    ECONOMY("economy", "经济系统", "金币账户、转账与排行榜 (/bal, /pay, /eco)"),
    CHEST_SHOP("chestshop", "箱子商店", "玩家箱子/木桶/潜影盒实体商店 (/qs)"),
    PLAYER_SHOP("playershop", "电子店铺", "玩家线上虚拟店铺 (/playershop)"),
    SYSTEM_SHOP("sysshop", "系统商城", "动态价格系统商城 (/shop)"),
    AUCTION("auction", "拍卖行", "全服物品拍卖行 (/ah)"),
    TRADE("trade", "玩家交易", "面对面安全交易 (/trade)"),
    CLAIM("claim", "领地系统", "木锄圈地与领地保护 (/claim)"),
    TOWN("town", "城镇系统", "城镇建城、居民、地块与金库 (/town, /tc)"),
    PLOT("plot", "地皮系统", "地皮世界网格与地皮买卖 (/plot)"),
    CMI("cmi", "基础便利(CMI)", "家、传送、TPA、礼包、悬赏、监狱等基础功能"),
    GRAVE("grave", "死亡墓碑", "死亡遗物保险箱与找回 (/grave)"),
    QUEST("quest", "任务系统", "每日/每周/章节任务与网页编辑器 (/quest)"),
    LOTTERY("lottery", "抽奖系统", "奖池抽奖与保底 (/lottery)"),
    GIFT("gift", "礼包系统", "全服/个人礼包发放与领取 (/gift)"),
    REDEEM("redeem", "兑换码系统", "兑换码生成与兑换 (/redeem)"),
    REDPACKET("redpacket", "红包系统", "全服/口令红包 (/redpacket)"),
    TITLE("title", "称号系统", "玩家称号购买与佩戴 (/title)"),
    LANDMARK("landmark", "公共地标", "公共地标传送点 (/landmark)"),
    HUB("hub", "独立主城", "主城保护、传送与出生点管理 (/hub)"),
    MENU("menu", "自定义菜单", "主菜单与 Shift+F 快捷菜单 (/menu)"),
    WORLD("world", "多世界管理", "多世界创建、传送、规则与重置 (/world)"),
    NATURAL_SPAWNING("natural_spawning", "自然刷新控制", "分世界生物自然刷新规则 (/worldspawn)"),
    ONBOARDING("onboarding", "新手引导", "首次进服引导与新手礼包 (/guide)"),
    PRESENTATION("presentation", "运营展示", "计分板、Tab栏与定时公告 (/settings)");

    private final String id;
    private final String displayName;
    private final String description;

    ModuleId(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static ModuleId fromId(String id) {
        if (id == null) return null;
        for (ModuleId module : values()) {
            if (module.id.equalsIgnoreCase(id.trim())) {
                return module;
            }
        }
        return null;
    }
}
