package netcrafts.detective.output;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

import org.jetbrains.annotations.Nullable;

import netcrafts.detective.query.EntityQuery.QueryResult;
import netcrafts.detective.query.MobCapInfo.CategoryInfo;

import java.util.List;

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
        String name = entity.hasCustomName() ? " \"" + entity.getCustomName().getString() + "\"" : "";
        String reason = persistenceReason(entity);
        String coords = String.format("%.1f, %.1f, %.1f", entity.getX(), entity.getY(), entity.getZ());
        String tpCommand = String.format("/tp @s %.1f %.1f %.1f", entity.getX(), entity.getY(), entity.getZ());
        MutableComponent line = Component.literal("    " + type + name + "  @ " + coords + "  (" + reason + ")")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.SuggestCommand(tpCommand)));
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
