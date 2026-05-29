package netcrafts.detective.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes {@code TicketStorage#tickets} (private) so that
 * {@code ChunkLoaderQuery} can iterate all per-chunk ticket lists.
 */
@Mixin(TicketStorage.class)
public interface TicketStorageAccessor {
    @Accessor("tickets")
    Long2ObjectOpenHashMap<List<Ticket>> detective_getTickets();
}
