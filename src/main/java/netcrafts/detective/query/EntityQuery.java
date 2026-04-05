package netcrafts.detective.query;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class EntityQuery {

    public record QueryResult(ChunkPos chunkPos, List<Entity> entities) {}

    /**
     * Finds all entities of the given MobCategory in the given dimension.
     *
     * @param world            the dimension to search
     * @param category         the MobCategory to match (engine-authoritative, replaces manual entity tag lists)
     * @param lazyOnly         if true, only include entities in lazy (non-block-ticking) chunks
     * @param includePersistent if true, include named/leashed/traded mobs; default false excludes them
     * @return results grouped by chunk, sorted descending by entity count
     */
    public static List<QueryResult> findEntities(
            ServerLevel world,
            MobCategory category,
            boolean lazyOnly,
            boolean includePersistent) {

        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();

        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(
            EntityTypeTest.forClass(Entity.class),
            entity -> {
                // Filter by MobCategory — same criterion the engine uses for mob cap
                if (entity.getType().getCategory() != category) return false;

                // A mob is "persistent" if it won't naturally despawn:
                //   - name-tagged, traded-with villager  → isPersistenceRequired()
                //   - baby zombies, special mobs         → requiresCustomPersistence()
                //   - riding a boat or minecart          → isPassenger()
                //   - leashed                            → isLeashed()
                boolean isPersistent = entity instanceof Mob mob
                        && (mob.isPersistenceRequired()
                            || mob.requiresCustomPersistence()
                            || mob.isPassenger()
                            || mob.isLeashed());

                // --persistent flag: show ONLY persistent mobs
                // default:           exclude persistent mobs (they never despawn anyway)
                if (includePersistent) {
                    return isPersistent;
                } else {
                    return !isPersistent;
                }
            },
            matched
        );

        for (Entity entity : matched) {
            if (lazyOnly && !ChunkStatusUtil.isLazy(world, entity)) continue;
            ChunkPos pos = ChunkPos.containing(entity.blockPosition());
            byChunk.computeIfAbsent(pos, k -> new ArrayList<>()).add(entity);
        }

        return byChunk.entrySet().stream()
                .map(e -> new QueryResult(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(r -> -r.entities().size()))
                .toList();
    }

    /**
     * Finds all entities of the given EntityType in the given dimension,
     * grouped by chunk. Used for /entitydetective entity <type> queries.
     */
    public static List<QueryResult> findByType(
            ServerLevel world,
            EntityType<?> entityType,
            boolean lazyOnly) {

        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(
            EntityTypeTest.forClass(Entity.class),
            entity -> entity.getType() == entityType,
            matched
        );

        for (Entity entity : matched) {
            if (lazyOnly && !ChunkStatusUtil.isLazy(world, entity)) continue;
            ChunkPos pos = ChunkPos.containing(entity.blockPosition());
            byChunk.computeIfAbsent(pos, k -> new ArrayList<>()).add(entity);
        }

        return byChunk.entrySet().stream()
                .map(e -> new QueryResult(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(r -> -r.entities().size()))
                .toList();
    }
}
