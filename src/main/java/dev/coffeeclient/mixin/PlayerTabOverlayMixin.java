package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import dev.coffeeclient.module.modules.render.BetterTab;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.List;

/** BetterTab: player count, columns, names and numeric ping in the tab list. */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
	@Shadow
	protected abstract List<PlayerInfo> getPlayerInfos();

	@ModifyConstant(method = "getPlayerInfos", constant = @Constant(longValue = 80L))
	private long coffeeclient$maxPlayers(long count) {
		BetterTab tab = BetterTab.active();
		return tab == null ? count : tab.tabSize.intValue();
	}

	@ModifyArg(method = "getPlayerInfos", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;sorted(Ljava/util/Comparator;)Ljava/util/stream/Stream;"))
	private Comparator<PlayerInfo> coffeeclient$sort(Comparator<PlayerInfo> vanilla) {
		BetterTab tab = BetterTab.active();
		return tab == null ? vanilla : tab.comparator(vanilla);
	}

	@Inject(method = "getNameForDisplay", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$name(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		BetterTab tab = BetterTab.active();
		if (tab != null) cir.setReturnValue(tab.displayName(info));
	}

	/** Room for the numeric ping, which is wider than the signal bars. */
	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), index = 0)
	private int coffeeclient$width(int width) {
		BetterTab tab = BetterTab.active();
		return tab != null && tab.accurateLatency.get() ? width + 30 : width;
	}

	@Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I", shift = At.Shift.BEFORE))
	private void coffeeclient$columns(CallbackInfo ci, @Local(name = "rows") LocalIntRef rows, @Local(name = "cols") LocalIntRef cols) {
		BetterTab tab = BetterTab.active();
		if (tab == null) return;
		int[] layout = tab.layout(getPlayerInfos().size());
		rows.set(layout[0]);
		cols.set(layout[1]);
	}

	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$ping(GuiGraphicsExtractor graphics, int slotWidth, int xo, int yo, PlayerInfo info, CallbackInfo ci) {
		BetterTab tab = BetterTab.active();
		if (tab != null && tab.drawPing(graphics, slotWidth, xo, yo, info.getLatency())) ci.cancel();
	}
}
