package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import netcrafts.detective.EntityDetective;
import netcrafts.detective.output.ResultFormatter;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton that manages two profiling modes:
 *
 * <ol>
 *   <li><b>Single-type</b> ({@link #start}) — times one EntityType across all dimensions
 *       (or within a chunk range if {@link #startWithRange} is used).</li>
 *   <li><b>All-types</b> ({@link #startAllTypes}) — times every entity type within a
 *       player-defined chunk range, bucketed by type. Always range-scoped.</li>
 * </ol>
 *
 * Both modes hook into {@link netcrafts.detective.mixin.EntityTickMixin} via
 * {@link #shouldTime} and fire results through {@link ResultFormatter} when the
 * tick window expires.
 *
 * Profiling approach adapted from fabric-carpet's CarpetProfiler.
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
@SuppressWarnings("null")
public class EntityProfiler {

    public static final EntityProfiler INSTANCE = new EntityProfiler();

    // -------------------------------------------------------------------------
    // Shared state
    // -------------------------------------------------------------------------

    private boolean active = false;
    private int ticksRequested;
    private int ticksRemaining;
    private @Nullable CommandSourceStack requester;

    // -------------------------------------------------------------------------
    // Single-type mode fields
    // -------------------------------------------------------------------------

    private @Nullable EntityType<?> targetType;
    /** Per-dimension: [0] = total nanos, [1] = total entity-ticks */
    private final Map<ResourceKey<Level>, long[]> perDim = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Range fields (used by both single-type-with-range and all-types modes)
    // -------------------------------------------------------------------------

    private boolean allTypesMode = false;
    private int playerChunkX = 0;        // chunk coordinates of the profiling origin
    private int playerChunkZ = 0;
    private int chunkRange = 0;          // stored for display in results header
    private @Nullable ResourceKey<Level> targetDim;

    // -------------------------------------------------------------------------
    // All-types mode fields
    // -------------------------------------------------------------------------

    /** Per-type: [0] = total nanos, [1] = total entity-ticks. Used in allTypesMode only. */
    private final Map<EntityType<?>, long[]> perType = new LinkedHashMap<>();

    private EntityProfiler() {}

    // -------------------------------------------------------------------------
    // Query methods (called from EntityTickMixin — hot path, must stay cheap)
    // -------------------------------------------------------------------------

    /**
     * Returns true if this entity should be timed this tick.
     * This is the single gate called from {@link netcrafts.detective.mixin.EntityTickMixin}.
     */
    public boolean shouldTime(Entity entity, Level world) {
        if (!active) return false;
        if (allTypesMode) {
            return isInRange(entity, world);
        } else {
            // Single-type mode: type must match; if range is set, chunk position must also match.
            if (entity.getType() != targetType) return false;
            if (targetDim == null) return true; // no range restriction
            return isInRange(entity, world);
        }
    }

    /** Returns true if the entity's chunk is within the active chunk-square range and dimension. */
    public boolean isInRange(Entity entity, Level world) {
        if (!world.dimension().equals(targetDim)) return false;
        ChunkPos ec = ChunkPos.containing(entity.blockPosition());
        return Math.abs(ec.x() - playerChunkX) <= chunkRange
                && Math.abs(ec.z() - playerChunkZ) <= chunkRange;
    }

    /** Returns true if the active session is in all-types mode. */
    public boolean isAllTypesMode() {
        return active && allTypesMode;
    }

    // -------------------------------------------------------------------------
    // Session start methods
    // -------------------------------------------------------------------------

    /**
     * Start a standard single-type profiling session (no range restriction).
     * Returns false if a session is already running.
     */
    public boolean start(CommandSourceStack source, EntityType<?> type, int ticks) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetType = type;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();
        source.sendSuccess(() -> Component.literal(
                "Profiling " + label + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a single-type profiling session restricted to a chunk-square range.
     * Returns false if a session is already running.
     */
    public boolean startWithRange(
            CommandSourceStack source,
            EntityType<?> type,
            int ticks,
            Vec3 centre,
            ResourceKey<Level> dim,
            int chunkRange) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetType = type;
        playerChunkX = (int) Math.floor(centre.x) >> 4;
        playerChunkZ = (int) Math.floor(centre.z) >> 4;
        this.chunkRange = chunkRange;
        targetDim = dim;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();
        source.sendSuccess(() -> Component.literal(
                "Profiling " + label + " within " + chunkRange + "-chunk range for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start an all-types profiling session within the given chunk-square range.
     * Returns false if a session is already running.
     */
    public boolean startAllTypes(
            CommandSourceStack source,
            int ticks,
            Vec3 centre,
            ResourceKey<Level> dim,
            int chunkRange) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        allTypesMode = true;
        playerChunkX = (int) Math.floor(centre.x) >> 4;
        playerChunkZ = (int) Math.floor(centre.z) >> 4;
        this.chunkRange = chunkRange;
        targetDim = dim;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        source.sendSuccess(() -> Component.literal(
                "Profiling all entity types within " + chunkRange + "-chunk range for " + ticks + " ticks..."), false);
        return true;
    }

    // -------------------------------------------------------------------------
    // Recording methods (called from EntityTickMixin — hot path)
    // -------------------------------------------------------------------------

    /**
     * Record a single-type entity tick measurement, bucketed by dimension.
     * Called from EntityTickMixin when allTypesMode is false.
     */
    public void record(Level world, long nanos) {
        if (!active) return;
        long[] data = perDim.computeIfAbsent(world.dimension(), k -> new long[2]);
        data[0] += nanos;
        data[1]++;
    }

    /**
     * Record an all-types entity tick measurement, bucketed by entity type.
     * Called from EntityTickMixin when allTypesMode is true.
     */
    public void recordByType(EntityType<?> type, long nanos) {
        if (!active) return;
        long[] data = perType.computeIfAbsent(type, k -> new long[2]);
        data[0] += nanos;
        data[1]++;
    }

    // -------------------------------------------------------------------------
    // Tick counter
    // -------------------------------------------------------------------------

    /** Called from ServerTickEvents.END_SERVER_TICK each tick to advance the counter. */
    public void onServerTick(MinecraftServer server) {
        if (!active) return;
        ticksRemaining--;
        if (ticksRemaining <= 0) {
            finalize(server);
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void resetState() {
        allTypesMode = false;
        targetType = null;
        playerChunkX = 0;
        playerChunkZ = 0;
        chunkRange = 0;
        targetDim = null;
        perDim.clear();
        perType.clear();
    }

    private void finalize(MinecraftServer server) {
        active = false;
        CommandSourceStack src = requester;
        requester = null;
        if (src == null) return;
        try {
            if (allTypesMode) {
                ResultFormatter.sendBaseProfileResults(src, ticksRequested, chunkRange, perType);
            } else {
                ResultFormatter.sendProfileResults(src, targetType, ticksRequested, chunkRange, perDim);
            }
        } catch (Exception e) {
            EntityDetective.LOGGER.error("EntityDetective: error sending profile results", e);
            src.sendFailure(Component.literal(
                    "An internal error occurred while finalizing profile. Check server logs."));
        }
    }
}
