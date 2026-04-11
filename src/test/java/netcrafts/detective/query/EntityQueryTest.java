package netcrafts.detective.query;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import netcrafts.detective.query.EntityQuery.EntityTypeCount;
import netcrafts.detective.query.EntityQuery.ItemTypeCount;
import netcrafts.detective.query.EntityQuery.QueryResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for pure-Java helpers and record types inside {@link EntityQuery}.
 *
 * <ul>
 *   <li>{@code toChunkCoord} — private static method; tested via reflection.</li>
 *   <li>{@code inChunkRange}  — private static method; tested via reflection.</li>
 *   <li>{@link QueryResult}, {@link EntityTypeCount}, {@link ItemTypeCount} records.</li>
 * </ul>
 *
 * No Minecraft bootstrap is required: {@link BlockPos}, {@link ChunkPos}, and
 * {@link Identifier} are all simple data classes that can be instantiated without
 * a running game server.
 */
@ExtendWith(MockitoExtension.class)
class EntityQueryTest {

    @Mock
    private Entity mockEntity;

    // ------------------------------------------------------------------
    // Cached reflection handles for private static methods
    // ------------------------------------------------------------------

    private static final Method TO_CHUNK_COORD;
    private static final Method IN_CHUNK_RANGE;

    static {
        try {
            TO_CHUNK_COORD = EntityQuery.class.getDeclaredMethod("toChunkCoord", double.class);
            TO_CHUNK_COORD.setAccessible(true);
            IN_CHUNK_RANGE = EntityQuery.class.getDeclaredMethod(
                    "inChunkRange", Entity.class, int.class, int.class, int.class);
            IN_CHUNK_RANGE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ------------------------------------------------------------------
    // toChunkCoord (private static) — world-space → chunk-space
    // ------------------------------------------------------------------

    @Test
    void toChunkCoord_zeroReturnZero() throws Exception {
        assertEquals(0, toChunkCoord(0.0));
    }

    @Test
    void toChunkCoord_positiveChunkBoundary() throws Exception {
        // x = 16.0 is exactly the start of chunk 1
        assertEquals(1, toChunkCoord(16.0));
    }

    @Test
    void toChunkCoord_justBeforeChunkBoundary() throws Exception {
        // x = 15.9 is still inside chunk 0
        assertEquals(0, toChunkCoord(15.9));
    }

    @Test
    void toChunkCoord_largePositiveCoord() throws Exception {
        // x = 256 → chunk 16
        assertEquals(16, toChunkCoord(256.0));
    }

    @Test
    void toChunkCoord_negativeCoordJustBelowZero() throws Exception {
        // x = -1 → chunk -1 (floor(-1) >> 4 = -1 >> 4 = -1 in Java)
        assertEquals(-1, toChunkCoord(-1.0));
    }

    @Test
    void toChunkCoord_negativeChunkBoundary() throws Exception {
        // x = -16 → chunk -1
        assertEquals(-1, toChunkCoord(-16.0));
    }

    @Test
    void toChunkCoord_negativeJustInsideChunkMinus2() throws Exception {
        // x = -17 → chunk -2
        assertEquals(-2, toChunkCoord(-17.0));
    }

    @Test
    void toChunkCoord_fractionalPositive() throws Exception {
        // x = 0.5 → floor = 0 → chunk 0
        assertEquals(0, toChunkCoord(0.5));
    }

    // ------------------------------------------------------------------
    // inChunkRange (private static) — spatial bounding box check
    // ------------------------------------------------------------------

    @Test
    void inChunkRange_entityAtCentreIsInRange() throws Exception {
        // Entity at world (0, 0) → chunk (0, 0); pcx=0, pcz=0, range=3
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        assertTrue(inChunkRange(mockEntity, 0, 0, 3));
    }

    @Test
    void inChunkRange_entityExactlyOnBoundaryIsInRange() throws Exception {
        // Entity at chunk (2, 2), range=2 → exactly on the boundary
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(32, 64, 32));
        assertTrue(inChunkRange(mockEntity, 0, 0, 2));
    }

    @Test
    void inChunkRange_entityOneChunkBeyondBoundaryIsOutOfRange() throws Exception {
        // Entity at chunk (3, 0), range=2 → outside
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(48, 64, 0));
        assertFalse(inChunkRange(mockEntity, 0, 0, 2));
    }

    @Test
    void inChunkRange_entityOutsideOnlyZAxisIsOutOfRange() throws Exception {
        // Entity at chunk (0, 5), range=2 → outside on Z axis
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 80));
        assertFalse(inChunkRange(mockEntity, 0, 0, 2));
    }

    @Test
    void inChunkRange_worksWithNegativePlayerChunks() throws Exception {
        // Player at chunk (-5, -5), range=1; entity at chunk (-5, -5) → in range
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(-80, 64, -80));
        assertTrue(inChunkRange(mockEntity, -5, -5, 1));
    }

    @Test
    void inChunkRange_entityAtChunkMinus6WithPlayerAtMinus5Range1_isOutOfRange() throws Exception {
        // Player at chunk (-5, -5), range=1; entity at chunk (-7, -5) → outside
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(-112, 64, -80));
        assertFalse(inChunkRange(mockEntity, -5, -5, 1));
    }

    @Test
    void inChunkRange_zeroRangeOnlyAllowsCentreChunk() throws Exception {
        // range=0: only the player's own chunk counts
        when(mockEntity.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        assertTrue(inChunkRange(mockEntity, 0, 0, 0));

        when(mockEntity.blockPosition()).thenReturn(new BlockPos(16, 64, 0));
        assertFalse(inChunkRange(mockEntity, 0, 0, 0));
    }

    // ------------------------------------------------------------------
    // QueryResult record
    // ------------------------------------------------------------------

    @Test
    void queryResult_storesChunkPosAndEntities() {
        ChunkPos pos = new ChunkPos(3, -5);
        List<Entity> entities = List.of(mockEntity);

        QueryResult result = new QueryResult(pos, entities);

        assertEquals(pos, result.chunkPos());
        assertEquals(entities, result.entities());
    }

    @Test
    void queryResult_equality() {
        ChunkPos pos = new ChunkPos(1, 2);
        List<Entity> entities = List.of();
        QueryResult a = new QueryResult(pos, entities);
        QueryResult b = new QueryResult(pos, entities);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void queryResult_inequalityOnDifferentChunkPos() {
        List<Entity> entities = List.of();
        QueryResult a = new QueryResult(new ChunkPos(0, 0), entities);
        QueryResult b = new QueryResult(new ChunkPos(1, 0), entities);

        assertNotEquals(a, b);
    }

    // ------------------------------------------------------------------
    // EntityTypeCount record
    // ------------------------------------------------------------------

    @Test
    void entityTypeCount_storesTypeIdAndCount() {
        Identifier typeId = Identifier.of("minecraft", "zombie");
        EntityTypeCount row = new EntityTypeCount(typeId, 42L);

        assertEquals(typeId, row.typeId());
        assertEquals(42L, row.count());
    }

    @Test
    void entityTypeCount_equality() {
        Identifier id = Identifier.of("minecraft", "creeper");
        EntityTypeCount a = new EntityTypeCount(id, 10L);
        EntityTypeCount b = new EntityTypeCount(id, 10L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void entityTypeCount_inequalityOnDifferentCount() {
        Identifier id = Identifier.of("minecraft", "creeper");
        assertNotEquals(new EntityTypeCount(id, 5L), new EntityTypeCount(id, 6L));
    }

    @Test
    void entityTypeCount_inequalityOnDifferentTypeId() {
        assertNotEquals(
                new EntityTypeCount(Identifier.of("minecraft", "zombie"), 5L),
                new EntityTypeCount(Identifier.of("minecraft", "creeper"), 5L));
    }

    // ------------------------------------------------------------------
    // ItemTypeCount record
    // ------------------------------------------------------------------

    @Test
    void itemTypeCount_storesItemIdEntityCountAndItemTotal() {
        Identifier itemId = Identifier.of("minecraft", "cobblestone");
        ItemTypeCount row = new ItemTypeCount(itemId, 3L, 192L);

        assertEquals(itemId, row.itemId());
        assertEquals(3L, row.entityCount());
        assertEquals(192L, row.itemTotal());
    }

    @Test
    void itemTypeCount_equality() {
        Identifier id = Identifier.of("minecraft", "dirt");
        ItemTypeCount a = new ItemTypeCount(id, 1L, 64L);
        ItemTypeCount b = new ItemTypeCount(id, 1L, 64L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void itemTypeCount_inequalityOnDifferentItemTotal() {
        Identifier id = Identifier.of("minecraft", "dirt");
        assertNotEquals(new ItemTypeCount(id, 1L, 64L), new ItemTypeCount(id, 1L, 65L));
    }

    @Test
    void itemTypeCount_inequalityOnDifferentEntityCount() {
        Identifier id = Identifier.of("minecraft", "dirt");
        assertNotEquals(new ItemTypeCount(id, 1L, 64L), new ItemTypeCount(id, 2L, 64L));
    }

    @Test
    void itemTypeCount_inequalityOnDifferentItemId() {
        assertNotEquals(
                new ItemTypeCount(Identifier.of("minecraft", "dirt"), 1L, 64L),
                new ItemTypeCount(Identifier.of("minecraft", "sand"), 1L, 64L));
    }

    // ------------------------------------------------------------------
    // Reflection helpers — delegates to the cached Method handles above
    // ------------------------------------------------------------------

    private static int toChunkCoord(double worldCoord) throws Exception {
        return (int) TO_CHUNK_COORD.invoke(null, worldCoord);
    }

    private static boolean inChunkRange(
            Entity entity, int pcx, int pcz, int chunkRange) throws Exception {
        return (boolean) IN_CHUNK_RANGE.invoke(null, entity, pcx, pcz, chunkRange);
    }
}
