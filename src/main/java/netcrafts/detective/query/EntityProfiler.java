package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import netcrafts.detective.EntityDetective;
import netcrafts.detective.output.ResultFormatter;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton that times entity ticks for a specific EntityType over a configurable
 * window of server ticks, then reports avg MSPT and avg count per tick.
 *
 * Hooks in via {@link netcrafts.detective.mixin.EntityTickMixin} (Level.guardEntityTick)
 * for per-entity timing, and via ServerTickEvents.END_SERVER_TICK for the tick counter.
 *
 * Profiling approach adapted from fabric-carpet's CarpetProfiler.
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
public class EntityProfiler {

    public static final EntityProfiler INSTANCE = new EntityProfiler();

    private boolean active = false;
    private EntityType<?> targetType;
    private int ticksRequested;
    private int ticksRemaining;
    private @Nullable CommandSourceStack requester;

    // Per-dimension: [0] = total nanos, [1] = total entity-ticks
    private final Map<ResourceKey<Level>, long[]> perDim = new LinkedHashMap<>();

    private EntityProfiler() {}

    /** Returns true if a profiling session is currently active. */
    public boolean isActive() {
        return active;
    }

    /** Returns true if the profiler is running and the given type is the target. */
    public boolean isTracking(EntityType<?> type) {
        return active && type == targetType;
    }

    /**
     * Start a profiling session. Sends feedback to the requester.
     * Returns false if a session is already running.
     */
    public boolean start(CommandSourceStack source, EntityType<?> type, int ticks) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        targetType = type;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        perDim.clear();
        active = true;

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();
        source.sendSuccess(() -> Component.literal(
                "Profiling " + label + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Record an entity tick measurement. Called from EntityTickMixin at TAIL of
     * Level.guardEntityTick when the entity type matches the target.
     *
     * @param world  the Level the entity belongs to
     * @param nanos  elapsed nanoseconds for this entity's tick
     */
    public void record(Level world, long nanos) {
        if (!active) return;
        long[] data = perDim.computeIfAbsent(world.dimension(), k -> new long[2]);
        data[0] += nanos;
        data[1]++;
    }

    /** Called from ServerTickEvents.END_SERVER_TICK each tick to advance the counter. */
    public void onServerTick(MinecraftServer server) {
        if (!active) return;
        ticksRemaining--;
        if (ticksRemaining <= 0) {
            finalize(server);
        }
    }

    private void finalize(MinecraftServer server) {
        active = false;
        CommandSourceStack src = requester;
        requester = null;
        if (src == null) return;
        try {
            ResultFormatter.sendProfileResults(src, targetType, ticksRequested, perDim);
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: error sending profile results", e);
            src.sendFailure(Component.literal(
                    "An internal error occurred while finalizing profile. Check server logs."));
        }
    }
}
