package netcrafts.detective.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import me.lucko.fabric.api.permissions.v0.Permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import netcrafts.detective.EntityDetective;
import netcrafts.detective.output.ResultFormatter;
import netcrafts.detective.query.EntityProfiler;
import netcrafts.detective.query.EntityQuery;
import netcrafts.detective.query.EntityQuery.ItemTypeCount;
import netcrafts.detective.query.EntityQuery.EntityTypeCount;
import netcrafts.detective.query.MobCapInfo;
import netcrafts.detective.query.RangeFilter;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


@SuppressWarnings({"null", "java:S2589"})
public class LocateCommand {

    private LocateCommand() {}

    private static final String ERR_INTERNAL  = "An internal error occurred. Check server logs.";
    private static final String ERR_DIM       = "Unknown dimension: ";
    private static final String ERR_CATEGORY  = "Unknown category: ";
    private static final String ERR_ENTITY    = "Unknown entity type: ";

    // 5.5.5 — Per-player cooldown: prevents command spam from stalling the server
    private static final Map<UUID, Long> LAST_USE = new HashMap<>();
    private static final int COOLDOWN_TICKS = 20; // 1 second

    /** Called on player disconnect to free the cooldown entry. */
    public static void clearCooldown(UUID playerId) {
        LAST_USE.remove(playerId);
    }

    /** Returns true (and sends a failure message) if the player is on cooldown. Console is never rate-limited. */
    private static boolean onCooldown(CommandContext<CommandSourceStack> ctx) {
        Entity entity = ctx.getSource().getEntity();
        if (entity == null) return false; // console source — never rate-limited
        UUID id = entity.getUUID();
        long now = ctx.getSource().getServer().getTickCount();
        if (now - LAST_USE.getOrDefault(id, 0L) < COOLDOWN_TICKS) {
            ctx.getSource().sendFailure(Component.literal("Command on cooldown, please wait a moment."));
            return true;
        }
        LAST_USE.put(id, now);
        return false;
    }

    private static final List<String> CATEGORIES = Arrays.stream(MobCategory.values())
            .filter(c -> c != MobCategory.MISC)
            .map(MobCategory::getName)
            .toList();

    private static final List<String> DIMENSIONS = List.of("overworld", "nether", "end");

    private static final SuggestionProvider<CommandSourceStack> CATEGORY_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder);

    private static final SuggestionProvider<CommandSourceStack> DIM_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(DIMENSIONS, builder);

    private static final SuggestionProvider<CommandSourceStack> ENTITY_TYPE_SUGGESTIONS =
            (ctx, builder) -> {
                java.util.Set<String> keys = new java.util.TreeSet<>();
                java.util.ArrayList<Entity> buf = new java.util.ArrayList<>();
                ctx.getSource().getServer().getAllLevels().forEach(level -> {
                    buf.clear();
                    level.getEntities(EntityTypeTest.forClass(Entity.class), e -> true, buf);
                    for (Entity e : buf) {
                        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
                        if (key != null) keys.add(key.toString());
                    }
                });
                String remaining = builder.getRemaining().toLowerCase();
                keys.stream()
                        .filter(k -> k.toLowerCase().contains(remaining))
                        .forEach(builder::suggest);
                return builder.buildFuture();
            };

    // Dynamic suggestions: only item types that currently have at least one loaded ItemEntity
    private static final SuggestionProvider<CommandSourceStack> ITEM_TYPE_SUGGESTIONS =
            (ctx, builder) -> {
                java.util.Set<String> keys = new java.util.TreeSet<>();
                java.util.ArrayList<ItemEntity> buf = new java.util.ArrayList<>();
                ctx.getSource().getServer().getAllLevels().forEach(level -> {
                    buf.clear();
                    level.getEntities(EntityTypeTest.forClass(ItemEntity.class), e -> true, buf);
                    for (ItemEntity ie : buf) {
                        Identifier key = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
                        if (key != null) keys.add(key.toString());
                    }
                });
                String remaining = builder.getRemaining().toLowerCase();
                keys.stream()
                        .filter(k -> k.toLowerCase().contains(remaining))
                        .forEach(builder::suggest);
                return builder.buildFuture();
            };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("entitydetective")
                .requires(Permissions.require("entitydetective.command", PermissionLevel.GAMEMASTERS))

                // /entitydetective mob cap
                // /entitydetective mob <category> [--lazy-only] [--persistent] [--world <dim>] [--detail]
                .then(Commands.literal("mob")

                    // mob cap — shows live mob cap for player's current dimension
                    .then(Commands.literal("cap")
                        .executes(ctx -> executeMobcap(ctx, null))
                    )

                    // mob <category> [flags]
                    .then(Commands.argument("category", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> executeMobSummary(ctx, null, false))
                        .then(Commands.literal("--detail")
                            .executes(ctx -> executeMobDetail(ctx, null, false, false))
                        )
                        .then(Commands.literal("--lazy-only")
                            .executes(ctx -> executeLazyMobList(ctx, null, false))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeMobDetail(ctx, null, true, false))
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeLazyMobList(ctx, StringArgumentType.getString(ctx, "dim"), false))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeMobDetail(ctx, StringArgumentType.getString(ctx, "dim"), true, false))
                                    )
                                )
                            )
                            .then(Commands.literal("--persistent")
                                .executes(ctx -> executeLazyMobList(ctx, null, true))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeMobDetail(ctx, null, true, true))
                                )
                            )
                        )
                        .then(Commands.literal("--persistent")
                            .executes(ctx -> executePersistentMobList(ctx, null))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeMobDetail(ctx, null, false, true))
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executePersistentMobList(ctx, StringArgumentType.getString(ctx, "dim")))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeMobDetail(ctx, StringArgumentType.getString(ctx, "dim"), false, true))
                                    )
                                )
                            )
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeMobSummary(ctx, StringArgumentType.getString(ctx, "dim"), false))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeMobDetail(ctx, StringArgumentType.getString(ctx, "dim"), false, false))
                                )
                            )
                        )
                        .then(Commands.literal("--range")
                            .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                .executes(ctx -> executeMobSummaryRange(ctx))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeMobDetailRange(ctx, false, false))
                                )
                                .then(Commands.literal("--lazy-only")
                                    .executes(ctx -> executeMobLazyListRange(ctx, false))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeMobDetailRange(ctx, true, false))
                                    )
                                    .then(Commands.literal("--persistent")
                                        .executes(ctx -> executeMobLazyListRange(ctx, true))
                                        .then(Commands.literal("--detail")
                                            .executes(ctx -> executeMobDetailRange(ctx, true, true))
                                        )
                                    )
                                )
                                .then(Commands.literal("--persistent")
                                    .executes(ctx -> executeMobPersistentListRange(ctx))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeMobDetailRange(ctx, false, true))
                                    )
                                )
                            )
                        )
                    )
                )

                // /entitydetective entity              — summary of all entity types by count
                // /entitydetective entity --range <n>  — census of all entity types in range
                // /entitydetective entity --lazy-only  — filtered type-count table
                // /entitydetective entity locate <type> [--lazy-only] [--world <dim>] [--detail]
                // /entitydetective entity profile <type> [<ticks>]
                .then(Commands.literal("entity")
                    .executes(ctx -> executeEntitySummary(ctx, null, false))
                    .then(Commands.literal("--detail")
                        .executes(ctx -> executeEntityDetail(ctx, null, false, false))
                    )
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeEntitySummary(ctx, StringArgumentType.getString(ctx, "dim"), false))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeEntityDetail(ctx, StringArgumentType.getString(ctx, "dim"), false, false))
                            )
                        )
                    )
                    .then(Commands.literal("--persistent")
                        .executes(ctx -> executePersistentEntityList(ctx, null))
                        .then(Commands.literal("--detail")
                            .executes(ctx -> executeEntityDetail(ctx, null, false, true))
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executePersistentEntityList(ctx, StringArgumentType.getString(ctx, "dim")))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeEntityDetail(ctx, StringArgumentType.getString(ctx, "dim"), false, true))
                                )
                            )
                        )
                    )
                    .then(Commands.literal("--lazy-only")
                        .executes(ctx -> executeLazyEntityList(ctx, null, false))
                        .then(Commands.literal("--detail")
                            .executes(ctx -> executeEntityDetail(ctx, null, true, false))
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLazyEntityList(ctx, StringArgumentType.getString(ctx, "dim"), false))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeEntityDetail(ctx, StringArgumentType.getString(ctx, "dim"), true, false))
                                )
                            )
                        )
                        .then(Commands.literal("--persistent")
                            .executes(ctx -> executeLazyEntityList(ctx, null, true))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeEntityDetail(ctx, null, true, true))
                            )
                        )
                    )

                    // entity locate <entityType> [flags]
                    .then(Commands.literal("locate")
                        .then(Commands.argument("entityType", IdentifierArgument.id())
                            .suggests(ENTITY_TYPE_SUGGESTIONS)
                            .executes(ctx -> executeEntityLocate(ctx, false, null, false))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeEntityLocate(ctx, false, null, true))
                            )
                            .then(Commands.literal("--lazy-only")
                                .executes(ctx -> executeEntityLocate(ctx, true, null, false))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeEntityLocate(ctx, true, null, true))
                                )
                                .then(Commands.literal("--world")
                                    .then(Commands.argument("dim", StringArgumentType.word())
                                        .suggests(DIM_SUGGESTIONS)
                                        .executes(ctx -> executeEntityLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), false))
                                        .then(Commands.literal("--detail")
                                            .executes(ctx -> executeEntityLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), true))
                                        )
                                    )
                                )
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeEntityLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), false))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeEntityLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), true))
                                    )
                                )
                            )
                            .then(Commands.literal("--range")
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                    .executes(ctx -> executeEntityLocateRange(ctx, false, false))
                                    .then(Commands.literal("--lazy-only")
                                        .executes(ctx -> executeEntityLocateRange(ctx, true, false))
                                        .then(Commands.literal("--detail")
                                            .executes(ctx -> executeEntityLocateRange(ctx, true, true))
                                        )
                                    )
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeEntityLocateRange(ctx, false, true))
                                    )
                                )
                            )
                        )
                    )
                    .then(Commands.literal("--range")
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                            .executes(ctx -> executeEntityCensus(ctx))
                        )
                    )

                    // entity profile all [ticks] --range <n>
                    // entity profile <type> [ticks] [--range <n>]
                    .then(Commands.literal("profile")
                        .then(Commands.literal("all")
                            .executes(ctx -> executeProfileAllGlobal(ctx, 100))
                            .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> executeProfileAllGlobal(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeProfileAllWorld(ctx, 100))
                                    .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                        .executes(ctx -> executeProfileAllWorld(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                    )
                                )
                            )
                            .then(Commands.literal("--range")
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                    .executes(ctx -> executeProfileAll(ctx, 100))
                                    .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                        .executes(ctx -> executeProfileAll(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                    )
                                )
                            )
                        )
                        .then(Commands.argument("entityType", IdentifierArgument.id())
                            .suggests(ENTITY_TYPE_SUGGESTIONS)
                            .executes(ctx -> executeProfile(ctx, 100))
                            .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> executeProfile(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                .then(Commands.literal("--range")
                                    .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                        .executes(ctx -> executeProfileRange(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                    )
                                )
                            )
                            .then(Commands.literal("--range")
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                    .executes(ctx -> executeProfileRange(ctx, 100))
                                    .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                        .executes(ctx -> executeProfileRange(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                    )
                                )
                            )
                        )
                    )
                )

                // /entitydetective item              — summary across all dimensions
                // /entitydetective item --world <dim>
                // /entitydetective item locate <item_id> [--world <dim>]
                .then(Commands.literal("item")
                    .executes(ctx -> executeItemSummary(ctx, null))
                    .then(Commands.literal("--detail")
                        .executes(ctx -> executeItemDetail(ctx, null, false))
                    )
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeItemSummary(ctx, StringArgumentType.getString(ctx, "dim")))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeItemDetail(ctx, StringArgumentType.getString(ctx, "dim"), false))
                            )
                        )
                    )
                    .then(Commands.literal("--lazy-only")
                        .executes(ctx -> executeLazyItemList(ctx, null))
                        .then(Commands.literal("--detail")
                            .executes(ctx -> executeItemDetail(ctx, null, true))
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLazyItemList(ctx, StringArgumentType.getString(ctx, "dim")))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeItemDetail(ctx, StringArgumentType.getString(ctx, "dim"), true))
                                )
                            )
                        )
                    )
                    .then(Commands.literal("--range")
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                            .executes(ctx -> executeItemSummaryRange(ctx))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeItemDetailRange(ctx, false))
                            )
                        )
                    )
                    .then(Commands.literal("locate")
                        .then(Commands.argument("itemType", IdentifierArgument.id())
                            .suggests(ITEM_TYPE_SUGGESTIONS)
                            .executes(ctx -> executeItemLocate(ctx, false, null, false))
                            .then(Commands.literal("--detail")
                                .executes(ctx -> executeItemLocate(ctx, false, null, true))
                            )
                            .then(Commands.literal("--lazy-only")
                                .executes(ctx -> executeItemLocate(ctx, true, null, false))
                                .then(Commands.literal("--detail")
                                    .executes(ctx -> executeItemLocate(ctx, true, null, true))
                                )
                                .then(Commands.literal("--world")
                                    .then(Commands.argument("dim", StringArgumentType.word())
                                        .suggests(DIM_SUGGESTIONS)
                                        .executes(ctx -> executeItemLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), false))
                                        .then(Commands.literal("--detail")
                                            .executes(ctx -> executeItemLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), true))
                                        )
                                    )
                                )
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeItemLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), false))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeItemLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), true))
                                    )
                                )
                            )
                            .then(Commands.literal("--range")
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, RangeFilter.MAX_RANGE_CHUNKS))
                                    .executes(ctx -> executeItemLocateRange(ctx, false, false))
                                    .then(Commands.literal("--detail")
                                        .executes(ctx -> executeItemLocateRange(ctx, false, true))
                                    )
                                    .then(Commands.literal("--lazy-only")
                                        .executes(ctx -> executeItemLocateRange(ctx, true, false))
                                        .then(Commands.literal("--detail")
                                            .executes(ctx -> executeItemLocateRange(ctx, true, true))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
        );
    }

    private static int executeMobcap(CommandContext<CommandSourceStack> ctx, String dimArg) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel world = resolveWorld(source, dimArg);
            if (world == null) {
                source.sendFailure(Component.literal(ERR_DIM + dimArg));
                return 0;
            }
            String dimName = ResultFormatter.dimensionName(world.dimension());
            ResultFormatter.sendMobcap(source, MobCapInfo.getForDimension(world), dimName);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mobcap command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeMobSummary(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst()
                    .orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }

            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countEntitiesByCategory(world, category, persistent);
                ResultFormatter.sendMobSummary(source, counts, categoryName, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob summary command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeLazyMobList(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst()
                    .orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }

            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countLazyByCategory(world, category, persistent);
                String label = categoryName + (persistent ? " (lazy + persistent)" : " (lazy only)");
                ResultFormatter.sendMobSummary(source, counts, label, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy mob list command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executePersistentMobList(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst()
                    .orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }

            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countEntitiesByCategory(world, category, true);
                ResultFormatter.sendMobSummary(source, counts, categoryName + " (persistent)", dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in persistent mob list command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executePersistentEntityList(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countEntitiesByType(world, true);
                ResultFormatter.sendEntitySummary(source, counts, dimName + " (persistent)");
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in persistent entity list command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeEntityLocate(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            String dimArg,
            boolean detail) {

        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal(ERR_ENTITY + id));
                return 0;
            }
            EntityType<?> entityType = typeOpt.get();
            String label = id.toString();

            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findByType(world, entityType, lazyOnly);
                ResultFormatter.sendLocateResults(source, results, label, dimName, lazyOnly, detail);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity locate command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeLazyEntityList(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countLazyEntities(world, persistent);
                String filterNote = persistent ? " (lazy + persistent)" : " (lazy only)";
                ResultFormatter.sendEntitySummary(source, counts, dimName + filterNote);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy entity list command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeEntitySummary(CommandContext<CommandSourceStack> ctx, @Nullable String dimArg, boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<EntityTypeCount> counts = EntityQuery.countEntitiesByType(world, persistent);
                ResultFormatter.sendEntitySummary(source, counts, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity summary command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeLazyItemList(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var counts = EntityQuery.countLazyItemsByType(world);
                ResultFormatter.sendItemSummary(source, counts, dimName + " (lazy only)");
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy item list command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemSummary(CommandContext<CommandSourceStack> ctx, @Nullable String dimArg) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<ItemTypeCount> counts = EntityQuery.countItemsByType(world);
                ResultFormatter.sendItemSummary(source, counts, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_summary command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemLocate(CommandContext<CommandSourceStack> ctx, boolean lazyOnly, @Nullable String dimArg, boolean detail) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            Identifier id = IdentifierArgument.getId(ctx, "itemType");
            // Validate item exists in registry
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                source.sendFailure(Component.literal("Unknown item: " + id));
                return 0;
            }
            String label = id.toString();

            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findItemsByType(world, id, lazyOnly);
                ResultFormatter.sendLocateResults(source, results, label, dimName, lazyOnly, detail);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_locate command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeProfile(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal(ERR_ENTITY + id));
                return 0;
            }
            EntityProfiler.INSTANCE.start(source, typeOpt.get(), ticks);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // --detail executor methods
    // -------------------------------------------------------------------------

    private static int executeMobDetail(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean lazyOnly,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }
            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findEntities(world, category, lazyOnly, persistent);
                String filter = (lazyOnly ? " (lazy)" : "") + (persistent ? " (persistent)" : "");
                ResultFormatter.sendChunkGroupedDetail(source, results, categoryName + filter, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob detail command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeMobDetailRange(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findByCategoryInRange(world, category, lazyOnly, persistent, centre, chunkRange);
            String filter = (lazyOnly ? " (lazy)" : "") + (persistent ? " (persistent)" : "");
            ResultFormatter.sendChunkGroupedDetail(source, results,
                    categoryName + " (" + chunkRange + "-chunk range)" + filter,
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob detail range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeEntityDetail(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean lazyOnly,
            boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }
            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findAllEntitiesGrouped(world, lazyOnly, persistent);
                String filter = (lazyOnly ? " (lazy)" : "") + (persistent ? " (persistent)" : "");
                ResultFormatter.sendChunkGroupedDetail(source, results, "entity" + filter, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity detail command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemDetail(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String dimArg,
            boolean lazyOnly) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal(ERR_DIM + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }
            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findAllItemEntitiesGrouped(world, lazyOnly);
                String filter = lazyOnly ? " (lazy)" : "";
                ResultFormatter.sendChunkGroupedDetail(source, results, "item" + filter, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item detail command", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemDetailRange(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findAllItemEntitiesInRange(world, lazyOnly, centre, chunkRange);
            String filter = lazyOnly ? " (lazy)" : "";
            ResultFormatter.sendChunkGroupedDetail(source, results,
                    "item (" + chunkRange + "-chunk range)" + filter,
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item detail range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Range executor methods
    // -------------------------------------------------------------------------

    private static int executeMobSummaryRange(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countEntitiesByCategoryInRange(world, category, false, centre, chunkRange);
            ResultFormatter.sendMobSummary(source, counts,
                    categoryName + " (" + chunkRange + "-chunk range)",
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob summary range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeMobLazyListRange(CommandContext<CommandSourceStack> ctx, boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countLazyByCategoryInRange(world, category, persistent, centre, chunkRange);
            String label = categoryName + " (" + chunkRange + "-chunk range)" + (persistent ? " (lazy + persistent)" : " (lazy only)");
            ResultFormatter.sendMobSummary(source, counts, label,
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob lazy range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeMobPersistentListRange(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal(ERR_CATEGORY + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countEntitiesByCategoryInRange(world, category, true, centre, chunkRange);
            ResultFormatter.sendMobSummary(source, counts,
                    categoryName + " (" + chunkRange + "-chunk range) (persistent)",
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob persistent range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeEntityLocateRange(
            CommandContext<CommandSourceStack> ctx, boolean lazyOnly, boolean detail) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal(ERR_ENTITY + id));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findByTypeInRange(world, typeOpt.get(), lazyOnly, centre, chunkRange);
            ResultFormatter.sendLocateResults(source, results,
                    id + " (" + chunkRange + "-chunk range)",
                    ResultFormatter.dimensionName(world.dimension()), lazyOnly, detail);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity locate range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeEntityCensus(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countAllEntitiesInRange(world, centre, chunkRange);
            ResultFormatter.sendEntityCensus(source, counts, chunkRange);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity census", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeProfileRange(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal(ERR_ENTITY + id));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            EntityProfiler.INSTANCE.startWithRange(source, typeOpt.get(), ticks,
                    centre, source.getLevel().dimension(), chunkRange);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeProfileAll(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            EntityProfiler.INSTANCE.startAllTypes(source, ticks,
                    centre, source.getLevel().dimension(), chunkRange);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile all", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeProfileAllGlobal(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        try {
            EntityProfiler.INSTANCE.startAllTypesGlobal(source, ticks);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile all global", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeProfileAllWorld(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        try {
            String dimArg = StringArgumentType.getString(ctx, "dim");
            ServerLevel world = resolveWorld(source, dimArg);
            if (world == null) {
                source.sendFailure(Component.literal(ERR_DIM + dimArg));
                return 0;
            }
            EntityProfiler.INSTANCE.startAllTypesDimension(source, ticks, world.dimension());
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile all world", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemSummaryRange(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countItemsByTypeInRange(world, centre, chunkRange);
            ResultFormatter.sendItemSummary(source, counts, chunkRange + "-chunk range");
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item summary range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    private static int executeItemLocateRange(CommandContext<CommandSourceStack> ctx, boolean lazyOnly, boolean detail) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RangeFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "itemType");
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                source.sendFailure(Component.literal("Unknown item: " + id));
                return 0;
            }
            int chunkRange = IntegerArgumentType.getInteger(ctx, "range");
            Vec3 centre = source.getPosition();
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findItemsByTypeInRange(world, id, lazyOnly, centre, chunkRange);
            ResultFormatter.sendLocateResults(source, results,
                    id + " (" + chunkRange + "-chunk range)",
                    ResultFormatter.dimensionName(world.dimension()), lazyOnly, detail);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item locate range", e);
            source.sendFailure(Component.literal(ERR_INTERNAL));
            return 0;
        }
    }

    @Nullable
    private static ServerLevel resolveWorld(CommandSourceStack source, @Nullable String dimArg) {
        if (dimArg == null) return source.getLevel();
        ResourceKey<Level> key = switch (dimArg) {
            case "overworld" -> Level.OVERWORLD;
            case "nether"    -> Level.NETHER;
            case "end"       -> Level.END;
            default          -> null;
        };
        if (key == null) return null;
        return source.getServer().getLevel(key);
    }
}
