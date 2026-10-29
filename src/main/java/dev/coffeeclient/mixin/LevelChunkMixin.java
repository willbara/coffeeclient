package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block update event for client chunks (server updates, prediction and rollback all land here). */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void coffeeclient$blockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		BlockState old = cir.getReturnValue();
		// null = nothing changed; server chunks are skipped (integrated server shares the classes).
		if (old == null) return;
		EventBus bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.BlockUpdate.class) || !((LevelChunk) (Object) this).getLevel().isClientSide()) return;
		bus.post(new Events.BlockUpdate(pos.immutable(), old, state));
	}
}
