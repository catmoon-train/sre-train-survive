package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.WorldPackSource;
import net.minecraft.server.packs.repository.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(PackRepository.class)
public abstract class WorldPackRepositoryMixin {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void habi$worldPack(RepositorySource[] input, CallbackInfo ci) {
        if (Arrays.stream(input).anyMatch(source -> source instanceof ServerPacksSource)) {
            this.sources = new LinkedHashSet<>(this.sources);
            this.sources.add(new WorldPackSource());
        }
    }
}
