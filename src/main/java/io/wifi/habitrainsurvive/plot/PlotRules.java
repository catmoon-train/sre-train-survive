package io.wifi.habitrainsurvive.plot;

import java.util.Set;
import java.util.UUID;

public final class PlotRules {
    public double additionalPlotPrice = 10000;
    /** Zero means no per-player limit; available land is still bounded by the world border. */
    public int maxPlotsPerPlayer = 0;

    public double price(UUID owner, Set<UUID> freeUsed) { return freeUsed.contains(owner) ? additionalPlotPrice : 0; }
    public void validate() {
        if (!Double.isFinite(additionalPlotPrice) || additionalPlotPrice < 0.01 || additionalPlotPrice > 1e12)
            throw new IllegalArgumentException("后续地皮价格必须为0.01到1万亿");
        if (maxPlotsPerPlayer < 0 || maxPlotsPerPlayer > 100000) throw new IllegalArgumentException("地皮上限必须为0到100000");
    }
}
