package netcrafts.detective.query;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

import java.util.Arrays;
import java.util.List;

public class MobCapInfo {

    // Same constant Carpet uses: (int)Math.pow(17.0, 2.0)
    // Represents the 17x17 spawn-eligible area per chunk in the cap formula
    private static final int MAGIC_NUMBER = 289;

    public record CategoryInfo(MobCategory category, int current, int max) {
        public boolean hasCap() {
            return max > 0;
        }
    }

    /**
     * Returns live mob cap counts for all MobCategory values in the given dimension.
     * Matches the data Carpet reads in printMobcapsForDimension().
     */
    public static List<CategoryInfo> getForDimension(ServerLevel world) {
        NaturalSpawner.SpawnState spawnState = world.getChunkSource().getLastSpawnState();
        if (spawnState == null) return List.of();

        Object2IntMap<MobCategory> counts = spawnState.getMobCategoryCounts();
        int chunkCount = world.getChunkSource().chunkMap
                .getDistanceManager().getNaturalSpawnChunkCount();

        return Arrays.stream(MobCategory.values())
                .map(cat -> {
                    int cur = counts.getOrDefault(cat, 0);
                    int max = (int)(chunkCount * (double) cat.getMaxInstancesPerChunk() / MAGIC_NUMBER);
                    return new CategoryInfo(cat, cur, max);
                })
                .toList();
    }
}
