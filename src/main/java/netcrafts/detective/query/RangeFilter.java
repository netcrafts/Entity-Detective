package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Utility constants and guards for the --range flag.
 *
 * The range unit is always chunks (1 chunk = 16 blocks).
 * --range N scopes a query to an (2N+1) × (2N+1) square of chunks centred on the player.
 */
public class RangeFilter {

    public static final int MAX_RANGE_CHUNKS = 32;

    /**
     * Guard: sends a failure message and returns true if the source is not a player.
     * Call at the start of every --range executor — range commands always need
     * a position, which only a player source provides.
     */
    public static boolean sourceIsNotPlayer(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(
                    "--range requires a player source (cannot be used from console)."));
            return true;
        }
        return false;
    }
}
