package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.coffeeclient.module.modules.misc.InvLeak;
import dev.coffeeclient.module.modules.render.NoEffect;
import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ambience: hide the scoreboard sidebar and boss bars. NoEffect: the vignette, the pumpkin and powder snow
 * overlays, the crosshair, titles, the held item's name and the effect icons. InvLeak: the hotbar's items,
 * the item name and the selection frame.
 */
@Mixin(Hud.class)
abstract class HudMixin {
	private static final Identifier HOTBAR_SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");

	@ModifyVariable(method = "extractSlot", at = @At("HEAD"), argsOnly = true)
	private ItemStack coffeeclient$invLeakHotbar(ItemStack stack, @Local(argsOnly = true, ordinal = 2) int seed) {
		return InvLeak.hotbar(stack, seed);
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$invLeakItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (InvLeak.hidesItemName()) ci.cancel();
	}

	@WrapWithCondition(method = "extractItemHotbar", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean coffeeclient$invLeakSelection(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
		return !(InvLeak.hidesSelectedSlot() && HOTBAR_SELECTION.equals(sprite));
	}

	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noVignette(GuiGraphicsExtractor graphics, Entity camera, CallbackInfo ci) {
		if (NoEffect.hidesVignette()) ci.cancel();
	}

	/** The camera overlay textures: the carved pumpkin's blur and the powder snow outline. */
	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noPumpkin(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo ci) {
		String path = texture.getPath();
		if (path.contains("pumpkin") && NoEffect.hidesPumpkinOverlay() || path.contains("powder_snow") && NoEffect.hidesPowderSnow()) ci.cancel();
	}

	/** ChatTweaks: chat survives leaving a server. */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "onDisconnected", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"))
	private void coffeeclient$keepChat(net.minecraft.client.gui.components.ChatComponent chat, boolean history,
									   com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		if (!dev.coffeeclient.module.modules.misc.ChatTweaks.keepsHistory()) original.call(chat, history);
	}

	/** ChatTweaks' filter on titles. */
	@org.spongepowered.asm.mixin.injection.ModifyVariable(method = {"setTitle", "setSubtitle"}, at = @At("HEAD"), argsOnly = true)
	private net.minecraft.network.chat.Component coffeeclient$filterTitle(net.minecraft.network.chat.Component text) {
		return dev.coffeeclient.module.modules.misc.ChatTweaks.filterText(text, dev.coffeeclient.module.modules.misc.ChatTweaks.Where.TITLE);
	}

	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noCrosshair(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.crosshair)) ci.cancel();
	}

	@Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noTitle(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.title)) ci.cancel();
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.heldItemName)) ci.cancel();
	}

	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noEffectIcons(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.potionIcons)) ci.cancel();
	}

	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$hideScoreboard(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Ambience.hidesScoreboard()) ci.cancel();
	}

	@Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$hideBossBars(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Ambience.hidesBossBars()) ci.cancel();
	}
}
