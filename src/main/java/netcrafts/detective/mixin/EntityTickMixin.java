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
 * Supports two profiling modes:
 * <ul>
 *   <li>Single-type: gate is {@link EntityProfiler#shouldTime} — type match, optional range.</li>
 *   <li>All-types: gate is {@link EntityProfiler#shouldTime} — any entity within range.</li>
 * </ul>
 *
 * The TAIL injection records into either {@link EntityProfiler#record} (single-type,
 * keyed by dimension) or {@link EntityProfiler#recordByType} (all-types, keyed by type).
 *
 * Injection technique adapted from fabric-carpet's Level_tickMixin.
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
@SuppressWarnings("null")
@Mixin(Level.class)
public abstract class EntityTickMixin {

    @Unique
    private long detectiveEntityTickStart = 0L;

    @Inject(method = "guardEntityTick", at = @At("HEAD"))
    private <T extends Entity> void detectiveStartEntityTick(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (EntityProfiler.INSTANCE.shouldTime(entity, (Level) (Object) this)) {
            detectiveEntityTickStart = System.nanoTime();
        }
    }

    @Inject(method = "guardEntityTick", at = @At("TAIL"))
    private <T extends Entity> void detectiveEndEntityTick(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (detectiveEntityTickStart == 0L) return;
        long elapsed = System.nanoTime() - detectiveEntityTickStart;
        detectiveEntityTickStart = 0L;
        EntityProfiler profiler = EntityProfiler.INSTANCE;
        if (profiler.isAllTypesMode()) {
            profiler.recordByType(entity.getType(), elapsed);
        } else {
            profiler.recordTick((Level) (Object) this, elapsed);
        }
    }
}

