package netcrafts.detective.query;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Utility constants and guards for the --radius flag.
 *
 * The radius unit is always chunks (1 chunk = 16 blocks).
 * Use {@link #toBlockRadius} to convert to blocks when building predicates.
 */
public class RadiusFilter {

    public static final int MAX_RADIUS_CHUNKS = 32;

    /** Converts a chunk radius to a block radius (radiusChunks × 16). */
    public static double toBlockRadius(int radiusChunks) {
        return radiusChunks * 16.0;
    }

    /**
     * Guard: sends a failure message and returns true if the source is not a player.
     * Call at the start of every --radius executor — radius commands always need
     * a position, which only a player source provides.
     */
    public static boolean sourceIsNotPlayer(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(
                    "--radius requires a player source (cannot be used from console)."));
            return true;
        }
        return false;
    }
}
