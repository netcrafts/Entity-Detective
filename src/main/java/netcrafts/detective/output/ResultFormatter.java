package netcrafts.detective.output;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import netcrafts.detective.query.EntityQuery.ItemTypeCount;
import netcrafts.detective.query.EntityQuery.EntityTypeCount;
import netcrafts.detective.query.EntityQuery.QueryResult;
import netcrafts.detective.query.MobCapInfo.CategoryInfo;

import java.util.List;
import java.util.Map;

public class ResultFormatter {

    private static final int MAX_CHUNKS_SHOWN = 50;

    // Color thresholds for mobcap saturation: green → yellow → red
    private static final int COLOR_LOW    = 0x55FF55; // <50%
    private static final int COLOR_MEDIUM = 0xFFFF55; // 50–85%
    private static final int COLOR_HIGH   = 0xFF5555; // >85%

    // -------------------------------------------------------------------------
    // Mobcap display
    // -------------------------------------------------------------------------

    public static void sendMobcap(CommandSourceStack source, List<CategoryInfo> caps, String dimName) {
        source.sendSuccess(() -> Component.literal("-- Mob Cap [" + dimName + "] --")
                .withStyle(ChatFormatting.GOLD), false);

        if (caps.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  No spawn data yet (no spawn cycle has run).")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }

        for (CategoryInfo info : caps) {
            source.sendSuccess(() -> formatCapLine(info), false);
        }
    }

    private static MutableComponent formatCapLine(CategoryInfo info) {
        String label = String.format("  %-30s", info.category().getName());
        MutableComponent line = Component.literal(label).withStyle(ChatFormatting.WHITE);

        if (!info.hasCap()) {
            line.append(Component.literal("no cap").withStyle(ChatFormatting.GRAY));
            return line;
        }

        int cur = info.current();
        int max = info.max();
        double pct = max > 0 ? (double) cur / max : 0;

        int color = pct < 0.5 ? COLOR_LOW : pct < 0.85 ? COLOR_MEDIUM : COLOR_HIGH;
        String fraction = cur + " / " + max + String.format("  (%.0f%%)", pct * 100);
        line.append(Component.literal(fraction).withStyle(Style.EMPTY.withColor(color)));
        return line;
    }

    // -------------------------------------------------------------------------
    // Entity locate display
    // -------------------------------------------------------------------------

    public static void sendLocateResults(
            CommandSourceStack source,
            List<QueryResult> results,
            String label,
            String dimName,
            boolean lazyOnly,
            boolean debug) {

        // 5.5.13 — use long to avoid overflow on servers with large entity counts
        long totalEntities = results.stream().mapToLong(r -> r.entities().size()).sum();

        if (totalEntities == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No " + label + " entities found" +
                    (lazyOnly ? " in lazy chunks" : "") + " in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        String header = String.format("-- %s [%s]%s: %d entities in %d chunks --",
                label, dimName,
                lazyOnly ? " (lazy only)" : "",
                totalEntities, results.size());
        source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.GOLD), false);

        int shown = Math.min(results.size(), MAX_CHUNKS_SHOWN);
        for (int i = 0; i < shown; i++) {
            QueryResult r = results.get(i);
            source.sendSuccess(() -> formatChunkLine(r), false);
            if (debug) {
                for (Entity entity : r.entities()) {
                    source.sendSuccess(() -> formatEntityDebugLine(entity), false);
                }
            }
        }

        if (results.size() > MAX_CHUNKS_SHOWN) {
            int remaining = results.size() - MAX_CHUNKS_SHOWN;
            source.sendSuccess(() -> Component.literal("  ...and " + remaining + " more chunks.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    public static void sendLocateSummary(
            CommandSourceStack source,
            List<QueryResult> results,
            String label,
            String dimName,
            boolean lazyOnly) {

        long total = results.stream().mapToLong(r -> r.entities().size()).sum();
        String msg = String.format("%s [%s]%s: %d entities across %d chunks",
                label, dimName,
                lazyOnly ? " (lazy only)" : "",
                total, results.size());
        source.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.YELLOW), false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MutableComponent formatEntityDebugLine(Entity entity) {
        // 5.5.2 / 5.5.8 — getKey() can return null for unregistered modded entity types
        @Nullable Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String type = typeKey != null ? typeKey.toString() : "unknown:" + entity.getType().getDescriptionId();
        String name   = entity.hasCustomName() ? " \"" + entity.getCustomName().getString() + "\"" : "";
        String reason = persistenceReason(entity);
        String coords = String.format("[%d, %d, %d]", (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
        String tpCommand = String.format("/tp @s %.1f %.1f %.1f", entity.getX(), entity.getY(), entity.getZ());
        MutableComponent line = Component.literal("    ")
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(tpCommand)));
        line.append(Component.literal(coords).withStyle(ChatFormatting.AQUA));
        line.append(Component.literal("  —  ").withStyle(ChatFormatting.WHITE));
        line.append(Component.literal(type).withStyle(ChatFormatting.GREEN));
        if (!name.isEmpty()) {
            line.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        }
        line.append(Component.literal("  (" + reason + ")").withStyle(ChatFormatting.GRAY));
        return line;
    }

    private static String persistenceReason(Entity entity) {
        if (!(entity instanceof Mob mob)) return "non-mob";
        if (mob.hasCustomName() && mob.isPersistenceRequired()) return "name tagged";
        if (mob.isPersistenceRequired()) return "holding item";
        if (mob.isPassenger()) return "riding vehicle";
        if (mob.isLeashed()) return "leashed";
        if (mob.requiresCustomPersistence()) return "custom persistence";
        return "unknown";
    }

    private static MutableComponent formatChunkLine(QueryResult r) {
        ChunkPos pos = r.chunkPos();
        int count = r.entities().size();
        int midX = pos.getMiddleBlockX();
        int midZ = pos.getMiddleBlockZ();

        // Suggest /tp so the admin can click to teleport to the chunk center
        String tpCommand = String.format("/tp @s %d ~ %d", midX, midZ);
        ClickEvent click = new ClickEvent.SuggestCommand(tpCommand);

        MutableComponent coords = Component.literal(
                String.format("  [%d, %d]", pos.x(), pos.z()))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(click));

        MutableComponent countPart = Component.literal(" — " + count + " entities")
                .withStyle(ChatFormatting.WHITE);

        return coords.append(countPart);
    }

    // -------------------------------------------------------------------------
    // Entity type summary display
    // -------------------------------------------------------------------------

    public static void sendEntitySummary(
            CommandSourceStack source,
            List<EntityTypeCount> counts,
            String dimName) {

        long total = counts.stream().mapToLong(EntityTypeCount::count).sum();

        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- Entity Types [" + dimName + "]: " + total + " entities --")
                .withStyle(ChatFormatting.GOLD), false);

        for (EntityTypeCount row : counts) {
            MutableComponent rowLine = Component.literal(String.format("  %5d  ", row.count())).withStyle(ChatFormatting.WHITE);
            rowLine.append(Component.literal(row.typeId().toString()).withStyle(ChatFormatting.GREEN));
            source.sendSuccess(() -> rowLine, false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + total + " entities across " + counts.size() + " types")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // -------------------------------------------------------------------------
    // Item entity display
    // -------------------------------------------------------------------------

    public static void sendItemSummary(
            CommandSourceStack source,
            List<ItemTypeCount> counts,
            String dimName) {

        long totalEntities = counts.stream().mapToLong(ItemTypeCount::entityCount).sum();
        long totalItems    = counts.stream().mapToLong(ItemTypeCount::itemTotal).sum();

        if (totalEntities == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No item entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- Item Entities [" + dimName + "]: " + totalEntities + " entities, "
                + totalItems + " items --")
                .withStyle(ChatFormatting.GOLD), false);

        for (ItemTypeCount row : counts) {
            // Colour by item count volume: green <100, yellow <1000, red ≥1000
            ChatFormatting color = row.itemTotal() < 100 ? ChatFormatting.GREEN
                    : row.itemTotal() < 1000 ? ChatFormatting.YELLOW
                    : ChatFormatting.RED;
            MutableComponent rowLine = Component.literal(String.format("  %5d items  (%d entities)  ", row.itemTotal(), row.entityCount())).withStyle(color);
            rowLine.append(Component.literal(row.itemId().toString()).withStyle(ChatFormatting.GREEN));
            source.sendSuccess(() -> rowLine, false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + totalItems + " items in " + totalEntities + " entities")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // -------------------------------------------------------------------------
    // Entity profile display
    // -------------------------------------------------------------------------

    /**
     * Sends the result of a single-type entity tick profiling session.
     *
     * @param source        who to send results to
     * @param type          the profiled entity type
     * @param ticks         the sample window (total ticks)
     * @param radiusChunks  chunk radius used during profiling; 0 means no radius restriction
     * @param perDim        per-dimension data: [0] total nanos, [1] total entity-ticks
     */
    public static void sendProfileResults(
            CommandSourceStack source,
            EntityType<?> type,
            int ticks,
            int radiusChunks,
            Map<ResourceKey<Level>, long[]> perDim) {

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();

        String radiusNote = radiusChunks > 0 ? " [" + radiusChunks + "-chunk radius]" : "";

        double divider = 1.0 / ticks / 1_000_000.0; // nanos per tick → ms per tick
        long totalNanos = perDim.values().stream().mapToLong(d -> d[0]).sum();
        long totalCount = perDim.values().stream().mapToLong(d -> d[1]).sum();

        source.sendSuccess(() -> Component.literal(
                String.format("-- Entity Profile: %s%s (%d ticks) --", label, radiusNote, ticks))
                .withStyle(ChatFormatting.GOLD), false);

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal(
                    "  No entities of this type were ticked during the sample window.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        double avgMs    = divider * totalNanos;
        double avgCount = (double) totalCount / ticks;

        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. tick cost:  %.3fms / tick", avgMs))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. count:      %.1f entities / tick", avgCount))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Total sampled:   %d entity-ticks", totalCount))
                .withStyle(ChatFormatting.GRAY), false);

        if (perDim.size() > 1) {
            for (var entry : perDim.entrySet()) {
                String dimName = dimensionName(entry.getKey());
                long[] data = entry.getValue();
                double dimMs       = divider * data[0];
                double dimAvgCount = (double) data[1] / ticks;
                source.sendSuccess(() -> Component.literal(
                        String.format("  %-12s %.3fms/tick,  %.1f entities/tick",
                                dimName + ":", dimMs, dimAvgCount))
                        .withStyle(ChatFormatting.AQUA), false);
            }
        } else if (!perDim.isEmpty()) {
            String dimName = dimensionName(perDim.keySet().iterator().next());
            source.sendSuccess(() -> Component.literal("  Dimension: " + dimName)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    // -------------------------------------------------------------------------
    // Entity census (entity summary --radius)
    // -------------------------------------------------------------------------

    /**
     * Sends a census of all entity types within a radius.
     *
     * @param radiusChunks  the radius used, shown in the header
     */
    public static void sendEntityCensus(
            CommandSourceStack source,
            List<EntityTypeCount> counts,
            int radiusChunks) {

        long total = counts.stream().mapToLong(EntityTypeCount::count).sum();

        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No entities found within " + radiusChunks + "-chunk radius.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- Entity Census: " + radiusChunks + "-chunk radius: " + total + " entities --")
                .withStyle(ChatFormatting.GOLD), false);

        for (EntityTypeCount row : counts) {
            MutableComponent rowLine = Component.literal(String.format("  %5d  ", row.count()))
                    .withStyle(ChatFormatting.WHITE);
            rowLine.append(Component.literal(row.typeId().toString()).withStyle(ChatFormatting.GREEN));
            source.sendSuccess(() -> rowLine, false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + total + " entities across " + counts.size() + " types")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // -------------------------------------------------------------------------
    // All-types base profile (entity profile all --radius)
    // -------------------------------------------------------------------------

    /**
     * Sends the result of an all-types profiling session (entity profile all --radius).
     * Rows are sorted by descending MSPT cost.
     *
     * @param radiusChunks  chunk radius used, shown in the header
     * @param perType       per-type data: [0] total nanos, [1] total entity-ticks
     */
    public static void sendBaseProfileResults(
            CommandSourceStack source,
            int ticks,
            int radiusChunks,
            Map<EntityType<?>, long[]> perType) {

        source.sendSuccess(() -> Component.literal(
                String.format("-- Base Profile: %d-chunk radius (%d ticks) --", radiusChunks, ticks))
                .withStyle(ChatFormatting.GOLD), false);

        long totalNanos = perType.values().stream().mapToLong(d -> d[0]).sum();
        long totalCount = perType.values().stream().mapToLong(d -> d[1]).sum();

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal(
                    "  No entities ticked within the radius during the sample window.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        double divider = 1.0 / ticks / 1_000_000.0;

        // Sort by descending total nanos (= descending MSPT)
        List<Map.Entry<EntityType<?>, long[]>> sorted = perType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .toList();

        for (var entry : sorted) {
            EntityType<?> type = entry.getKey();
            long[] data = entry.getValue();
            double ms = divider * data[0];
            double avgCount = (double) data[1] / ticks;
            @Nullable Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String label = typeId != null ? typeId.toString() : type.getDescriptionId();
            source.sendSuccess(() -> Component.literal(
                    String.format("  %-40s  %8.3fms/tick  %7.1f entities/tick", label, ms, avgCount))
                    .withStyle(ChatFormatting.WHITE), false);
        }

        double totalMs = divider * totalNanos;
        double totalAvg = (double) totalCount / ticks;
        source.sendSuccess(() -> Component.literal(
                String.format("  %-40s  %8.3fms/tick  %7.1f entities/tick", "TOTAL", totalMs, totalAvg))
                .withStyle(ChatFormatting.GOLD), false);
    }

    // -------------------------------------------------------------------------
    // Mob category type-count summary
    // -------------------------------------------------------------------------

    public static void sendMobSummary(
            CommandSourceStack source,
            List<EntityTypeCount> counts,
            String category,
            String dimName) {

        long total = counts.stream().mapToLong(EntityTypeCount::count).sum();

        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No " + category + " entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- " + category + " [" + dimName + "]: " + total + " entities --")
                .withStyle(ChatFormatting.GOLD), false);

        for (EntityTypeCount row : counts) {
            MutableComponent rowLine = Component.literal(String.format("  %5d  ", row.count())).withStyle(ChatFormatting.WHITE);
            rowLine.append(Component.literal(row.typeId().toString()).withStyle(ChatFormatting.GREEN));
            source.sendSuccess(() -> rowLine, false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + total + " entities across " + counts.size() + " types")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // -------------------------------------------------------------------------
    // Lazy entity flat list (--lazy-only on bare mob / entity / item)
    // -------------------------------------------------------------------------

    public static void sendLazyEntityList(
            CommandSourceStack source,
            List<Entity> entities,
            String label,
            String dimName) {

        if (entities.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No lazy " + label + " entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- lazy " + label + " [" + dimName + "]: " + entities.size() + " entities --")
                .withStyle(ChatFormatting.GOLD), false);

        for (Entity entity : entities) {
            source.sendSuccess(() -> formatLazyEntityLine(entity), false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + entities.size() + " entities")
                .withStyle(ChatFormatting.GRAY), false);
    }

    public static void sendPersistentEntityList(
            CommandSourceStack source,
            List<Entity> entities,
            String label,
            String dimName) {

        if (entities.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No persistent " + label + " entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- persistent " + label + " [" + dimName + "]: " + entities.size() + " entities --")
                .withStyle(ChatFormatting.GOLD), false);

        for (Entity entity : entities) {
            source.sendSuccess(() -> formatLazyEntityLine(entity), false);
        }

        source.sendSuccess(() -> Component.literal(
                "Total: " + entities.size() + " entities")
                .withStyle(ChatFormatting.GRAY), false);
    }

    private static MutableComponent formatLazyEntityLine(Entity entity) {
        String type;
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity ie) {
            @Nullable Identifier itemKey = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
            type = itemKey != null ? itemKey.toString() : "unknown:item";
        } else {
            @Nullable Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            type = typeKey != null ? typeKey.toString() : "unknown:" + entity.getType().getDescriptionId();
        }
        String name   = entity.hasCustomName() ? " \"" + entity.getCustomName().getString() + "\"" : "";
        String coords = String.format("[%d, %d, %d]", (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
        String tpCmd  = String.format("/tp @s %.1f %.1f %.1f", entity.getX(), entity.getY(), entity.getZ());
        MutableComponent line = Component.literal("  ")
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(tpCmd)));
        line.append(Component.literal(coords).withStyle(ChatFormatting.AQUA));
        line.append(Component.literal("  —  ").withStyle(ChatFormatting.WHITE));
        line.append(Component.literal(type).withStyle(ChatFormatting.GREEN));
        if (!name.isEmpty()) {
            line.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        }
        return line;
    }

    public static String dimensionName(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        String path = key.identifier().getPath();
        return switch (path) {
            case "overworld" -> "overworld";
            case "the_nether" -> "nether";
            case "the_end"    -> "end";
            default           -> path;
        };
    }
}
