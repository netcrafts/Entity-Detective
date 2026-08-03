package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;

import netcrafts.detective.EntityDetective;
import netcrafts.detective.output.ResultFormatter;
import netcrafts.detective.query.RangeFilter;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
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
@SuppressWarnings({"null", "java:S2589", "java:S6548", "java:S1172"})
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

    /** Per-block-entity-type: [0] = total nanos, [1] = total BE-ticks. Used in allTypesMode only. */
    private final Map<BlockEntityType<?>, long[]> perBEType = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Single-BE-type mode fields
    // -------------------------------------------------------------------------

    private @Nullable BlockEntityType<?> targetBEType;
    /** Per-dimension: [0] = total nanos, [1] = total BE-ticks. Used in single-BE-type mode only. */
    private final Map<ResourceKey<Level>, long[]> perBEDim = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Locate-mode fields (--ticking-only)
    // -------------------------------------------------------------------------

    /**
     * When true, the session observes which block entity positions actually tick
     * rather than recording timing data. Used by {@code entity locate <be_type> --ticking-only}.
     * Results are emitted as a {@link EntityQuery.BELocateResult} list via
     * {@link ResultFormatter#sendBELocateResults} when the window closes.
     */
    private boolean locateMode = false;
    private boolean locateDetail = false;

    // -------------------------------------------------------------------------
    // Hotspot mode fields
    // -------------------------------------------------------------------------

    /**
     * When true, the session records timing bucketed by (dim, chunk, type) across all
     * loaded dimensions. For {@code ItemEntity} instances the type key is the item's
     * registry ID; for all other entities it is the entity type registry ID.
     * Used by {@code /ed hotspot}.
     */
    private boolean hotspotMode = false;

    /** dim → chunk → typeId → [totalNanos, entityTickCount] */
    private final Map<ResourceKey<Level>, Map<ChunkPos, Map<Identifier, long[]>>> hotspotData = new LinkedHashMap<>();

    /**
     * A single hotspot result row: the dominant (highest-MSPT) entity type in one chunk.
     *
     * @param chunk   the chunk position
     * @param dim     the dimension the chunk is in
     * @param typeId  item registry key for item-entity piles; entity type key otherwise
     * @param mspt    average milliseconds spent ticking this type in this chunk per tick
     * @param count   average number of entities of this type in this chunk per tick
     */
    public record HotspotResult(
            ChunkPos chunk,
            ResourceKey<Level> dim,
            Identifier typeId,
            double mspt,
            long count) {}

    // -------------------------------------------------------------------------
    // Debug-mode fields (entity profile <be_type> --debug)
    // -------------------------------------------------------------------------

    /** When true, records tick counts per BlockPos instead of aggregate timing. */
    private boolean debugMode = false;
    /**
     * Per-position tick count. Used in both debug mode and locate mode:
     * - debug: reports all observed positions with their counts
     * - locate: filtered to positions that ticked every tick in the window (count >= ticksRequested)
     */
    private final Map<BlockPos, int[]> debugTickCounts = new LinkedHashMap<>();

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
        if (hotspotMode) return true; // all entities, all dimensions
        if (allTypesMode) {
            if (targetDim == null) return true; // global all-types: no restriction
            if (!world.dimension().equals(targetDim)) return false;
            if (chunkRange < 0) return true;   // whole-dimension mode: dim check is enough
            return isInRange(entity, world);
        } else {
            // Single-type mode: type must match; if range is set, chunk position must also match.
            if (entity.getType() != targetType) return false;
            if (targetDim == null) return true; // no restriction
            if (chunkRange < 0) return world.dimension().equals(targetDim); // whole-dimension mode
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

    /** Returns true if the active session is in hotspot mode. */
    public boolean isHotspotMode() {
        return active && hotspotMode;
    }

    /**
     * Returns true if this block entity should be timed this tick.
     * Called from {@link netcrafts.detective.mixin.BlockEntityTickMixin} — hot path.
     */
    public boolean shouldTimeBE(BlockEntity be) {
        if (!active) return false;
        Level world = be.getLevel();
        if (world == null) return false;
        if (allTypesMode) {
            if (targetDim != null && !world.dimension().equals(targetDim)) return false;
            if (chunkRange < 0 || targetDim == null) return true; // whole-dim or global mode
            BlockPos pos = be.getBlockPos();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            return Math.abs(cx - playerChunkX) <= chunkRange
                    && Math.abs(cz - playerChunkZ) <= chunkRange;
        } else if (targetBEType != null) {
            // Single-BE-type mode
            if (be.getType() != targetBEType) return false;
            if (targetDim == null) return true; // no restriction
            if (chunkRange < 0) return world.dimension().equals(targetDim); // whole-dimension mode
            return isInBERange(be, world);
        }
        return false;
    }

    /** Returns true if the block entity's chunk is within the active chunk-square range and dimension. */
    public boolean isInBERange(BlockEntity be, Level world) {
        if (!world.dimension().equals(targetDim)) return false;
        BlockPos pos = be.getBlockPos();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        return Math.abs(cx - playerChunkX) <= chunkRange
                && Math.abs(cz - playerChunkZ) <= chunkRange;
    }

    // -------------------------------------------------------------------------
    // Session start methods
    // -------------------------------------------------------------------------

    /**
     * Start a hotspot scan: times all entities in all loaded dimensions for 20 ticks,
     * then reports the top-5 (chunk, entity-type) pairs by MSPT.
     * Returns false if a session is already running.
     */
    public boolean startHotspot(CommandSourceStack source) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        hotspotMode = true;
        ticksRequested = 20;
        ticksRemaining = 20;
        requester = source;
        active = true;
        source.sendSuccess(() -> Component.literal(
                "Scanning for entity hotspots (20 ticks)..."), false);
        return true;
    }

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
                "Profiling " + label + (chunkRange == 0 ? " in the player's chunk for " : " within " + chunkRange + "-chunk range for ") + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a single-BE-type profiling session (no range restriction).
     * Returns false if a session is already running.
     */
    public boolean startBE(CommandSourceStack source, BlockEntityType<?> type, int ticks) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetBEType = type;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        source.sendSuccess(() -> Component.literal(
                "Profiling [be] " + label + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a debug session: records how many times each individual block entity position
     * ticked during the window. Used by {@code entity profile <be_type> --debug}.
     */
    public boolean startBEDebug(CommandSourceStack source, BlockEntityType<?> type, int ticks) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetBEType = type;
        debugMode = true;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        source.sendSuccess(() -> Component.literal(
                "Debug profiling [be] " + label + " for " + ticks + " ticks (reporting per-position tick counts)..."), false);
        return true;
    }

    /**
     * Start a single-BE-type profiling session restricted to a chunk-square range.
     * Returns false if a session is already running.
     */
    public boolean startBEWithRange(
            CommandSourceStack source,
            BlockEntityType<?> type,
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
        targetBEType = type;
        playerChunkX = (int) Math.floor(centre.x) >> 4;
        playerChunkZ = (int) Math.floor(centre.z) >> 4;
        this.chunkRange = chunkRange;
        targetDim = dim;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        source.sendSuccess(() -> Component.literal(
                "Profiling [be] " + label + (chunkRange == 0 ? " in the player's chunk for " : " within " + chunkRange + "-chunk range for ") + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a locate-mode session: observe which instances of a block entity type actually
     * tick during a short window, then report their positions.
     * No range restriction — all loaded dimensions.
     */
    public boolean startBELocate(CommandSourceStack source, BlockEntityType<?> type, int ticks, boolean detail) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetBEType = type;
        locateMode = true;
        locateDetail = detail;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        source.sendSuccess(() -> Component.literal(
                "Observing ticking [be] " + label + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a locate-mode session restricted to a chunk-square range.
     */
    public boolean startBELocateWithRange(
            CommandSourceStack source,
            BlockEntityType<?> type,
            int ticks,
            Vec3 centre,
            ResourceKey<Level> dim,
            int chunkRange,
            boolean detail) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetBEType = type;
        locateMode = true;
        locateDetail = detail;
        playerChunkX = (int) Math.floor(centre.x) >> 4;
        playerChunkZ = (int) Math.floor(centre.z) >> 4;
        this.chunkRange = chunkRange;
        targetDim = dim;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        source.sendSuccess(() -> Component.literal(
                "Observing ticking [be] " + label
                + (chunkRange == 0 ? " in the player's chunk for " : " within " + chunkRange + "-chunk range for ")
                + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a single-type profiling session restricted to one dimension (no chunk range).
     * Returns false if a session is already running.
     */
    public boolean startWithDimension(
            CommandSourceStack source,
            EntityType<?> type,
            int ticks,
            ResourceKey<Level> dim) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetType = type;
        targetDim = dim;
        chunkRange = -1; // sentinel: whole dimension, no chunk range
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.getDescriptionId();
        String dimName = ResultFormatter.dimensionName(dim);
        source.sendSuccess(() -> Component.literal(
                "Profiling " + label + " in " + dimName + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start a single-BE-type profiling session restricted to one dimension (no chunk range).
     * Returns false if a session is already running.
     */
    public boolean startBEWithDimension(
            CommandSourceStack source,
            BlockEntityType<?> type,
            int ticks,
            ResourceKey<Level> dim) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        targetBEType = type;
        targetDim = dim;
        chunkRange = -1; // sentinel: whole dimension, no chunk range
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;

        @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        String label = id != null ? id.toString() : type.toString();
        String dimName = ResultFormatter.dimensionName(dim);
        source.sendSuccess(() -> Component.literal(
                "Profiling [be] " + label + " in " + dimName + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start an all-types profiling session restricted to one dimension (no chunk range).
     * Returns false if a session is already running.
     */
    public boolean startAllTypesDimension(
            CommandSourceStack source,
            int ticks,
            ResourceKey<Level> dim) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        allTypesMode = true;
        targetDim = dim;
        chunkRange = -1; // sentinel: whole dimension, no chunk range
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;
        String dimName = ResultFormatter.dimensionName(dim);
        source.sendSuccess(() -> Component.literal(
                "Profiling all entity types in " + dimName + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Start an all-types profiling session across ALL loaded entities in ALL dimensions.
     * Returns false if a session is already running.
     */
    public boolean startAllTypesGlobal(CommandSourceStack source, int ticks) {
        if (active) {
            source.sendFailure(Component.literal(
                    "A profile is already running. Wait for it to complete."));
            return false;
        }
        resetState();
        allTypesMode = true;
        ticksRequested = ticks;
        ticksRemaining = ticks;
        requester = source;
        active = true;
        source.sendSuccess(() -> Component.literal(
                "Profiling all entity types (all loaded entities, all dimensions) for " + ticks + " ticks..."), false);
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

        int side = 2 * chunkRange + 1;
        int totalChunks = side * side;
        String areaDesc = chunkRange == 0 ? "1-chunk area" : totalChunks + "-chunk (" + side + "x" + side + ") area";
        source.sendSuccess(() -> Component.literal(
                "Profiling all entity types in a " + areaDesc + " centered on player for " + ticks + " ticks..."), false);
        return true;
    }

    /** Variant of startAllTypes that names the target player in the start message. */
    public boolean startAllTypesForPlayer(
            CommandSourceStack source,
            int ticks,
            String targetName,
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

        int side = 2 * chunkRange + 1;
        int totalChunks = side * side;
        String areaDesc = chunkRange == 0 ? "1-chunk area" : totalChunks + "-chunk (" + side + "x" + side + ") area";
        source.sendSuccess(() -> Component.literal(
                "Profiling all entity types in a " + areaDesc + " around " + targetName + " for " + ticks + " ticks..."), false);
        return true;
    }

    /**
     * Record a hotspot entity tick measurement, bucketed by (dim, chunk, typeId).
     * For {@link ItemEntity} the key is the item registry ID; otherwise the entity type ID.
     * Called from EntityTickMixin when hotspotMode is true — hot path.
     */
    public void recordHotspot(Entity entity, Level world, long nanos) {
        if (!active) return;
        @Nullable Identifier typeId;
        if (entity instanceof ItemEntity ie) {
            typeId = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
        } else {
            typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        }
        if (typeId == null) return;
        ChunkPos chunk = ChunkPos.containing(entity.blockPosition());
        long[] data = hotspotData
                .computeIfAbsent(world.dimension(), d -> new LinkedHashMap<>())
                .computeIfAbsent(chunk, c -> new LinkedHashMap<>())
                .computeIfAbsent(typeId, t -> new long[]{0L, 0L});
        data[0] += nanos;
        data[1]++;
    }

    /**
     * Record a single-type entity tick measurement, bucketed by dimension.
     * Called from EntityTickMixin when allTypesMode is false.
     */
    public void recordTick(Level world, long nanos) {
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

    /**
     * Record a block entity tick measurement.
     * Routes to {@link #perBEType} in allTypesMode, or {@link #perBEDim} in single-BE-type mode.
     * In locate mode, records the block entity's position instead of timing data.
     * Called from BlockEntityTickMixin.
     */
    public void recordByBlockEntityType(BlockEntityType<?> type, long nanos, Level world, BlockPos pos) {
        if (!active) return;
        if (debugMode && targetBEType != null) {
            debugTickCounts.computeIfAbsent(pos, k -> new int[1])[0]++;
        } else if (locateMode && targetBEType != null) {
            debugTickCounts.computeIfAbsent(pos, k -> new int[1])[0]++;
        } else if (allTypesMode) {
            long[] data = perBEType.computeIfAbsent(type, k -> new long[2]);
            data[0] += nanos;
            data[1]++;
        } else if (targetBEType != null) {
            long[] data = perBEDim.computeIfAbsent(world.dimension(), k -> new long[2]);
            data[0] += nanos;
            data[1]++;
        }
    }

    // -------------------------------------------------------------------------
    // Tick counter
    // -------------------------------------------------------------------------

    /** Called from ServerTickEvents.END_SERVER_TICK each tick to advance the counter. */
    public void onServerTick(MinecraftServer server) {
        if (!active) return;
        ticksRemaining--;
        if (ticksRemaining <= 0) {
            completeSession();
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void resetState() {
        allTypesMode = false;
        hotspotMode = false;
        locateMode = false;
        locateDetail = false;
        debugMode = false;
        targetType = null;
        targetBEType = null;
        playerChunkX = 0;
        playerChunkZ = 0;
        chunkRange = 0;
        targetDim = null;
        perDim.clear();
        perType.clear();
        perBEType.clear();
        perBEDim.clear();
        debugTickCounts.clear();
        hotspotData.clear();
    }

    private List<HotspotResult> computeHotspotResults() {
        java.util.ArrayList<HotspotResult> all = new java.util.ArrayList<>();
        for (var dimEntry : hotspotData.entrySet()) {
            ResourceKey<Level> dim = dimEntry.getKey();
            for (var chunkEntry : dimEntry.getValue().entrySet()) {
                ChunkPos chunk = chunkEntry.getKey();
                // Find the single dominant type (highest MSPT) in this chunk
                @Nullable Identifier dominantType = null;
                double dominantMspt = 0.0;
                long dominantCount = 0;
                for (var typeEntry : chunkEntry.getValue().entrySet()) {
                    long[] data = typeEntry.getValue();
                    double mspt = data[0] / (ticksRequested * 1_000_000.0);
                    if (mspt > dominantMspt) {
                        dominantMspt = mspt;
                        dominantType = typeEntry.getKey();
                        dominantCount = Math.max(1L, data[1] / ticksRequested);
                    }
                }
                if (dominantType != null && dominantMspt >= 2.0) {
                    all.add(new HotspotResult(chunk, dim, dominantType, dominantMspt, dominantCount));
                }
            }
        }
        all.sort((a, b) -> Double.compare(b.mspt(), a.mspt()));
        return all.size() > 5 ? all.subList(0, 5) : all;
    }

    private void completeSession() {
        active = false;
        CommandSourceStack src = requester;
        requester = null;
        if (src == null) return;
        try {
            if (debugMode && targetBEType != null) {
                @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(targetBEType);
                String label = id != null ? id.toString() : targetBEType.toString();
                // Snapshot: Map<BlockPos, tickCount> sorted descending
                java.util.LinkedHashMap<BlockPos, Integer> sorted = new java.util.LinkedHashMap<>();
                debugTickCounts.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                        .forEach(e -> sorted.put(e.getKey(), e.getValue()[0]));
                ResultFormatter.sendBEDebugResults(src, sorted, label, ticksRequested);
            } else if (locateMode && targetBEType != null) {
                @Nullable Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(targetBEType);
                String label = id != null ? id.toString() : targetBEType.toString();
                String scopeLabel = chunkRange >= 0
                        ? label + " (" + RangeFilter.rangeLabel(chunkRange) + ")"
                        : label;
                // Only include positions that ticked every single tick in the window.
                java.util.LinkedHashMap<ChunkPos, java.util.List<BlockPos>> byChunk = new java.util.LinkedHashMap<>();
                debugTickCounts.entrySet().stream()
                        .filter(e -> e.getValue()[0] >= ticksRequested)
                        .forEach(e -> byChunk
                                .computeIfAbsent(ChunkPos.containing(e.getKey()), k -> new java.util.ArrayList<>())
                                .add(e.getKey()));
                List<EntityQuery.BELocateResult> results = byChunk.entrySet().stream()
                        .map(e -> new EntityQuery.BELocateResult(e.getKey(), e.getValue()))
                        .sorted(java.util.Comparator.comparingInt(r -> -r.positions().size()))
                        .toList();
                String dimName = targetDim != null
                        ? ResultFormatter.dimensionName(targetDim)
                        : "all dimensions";
                ResultFormatter.sendBELocateResults(src, results, scopeLabel + " (ticking only)", dimName, locateDetail);
            } else if (hotspotMode) {
                List<HotspotResult> results = computeHotspotResults();
                ResultFormatter.sendHotspotResults(src, results, ticksRequested);
            } else if (allTypesMode) {
                String scopeLabel;
                if (targetDim == null) {
                    scopeLabel = "all loaded entities, all dimensions";
                } else if (chunkRange < 0) {
                    scopeLabel = "all entities in " + ResultFormatter.dimensionName(targetDim);
                } else {
                    int side = 2 * chunkRange + 1;
                    scopeLabel = (chunkRange == 0 ? "1-chunk area" : chunkRange + "-chunk range (" + side + "x" + side + ")") + " in "
                            + ResultFormatter.dimensionName(targetDim);
                }
                ResultFormatter.sendBaseProfileResults(src, ticksRequested, scopeLabel, perType, perBEType);
            } else if (targetBEType != null) {
                ResultFormatter.sendBEProfileResults(src, targetBEType, ticksRequested, chunkRange, perBEDim);
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
