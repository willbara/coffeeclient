package dev.coffeeclient.module.modules.movement;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.Wireframe;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Player;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holds your movement packets, so on the server you stand still while you keep moving locally. Disable
 * (or use the cancel key) to release them. Hold all also queues your other
 * actions (keep-alives, pongs and chat still go out). The held path can be drawn as a line, and
 * the spot the server sees as a model, a box or both.
 */
public final class Blink extends Module {
	public final BooleanSetting renderOriginal = setting(new BooleanSetting("Render original", "Shows your player model at the position the server sees", true));
	public final BooleanSetting wireframe = setting(new BooleanSetting("Wireframe", "Draw the original as an outline instead of a solid model", false));
	public final BooleanSetting renderBox = setting(new BooleanSetting("Render box", "Outlines the position the server sees", false));
	public final BooleanSetting renderPath = setting(new BooleanSetting("Render path", "Draws the latest part of the path held back", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Colour of the box and path", 0xFF55AAFF));
	public final BooleanSetting holdAll = setting(new BooleanSetting("Hold all", "Also hold your other actions, not just movement", false));
	public final NumberSetting pulseDelay = setting(new NumberSetting("Pulse delay", "After this many ticks release everything and start again (0 = off)", 0, 0, 60, 1));
	public final KeybindSetting cancelBlink = setting(new KeybindSetting("Cancel blink", "Key that abandons the held packets and snaps you back", KeybindSetting.UNBOUND));

	private static final int PATH_POINTS = 500;

	private final List<Packet<?>> packets = new ArrayList<>();
	/** Latest positions of the held move packets, for the path line (older ones are dropped from the line only). */
	private final List<Vec3> path = new ArrayList<>();
	private ServerboundMovePlayerPacket lastMove;
	private RemotePlayer model;
	private Vec3 start = Vec3.ZERO;
	private float startYaw;
	private boolean cancelled;
	private boolean sending;
	private int timer;

	public Blink() {
		super("Blink", Category.MOVEMENT, "Suspends your movement on the server while you keep moving.");
		runInMainMenu(true);
		color.visibleWhen(() -> renderBox.get() || renderPath.get() || renderOriginal.get() && wireframe.get());
		wireframe.visibleWhen(renderOriginal::get);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.KeyPress.class, e -> {
			if (e.screenOpen() || e.action() != 1) return;
			cancel(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action != 1) return;
			cancel(KeyNames.mouseKey(e.button));
		});
		// Holds the packet as it would finally be sent: after the rewriters, before AntiHunger.
		listen(Events.PacketSend.class, EventBus.LOW, e -> {
			if (!e.isCancelled() && holds(e.packet)) e.cancel();
		});
	}

	private void cancel(int key) {
		if (cancelBlink.isBound() && key == cancelBlink.key()) {
			cancelled = true;
			toggle();
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public void onEnable() {
		if (!Player.canUpdate()) return;
		cancelled = false;
		timer = 0;
		start = mc().player.position();
		startYaw = mc().player.getYRot();

		if (renderOriginal.get() && !wireframe.get()) {
			ClientLevel level = mc().level;
			if (level != null) {
				model = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), mc().player.getGameProfile().name()));
				model.setPos(start);
				level.addEntity(model);
			}
		}
	}

	@Override
	public void onDisable() {
		if (!Player.canUpdate()) {
			packets.clear();
			return;
		}
		dump(!cancelled);

		if (cancelled) {
			mc().player.setPos(start.x, start.y, start.z);
			mc().player.setDeltaMovement(Vec3.ZERO);
		}
		cancelled = false;
	}

	@Override
	public void onTick() {
		if (!Player.canUpdate()) return;
		timer++;
		if (pulseDelay.get() != 0 && timer >= pulseDelay.get()) {
			onDisable();
			onEnable();
		}
	}

	private void dump(boolean send) {
		sending = true;
		synchronized (packets) {
			// One flush: the server reads the whole held path in the same batch.
			if (send) PacketBatch.run(() -> {
				for (Packet<?> p : packets) mc().player.connection.send(p);
			});
			packets.clear();
			path.clear();
			lastMove = null;
		}
		sending = false;

		if (model != null) {
			model.remove(Entity.RemovalReason.DISCARDED);
			model = null;
		}
		timer = 0;
	}

	/** True when the outgoing packet is held back (may run on the netty thread). */
	private boolean holds(Packet<?> packet) {
		if (sending || !Player.canUpdate()) return false;
		if (!(packet instanceof ServerboundMovePlayerPacket p)) {
			if (!holdAll.get() || packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket
				|| packet instanceof ServerboundChatPacket || packet instanceof ServerboundChatCommandPacket) return false;
			synchronized (packets) {
				packets.add(packet);
			}
			return true;
		}

		ServerboundMovePlayerPacket previous = lastMove;
		if (previous != null
			&& p.isOnGround() == previous.isOnGround()
			&& p.getYRot(-1) == previous.getYRot(-1)
			&& p.getXRot(-1) == previous.getXRot(-1)
			&& p.getX(-1) == previous.getX(-1)
			&& p.getY(-1) == previous.getY(-1)
			&& p.getZ(-1) == previous.getZ(-1)) {
			return true;
		}

		synchronized (packets) {
			packets.add(p);
			lastMove = p;
			if (p.hasPosition()) {
				path.add(new Vec3(p.getX(0), p.getY(0), p.getZ(0)));
				if (path.size() > PATH_POINTS) path.removeFirst();
			}
		}
		return true;
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		if (mc().player == null) return;
		int argb = color.get();
		if (renderOriginal.get() && wireframe.get() && isEnabled()) Wireframe.draw(OverlayRenderer.buffer(WorldOverlays.outlineType(true)), start, startYaw, cam, argb, 1.5f);
		if (renderBox.get()) {
			AABB box = mc().player.getDimensions(mc().player.getPose()).makeBoundingBox(start);
			EspRender.boxes(List.of(box), new int[]{argb}, WorldOverlays.Shape.Outline, true, 2, 0, cam);
		}
		if (!renderPath.get()) return;
		List<Vec3> points;
		synchronized (packets) {
			points = new ArrayList<>(path);
		}
		points.addFirst(start);
		VertexConsumer vc = OverlayRenderer.buffer(WorldOverlays.outlineType(true));
		for (int i = 1; i < points.size(); i++) {
			Vec3 a = points.get(i - 1), b = points.get(i), d = b.subtract(a);
			double len = d.length();
			if (len < 1e-6) continue;
			WorldOverlays.line(vc, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z),
				(float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z),
				(float) (d.x / len), (float) (d.y / len), (float) (d.z / len), argb, 2);
		}
	}

	/** Called from {@code MinecraftMixin} when the world is left. */
	public static void onGameLeft() {
		if (CoffeeClient.modules() == null) return;
		Blink blink = CoffeeClient.modules().get(Blink.class);
		blink.dump(false);
		blink.cancelled = false;
	}

	@Override
	public String infoString() {
		return String.valueOf(packets.size());
	}
}
