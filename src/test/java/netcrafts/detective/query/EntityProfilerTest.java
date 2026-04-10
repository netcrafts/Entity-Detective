package netcrafts.detective.query;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityProfiler}.
 *
 * <p>Because {@code EntityProfiler} is a stateful singleton, each test resets all
 * internal fields via reflection in {@link #resetProfiler()} so tests are fully
 * independent of execution order.
 *
 * <p>Tests that exercise code paths which call {@code BuiltInRegistries} (e.g. the
 * normal {@code start()} path when {@code active=false}) are intentionally excluded
 * because those paths depend on the Minecraft bootstrap not available in unit tests.
 * The "already-active" guard branch of {@code start()} <em>is</em> covered because
 * it returns before any registry access.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityProfilerTest {

    /** Convenience handle for the singleton under test. */
    private static final EntityProfiler PROFILER = EntityProfiler.INSTANCE;

    @Mock
    private EntityType<?> mockEntityType;

    @Mock
    private EntityType<?> otherEntityType;

    @Mock
    private Entity mockEntity;

    @Mock
    private Level mockLevel;

    @Mock
    @SuppressWarnings("unchecked")
    private ResourceKey<Level> mockDimKey;

    @Mock
    @SuppressWarnings("unchecked")
    private ResourceKey<Level> otherDimKey;

    @Mock
    private net.minecraft.commands.CommandSourceStack mockSource;

    @Mock
    private net.minecraft.server.MinecraftServer mockServer;

    // ------------------------------------------------------------------
    // Test lifecycle
    // ------------------------------------------------------------------

    @BeforeEach
    void resetProfiler() throws Exception {
        // Call the private resetState() method first, then manually reset
        // the remaining fields that resetState() does not touch.
        Method resetState = EntityProfiler.class.getDeclaredMethod("resetState");
        resetState.setAccessible(true);
        resetState.invoke(PROFILER);

        setField("active", false);
        setField("ticksRequested", 0);
        setField("ticksRemaining", 0);
        setField("requester", null);
    }

    // ------------------------------------------------------------------
    // Initial / inactive-state tests (no Minecraft bootstrap needed)
    // ------------------------------------------------------------------

    @Test
    void isAllTypesMode_returnsFalseWhenNotActive() {
        assertFalse(PROFILER.isAllTypesMode());
    }

    @Test
    void shouldTime_returnsFalseWhenNotActive() {
        assertFalse(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    @Test
    void recordTick_isNoOpWhenNotActive() throws Exception {
        PROFILER.recordTick(mockLevel, 12_345L);

        @SuppressWarnings("unchecked")
        Map<?, long[]> perDim = (Map<?, long[]>) getField("perDim");
        assertTrue(perDim.isEmpty(),
                "recordTick must not store data when the profiler is inactive");
    }

    @Test
    void recordByType_isNoOpWhenNotActive() throws Exception {
        PROFILER.recordByType(mockEntityType, 12_345L);

        @SuppressWarnings("unchecked")
        Map<?, long[]> perType = (Map<?, long[]>) getField("perType");
        assertTrue(perType.isEmpty(),
                "recordByType must not store data when the profiler is inactive");
    }

    @Test
    void onServerTick_isNoOpWhenNotActive() throws Exception {
        setField("ticksRemaining", 5);
        PROFILER.onServerTick(mockServer);

        // ticksRemaining should be unchanged because active=false short-circuits the method
        assertEquals(5, getField("ticksRemaining"),
                "onServerTick must not decrement when inactive");
    }

    // ------------------------------------------------------------------
    // start() — already-active guard (safe: returns before registry access)
    // ------------------------------------------------------------------

    @Test
    void start_returnsFalseWhenAlreadyActive() throws Exception {
        setField("active", true);

        boolean result = PROFILER.start(mockSource, mockEntityType, 100);

        assertFalse(result);
    }

    @Test
    void start_sendsFailureMessageWhenAlreadyActive() throws Exception {
        setField("active", true);

        PROFILER.start(mockSource, mockEntityType, 100);

        // sendFailure must have been called with any Component argument
        verify(mockSource).sendFailure(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startWithRange_returnsFalseWhenAlreadyActive() throws Exception {
        setField("active", true);

        boolean result = PROFILER.startWithRange(
                mockSource, mockEntityType, 100,
                net.minecraft.world.phys.Vec3.ZERO, mockDimKey, 5);

        assertFalse(result);
    }

    @Test
    void startAllTypes_returnsFalseWhenAlreadyActive() throws Exception {
        setField("active", true);

        boolean result = PROFILER.startAllTypes(
                mockSource, 100,
                net.minecraft.world.phys.Vec3.ZERO, mockDimKey, 5);

        assertFalse(result);
    }

    // ------------------------------------------------------------------
    // shouldTime() — single-type mode (no dim restriction)
    // ------------------------------------------------------------------

    @Test
    void shouldTime_returnsTrueWhenActiveTypeMatchesAndNoDimRestriction() throws Exception {
        activateSingleType(mockEntityType, /*targetDim=*/ null);

        when(mockEntity.getType()).thenAnswer(inv -> mockEntityType);

        assertTrue(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    @Test
    void shouldTime_returnsFalseWhenActiveTypeMismatch() throws Exception {
        activateSingleType(mockEntityType, /*targetDim=*/ null);

        when(mockEntity.getType()).thenAnswer(inv -> otherEntityType);

        assertFalse(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    // ------------------------------------------------------------------
    // shouldTime() — all-types mode
    // ------------------------------------------------------------------

    @Test
    void shouldTime_inAllTypesMode_returnsTrueWhenEntityInRange() throws Exception {
        activateAllTypes(mockDimKey, 0, 0, 5);

        when(mockLevel.dimension()).thenReturn(mockDimKey);
        // Entity at world position (0, 64, 0) → chunk (0, 0) — within range 5
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));

        assertTrue(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    @Test
    void shouldTime_inAllTypesMode_returnsFalseWhenWrongDimension() throws Exception {
        activateAllTypes(mockDimKey, 0, 0, 5);

        when(mockLevel.dimension()).thenReturn(otherDimKey);
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));

        assertFalse(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    @Test
    void shouldTime_inAllTypesMode_returnsFalseWhenEntityOutsideRange() throws Exception {
        // chunkRange = 1 → only chunks [-1..1] in each axis
        activateAllTypes(mockDimKey, 0, 0, 1);

        when(mockLevel.dimension()).thenReturn(mockDimKey);
        // Entity at x=48 → chunk 3, which is outside range 1
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(48, 64, 0));

        assertFalse(PROFILER.shouldTime(mockEntity, mockLevel));
    }

    // ------------------------------------------------------------------
    // isInRange()
    // ------------------------------------------------------------------

    @Test
    void isInRange_returnsTrueForEntityAtCentreChunk() throws Exception {
        activateAllTypes(mockDimKey, 0, 0, 3);
        when(mockLevel.dimension()).thenReturn(mockDimKey);
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));

        assertTrue(PROFILER.isInRange(mockEntity, mockLevel));
    }

    @Test
    void isInRange_returnsTrueForEntityOnBoundaryChunk() throws Exception {
        // chunkRange = 2, entity at chunk (2, 2) — exactly on the boundary
        activateAllTypes(mockDimKey, 0, 0, 2);
        when(mockLevel.dimension()).thenReturn(mockDimKey);
        // x=32 → chunk 2, z=32 → chunk 2
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(32, 64, 32));

        assertTrue(PROFILER.isInRange(mockEntity, mockLevel));
    }

    @Test
    void isInRange_returnsFalseForEntityBeyondBoundaryChunk() throws Exception {
        // chunkRange = 2, entity at chunk (3, 0) — one chunk outside boundary
        activateAllTypes(mockDimKey, 0, 0, 2);
        when(mockLevel.dimension()).thenReturn(mockDimKey);
        // x=48 → chunk 3
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(48, 64, 0));

        assertFalse(PROFILER.isInRange(mockEntity, mockLevel));
    }

    @Test
    void isInRange_returnsFalseWhenDimensionDoesNotMatch() throws Exception {
        activateAllTypes(mockDimKey, 0, 0, 5);
        when(mockLevel.dimension()).thenReturn(otherDimKey);
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));

        assertFalse(PROFILER.isInRange(mockEntity, mockLevel));
    }

    @Test
    void isInRange_returnsTrueForNegativeChunkCoordinates() throws Exception {
        // Player chunk at (-3, -3), range 2 → valid for entity at chunk (-4, -3)
        activateAllTypes(mockDimKey, -3, -3, 2);
        when(mockLevel.dimension()).thenReturn(mockDimKey);
        // x = -4*16 = -64 → chunk -4, z = -3*16 = -48 → chunk -3
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(-64, 64, -48));

        assertTrue(PROFILER.isInRange(mockEntity, mockLevel));
    }

    // ------------------------------------------------------------------
    // recordTick() / recordByType() — accumulation while active
    // ------------------------------------------------------------------

    @Test
    void recordTick_accumulatesNanosPerDimension() throws Exception {
        setField("active", true);

        PROFILER.recordTick(mockLevel, 1_000L);
        PROFILER.recordTick(mockLevel, 2_000L);

        @SuppressWarnings("unchecked")
        Map<Level, long[]> perDim = (Map<Level, long[]>) getField("perDim");
        // recordTick keys by world.dimension() internally; use the mock Level as a key
        // The map key is the ResourceKey returned by world.dimension(); here the mock
        // Level is passed directly — the actual key comes from Level.dimension().
        // We verify total nanos across all entries.
        long totalNanos = perDim.values().stream().mapToLong(d -> d[0]).sum();
        assertEquals(3_000L, totalNanos);
    }

    @Test
    void recordTick_incrementsEntityTickCount() throws Exception {
        setField("active", true);

        PROFILER.recordTick(mockLevel, 500L);
        PROFILER.recordTick(mockLevel, 500L);
        PROFILER.recordTick(mockLevel, 500L);

        @SuppressWarnings("unchecked")
        Map<Level, long[]> perDim = (Map<Level, long[]>) getField("perDim");
        long totalTicks = perDim.values().stream().mapToLong(d -> d[1]).sum();
        assertEquals(3L, totalTicks);
    }

    @Test
    void recordByType_accumulatesNanosPerType() throws Exception {
        setField("active", true);

        PROFILER.recordByType(mockEntityType, 1_000L);
        PROFILER.recordByType(mockEntityType, 2_000L);
        PROFILER.recordByType(otherEntityType, 500L);

        @SuppressWarnings("unchecked")
        Map<EntityType<?>, long[]> perType = (Map<EntityType<?>, long[]>) getField("perType");
        assertEquals(3_000L, perType.get(mockEntityType)[0]);
        assertEquals(500L,   perType.get(otherEntityType)[0]);
    }

    @Test
    void recordByType_incrementsEntityTickCountPerType() throws Exception {
        setField("active", true);

        PROFILER.recordByType(mockEntityType, 100L);
        PROFILER.recordByType(mockEntityType, 100L);

        @SuppressWarnings("unchecked")
        Map<EntityType<?>, long[]> perType = (Map<EntityType<?>, long[]>) getField("perType");
        assertEquals(2L, perType.get(mockEntityType)[1]);
    }

    // ------------------------------------------------------------------
    // onServerTick() — countdown while active
    // ------------------------------------------------------------------

    @Test
    void onServerTick_decrementsTicksRemainingByOne() throws Exception {
        setField("active", true);
        setField("ticksRemaining", 10);

        PROFILER.onServerTick(mockServer);

        assertEquals(9, getField("ticksRemaining"));
    }

    @Test
    void onServerTick_doesNotDecrementWhenInactive() throws Exception {
        setField("active", false);
        setField("ticksRemaining", 10);

        PROFILER.onServerTick(mockServer);

        assertEquals(10, getField("ticksRemaining"));
    }

    @Test
    void isAllTypesMode_returnsTrueWhenActiveAndInAllTypesMode() throws Exception {
        setField("active", true);
        setField("allTypesMode", true);

        assertTrue(PROFILER.isAllTypesMode());
    }

    @Test
    void isAllTypesMode_returnsFalseWhenActiveButNotAllTypes() throws Exception {
        setField("active", true);
        setField("allTypesMode", false);

        assertFalse(PROFILER.isAllTypesMode());
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    private void setField(String name, Object value) throws Exception {
        Field field = EntityProfiler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(PROFILER, value);
    }

    private Object getField(String name) throws Exception {
        Field field = EntityProfiler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(PROFILER);
    }

    /** Puts the profiler into active single-type mode without going through start(). */
    private void activateSingleType(EntityType<?> type, ResourceKey<Level> dim) throws Exception {
        setField("active", true);
        setField("allTypesMode", false);
        setField("targetType", type);
        setField("targetDim", dim);
        setField("ticksRemaining", 100);
        setField("ticksRequested", 100);
    }

    /** Puts the profiler into active all-types mode without going through startAllTypes(). */
    private void activateAllTypes(
            ResourceKey<Level> dim, int pcx, int pcz, int range) throws Exception {
        setField("active", true);
        setField("allTypesMode", true);
        setField("targetDim", dim);
        setField("playerChunkX", pcx);
        setField("playerChunkZ", pcz);
        setField("chunkRange", range);
        setField("ticksRemaining", 100);
        setField("ticksRequested", 100);
    }
}
