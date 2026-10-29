package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.core.PacketApplyHooks;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.misc.ChatEffects;
import dev.coffeeclient.module.modules.player.NoRotate;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes a few packets after vanilla handled them, and lets ChatEffects rewrite outgoing chat. TAIL injections only run on the client thread
 * (the first, network-thread call exits early via ensureRunningOnSameThread).
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Unique
	private Vec3 coffeeclient$beforeTeleport;

	@Unique
	private float coffeeclient$noRotateYaw;

	@Unique
	private float coffeeclient$noRotatePitch;

	@Unique
	private boolean coffeeclient$noRotate;

	/** ChatEffects: rewrite outgoing chat before it is signed (commands go through sendCommand instead). */
	@ModifyVariable(method = "sendChat", at = @At("HEAD"), argsOnly = true)
	private String coffeeclient$chatEffects(String content) {
		return ChatEffects.rewrite(content);
	}

	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void coffeeclient$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		HudStats.onServerTime(packet.gameTime());
	}

	/** Velocity: change the motion before it is applied (also for bundled and released packets). */
	@WrapOperation(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
	private void coffeeclient$onSetEntityMotion(Entity entity, Vec3 motion, Operation<Void> original) {
		original.call(entity, dev.coffeeclient.module.modules.movement.Velocity.knockback(entity, motion));
	}

	@ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
	private java.util.Optional<Vec3> coffeeclient$explosionKnockback(java.util.Optional<Vec3> knockback) {
		return knockback.map(dev.coffeeclient.module.modules.movement.Velocity::modifyExplosion);
	}

	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void coffeeclient$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		byte id = packet.getEventId();
		if (id != EntityEvent.PROTECTED_FROM_DEATH && id != EntityEvent.DEATH || level == null || CoffeeClient.events() == null) return;
		Entity entity = packet.getEntity(level);
		if (entity == null) return;
		if (id == EntityEvent.PROTECTED_FROM_DEATH) CoffeeClient.events().post(new Events.TotemPop(entity));
		else if (entity instanceof LivingEntity living) CoffeeClient.events().post(new Events.EntityDeath(living));
	}

	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void coffeeclient$beforeMove(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		coffeeclient$beforeTeleport = mc.isSameThread() && mc.player != null ? mc.player.position() : null;

		// NoRotate: remember the rotation the server is about to overwrite.
		coffeeclient$noRotate = mc.isSameThread() && mc.player != null && ModuleManager.on(NoRotate.class);
		if (coffeeclient$noRotate) {
			coffeeclient$noRotateYaw = mc.player.getYRot();
			coffeeclient$noRotatePitch = mc.player.getXRot();
		}
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void coffeeclient$afterMove(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();

		if (coffeeclient$noRotate && mc.player != null) {
			coffeeclient$noRotate = false;
			mc.player.setYRot(coffeeclient$noRotateYaw + 0.000001f);
			mc.player.setXRot(coffeeclient$noRotatePitch + 0.000001f);
			mc.player.yHeadRot = coffeeclient$noRotateYaw;
			mc.player.yBodyRot = coffeeclient$noRotateYaw;
		}

		if (coffeeclient$beforeTeleport == null || mc.player == null || CoffeeClient.events() == null) return;
		var from = coffeeclient$beforeTeleport;
		double distance = from.distanceTo(mc.player.position());
		coffeeclient$beforeTeleport = null;
		CoffeeClient.events().post(new Events.ServerTeleport(distance, from, mc.player.position()));
	}

	/** Events.PacketApply for each packet of a bundle (the outer bundle is skipped by PacketProcessorMixin). */
	@WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
	private void coffeeclient$applyBundled(Packet<?> packet, PacketListener listener, Operation<Void> original) {
		PacketApplyHooks.apply(packet, () -> original.call(packet, listener));
	}

	@Unique
	private static ClientLevel coffeeclient$lastLevel;

	@Inject(method = {"handleLogin", "handleRespawn"}, at = @At("TAIL"))
	private void coffeeclient$worldChange(CallbackInfo ci) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || level == coffeeclient$lastLevel || !Minecraft.getInstance().isSameThread() || CoffeeClient.events() == null) return;
		coffeeclient$lastLevel = level;
		CoffeeClient.events().post(new Events.WorldChange(level));
	}

	/** NoChatReports: count the insecure-chat toast as seen, so login does not show it. */
	@Inject(method = "handleLogin", at = @At("HEAD"))
	private void coffeeclient$hideInsecureToast(net.minecraft.network.protocol.game.ClientboundLoginPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && dev.coffeeclient.module.modules.misc.NoChatReports.hideInsecureToast()) {
			((ClientCommonPacketListenerImplAccessor) this).coffeeclient$setSeenInsecureChatWarning(true);
		}
	}
}
