package io.wifi.habitrainsurvive.world;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import java.util.Optional;
import java.util.function.Consumer;

public final class WorldPackSource implements RepositorySource {
    @Override public void loadPacks(Consumer<Pack> consumer) {
        PackLocationInfo location = new PackLocationInfo("habi/managed_worlds", Component.literal("Habi 多世界"), PackSource.SERVER, Optional.empty());
        Pack pack = Pack.readMetaAndCreate(location, new PathPackResources.PathResourcesSupplier(WorldBootstrap.pack()),
                PackType.SERVER_DATA, new PackSelectionConfig(true, Pack.Position.TOP, true));
        if (pack == null) throw new IllegalStateException("多世界数据包元数据读取失败");
        consumer.accept(pack);
    }
}
