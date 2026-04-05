package netcrafts.detective.commands;

import com.mojang.brigadier.CommandDispatcher;
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

import netcrafts.detective.EntityDetective;
import netcrafts.detective.output.ResultFormatter;
import netcrafts.detective.query.EntityQuery;
import netcrafts.detective.query.EntityQuery.ItemTypeCount;
import netcrafts.detective.query.MobCapInfo;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class LocateCommand {

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
            .map(MobCategory::getName)
            .collect(Collectors.toList());

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

                // /entitydetective mobcap  (player's current dimension only)
                .then(Commands.literal("mobcap")
                    .executes(ctx -> executeMobcap(ctx, null))
                )

                // /entitydetective <category> [--lazy-only] [--summary] [--persistent] [--world <dim>] [--debug]
                .then(Commands.argument("category", StringArgumentType.word())
                    .suggests(CATEGORY_SUGGESTIONS)
                    .executes(ctx -> executeLocate(ctx, false, false, null, false, false))
                    .then(Commands.literal("--debug")
                        .executes(ctx -> executeLocate(ctx, false, false, null, false, true))
                    )
                    .then(Commands.literal("--lazy-only")
                        // no --world: scans all dimensions
                        .executes(ctx -> executeLocate(ctx, true, false, null, false, false))
                        .then(Commands.literal("--debug")
                            .executes(ctx -> executeLocate(ctx, true, false, null, false, true))
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLocate(ctx, true, false, StringArgumentType.getString(ctx, "dim"), false, false))
                                .then(Commands.literal("--debug")
                                    .executes(ctx -> executeLocate(ctx, true, false, StringArgumentType.getString(ctx, "dim"), false, true))
                                )
                            )
                        )
                    )
                    .then(Commands.literal("--summary")
                        .executes(ctx -> executeLocate(ctx, false, true, null, false, false))
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLocate(ctx, false, true, StringArgumentType.getString(ctx, "dim"), false, false))
                            )
                        )
                    )
                    .then(Commands.literal("--persistent")
                        .executes(ctx -> executeLocate(ctx, false, false, null, true, false))
                        .then(Commands.literal("--debug")
                            .executes(ctx -> executeLocate(ctx, false, false, null, true, true))
                        )
                    )
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeLocate(ctx, false, false, StringArgumentType.getString(ctx, "dim"), false, false))
                            .then(Commands.literal("--debug")
                                .executes(ctx -> executeLocate(ctx, false, false, StringArgumentType.getString(ctx, "dim"), false, true))
                            )
                        )
                    )
                )

                // /entitydetective entity <entityType> [--lazy-only [--world <dim>]] [--world <dim>] [--debug]
                .then(Commands.literal("entity")
                    .then(Commands.argument("entityType", IdentifierArgument.id())
                        .suggests(ENTITY_TYPE_SUGGESTIONS)
                        .executes(ctx -> executeEntityLocate(ctx, false, null, false))
                        .then(Commands.literal("--debug")
                            .executes(ctx -> executeEntityLocate(ctx, false, null, true))
                        )
                        .then(Commands.literal("--lazy-only")
                            .executes(ctx -> executeEntityLocate(ctx, true, null, false))
                            .then(Commands.literal("--debug")
                                .executes(ctx -> executeEntityLocate(ctx, true, null, true))
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeEntityLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), false))
                                    .then(Commands.literal("--debug")
                                        .executes(ctx -> executeEntityLocate(ctx, true, StringArgumentType.getString(ctx, "dim"), true))
                                    )
                                )
                            )
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeEntityLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), false))
                                .then(Commands.literal("--debug")
                                    .executes(ctx -> executeEntityLocate(ctx, false, StringArgumentType.getString(ctx, "dim"), true))
                                )
                            )
                        )
                    )
                )

                // /entitydetective item_summary [--world <dim>]
                .then(Commands.literal("item_summary")
                    .executes(ctx -> executeItemSummary(ctx, null))
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeItemSummary(ctx, StringArgumentType.getString(ctx, "dim")))
                        )
                    )
                )

                // /entitydetective item_locate <item_id> [--world <dim>]
                .then(Commands.literal("item_locate")
                    .then(Commands.argument("itemType", IdentifierArgument.id())
                        .suggests(ITEM_TYPE_SUGGESTIONS)
                        .executes(ctx -> executeItemLocate(ctx, null))
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeItemLocate(ctx, StringArgumentType.getString(ctx, "dim")))
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
                source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                return 0;
            }
            String dimName = ResultFormatter.dimensionName(world.dimension());
            ResultFormatter.sendMobcap(source, MobCapInfo.getForDimension(world), dimName);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mobcap command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeLocate(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            boolean summary,
            String dimArg,
            boolean persistent,
            boolean debug) {

        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");

            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst()
                    .orElse(null);
            if (category == null) {
                source.sendFailure(Component.literal("Unknown category: " + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }

            // No --world specified → scan all loaded dimensions
            List<ServerLevel> worlds;
            if (dimArg == null) {
                worlds = new java.util.ArrayList<>();
                source.getServer().getAllLevels().forEach(worlds::add);
            } else {
                ServerLevel world = resolveWorld(source, dimArg);
                if (world == null) {
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findEntities(world, category, lazyOnly, persistent);
                if (summary) {
                    ResultFormatter.sendLocateSummary(source, results, category.getName(), dimName, lazyOnly);
                } else {
                    ResultFormatter.sendLocateResults(source, results, category.getName(), dimName, lazyOnly, debug);
                }
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in locate command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeEntityLocate(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            String dimArg,
            boolean debug) {

        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal("Unknown entity type: " + id));
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findByType(world, entityType, lazyOnly);
                ResultFormatter.sendLocateResults(source, results, label, dimName, lazyOnly, debug);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity locate command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            // Aggregate counts across all targeted dimensions
            java.util.Map<Identifier, long[]> combined = new java.util.LinkedHashMap<>();
            for (ServerLevel world : worlds) {
                for (ItemTypeCount row : EntityQuery.countItemsByType(world)) {
                    long[] acc = combined.computeIfAbsent(row.itemId(), k -> new long[]{0L, 0L});
                    acc[0] += row.entityCount();
                    acc[1] += row.itemTotal();
                }
            }
            List<ItemTypeCount> merged = combined.entrySet().stream()
                    .map(e -> new ItemTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .sorted(java.util.Comparator.<ItemTypeCount>comparingLong(r -> -r.itemTotal()))
                    .toList();

            String dimName = dimArg == null ? "all dimensions" : dimArg;
            ResultFormatter.sendItemSummary(source, merged, dimName);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_summary command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeItemLocate(CommandContext<CommandSourceStack> ctx, @Nullable String dimArg) {
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                var results = EntityQuery.findItemsByType(world, id);
                ResultFormatter.sendLocateResults(source, results, label, dimName, false, false);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_locate command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
