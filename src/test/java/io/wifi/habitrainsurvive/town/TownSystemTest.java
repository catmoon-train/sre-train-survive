package io.wifi.habitrainsurvive.town;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TownSystemTest {

    @Test
    void dimensionAwareBuildAndInteractAndPlotTrust() {
        UUID mayor = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID outlaw = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();

        Town town = new Town("oak", "Oak", mayor, "Mayor", new GlobalPosInfo("minecraft:overworld", 0, 64, 0, 0, 0));
        town.addResident(resident, "Res", TownRank.RESIDENT);
        town.addResident(friend, "Friend", TownRank.RESIDENT);
        town.outlaws.add(outlaw);

        String overworld = "minecraft:overworld";
        String nether = "minecraft:the_nether";
        BlockPos pos = new BlockPos(8, 64, 8);
        String key = Town.chunkKeyAt(overworld, pos);
        TownPlot plot = new TownPlot(key);
        plot.ownerUuid = resident;
        plot.ownerName = "Res";
        plot.trusted.add(friend);
        town.claimedChunks.put(key, plot);

        // Owner & trusted can build in overworld plot
        assertTrue(town.canBuild(overworld, resident, pos));
        assertTrue(town.canBuild(overworld, friend, pos));
        assertTrue(town.canBuild(overworld, mayor, pos));

        // Nether chunk at same coords is NOT owned by resident's overworld plot
        assertTrue(town.canBuild(nether, resident, pos)); // falls back to allowAllResidentBuild=true

        // Outlaw cannot build or interact even if publicInteract=true
        town.publicInteract = true;
        assertFalse(town.canBuild(overworld, outlaw, pos));
        assertFalse(town.canInteract(overworld, outlaw, pos));

        // Outsider cannot build, and cannot interact on a player-owned plot
        assertFalse(town.canBuild(overworld, outsider, pos));
        assertFalse(town.canInteract(overworld, outsider, pos));

        // Leaving clears owned plots
        town.removeResident(resident);
        assertNull(plot.ownerUuid);
        assertFalse(town.isResident(resident));
    }

    @Test
    void transferMayorDemotesOldMayorToCoMayor() {
        UUID mayor = UUID.randomUUID();
        UUID successor = UUID.randomUUID();
        Town town = new Town("sun", "Sun", mayor, "OldMayor", new GlobalPosInfo("minecraft:overworld", 0, 64, 0, 0, 0));
        town.addResident(successor, "NewMayor", TownRank.RESIDENT);

        town.transferMayor(successor, "NewMayor");

        assertEquals(successor, town.mayorUuid);
        assertEquals("NewMayor", town.mayorName);
        assertEquals(TownRank.MAYOR, town.getRank(successor));
        assertEquals(TownRank.CO_MAYOR, town.getRank(mayor));
    }
}
