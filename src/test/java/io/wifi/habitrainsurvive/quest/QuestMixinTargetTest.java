package io.wifi.habitrainsurvive.quest;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the @Inject method names and the mixin registration list against silent renames. */
class QuestMixinTargetTest {
    @Test void injectedMethodsStillExistInOfficialMappings() {
        assertDoesNotThrow(() -> ItemEntity.class.getDeclaredMethod("playerTouch", Player.class));
        assertDoesNotThrow(() -> ResultSlot.class.getDeclaredMethod("onTake", Player.class, ItemStack.class));
        assertDoesNotThrow(() -> BlockItem.class.getDeclaredMethod("place", BlockPlaceContext.class));
    }

    @Test void everyQuestMixinIsRegisteredInTheMixinConfig() throws Exception {
        String json;
        try (InputStream stream = QuestMixinTargetTest.class.getResourceAsStream("/habi_train_survive.mixins.json")) {
            assertNotNull(stream, "找不到 mixins 配置");
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String mixin : new String[]{"QuestPickupMixin", "QuestCraftMixin", "QuestPlaceMixin"}) {
            assertTrue(json.contains("\"" + mixin + "\""), mixin + " 未注册到 habi_train_survive.mixins.json");
        }
    }
}
