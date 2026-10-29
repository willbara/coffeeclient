package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.PacketHold;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * After you hit something, its movement reaches you late for a while: you keep seeing (and hitting)
 * where it was a moment ago. A box shows where the server really has it.
 */
public final class Backtrack extends Module {
	public final NumberSetting range = setting(new NumberSetting("Range", "Only while the target is this close", 4, 1, 8, 0.25));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Milliseconds its movement is held", 150, 25, 1000, 25));
	public final NumberSetting keep = setting(new NumberSetting("Keep", "Milliseconds after your last hit it stays the target", 1000, 100, 5000, 50));
	public final BooleanSetting pauseOnHurt = setting(new BooleanSetting("Pause on hurt", "Let everything through while you are hurt", false));
	public final BooleanSetting render = setting(new BooleanSetting("Render", "Box where the server has the target", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Box colour", 0xFF55AAFF));

	private Entity target;
	private long lastHit;
	/** Where the server has the target: its position with the held movement applied. */
	private Vec3 real;

	public Backtrack() {
		super("Backtrack", List.of("Back Track"), Category.COMBAT, "Hit where your target was a moment ago.");
		color.visibleWhen(render::get);
		listen(Events.AttackEntity.class, e -> {
			if (e.target() instanceof LivingEntity && e.target() != mc().player) {
				if (e.target() != target) flush();
				target = e.target();
				lastHit = System.currentTimeMillis();
			}
		});
		listen(Events.PacketApply.Pre.class, e -> {
			if (target == null || mc().level == null) return;
			Vec3 moved = moved(e.packet);
			if (moved != null && PacketHold.hold(this, e)) real = moved;
		});
	}

	@Override
	protected void onDisable() {
		flush();
		target = null;
	}

	/** The target's server position after {@code packet}, when it moves the target; null otherwise. */
	private Vec3 moved(Packet<?> packet) {
		Vec3 from = real != null ? real : target.position();
		return switch (packet) {
			case ClientboundMoveEntityPacket p when p.hasPosition() && p.getEntity(mc().level) == target ->
				from.add(p.getXa() / 4096.0, p.getYa() / 4096.0, p.getZa() / 4096.0);
			case ClientboundMoveEntityPacket p when p.getEntity(mc().level) == target -> from;
			case ClientboundEntityPositionSyncPacket p when p.id() == target.getId() -> p.values().position();
			case ClientboundTeleportEntityPacket p when p.id() == target.getId() ->
				PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(target), p.change(), p.relatives()).position();
			case ClientboundSetEntityMotionPacket p when p.id() == target.getId() -> from;
			default -> null;
		};
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || target == null) return;
		boolean done = !target.isAlive() || System.currentTimeMillis() - lastHit > keep.get()
			|| target.distanceToSqr(player) > range.get() * range.get() || pauseOnHurt.get() && player.hurtTime > 0;
		if (done) {
			flush();
			target = null;
			return;
		}
		PacketHold.releaseUntil(this, System.currentTimeMillis() - delay.intValue());
		if (PacketHold.count(this) == 0) real = null;
	}

	private void flush() {
		PacketHold.release(this);
		real = null;
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cameraPos) {
		if (!render.get() || target == null || real == null) return;
		AABB box = target.getBoundingBox().move(real.subtract(target.position()));
		EspRender.boxes(List.of(box), new int[]{color.argb() & 0xFFFFFF}, WorldOverlays.Shape.Both, false, 1.5f, (color.argb() >>> 24) / 3, cameraPos);
	}

	@Override
	public String infoString() {
		int held = PacketHold.count(this);
		return held == 0 ? null : String.valueOf(held);
	}
}
