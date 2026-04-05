package netcrafts.detective.query;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EntityQuery {

    public record QueryResult(ChunkPos chunkPos, List<Entity> entities) {}

    /**
     * Summary row for item_summary: one entry per distinct item type.
     *
     * @param itemId      registry key, e.g. minecraft:cobblestone
     * @param entityCount number of ItemEntity instances
     * @param itemTotal   sum of ItemStack.getCount() across all those entities
     */
    public record ItemTypeCount(Identifier itemId, long entityCount, long itemTotal) {}

    /**
     * Finds all entities of the given MobCategory in the given dimension.
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
                if (entity.getType().getCategory() != category) return false;

                boolean isPersistent = entity instanceof Mob mob
                        && (mob.isPersistenceRequired()
                            || mob.requiresCustomPersistence()
                            || mob.isPassenger()
                            || mob.isLeashed());

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
     * grouped by chunk.
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

    /**
     * Counts all loaded item entities in the given dimension, grouped by item type.
     * Each row contains both entity count and total item count (stack × quantity).
     * Results are sorted descending by item total.
     * Items whose registry key is null (unregistered modded items) are skipped.
     */
    public static List<ItemTypeCount> countItemsByType(ServerLevel world) {
        ArrayList<ItemEntity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class), e -> true, all);

        // entity count and item total keyed by Identifier
        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (ItemEntity ie : all) {
            @Nullable Identifier key = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
            if (key == null) continue;
            long[] row = counts.computeIfAbsent(key, k -> new long[]{0L, 0L});
            row[0] += 1;                       // entity count
            row[1] += ie.getItem().getCount(); // item total
        }

        return counts.entrySet().stream()
                .map(e -> new ItemTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.<ItemTypeCount>comparingLong(r -> -r.itemTotal()))
                .toList();
    }

    /**
     * Finds all item entities in the given dimension matching the given item ID,
     * grouped by chunk, sorted descending by entity count per chunk.
     * Used for /entitydetective item locate.
     *
     * @param lazyOnly if true, only include items in lazy (non-block-ticking) chunks
     */
    public static List<QueryResult> findItemsByType(ServerLevel world, Identifier itemId, boolean lazyOnly) {
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<ItemEntity> matched = new ArrayList<>();
        world.getEntities(
            EntityTypeTest.forClass(ItemEntity.class),
            ie -> {
                @Nullable Identifier key = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
                return itemId.equals(key);
            },
            matched
        );

        for (ItemEntity ie : matched) {
            if (lazyOnly && !ChunkStatusUtil.isLazy(world, ie)) continue;
            ChunkPos pos = ChunkPos.containing(ie.blockPosition());
            byChunk.computeIfAbsent(pos, k -> new ArrayList<>()).add(ie);
        }

        return byChunk.entrySet().stream()
                .map(e -> new QueryResult(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(r -> -r.entities().size()))
                .toList();
    }
}

