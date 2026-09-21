package netcrafts.detective.query;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

import netcrafts.detective.mixin.DistanceManagerAccessor;
import netcrafts.detective.mixin.TicketStorageAccessor;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds active chunk loaders and ender pearl stasis chambers by inspecting
 * chunk ticket storage across all loaded dimensions.
 *
 * <p>Only {@code PORTAL} and {@code ENDER_PEARL} tickets are reported —
 * player-proximity tickets ({@code PLAYER_LOADING}, {@code PLAYER_SIMULATION},
 * etc.) are ignored.
 *
 * <p>Overworld PORTAL tickets are paired with their Nether counterpart
 * (within ±1 Nether chunk of OW_pos / 8) so one portal chunk loader
 * shows both endpoints on a single output line.
 */
public class ChunkLoaderQuery {

    private ChunkLoaderQuery() {}

    public enum LoaderType {
        /** Nether/End portal entity cycling — loads a 3×3 chunk area. */
        PORTAL,
        /** Ender pearl stasis chamber — loads a 1×1 chunk area. */
        ENDER_PEARL
    }

    /**
     * A single ticket-bearing chunk position.
     *
     * @param pos     chunk coordinates
     * @param type    the reason this chunk is force-loaded
     * @param dimPath raw MC dimension path, e.g. {@code "overworld"}, {@code "the_nether"}
     */
    public record LoaderEntry(ChunkPos pos, LoaderType type, String dimPath) {}

    /**
     * A reported chunk loader result.
     *
     * @param primary the anchor entry (always present; Overworld for paired portals)
     * @param paired  the Nether counterpart for paired portal loaders, else {@code null}
     */
    public record ChunkLoaderResult(LoaderEntry primary, @Nullable LoaderEntry paired) {}

    /**
     * Collects all portal and stasis-chamber chunk loaders across every loaded
     * dimension on {@code server}, pairing Overworld↔Nether portal entries.
     */
    public static List<ChunkLoaderResult> findAll(MinecraftServer server) {
        List<LoaderEntry> overworld = new ArrayList<>();
        List<LoaderEntry> nether    = new ArrayList<>();
        List<LoaderEntry> other     = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dimPath = level.dimension().identifier().getPath();
            List<LoaderEntry> bucket = switch (dimPath) {
                case "overworld"  -> overworld;
                case "the_nether" -> nether;
                default           -> other;
            };
            collectTickets(level, dimPath, bucket);
        }

        return buildResults(overworld, nether, other);
    }

    // -------------------------------------------------------------------------
    // Ticket collection
    // -------------------------------------------------------------------------

    private static void collectTickets(ServerLevel level, String dimPath, List<LoaderEntry> out) {
        DistanceManager dm = level.getChunkSource().chunkMap.getDistanceManager();
        TicketStorage storage = ((DistanceManagerAccessor) dm).detective_getTicketStorage();
        Long2ObjectOpenHashMap<List<Ticket>> tickets = ((TicketStorageAccessor) storage).detective_getTickets();

        // Moonrise (and similar optimization mods) replace the vanilla ticket system and may leave tickets null
        if (tickets == null) return;

        for (Long2ObjectMap.Entry<List<Ticket>> entry : tickets.long2ObjectEntrySet()) {
            long posLong = entry.getLongKey();
            List<Ticket> list = entry.getValue();
            if (list == null || list.isEmpty()) continue;

            LoaderType found = classifyTickets(list);
            if (found != null) {
                out.add(new LoaderEntry(ChunkPos.unpack(posLong), found, dimPath));
            }
        }
    }

    /**
     * Returns the most significant chunk-loader ticket type in {@code tickets},
     * or {@code null} if none qualify. PORTAL takes priority over ENDER_PEARL.
     */
    @Nullable
    private static LoaderType classifyTickets(List<Ticket> tickets) {
        boolean hasPortal = false;
        boolean hasPearl  = false;
        for (Ticket ticket : tickets) {
            TicketType type = ticket.getType();
            if (type == TicketType.PORTAL)      { hasPortal = true; }
            else if (type == TicketType.ENDER_PEARL) { hasPearl  = true; }
        }
        if (hasPortal) return LoaderType.PORTAL;
        if (hasPearl)  return LoaderType.ENDER_PEARL;
        return null;
    }

    // -------------------------------------------------------------------------
    // Pairing logic
    // -------------------------------------------------------------------------

    private static List<ChunkLoaderResult> buildResults(
            List<LoaderEntry> overworld,
            List<LoaderEntry> nether,
            List<LoaderEntry> other) {

        List<ChunkLoaderResult> results = new ArrayList<>();
        Set<Integer> usedNetherIndices = new HashSet<>();

        // Anchor from Overworld: pair PORTAL tickets with their Nether counterpart.
        // Direction is OW→Nether only: dividing by 8 gives tight ±1-chunk tolerance;
        // going the other way (×8) gives ±8-chunk tolerance — too loose on busy servers.
        for (LoaderEntry ow : overworld) {
            if (ow.type() != LoaderType.PORTAL) {
                // Pearl stasis or other: no cross-dimension pairing
                results.add(new ChunkLoaderResult(ow, null));
                continue;
            }

            int expectedNx = Math.floorDiv(ow.pos().x(), 8);
            int expectedNz = Math.floorDiv(ow.pos().z(), 8);
            LoaderEntry match = null;
            int matchIdx = -1;

            for (int i = 0; i < nether.size(); i++) {
                LoaderEntry ne = nether.get(i);
                if (ne.type() == LoaderType.PORTAL
                        && Math.abs(ne.pos().x() - expectedNx) <= 1
                        && Math.abs(ne.pos().z() - expectedNz) <= 1) {
                    match    = ne;
                    matchIdx = i;
                    break;
                }
            }

            if (match != null) {
                usedNetherIndices.add(matchIdx);
                results.add(new ChunkLoaderResult(ow, match));
            } else {
                results.add(new ChunkLoaderResult(ow, null));
            }
        }

        // Unpaired Nether tickets (no matching Overworld portal found)
        for (int i = 0; i < nether.size(); i++) {
            if (!usedNetherIndices.contains(i)) {
                results.add(new ChunkLoaderResult(nether.get(i), null));
            }
        }

        // End and any other dimensions — always listed individually
        for (LoaderEntry e : other) {
            results.add(new ChunkLoaderResult(e, null));
        }

        return results;
    }
}
