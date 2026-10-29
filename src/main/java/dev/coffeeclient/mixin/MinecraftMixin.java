package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Blink;
import dev.coffeeclient.module.modules.player.FastUse;
import dev.coffeeclient.module.modules.player.Multitask;
import dev.coffeeclient.module.modules.render.Freecam;
import dev.coffeeclient.module.modules.render.Outlines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.ref.WeakReference;

/** ESP outlines, FastUse's item cooldown, and the game-left notification Blink needs. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Unique
	private boolean coffeeclient$freecamPicking;

	@Shadow
	private void pick(float partialTick) {
	}

	@Shadow
	private int rightClickDelay;

	@Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$outline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Outlines.color(entity) >= 0) cir.setReturnValue(true);
	}

	@Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isItemEnabled(Lnet/minecraft/world/flag/FeatureFlagSet;)Z"))
	private void coffeeclient$fastUse(CallbackInfo ci, @Local(name = "heldItem") ItemStack heldItem) {
		if (ModuleManager.on(FastUse.class)) rightClickDelay = CoffeeClient.modules().get(FastUse.class).itemUseCooldown(heldItem);
	}

	/** The connection GameLeft was last posted for (weak: it holds the old level), so one leave posts once. */
	@Unique
	private WeakReference<ClientPacketListener> coffeeclient$left = new WeakReference<>(null);

	/** Disconnect from the pause menu: posted before the connection closes. */
	@Inject(method = "disconnectFromWorld", at = @At("HEAD"))
	private void coffeeclient$disconnect(Component reason, CallbackInfo ci) {
		coffeeclient$gameLeft(true);
	}

	/** Every other way out of a world ends here: kicks, lost connections, transfers, the death screen, closing the window. */
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void coffeeclient$disconnectAny(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		coffeeclient$gameLeft(false);
	}

	@Unique
	private void coffeeclient$gameLeft(boolean always) {
		ClientPacketListener connection = ((Minecraft) (Object) this).getConnection();
		if (connection == null ? !always : connection == coffeeclient$left.get()) return;
		coffeeclient$left = new WeakReference<>(connection);
		Blink.onGameLeft();
		if (CoffeeClient.events() != null) CoffeeClient.events().post(new Events.GameLeft());
	}

	// Multitask
	@ModifyExpressionValue(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;isDestroying()Z"))
	private boolean coffeeclient$multitaskStartUse(boolean original) {
		return !ModuleManager.on(Multitask.class) && original;
	}

	@ModifyExpressionValue(method = "continueAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
	private boolean coffeeclient$multitaskContinueAttack(boolean original) {
		return !ModuleManager.on(Multitask.class) && original;
	}

	@ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
	private boolean coffeeclient$multitaskHandleKeybinds(boolean original) {
		if (!ModuleManager.on(Multitask.class)) return original;
		Multitask m = CoffeeClient.modules().get(Multitask.class);
		if (!m.attackingEntities()) return original;
		return false;
	}

	@Inject(method = "pick", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$freecamPick(float partialTick, CallbackInfo ci) {
		if (coffeeclient$freecamPicking || !ModuleManager.on(Freecam.class)) return;
		LocalPlayer player = ((Minecraft) (Object) this).player;
		if (player == null) return;

		Freecam freecam = CoffeeClient.modules().get(Freecam.class);
		Vec3 position = player.position();
		double oldX = player.xo, oldY = player.yo, oldZ = player.zo;
		float oldYaw = player.getYRot(), oldPitch = player.getXRot();
		float oldYawO = player.yRotO, oldPitchO = player.xRotO;
		double x = freecam.x(partialTick), y = freecam.y(partialTick) - player.getEyeHeight(), z = freecam.z(partialTick);
		// Interact from body: keep the real position, only aim along the camera.
		if (freecam.interactFromBody.get()) {
			x = position.x;
			y = position.y;
			z = position.z;
		}
		float yaw = freecam.yaw(partialTick), pitch = freecam.pitch(partialTick);

		player.setPos(x, y, z);
		player.xo = x;
		player.yo = y;
		player.zo = z;
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		coffeeclient$freecamPicking = true;
		try {
			pick(partialTick);
		} finally {
			coffeeclient$freecamPicking = false;
			player.setPos(position);
			player.xo = oldX;
			player.yo = oldY;
			player.zo = oldZ;
			player.setYRot(oldYaw);
			player.setXRot(oldPitch);
			player.yRotO = oldYawO;
			player.xRotO = oldPitchO;
		}
		ci.cancel();
	}

	@Inject(method = "getProfileKeyPairManager", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$noChatReports(CallbackInfoReturnable<net.minecraft.client.multiplayer.ProfileKeyPairManager> cir) {
		cir.setReturnValue(dev.coffeeclient.module.modules.misc.NoChatReports.keyManager(cir.getReturnValue()));
	}
}
