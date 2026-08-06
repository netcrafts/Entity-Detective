package netcrafts.detective.query;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/** Persistence sub-type filter for --persistent commands. Predicates mirror persistenceReason() in ResultFormatter. */
public enum PersistenceType {
    ALL, NAME_TAGGED, HOLDING_ITEM, STRUCTURE_SPAWN, IN_VEHICLE, LEASHED, CUSTOM;

    public boolean matches(Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        return switch (this) {
            case ALL            -> mob.isPersistenceRequired() || mob.requiresCustomPersistence()
                                   || mob.isPassenger() || mob.isLeashed();
            case NAME_TAGGED    -> mob.hasCustomName() && mob.isPersistenceRequired();
            case LEASHED        -> mob.isLeashed();
            case IN_VEHICLE     -> mob.isPassenger();
            case HOLDING_ITEM   -> mob.isPersistenceRequired() && !mob.hasCustomName() && !mob.isPassenger()
                                   && (!mob.getMainHandItem().isEmpty() || !mob.getOffhandItem().isEmpty());
            case STRUCTURE_SPAWN -> mob.isPersistenceRequired() && !mob.hasCustomName()
                                    && mob.getMainHandItem().isEmpty() && mob.getOffhandItem().isEmpty();
            case CUSTOM         -> mob.requiresCustomPersistence();
        };
    }

    public static final List<String> COMMAND_NAMES = List.of(
            "all", "name-tagged", "holding-item", "structure-spawn", "in-vehicle", "leashed", "custom");

    /** Returns null for unrecognised names — caller must reject with an error. */
    public static PersistenceType fromCommandName(String name) {
        return switch (name) {
            case "all"             -> ALL;
            case "name-tagged"     -> NAME_TAGGED;
            case "holding-item"    -> HOLDING_ITEM;
            case "structure-spawn" -> STRUCTURE_SPAWN;
            case "in-vehicle"      -> IN_VEHICLE;
            case "leashed"         -> LEASHED;
            case "custom"          -> CUSTOM;
            default                -> null;
        };
    }
}
