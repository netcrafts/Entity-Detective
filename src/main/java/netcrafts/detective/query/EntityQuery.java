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
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
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
     * Summary row for entity_summary: one entry per distinct entity type.
     *
     * @param typeId registry key, e.g. minecraft:zombie
     * @param count  number of loaded entities of this type
     */
    public record EntityTypeCount(Identifier typeId, long count) {}

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
     * Counts all loaded entities in the given dimension, grouped by entity type.
     * Results are sorted descending by count.
     * Entities whose registry key is null (unregistered modded entities) are skipped.
     */
    public static List<EntityTypeCount> countEntitiesByType(ServerLevel world, boolean persistentOnly) {
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (persistentOnly) {
                return entity instanceof Mob mob
                        && (mob.isPersistenceRequired()
                            || mob.requiresCustomPersistence()
                            || mob.isPassenger()
                            || mob.isLeashed());
            }
            return true;
        }, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = entityTypeKey(entity);
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }

        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
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
            @Nullable Identifier key = itemEntityKey(ie);
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

    /**
     * Counts all entities of the given MobCategory in the given dimension, grouped by entity type.
     * If persistent is true, only counts persistent mobs; otherwise excludes them.
     */
    public static List<EntityTypeCount> countEntitiesByCategory(
            ServerLevel world, MobCategory category, boolean persistent) {
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            boolean isPersistent = entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
            return persistent == isPersistent;
        }, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = entityTypeKey(entity);
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }

        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
                .toList();
    }

    /**
     * Returns all lazy entities of the given MobCategory, sorted by squared XZ distance
     * from spawn (0, 0). If persistent is true, also applies persistent filter.
     */
    public static List<Entity> findLazyByCategory(
            ServerLevel world, MobCategory category, boolean persistent) {
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (!ChunkStatusUtil.isLazy(world, entity)) return false;
            if (!persistent) return true;
            return entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Returns lazy entities in the given dimension, sorted by squared XZ distance from spawn.
     * If persistentOnly is true, only returns persistent mobs.
     */
    public static List<Entity> findLazyEntities(ServerLevel world, boolean persistentOnly) {
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (!ChunkStatusUtil.isLazy(world, entity)) return false;
            if (persistentOnly) {
                return entity instanceof Mob mob
                        && (mob.isPersistenceRequired()
                            || mob.requiresCustomPersistence()
                            || mob.isPassenger()
                            || mob.isLeashed());
            }
            return true;
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Returns all persistent entities of the given MobCategory across all loaded chunks,
     * sorted by squared XZ distance from spawn (0, 0).
     */
    public static List<Entity> findPersistentByCategory(
            ServerLevel world, MobCategory category) {
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            return entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Returns all persistent entities of any type across all loaded chunks,
     * sorted by squared XZ distance from spawn (0, 0).
     */
    public static List<Entity> findPersistentEntities(ServerLevel world) {
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            return entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Returns all lazy item entities in the given dimension, sorted by squared XZ distance
     * from spawn (0, 0).
     */
    public static List<Entity> findLazyItemEntities(ServerLevel world) {
        ArrayList<ItemEntity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                ie -> ChunkStatusUtil.isLazy(world, ie), all);
        List<Entity> result = new ArrayList<>(all);
        result.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return result;
    }

    // -------------------------------------------------------------------------
    // Range-filtered query variants (chunk-square check)
    // All accept Vec3 centre and int chunkRange.
    // --range N covers a (2N+1) x (2N+1) square of chunks centred on the player's chunk.
    // -------------------------------------------------------------------------

    /** Returns the chunk coordinate for a world-space coordinate. */
    private static int toChunkCoord(double worldCoord) {
        return (int) Math.floor(worldCoord) >> 4;
    }

    /** Returns true if the entity's chunk is within chunkRange of (pcx, pcz). */
    private static boolean inChunkRange(Entity entity, int pcx, int pcz, int chunkRange) {
        ChunkPos ec = ChunkPos.containing(entity.blockPosition());
        return Math.abs(ec.x() - pcx) <= chunkRange && Math.abs(ec.z() - pcz) <= chunkRange;
    }

    /** Registry key lookup declared @Nullable to allow defensive null checks (modded entities may be unregistered). */
    private static @Nullable Identifier entityTypeKey(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    /** Registry key lookup declared @Nullable to allow defensive null checks (modded items may be unregistered). */
    private static @Nullable Identifier itemEntityKey(ItemEntity ie) {
        return BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
    }

    /**
     * Counts entities of the given MobCategory within chunkRange of centre, by entity type.
     * Used by mob &lt;category&gt; --range (summary mode).
     */
    public static List<EntityTypeCount> countEntitiesByCategoryInRange(
            ServerLevel world,
            MobCategory category,
            boolean persistent,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (!inChunkRange(entity, pcx, pcz, chunkRange)) return false;
            boolean isPersistent = entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
            return persistent == isPersistent;
        }, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = entityTypeKey(entity);
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }
        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
                .toList();
    }

    /**
     * Returns lazy entities of the given category within chunkRange of centre.
     * Used by mob &lt;category&gt; --range --lazy-only.
     */
    public static List<Entity> findLazyByCategoryInRange(
            ServerLevel world,
            MobCategory category,
            boolean persistent,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (!inChunkRange(entity, pcx, pcz, chunkRange)) return false;
            if (!ChunkStatusUtil.isLazy(world, entity)) return false;
            if (!persistent) return true;
            return entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Returns persistent entities of the given category within chunkRange of centre.
     * Used by mob &lt;category&gt; --range --persistent.
     */
    public static List<Entity> findPersistentByCategoryInRange(
            ServerLevel world,
            MobCategory category,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (!inChunkRange(entity, pcx, pcz, chunkRange)) return false;
            return entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
        }, matched);
        matched.sort(Comparator.comparingDouble(e -> e.getX() * e.getX() + e.getZ() * e.getZ()));
        return matched;
    }

    /**
     * Finds entities of the given EntityType within chunkRange of centre, grouped by chunk.
     * Used by entity locate &lt;type&gt; --range.
     */
    public static List<QueryResult> findByTypeInRange(
            ServerLevel world,
            EntityType<?> entityType,
            boolean lazyOnly,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType() != entityType) return false;
            return inChunkRange(entity, pcx, pcz, chunkRange);
        }, matched);

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
     * Counts ALL entity types within chunkRange of centre.
     * Used by entity summary --range (census query).
     */
    public static List<EntityTypeCount> countAllEntitiesInRange(
            ServerLevel world,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class),
                entity -> inChunkRange(entity, pcx, pcz, chunkRange), all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = entityTypeKey(entity);
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }
        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
                .toList();
    }

    /**
     * Counts item entities within chunkRange of centre, grouped by item type.
     * Used by item --range.
     */
    public static List<ItemTypeCount> countItemsByTypeInRange(
            ServerLevel world,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        ArrayList<ItemEntity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                ie -> inChunkRange(ie, pcx, pcz, chunkRange), all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (ItemEntity ie : all) {
            @Nullable Identifier key = itemEntityKey(ie);
            if (key == null) continue;
            long[] row = counts.computeIfAbsent(key, k -> new long[]{0L, 0L});
            row[0] += 1;
            row[1] += ie.getItem().getCount();
        }
        return counts.entrySet().stream()
                .map(e -> new ItemTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.<ItemTypeCount>comparingLong(r -> -r.itemTotal()))
                .toList();
    }

    /**
     * Finds item entities of the given item ID within chunkRange of centre, grouped by chunk.
     * Used by item locate &lt;id&gt; --range.
     */
    public static List<QueryResult> findItemsByTypeInRange(
            ServerLevel world,
            Identifier itemId,
            boolean lazyOnly,
            Vec3 centre,
            int chunkRange) {

        int pcx = toChunkCoord(centre.x);
        int pcz = toChunkCoord(centre.z);
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<ItemEntity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class), ie -> {
            if (!inChunkRange(ie, pcx, pcz, chunkRange)) return false;
            @Nullable Identifier key = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
            return itemId.equals(key);
        }, matched);

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

