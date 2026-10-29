package dev.coffeeclient.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the sequence-allocating prediction helper and the held-slot sync (private in vanilla). */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Invoker("startPrediction")
	void coffeeclient$startPrediction(ClientLevel level, PredictiveAction action);

	/** Sends the selected hotbar slot to the server now if it changed (vanilla waits for the next action or tick). */
	@Invoker("ensureHasSentCarriedItem")
	void coffeeclient$syncSelectedSlot();

	@Accessor("destroyDelay")
	void coffeeclient$setDestroyDelay(int delay);

	/** How far the block being broken has got (0-1), and which block. */
	@Accessor("destroyProgress")
	float coffeeclient$destroyProgress();

	@Accessor("destroyBlockPos")
	net.minecraft.core.BlockPos coffeeclient$destroyBlockPos();

	/** The hotbar slot the server was last told about. */
	@Accessor("carriedIndex")
	int coffeeclient$carriedIndex();

	@Accessor("carriedIndex")
	void coffeeclient$setCarriedIndex(int slot);
}
