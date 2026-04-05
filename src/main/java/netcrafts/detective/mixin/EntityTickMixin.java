package netcrafts.detective.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import netcrafts.detective.query.EntityProfiler;

import java.util.function.Consumer;

/**
 * Hooks into Level.guardEntityTick to time individual entity ticks for
 * EntityProfiler. One start-time field per Level instance ensures timing
 * is correct when multiple dimensions are loaded (serial tick per level).
 *
 * Injection technique adapted from fabric-carpet's Level_tickMixin.
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
@Mixin(Level.class)
public abstract class EntityTickMixin {

    @Unique
    private long detectiveEntityTickStart = 0L;

    @Inject(method = "guardEntityTick", at = @At("HEAD"))
    private <T extends Entity> void detectiveStartEntityTick(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (EntityProfiler.INSTANCE.isTracking(entity.getType())) {
            detectiveEntityTickStart = System.nanoTime();
        }
    }

    @Inject(method = "guardEntityTick", at = @At("TAIL"))
    private <T extends Entity> void detectiveEndEntityTick(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (detectiveEntityTickStart != 0L && EntityProfiler.INSTANCE.isTracking(entity.getType())) {
            EntityProfiler.INSTANCE.record((Level) (Object) this, System.nanoTime() - detectiveEntityTickStart);
            detectiveEntityTickStart = 0L;
        }
    }
}
