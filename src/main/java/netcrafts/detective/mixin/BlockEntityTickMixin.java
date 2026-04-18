package netcrafts.detective.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import netcrafts.detective.query.EntityProfiler;

/**
 * Hooks into LevelChunk$BoundTickingBlockEntity to time individual block entity
 * ticks for EntityProfiler when allTypesMode is active.
 *
 * This single mixin point covers all ticking block entities (hoppers, furnaces,
 * brewing stands, chests, spawners, etc.) — the same approach used by
 * fabric-carpet's BoundTickingBlockEntity_profilerMixin.
 *
 * @see <a href="https://github.com/gnembon/fabric-carpet">fabric-carpet</a>
 */
@SuppressWarnings("null")
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public class BlockEntityTickMixin<T extends BlockEntity> {

    @Shadow @Final private T blockEntity;

    @Unique
    private long detectiveBETickStart = 0L;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void detectiveStartBETick(CallbackInfo ci) {
        if (EntityProfiler.INSTANCE.shouldTimeBE(blockEntity)) {
            detectiveBETickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void detectiveEndBETick(CallbackInfo ci) {
        if (detectiveBETickStart == 0L) return;
        long elapsed = System.nanoTime() - detectiveBETickStart;
        detectiveBETickStart = 0L;
        Level world = blockEntity.getLevel();
        if (world == null) return;
        EntityProfiler.INSTANCE.recordByBlockEntityType(blockEntity.getType(), elapsed, world);
    }
}
