package netcrafts.detective.query;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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

                // Exclude persistent mobs unless caller asks for them:
                //   isPersistenceRequired()      — named mobs, traded-with villagers
                //   requiresCustomPersistence()  — leashed, baby animals, etc.
                if (!includePersistent && entity instanceof Mob mob
                        && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
                    return false;
                }

                return true;
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
}
