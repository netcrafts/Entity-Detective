package netcrafts.detective.query;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.entity.EntityTypeTest;

import netcrafts.detective.mixin.DistanceManagerAccessor;
import netcrafts.detective.mixin.TicketStorageAccessor;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    public record LoaderEntry(ChunkPos pos, LoaderType type, String dimPath, long lazyMonsterCount) {}

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
            countLazyMonsterRings(level, bucket);
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
                out.add(new LoaderEntry(ChunkPos.unpack(posLong), found, dimPath, 0L));
            }
        }
    }

    private static void countLazyMonsterRings(ServerLevel level, List<LoaderEntry> entries) {
        if (entries.isEmpty()) return;

        Map<Long, Long> countsByChunk = countLazyMonsterChunks(level);
        long[] countsByLoader = new long[entries.size()];
        for (Map.Entry<Long, Long> chunkCount : countsByChunk.entrySet()) {
            int nearestIndex = findNearestLoader(entries, ChunkPos.unpack(chunkCount.getKey()));
            if (nearestIndex >= 0) countsByLoader[nearestIndex] += chunkCount.getValue();
        }

        for (int i = 0; i < entries.size(); i++) {
            LoaderEntry entry = entries.get(i);
            entries.set(i, new LoaderEntry(entry.pos(), entry.type(), entry.dimPath(), countsByLoader[i]));
        }
    }

    private static Map<Long, Long> countLazyMonsterChunks(ServerLevel level) {
        List<Entity> lazyMonsters = new ArrayList<>();
        level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                entity -> entity.getType().getCategory() == MobCategory.MONSTER
                && ChunkStatusUtil.isLazy(level, entity)
                && !isPersistentMob(entity),
                lazyMonsters);

        Map<Long, Long> countsByChunk = new HashMap<>();
        for (Entity entity : lazyMonsters) {
            ChunkPos pos = ChunkPos.containing(entity.blockPosition());
            long chunkKey = pos.pack();
            countsByChunk.put(chunkKey, countsByChunk.getOrDefault(chunkKey, 0L) + 1L);
        }
        return countsByChunk;
    }

    private static int findNearestLoader(List<LoaderEntry> entries, ChunkPos mobChunk) {
        int nearestIndex = -1;
        int nearestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            LoaderEntry candidate = entries.get(i);
            int distance = lazyRingDistance(candidate, mobChunk);
            if (distance < 0) continue;
            if (isPreferredLoader(candidate, distance, entries, nearestIndex, nearestDistance)) {
                nearestIndex = i;
                nearestDistance = distance;
            }
        }
        return nearestIndex;
    }

    private static boolean isPreferredLoader(
            LoaderEntry candidate, int distance, List<LoaderEntry> entries, int nearestIndex, int nearestDistance) {
        if (nearestIndex < 0 || distance < nearestDistance) return true;
        return distance == nearestDistance
                && compareChunkPositions(candidate.pos(), entries.get(nearestIndex).pos()) < 0;
    }

    private static int lazyRingDistance(LoaderEntry entry, ChunkPos mobChunk) {
        int lazyRingWidth = entry.type() == LoaderType.PORTAL ? 5 : 3;
        int activeRadius = entry.type() == LoaderType.PORTAL ? 1 : 0;
        int radius = activeRadius + lazyRingWidth;
        int dx = mobChunk.x() - entry.pos().x();
        int dz = mobChunk.z() - entry.pos().z();
        int distance = Math.max(Math.abs(dx), Math.abs(dz));
        if (distance > radius) return -1;
        if (entry.type() == LoaderType.PORTAL && Math.abs(dx) <= activeRadius && Math.abs(dz) <= activeRadius) return -1;
        if (entry.type() == LoaderType.ENDER_PEARL && distance == 0) return -1;
        return distance;
    }

    private static int compareChunkPositions(ChunkPos left, ChunkPos right) {
        int xComparison = Integer.compare(left.x(), right.x());
        return xComparison != 0 ? xComparison : Integer.compare(left.z(), right.z());
    }

    private static boolean isPersistentMob(Entity entity) {
        return entity instanceof Mob mob
                && (mob.isPersistenceRequired()
                    || mob.requiresCustomPersistence()
                    || mob.isPassenger()
                    || mob.isLeashed());
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
