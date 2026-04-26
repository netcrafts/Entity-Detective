package netcrafts.detective.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import netcrafts.detective.query.EntityProfiler;

/**
 * Hooks into Entity.rideTick to time passenger entities for EntityProfiler.
 *
 * <p>Passenger entities are ticked via ServerLevel.tickPassenger → Entity.rideTick,
 * bypassing Level.guardEntityTick entirely. Without this mixin, any entity riding
 * a vehicle (e.g. villagers in minecarts) is invisible to the profiler.
 *
 * <p>Works in tandem with {@link EntityTickMixin}: the two paths are mutually
 * exclusive — ServerLevel skips guardEntityTick for passengers and returns early,
 * so there is no double-counting between the two mixins.
 *
 * <p>For nested passengers (A → B → C), ServerLevel.tickPassenger recurses
 * after rideTick returns, so each passenger fires its own HEAD/TAIL pair
 * independently — no nesting or overlap.
 *
 * Injection technique adapted from fabric-carpet's Level_tickMixin.
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
@SuppressWarnings("null")
@Mixin(Entity.class)
public abstract class EntityRideTickMixin {

    @Unique
    private long detectiveRideTickStart = 0L;

    @Inject(method = "rideTick", at = @At("HEAD"))
    private void detectiveStartRideTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Level world = self.level();
        if (EntityProfiler.INSTANCE.shouldTime(self, world)) {
            detectiveRideTickStart = System.nanoTime();
        }
    }

    @Inject(method = "rideTick", at = @At("TAIL"))
    private void detectiveEndRideTick(CallbackInfo ci) {
        if (detectiveRideTickStart == 0L) return;
        long elapsed = System.nanoTime() - detectiveRideTickStart;
        detectiveRideTickStart = 0L;
        Entity self = (Entity) (Object) this;
        EntityProfiler profiler = EntityProfiler.INSTANCE;
        if (profiler.isAllTypesMode()) {
            profiler.recordByType(self.getType(), elapsed);
        } else {
            profiler.recordTick(self.level(), elapsed);
        }
    }
}
