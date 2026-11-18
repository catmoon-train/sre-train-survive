package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.*;
import java.nio.file.*;

/** Custom End arenas have their own fight state inside their backed-up dimension directory. */
@Mixin(ServerLevel.class)
public abstract class ManagedEndFightMixin {
    @Shadow private EndDragonFight dragonFight;
    @Unique private Path habi$fightFile() throws IOException {
        ServerLevel level = (ServerLevel) (Object) this;
        return WorldPaths.dimension(WorldManager.get().saveRoot(), level.dimension().location().toString()).resolve("data/habi_dragon.dat");
    }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void habi$independentArena(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        WorldDefinition d = WorldManager.get().definition(level.dimension().location().toString());
        if (d == null || !d.managed() || !d.preset.equals("end")) return;
        try {
            Path file = habi$fightFile();
            EndDragonFight.Data data = Files.exists(file)
                    ? EndDragonFight.Data.CODEC.parse(NbtOps.INSTANCE, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())).getOrThrow()
                    : EndDragonFight.Data.DEFAULT;
            dragonFight = new EndDragonFight(level, level.getSeed(), data);
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("资源末地龙战状态损坏，拒绝重置该状态", e); }
    }
    @Redirect(method = "saveLevelData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;setEndDragonFightData(Lnet/minecraft/world/level/dimension/end/EndDragonFight$Data;)V"))
    private void habi$saveIndependentArena(WorldData world, EndDragonFight.Data data) {
        ServerLevel level = (ServerLevel) (Object) this;
        WorldDefinition d = WorldManager.get().definition(level.dimension().location().toString());
        if (d == null || !d.managed()) { world.setEndDragonFightData(data); return; }
        try {
            Path file = habi$fightFile(); Files.createDirectories(file.getParent()); Path tmp = file.resolveSibling("habi_dragon.dat.tmp");
            NbtIo.writeCompressed((CompoundTag) EndDragonFight.Data.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow(), tmp);
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException("无法保存资源末地龙战状态", e); }
    }
}
