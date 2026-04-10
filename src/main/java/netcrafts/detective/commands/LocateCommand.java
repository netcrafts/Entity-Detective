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
import netcrafts.detective.query.RadiusFilter;

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
            .filter(c -> c != MobCategory.MISC)
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

                // /entitydetective mob cap
                // /entitydetective mob <category> [--lazy-only] [--summary] [--persistent] [--world <dim>] [--debug]
                .then(Commands.literal("mob")

                    // mob cap — shows live mob cap for player's current dimension
                    .then(Commands.literal("cap")
                        .executes(ctx -> executeMobcap(ctx, null))
                    )

                    // mob <category> [flags]
                    .then(Commands.argument("category", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> executeMobSummary(ctx, null, false))
                        .then(Commands.literal("--lazy-only")
                            .executes(ctx -> executeLazyMobList(ctx, null, false))
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeLazyMobList(ctx, StringArgumentType.getString(ctx, "dim"), false))
                                )
                            )
                            .then(Commands.literal("--persistent")
                                .executes(ctx -> executeLazyMobList(ctx, null, true))
                            )
                        )
                        .then(Commands.literal("--persistent")
                            .executes(ctx -> executePersistentMobList(ctx, null))
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executePersistentMobList(ctx, StringArgumentType.getString(ctx, "dim")))
                                )
                            )
                        )
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeMobSummary(ctx, StringArgumentType.getString(ctx, "dim"), false))
                            )
                        )
                        .then(Commands.literal("--radius")
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                .executes(ctx -> executeMobSummaryRadius(ctx))
                                .then(Commands.literal("--lazy-only")
                                    .executes(ctx -> executeMobLazyListRadius(ctx, false))
                                    .then(Commands.literal("--persistent")
                                        .executes(ctx -> executeMobLazyListRadius(ctx, true))
                                    )
                                )
                                .then(Commands.literal("--persistent")
                                    .executes(ctx -> executeMobPersistentListRadius(ctx))
                                )
                            )
                        )
                    )
                )

                // /entitydetective entity              — summary of all entity types by count
                // /entitydetective entity --lazy-only  — flat lazy entity list
                // /entitydetective entity locate <type> [--lazy-only] [--world <dim>] [--debug]
                // /entitydetective entity profile <type> [<ticks>]
                .then(Commands.literal("entity")
                    .executes(ctx -> executeEntitySummary(ctx, null, false))
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeEntitySummary(ctx, StringArgumentType.getString(ctx, "dim"), false))
                        )
                    )
                    .then(Commands.literal("--persistent")
                        .executes(ctx -> executePersistentEntityList(ctx, null))
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executePersistentEntityList(ctx, StringArgumentType.getString(ctx, "dim")))
                            )
                        )
                    )
                    .then(Commands.literal("--lazy-only")
                        .executes(ctx -> executeLazyEntityList(ctx, null, false))
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLazyEntityList(ctx, StringArgumentType.getString(ctx, "dim"), false))
                            )
                        )
                        .then(Commands.literal("--persistent")
                            .executes(ctx -> executeLazyEntityList(ctx, null, true))
                        )
                    )

                    // entity locate <entityType> [flags]
                    .then(Commands.literal("locate")
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
                            .then(Commands.literal("--radius")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                    .executes(ctx -> executeEntityLocateRadius(ctx, false, false))
                                    .then(Commands.literal("--lazy-only")
                                        .executes(ctx -> executeEntityLocateRadius(ctx, true, false))
                                        .then(Commands.literal("--debug")
                                            .executes(ctx -> executeEntityLocateRadius(ctx, true, true))
                                        )
                                    )
                                    .then(Commands.literal("--debug")
                                        .executes(ctx -> executeEntityLocateRadius(ctx, false, true))
                                    )
                                )
                            )
                        )
                    )

                    // entity summary --radius <n>  — census of all entity types in radius
                    .then(Commands.literal("summary")
                        .then(Commands.literal("--radius")
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                .executes(ctx -> executeEntityCensus(ctx))
                            )
                        )
                    )

                    // entity profile all [ticks] --radius <n>
                    // entity profile <type> [ticks] [--radius <n>]
                    .then(Commands.literal("profile")
                        .then(Commands.literal("all")
                            .then(Commands.literal("--radius")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
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
                                .then(Commands.literal("--radius")
                                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                        .executes(ctx -> executeProfileRadius(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
                                    )
                                )
                            )
                            .then(Commands.literal("--radius")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                    .executes(ctx -> executeProfileRadius(ctx, 100))
                                    .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                        .executes(ctx -> executeProfileRadius(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))
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
                    .then(Commands.literal("--world")
                        .then(Commands.argument("dim", StringArgumentType.word())
                            .suggests(DIM_SUGGESTIONS)
                            .executes(ctx -> executeItemSummary(ctx, StringArgumentType.getString(ctx, "dim")))
                        )
                    )
                    .then(Commands.literal("--lazy-only")
                        .executes(ctx -> executeLazyItemList(ctx, null))
                        .then(Commands.literal("--world")
                            .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(DIM_SUGGESTIONS)
                                .executes(ctx -> executeLazyItemList(ctx, StringArgumentType.getString(ctx, "dim")))
                            )
                        )
                    )
                    .then(Commands.literal("--radius")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                            .executes(ctx -> executeItemSummaryRadius(ctx))
                        )
                    )
                    .then(Commands.literal("locate")
                        .then(Commands.argument("itemType", IdentifierArgument.id())
                            .suggests(ITEM_TYPE_SUGGESTIONS)
                            .executes(ctx -> executeItemLocate(ctx, false, null))
                            .then(Commands.literal("--lazy-only")
                                .executes(ctx -> executeItemLocate(ctx, true, null))
                                .then(Commands.literal("--world")
                                    .then(Commands.argument("dim", StringArgumentType.word())
                                        .suggests(DIM_SUGGESTIONS)
                                        .executes(ctx -> executeItemLocate(ctx, true, StringArgumentType.getString(ctx, "dim")))
                                    )
                                )
                            )
                            .then(Commands.literal("--world")
                                .then(Commands.argument("dim", StringArgumentType.word())
                                    .suggests(DIM_SUGGESTIONS)
                                    .executes(ctx -> executeItemLocate(ctx, false, StringArgumentType.getString(ctx, "dim")))
                                )
                            )
                            .then(Commands.literal("--radius")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, RadiusFilter.MAX_RADIUS_CHUNKS))
                                    .executes(ctx -> executeItemLocateRadius(ctx, false))
                                    .then(Commands.literal("--lazy-only")
                                        .executes(ctx -> executeItemLocateRadius(ctx, true))
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
                source.sendFailure(Component.literal("Unknown category: " + categoryName
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
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
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                source.sendFailure(Component.literal("Unknown category: " + categoryName
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<Entity> entities = EntityQuery.findLazyByCategory(world, category, persistent);
                ResultFormatter.sendLazyEntityList(source, entities, categoryName, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy mob list command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                source.sendFailure(Component.literal("Unknown category: " + categoryName
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<Entity> entities = EntityQuery.findPersistentByCategory(world, category);
                ResultFormatter.sendPersistentEntityList(source, entities, categoryName, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in persistent mob list command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<Entity> entities = EntityQuery.findPersistentEntities(world);
                ResultFormatter.sendPersistentEntityList(source, entities, "entities", dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in persistent entity list command", e);
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
            if (category == null || category == MobCategory.MISC) {
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<Entity> entities = EntityQuery.findLazyEntities(world, persistent);
                ResultFormatter.sendLazyEntityList(source, entities, "entity", dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy entity list command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
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
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
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
                    source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
                    return 0;
                }
                worlds = List.of(world);
            }

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<Entity> entities = EntityQuery.findLazyItemEntities(world);
                ResultFormatter.sendLazyEntityList(source, entities, "item", dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in lazy item list command", e);
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

            for (ServerLevel world : worlds) {
                String dimName = ResultFormatter.dimensionName(world.dimension());
                List<ItemTypeCount> counts = EntityQuery.countItemsByType(world);
                ResultFormatter.sendItemSummary(source, counts, dimName);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_summary command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeItemLocate(CommandContext<CommandSourceStack> ctx, boolean lazyOnly, @Nullable String dimArg) {
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
                var results = EntityQuery.findItemsByType(world, id, lazyOnly);
                ResultFormatter.sendLocateResults(source, results, label, dimName, lazyOnly, false);
            }
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item_locate command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeProfile(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal("Unknown entity type: " + id));
                return 0;
            }
            EntityProfiler.INSTANCE.start(source, typeOpt.get(), ticks);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile command", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Radius executor methods
    // -------------------------------------------------------------------------

    private static int executeMobSummaryRadius(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal("Unknown category: " + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countEntitiesByCategoryInRadius(world, category, false, centre, blockRadius);
            ResultFormatter.sendMobSummary(source, counts,
                    categoryName + " (" + radiusChunks + "-chunk radius)",
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob summary radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeMobLazyListRadius(CommandContext<CommandSourceStack> ctx, boolean persistent) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal("Unknown category: " + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            List<Entity> entities = EntityQuery.findLazyByCategoryInRadius(world, category, persistent, centre, blockRadius);
            ResultFormatter.sendLazyEntityList(source, entities,
                    categoryName + " (" + radiusChunks + "-chunk radius)",
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob lazy radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeMobPersistentListRadius(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            String categoryName = StringArgumentType.getString(ctx, "category");
            MobCategory category = Arrays.stream(MobCategory.values())
                    .filter(c -> c.getName().equals(categoryName))
                    .findFirst().orElse(null);
            if (category == null || category == MobCategory.MISC) {
                source.sendFailure(Component.literal("Unknown category: " + categoryName
                        + ". Valid: " + String.join(", ", CATEGORIES)));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            List<Entity> entities = EntityQuery.findPersistentByCategoryInRadius(world, category, centre, blockRadius);
            ResultFormatter.sendPersistentEntityList(source, entities,
                    categoryName + " (" + radiusChunks + "-chunk radius)",
                    ResultFormatter.dimensionName(world.dimension()));
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in mob persistent radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeEntityLocateRadius(
            CommandContext<CommandSourceStack> ctx, boolean lazyOnly, boolean debug) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal("Unknown entity type: " + id));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findByTypeInRadius(world, typeOpt.get(), lazyOnly, centre, blockRadius);
            ResultFormatter.sendLocateResults(source, results,
                    id + " (" + radiusChunks + "-chunk radius)",
                    ResultFormatter.dimensionName(world.dimension()), lazyOnly, debug);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity locate radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeEntityCensus(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countAllEntitiesInRadius(world, centre, blockRadius);
            ResultFormatter.sendEntityCensus(source, counts, radiusChunks);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in entity census", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeProfileRadius(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "entityType");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (typeOpt.isEmpty()) {
                source.sendFailure(Component.literal("Unknown entity type: " + id));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            EntityProfiler.INSTANCE.startWithRadius(source, typeOpt.get(), ticks,
                    centre, source.getLevel().dimension(), blockRadius);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeProfileAll(CommandContext<CommandSourceStack> ctx, int ticks) {
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            EntityProfiler.INSTANCE.startAllTypes(source, ticks,
                    centre, source.getLevel().dimension(), blockRadius);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in profile all", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeItemSummaryRadius(CommandContext<CommandSourceStack> ctx) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            var counts = EntityQuery.countItemsByTypeInRadius(world, centre, blockRadius);
            ResultFormatter.sendItemSummary(source, counts, radiusChunks + "-chunk radius");
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item summary radius", e);
            source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
            return 0;
        }
    }

    private static int executeItemLocateRadius(CommandContext<CommandSourceStack> ctx, boolean lazyOnly) {
        if (onCooldown(ctx)) return 0;
        CommandSourceStack source = ctx.getSource();
        if (RadiusFilter.sourceIsNotPlayer(source)) return 0;
        try {
            Identifier id = IdentifierArgument.getId(ctx, "itemType");
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                source.sendFailure(Component.literal("Unknown item: " + id));
                return 0;
            }
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radius");
            Vec3 centre = source.getPosition();
            double blockRadius = RadiusFilter.toBlockRadius(radiusChunks);
            ServerLevel world = source.getLevel();
            var results = EntityQuery.findItemsByTypeInRadius(world, id, lazyOnly, centre, blockRadius);
            ResultFormatter.sendLocateResults(source, results,
                    id + " (" + radiusChunks + "-chunk radius)",
                    ResultFormatter.dimensionName(world.dimension()), lazyOnly, false);
            return 1;
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: unexpected error in item locate radius", e);
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
