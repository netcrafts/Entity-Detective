package netcrafts.detective.output;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

import org.jetbrains.annotations.Nullable;

import netcrafts.detective.query.EntityQuery.BELocateResult;
import netcrafts.detective.query.EntityQuery.ItemTypeCount;
import netcrafts.detective.query.EntityQuery.EntityTypeCount;
import netcrafts.detective.query.EntityQuery.QueryResult;
import netcrafts.detective.query.MobCapInfo.CategoryInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"null", "java:S2589"})
public class ResultFormatter {

    private ResultFormatter() {}

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

        int color;
        if (pct < 0.5)       color = COLOR_LOW;
        else if (pct < 0.85) color = COLOR_MEDIUM;
        else                 color = COLOR_HIGH;
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
            boolean detail) {

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
            if (detail) {
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MutableComponent formatEntityDebugLine(Entity entity) {
        // 5.5.2 / 5.5.8 — getKey() can return null for unregistered modded entity types
        @Nullable Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String type = typeKey != null ? typeKey.toString() : "unknown:" + entity.getType().getDescriptionId();
        net.minecraft.network.chat.Component cn = entity.getCustomName();
        String name   = cn != null ? " \"" + cn.getString() + "\"" : "";
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
    // Block entity locate display
    // -------------------------------------------------------------------------

    public static void sendBELocateResults(
            CommandSourceStack source,
            List<BELocateResult> results,
            String label,
            String dimName,
            boolean detail) {

        long total = results.stream().mapToLong(r -> r.positions().size()).sum();

        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No " + label + " block entities found in " + dimName + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        String header = String.format("-- %s [%s]: %d block entities in %d chunks --",
                label, dimName, total, results.size());
        source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.GOLD), false);

        int shown = Math.min(results.size(), MAX_CHUNKS_SHOWN);
        for (int i = 0; i < shown; i++) {
            BELocateResult r = results.get(i);
            source.sendSuccess(() -> formatBEChunkLine(r), false);
            if (detail) {
                for (BlockPos pos : r.positions()) {
                    source.sendSuccess(() -> formatBEPosLine(pos), false);
                }
            }
        }

        if (results.size() > MAX_CHUNKS_SHOWN) {
            int remaining = results.size() - MAX_CHUNKS_SHOWN;
            source.sendSuccess(() -> Component.literal("  ...and " + remaining + " more chunks.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static MutableComponent formatBEChunkLine(BELocateResult r) {
        ChunkPos pos = r.chunkPos();
        int count = r.positions().size();
        int midX = pos.getMiddleBlockX();
        int midZ = pos.getMiddleBlockZ();

        String tpCommand = String.format("/tp @s %d ~ %d", midX, midZ);
        ClickEvent click = new ClickEvent.SuggestCommand(tpCommand);

        MutableComponent coords = Component.literal(
                String.format("  [%d, %d]", pos.x(), pos.z()))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(click));

        MutableComponent countPart = Component.literal(" — " + count + " block entities")
                .withStyle(ChatFormatting.WHITE);

        return coords.append(countPart);
    }

    private static MutableComponent formatBEPosLine(BlockPos pos) {
        String coords = String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
        String tpCommand = String.format("/tp @s %d %d %d", pos.getX(), pos.getY(), pos.getZ());
        return Component.literal("    ")
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(tpCommand)))
                .append(Component.literal(coords).withStyle(ChatFormatting.AQUA));
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
            ChatFormatting color;
            if (row.itemTotal() < 100)       color = ChatFormatting.GREEN;
            else if (row.itemTotal() < 1000) color = ChatFormatting.YELLOW;
            else                             color = ChatFormatting.RED;
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
     * @param chunkRange  chunk range used during profiling; 0 means no range restriction
     * @param perDim        per-dimension data: [0] total nanos, [1] total entity-ticks
     */
    public static void sendProfileResults(
            CommandSourceStack source,
            EntityType<?> type,
            int ticks,
            int chunkRange,
            Map<ResourceKey<Level>, long[]> perDim) {

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();

        String rangeNote = chunkRange > 0 ? " [" + chunkRange + "-chunk range]" : "";

        double divider = 1.0 / ticks / 1_000_000.0; // nanos per tick → ms per tick
        long totalNanos = perDim.values().stream().mapToLong(d -> d[0]).sum();
        long totalCount = perDim.values().stream().mapToLong(d -> d[1]).sum();

        source.sendSuccess(() -> Component.literal(
                String.format("-- Entity Profile: %s%s (%d ticks) --", label, rangeNote, ticks))
                .withStyle(ChatFormatting.GOLD), false);

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal(
                    "  No entities of this type were ticked during the sample window.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        double totalMs    = divider * totalNanos;
        double perEntityMs = totalNanos / (double) totalCount / 1_000_000.0;
        double avgCount   = (double) totalCount / ticks;

        source.sendSuccess(() -> Component.literal(
                String.format("  Total tick cost:  %.3fmspt", totalMs))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. tick cost:   %.3fms / entity", perEntityMs))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. count:       %.1f entities/tick", avgCount))
                .withStyle(ChatFormatting.WHITE), false);

        if (perDim.size() > 1) {
            for (var entry : perDim.entrySet()) {
                String dimName = dimensionName(entry.getKey());
                long[] data = entry.getValue();
                double dimMs        = divider * data[0];
                double dimPerEntity = data[0] / (double) data[1] / 1_000_000.0;
                double dimAvgCount  = (double) data[1] / ticks;
                source.sendSuccess(() -> Component.literal(
                        String.format("  %-12s %.3fmspt  avg: %.3fms  \u00d7%.1f",
                                dimName + ":", dimMs, dimPerEntity, dimAvgCount))
                        .withStyle(ChatFormatting.AQUA), false);
            }
        } else if (!perDim.isEmpty()) {
            String dimName = dimensionName(perDim.keySet().iterator().next());
            source.sendSuccess(() -> Component.literal("  Dimension: " + dimName)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    // -------------------------------------------------------------------------
    // Single-BE-type profile
    // -------------------------------------------------------------------------

    /**
     * Sends the result of a single-block-entity-type profiling session.
     * Mirrors {@link #sendProfileResults} but resolves the type name via the BE registry.
     *
     * @param source        who to send results to
     * @param type          the profiled block entity type
     * @param ticks         the sample window (total ticks)
     * @param chunkRange    chunk range used during profiling; 0 means no range restriction
     * @param perBEDim      per-dimension data: [0] total nanos, [1] total BE-ticks
     */
    public static void sendBEProfileResults(
            CommandSourceStack source,
            BlockEntityType<?> type,
            int ticks,
            int chunkRange,
            Map<ResourceKey<Level>, long[]> perBEDim) {

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();

        String rangeNote = chunkRange > 0 ? " [" + chunkRange + "-chunk range]" : "";

        double divider = 1.0 / ticks / 1_000_000.0;
        long totalNanos = perBEDim.values().stream().mapToLong(d -> d[0]).sum();
        long totalCount = perBEDim.values().stream().mapToLong(d -> d[1]).sum();

        source.sendSuccess(() -> Component.literal(
                String.format("-- BE Profile: %s%s (%d ticks) --", label, rangeNote, ticks))
                .withStyle(ChatFormatting.GOLD), false);

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal(
                    "  No block entities of this type were ticked during the sample window.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        double totalMs    = divider * totalNanos;
        double perBEMs    = totalNanos / (double) totalCount / 1_000_000.0;
        double avgCount   = (double) totalCount / ticks;

        source.sendSuccess(() -> Component.literal(
                String.format("  Total tick cost:  %.3fmspt", totalMs))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. tick cost:   %.3fms / BE", perBEMs))
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal(
                String.format("  Avg. count:       %.1f BEs/tick", avgCount))
                .withStyle(ChatFormatting.WHITE), false);

        if (perBEDim.size() > 1) {
            for (var entry : perBEDim.entrySet()) {
                String dimName = dimensionName(entry.getKey());
                long[] data = entry.getValue();
                double dimMs        = divider * data[0];
                double dimPerBE     = data[0] / (double) data[1] / 1_000_000.0;
                double dimAvgCount  = (double) data[1] / ticks;
                source.sendSuccess(() -> Component.literal(
                        String.format("  %-12s %.3fmspt  avg: %.3fms  \u00d7%.1f",
                                dimName + ":", dimMs, dimPerBE, dimAvgCount))
                        .withStyle(ChatFormatting.AQUA), false);
            }
        } else if (!perBEDim.isEmpty()) {
            String dimName = dimensionName(perBEDim.keySet().iterator().next());
            source.sendSuccess(() -> Component.literal("  Dimension: " + dimName)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    // -------------------------------------------------------------------------
    // Entity census (entity summary --range)
    // -------------------------------------------------------------------------

    /**
     * Sends a census of all entity types within a chunk range.
     *
     * @param chunkRange  the range used, shown in the header
     */
    public static void sendEntityCensus(
            CommandSourceStack source,
            List<EntityTypeCount> counts,
            int chunkRange) {

        long total = counts.stream().mapToLong(EntityTypeCount::count).sum();

        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No entities found within " + chunkRange + "-chunk range.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(
                "-- Entity Census: " + chunkRange + "-chunk range: " + total + " entities --")
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
    // All-types base profile (entity profile all --range)
    // -------------------------------------------------------------------------

    /**
     * Sends the result of an all-types profiling session (entity profile all --range).
     * Merges entity types and block entity types into a single list sorted by descending MSPT cost.
     *
     * @param perType   per-entity-type data: [0] total nanos, [1] total ticks
     * @param perBEType per-block-entity-type data: [0] total nanos, [1] total ticks
     */
    public static void sendBaseProfileResults(
            CommandSourceStack source,
            int ticks,
            String scopeLabel,
            Map<EntityType<?>, long[]> perType,
            Map<BlockEntityType<?>, long[]> perBEType) {

        source.sendSuccess(() -> Component.literal(
                String.format("-- Base Profile: %s (%d ticks) --", scopeLabel, ticks))
                .withStyle(ChatFormatting.GOLD), false);

        // Build a unified list of (displayName, nanos, ticks) records
        record ProfileRow(String name, long nanos, long count) {}
        List<ProfileRow> rows = new ArrayList<>();

        for (var entry : perType.entrySet()) {
            @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey());
            String name = id != null
                    ? ("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString())
                    : entry.getKey().getDescriptionId();
            rows.add(new ProfileRow(name, entry.getValue()[0], entry.getValue()[1]));
        }

        for (var entry : perBEType.entrySet()) {
            @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entry.getKey());
            String rawName = id != null
                    ? ("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString())
                    : entry.getKey().toString();
            // Prefix block entities to distinguish them from mobs with the same short name
            String name = "[be] " + rawName;
            rows.add(new ProfileRow(name, entry.getValue()[0], entry.getValue()[1]));
        }

        long totalNanos = rows.stream().mapToLong(ProfileRow::nanos).sum();
        long totalCount = rows.stream().mapToLong(ProfileRow::count).sum();

        if (totalCount == 0) {
            source.sendSuccess(() -> Component.literal(
                    "  No entities or block entities ticked during the sample window.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        double divider = 1.0 / ticks / 1_000_000.0;

        rows.sort((a, b) -> Long.compare(b.nanos(), a.nanos()));

        for (ProfileRow row : rows) {
            double ms = divider * row.nanos();
            double avgCount = (double) row.count() / ticks;
            double msPerTick = avgCount > 0 ? ms / avgCount : 0.0;
            source.sendSuccess(() -> Component.literal(
                    String.format("  %7.3fmspt  avg: %6.3fms  %s \u00d7%.0f",
                            ms, msPerTick, row.name(), avgCount))
                    .withStyle(ChatFormatting.WHITE), false);
        }

        double totalMs = divider * totalNanos;
        double totalAvg = (double) totalCount / ticks;
        source.sendSuccess(() -> Component.literal(
                String.format("  %7.3fmspt  TOTAL \u00d7%.0f", totalMs, totalAvg))
                .withStyle(ChatFormatting.GOLD), false);
    }

    // -------------------------------------------------------------------------
    // Chunk-grouped detail view (--detail on mob / entity / item)
    // -------------------------------------------------------------------------

    /**
     * Sends a chunk-grouped detail listing for mob / entity / item commands when --detail is used.
     * Each chunk is a clickable /tp line; each entity line is a clickable /tp with
     * persistence reason shown only when the entity is actually persistent.
     */
    public static void sendChunkGroupedDetail(
            CommandSourceStack source,
            List<QueryResult> results,
            String label,
            String dimName) {

        long totalEntities = results.stream().mapToLong(r -> r.entities().size()).sum();

        if (totalEntities == 0) {
            source.sendSuccess(() -> Component.literal(
                    "No " + label + " entities found in " + dimName + " (--detail).")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        String header = String.format("-- %s [%s] (--detail): %d entities in %d chunks --",
                label, dimName, totalEntities, results.size());
        source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.GOLD), false);

        int shown = Math.min(results.size(), MAX_CHUNKS_SHOWN);
        for (int i = 0; i < shown; i++) {
            QueryResult r = results.get(i);
            source.sendSuccess(() -> formatDetailChunkLine(r), false);
            for (Entity entity : r.entities()) {
                source.sendSuccess(() -> formatDetailEntityLine(entity), false);
            }
        }

        if (results.size() > MAX_CHUNKS_SHOWN) {
            int remaining = results.size() - MAX_CHUNKS_SHOWN;
            source.sendSuccess(() -> Component.literal("  ...and " + remaining + " more chunks.")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        long finalTotal = totalEntities;
        source.sendSuccess(() -> Component.literal("Total: " + finalTotal + " entities")
                .withStyle(ChatFormatting.GRAY), false);
    }

    private static MutableComponent formatDetailChunkLine(QueryResult r) {
        ChunkPos pos = r.chunkPos();
        int count = r.entities().size();
        int midX = pos.getMiddleBlockX();
        int midZ = pos.getMiddleBlockZ();
        String tpCmd = String.format("/tp @s %d ~ %d", midX, midZ);
        ClickEvent click = new ClickEvent.SuggestCommand(tpCmd);
        String text = String.format("  Chunk (%d, %d)  \u00d7  %d  \u2192  /tp @s %d ~ %d",
                pos.x(), pos.z(), count, midX, midZ);
        return Component.literal(text)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(click));
    }

    private static MutableComponent formatDetailEntityLine(Entity entity) {
        String type;
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity ie) {
            @Nullable Identifier itemKey = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
            type = itemKey != null ? itemKey.toString() : "unknown:item";
        } else {
            @Nullable Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            type = typeKey != null ? typeKey.toString() : "unknown:" + entity.getType().getDescriptionId();
        }
        net.minecraft.network.chat.Component cn = entity.getCustomName();
        String name = cn != null ? " \"" + cn.getString() + "\"" : "";
        String coords = String.format("[%d, %d, %d]", (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
        String tpCmd = String.format("/tp @s %.1f %.1f %.1f", entity.getX(), entity.getY(), entity.getZ());

        MutableComponent line = Component.literal("    ")
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(tpCmd)));
        line.append(Component.literal(coords).withStyle(ChatFormatting.AQUA));
        line.append(Component.literal("  \u2014  ").withStyle(ChatFormatting.WHITE));
        line.append(Component.literal(type).withStyle(ChatFormatting.GREEN));
        if (!name.isEmpty()) {
            line.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        }
        if (isActuallyPersistent(entity)) {
            line.append(Component.literal("  (" + persistenceReason(entity) + ")").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    private static boolean isActuallyPersistent(Entity entity) {
        return entity instanceof Mob mob
                && (mob.isPersistenceRequired()
                    || mob.requiresCustomPersistence()
                    || mob.isPassenger()
                    || mob.isLeashed());
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
        net.minecraft.network.chat.Component cn2 = entity.getCustomName();
        String name   = cn2 != null ? " \"" + cn2.getString() + "\"" : "";
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
