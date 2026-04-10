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
    public static List<EntityTypeCount> countEntitiesByType(ServerLevel world) {
        return countEntitiesByType(world, false);
    }

    /**
     * Counts entities in the given dimension, grouped by entity type.
     * If persistentOnly is true, only counts persistent mobs (name-tagged, holding item, leashed, riding).
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
            @Nullable Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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
            @Nullable Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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
     * Returns all lazy entities of any type in the given dimension, sorted by squared XZ
     * distance from spawn (0, 0).
     */
    public static List<Entity> findLazyEntities(ServerLevel world) {
        return findLazyEntities(world, false);
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
    // Radius-filtered query variants (AABB-style pre-check + sphere cull)
    // All accept Vec3 centre and double blockRadius (= radiusChunks * 16).
    // -------------------------------------------------------------------------

    /**
     * Finds entities of the given MobCategory within blockRadius of centre, grouped by chunk.
     * Used by mob &lt;category&gt; --radius.
     */
    public static List<QueryResult> findEntitiesInRadius(
            ServerLevel world,
            MobCategory category,
            boolean lazyOnly,
            boolean includePersistent,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (entity.distanceToSqr(centre) > rSq) return false;
            boolean isPersistent = entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
            return includePersistent ? isPersistent : !isPersistent;
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
     * Counts entities of the given MobCategory within blockRadius of centre, by entity type.
     * Used by mob &lt;category&gt; --radius (summary mode).
     */
    public static List<EntityTypeCount> countEntitiesByCategoryInRadius(
            ServerLevel world,
            MobCategory category,
            boolean persistent,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (entity.distanceToSqr(centre) > rSq) return false;
            boolean isPersistent = entity instanceof Mob mob
                    && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence()
                        || mob.isPassenger()
                        || mob.isLeashed());
            return persistent == isPersistent;
        }, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }
        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
                .toList();
    }

    /**
     * Returns lazy entities of the given category within blockRadius of centre.
     * Used by mob &lt;category&gt; --radius --lazy-only.
     */
    public static List<Entity> findLazyByCategoryInRadius(
            ServerLevel world,
            MobCategory category,
            boolean persistent,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (entity.distanceToSqr(centre) > rSq) return false;
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
     * Returns persistent entities of the given category within blockRadius of centre.
     * Used by mob &lt;category&gt; --radius --persistent.
     */
    public static List<Entity> findPersistentByCategoryInRadius(
            ServerLevel world,
            MobCategory category,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType().getCategory() != category) return false;
            if (entity.distanceToSqr(centre) > rSq) return false;
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
     * Finds entities of the given EntityType within blockRadius of centre, grouped by chunk.
     * Used by entity locate &lt;type&gt; --radius.
     */
    public static List<QueryResult> findByTypeInRadius(
            ServerLevel world,
            EntityType<?> entityType,
            boolean lazyOnly,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<Entity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class), entity -> {
            if (entity.getType() != entityType) return false;
            return entity.distanceToSqr(centre) <= rSq;
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
     * Counts ALL entity types within blockRadius of centre.
     * Used by entity summary --radius (census query).
     */
    public static List<EntityTypeCount> countAllEntitiesInRadius(
            ServerLevel world,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        ArrayList<Entity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(Entity.class),
                entity -> entity.distanceToSqr(centre) <= rSq, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (Entity entity : all) {
            @Nullable Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key == null) continue;
            counts.computeIfAbsent(key, k -> new long[]{0L})[0] += 1;
        }
        return counts.entrySet().stream()
                .map(e -> new EntityTypeCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.<EntityTypeCount>comparingLong(r -> -r.count()))
                .toList();
    }

    /**
     * Counts item entities within blockRadius of centre, grouped by item type.
     * Used by item --radius.
     */
    public static List<ItemTypeCount> countItemsByTypeInRadius(
            ServerLevel world,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        ArrayList<ItemEntity> all = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                ie -> ie.distanceToSqr(centre) <= rSq, all);

        Map<Identifier, long[]> counts = new LinkedHashMap<>();
        for (ItemEntity ie : all) {
            @Nullable Identifier key = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
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
     * Finds item entities of the given item ID within blockRadius of centre, grouped by chunk.
     * Used by item locate &lt;id&gt; --radius.
     */
    public static List<QueryResult> findItemsByTypeInRadius(
            ServerLevel world,
            Identifier itemId,
            boolean lazyOnly,
            Vec3 centre,
            double blockRadius) {

        double rSq = blockRadius * blockRadius;
        Map<ChunkPos, List<Entity>> byChunk = new HashMap<>();
        ArrayList<ItemEntity> matched = new ArrayList<>();
        world.getEntities(EntityTypeTest.forClass(ItemEntity.class), ie -> {
            if (ie.distanceToSqr(centre) > rSq) return false;
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

