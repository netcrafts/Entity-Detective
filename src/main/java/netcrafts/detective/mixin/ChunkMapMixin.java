package netcrafts.detective.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import netcrafts.detective.access.ChunkMapAccessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.stream.Stream;

/**
 * Mixin on {@link ChunkMap} that implements {@link ChunkMapAccessor}, delegating
 * to the package-private {@code allChunksWithAtLeastStatus} method so that
 * {@code EntityQuery} can iterate fully-loaded {@code LevelChunk} instances.
 */
@SuppressWarnings("null")
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements ChunkMapAccessor {

    @Shadow
    abstract Stream<ChunkHolder> allChunksWithAtLeastStatus(ChunkStatus status);

    @Override
    public Stream<ChunkHolder> detective_allChunksWithAtLeastStatus(ChunkStatus status) {
        return this.allChunksWithAtLeastStatus(status);
    }
}
