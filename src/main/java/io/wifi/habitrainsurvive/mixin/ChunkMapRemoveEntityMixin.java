package io.wifi.habitrainsurvive.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 玩家移除时会通知每一个已追踪实体，回调链会重新进入实体移除流程（例如同一次
 * teleportTo 触发的载具/乘客级联移除），从而在遍历中修改 ChunkMap 的追踪表。
 * fastutil 的 values() 迭代器不是 fail-fast 的：表被外部改动后 wrapped 仍为 null，
 * 下一次 next() 会抛 “Cannot invoke IntArrayList.getInt(int) because this.wrapped is null”。
 *
 * 这里只把 removeEntity 的遍历换成快照，不改移除顺序、不吞掉异常，也不影响 addEntity。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRemoveEntityMixin {
    @Redirect(
            method = "removeEntity",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"),
            remap = false)
    private static ObjectCollection<?> habi$snapshotTrackedEntities(Int2ObjectMap<?> tracked) {
        return new ObjectArrayList<>(tracked.values());
    }
}
