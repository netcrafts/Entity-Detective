package netcrafts.detective.access;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.stream.Stream;

/**
 * Exposes {@code ChunkMap#allChunksWithAtLeastStatus} (package-private) so that
 * code outside {@code net.minecraft.server.level} can iterate fully-loaded chunks.
 */
public interface ChunkMapAccessor {
    Stream<ChunkHolder> detective_allChunksWithAtLeastStatus(ChunkStatus status);
}
