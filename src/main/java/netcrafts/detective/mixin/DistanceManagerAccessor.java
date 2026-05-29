package netcrafts.detective.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code DistanceManager#ticketStorage} (private) so that
 * {@code ChunkLoaderQuery} can read chunk tickets without server-tick injection.
 */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("ticketStorage")
    TicketStorage detective_getTicketStorage();
}
