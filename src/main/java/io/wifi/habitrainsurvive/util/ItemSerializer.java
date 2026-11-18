package io.wifi.habitrainsurvive.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

public class ItemSerializer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String serialize(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            DataResult<JsonElement> result = ItemStack.CODEC.encodeStart(ops, stack);
            return result.resultOrPartial(err -> HabiTrainSurvive.LOGGER.error("ItemStack 序列化失败: {}", err))
                    .map(GSON::toJson)
                    .orElse("");
        } catch (Exception e) {
            HabiTrainSurvive.LOGGER.error("ItemStack 序列化异常", e);
            return "";
        }
    }

    public static ItemStack deserialize(String json, HolderLookup.Provider registries) {
        if (json == null || json.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, element);
            return result.resultOrPartial(err -> HabiTrainSurvive.LOGGER.error("ItemStack 反序列化失败: {}", err))
                    .orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            HabiTrainSurvive.LOGGER.error("ItemStack 反序列化异常", e);
            return ItemStack.EMPTY;
        }
    }
}
