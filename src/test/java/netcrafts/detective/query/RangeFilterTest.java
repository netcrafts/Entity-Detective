package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RangeFilter}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RangeFilterTest {

    @Mock
    private CommandSourceStack mockSource;

    @Mock
    private Entity mockNonPlayerEntity;

    @Mock
    private ServerPlayer mockPlayer;

    // ------------------------------------------------------------------
    // MAX_RANGE_CHUNKS constant
    // ------------------------------------------------------------------

    @Test
    void maxRangeChunks_isThirtyTwo() {
        assertEquals(32, RangeFilter.MAX_RANGE_CHUNKS);
    }

    // ------------------------------------------------------------------
    // sourceIsNotPlayer()
    // ------------------------------------------------------------------

    @Test
    void sourceIsNotPlayer_returnsTrueWhenEntityIsNull() {
        when(mockSource.getEntity()).thenReturn(null);

        assertTrue(RangeFilter.sourceIsNotPlayer(mockSource));
    }

    @Test
    void sourceIsNotPlayer_sendsFailureWhenEntityIsNull() {
        when(mockSource.getEntity()).thenReturn(null);

        RangeFilter.sourceIsNotPlayer(mockSource);

        verify(mockSource).sendFailure(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sourceIsNotPlayer_returnsTrueWhenEntityIsNotServerPlayer() {
        when(mockSource.getEntity()).thenReturn(mockNonPlayerEntity);

        assertTrue(RangeFilter.sourceIsNotPlayer(mockSource));
    }

    @Test
    void sourceIsNotPlayer_sendsFailureWhenEntityIsNotServerPlayer() {
        when(mockSource.getEntity()).thenReturn(mockNonPlayerEntity);

        RangeFilter.sourceIsNotPlayer(mockSource);

        verify(mockSource).sendFailure(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sourceIsNotPlayer_returnsFalseWhenEntityIsServerPlayer() {
        when(mockSource.getEntity()).thenReturn(mockPlayer);

        assertFalse(RangeFilter.sourceIsNotPlayer(mockSource));
    }

    @Test
    void sourceIsNotPlayer_doesNotSendFailureWhenEntityIsServerPlayer() {
        when(mockSource.getEntity()).thenReturn(mockPlayer);

        RangeFilter.sourceIsNotPlayer(mockSource);

        // No failure or success message should be sent for a valid player source
        verifyNoMoreInteractions(mockSource);
    }
}
