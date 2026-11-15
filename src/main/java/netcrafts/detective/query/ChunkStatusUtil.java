package netcrafts.detective.query;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

@SuppressWarnings("null")
public class ChunkStatusUtil {

    private ChunkStatusUtil() {}

    public static boolean isLazy(ServerLevel world, Entity entity) {
        return !world.isPositionEntityTicking(entity.blockPosition());
    }
}
