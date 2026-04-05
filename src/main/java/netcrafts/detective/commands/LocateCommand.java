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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;

import netcrafts.detective.output.ResultFormatter;
import netcrafts.detective.query.EntityQuery;
import netcrafts.detective.query.MobCapInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class LocateCommand {

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
        );
    }

    private static int executeMobcap(CommandContext<CommandSourceStack> ctx, String dimArg) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = resolveWorld(source, dimArg);
        if (world == null) {
            source.sendFailure(Component.literal("Unknown dimension: " + dimArg));
            return 0;
        }
        String dimName = ResultFormatter.dimensionName(world.dimension());
        ResultFormatter.sendMobcap(source, MobCapInfo.getForDimension(world), dimName);
        return 1;
    }

    private static int executeLocate(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            boolean summary,
            String dimArg,
            boolean persistent,
            boolean debug) {

        CommandSourceStack source = ctx.getSource();
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
    }

    private static int executeEntityLocate(
            CommandContext<CommandSourceStack> ctx,
            boolean lazyOnly,
            String dimArg,
            boolean debug) {

        CommandSourceStack source = ctx.getSource();
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
    }

    private static ServerLevel resolveWorld(CommandSourceStack source, String dimArg) {
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
