package netcrafts.detective.query;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

@SuppressWarnings("null")
public class ChunkStatusUtil {

    private ChunkStatusUtil() {}

    /**
     * Vanilla's despawn check fires only when a player is within 128 blocks.
     * If no player is within that range the mob will never despawn naturally,
     * regardless of what chunk ticket level the chunk holds — this is the
     * practical definition of a "lazy" mob for admin purposes.
     *
     * This is more reliable than checking FullChunkStatus because Minecraft's
     * ticket system takes time to downgrade chunks after a player leaves a
     * dimension, so chunk status can report ENTITY_TICKING even when no player
     * is actually present.
     */
    private static final double DESPAWN_RANGE = 128.0;

    public static boolean isLazy(ServerLevel world, Entity entity) {
        return world.getNearestPlayer(entity, DESPAWN_RANGE) == null;
    }
}
