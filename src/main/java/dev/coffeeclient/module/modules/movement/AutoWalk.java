package dev.coffeeclient.module.modules.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.combat.AutoLog;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BlockPosSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Player;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Holds a movement key down for you, plus any chosen modifiers (sprint, sneak, jump).
 *
 * <p>Only the keys it is told to hold are touched. When one is let go it returns to whatever the
 * physical key is doing, so your own input (strafing, jumping, elytra control) is never overridden.
 *
 * <p>Line lock snaps your heading to the nearest 45° axis and steers back onto that line through a
 * point a little ahead on it. Go to steers toward an X/Z instead and stops (or disconnects) on
 * arrival. Safety stops end the walk on a fall, low food, a worn tool or a dimension change.
 */
public final class AutoWalk extends Module {
	public enum Direction {
		Forwards, Backwards, Left, Right
	}

	public enum Jump {
		Off, Auto, Hold
	}

	public enum Arrival {
		Disable, Disconnect
	}

	/** How far in front the step-up probe looks when deciding to jump. */
	private static final double STEP_PROBE = 0.7;
	/** How far in front the unloaded-chunk check looks. */
	private static final double CHUNK_PROBE = 2.0;

	public final EnumSetting<Direction> direction = group(new EnumSetting<>("Direction", "Which movement key to hold", Direction.Forwards), "Walking");
	public final BooleanSetting sprint = group(new BooleanSetting("Sprint", "Also hold sprint", true), "Modifiers");
	public final BooleanSetting sneak = group(new BooleanSetting("Sneak", "Also hold sneak", false), "Modifiers");
	public final EnumSetting<Jump> jump = group(new EnumSetting<>("Jump", "Off, Auto (only to step up a block) or Hold", Jump.Auto), "Modifiers");

	public final BooleanSetting lineLock = group(new BooleanSetting("Line lock", "Snap to the nearest 45° axis and stay on that line", false), "Walking");
	public final NumberSetting lookahead = group(new NumberSetting("Lookahead", "How far ahead on the line to aim when drifting off it", 3, 1, 10, 0.5), "Walking");
	public final BooleanSetting renderLine = group(new BooleanSetting("Render line", "Draw the locked line", true), "Walking");
	public final ColorSetting lineColor = group(new ColorSetting("Line colour", "Colour of the locked line", 0xFF55FF55), "Walking");
	public final BooleanSetting goTo = group(new BooleanSetting("Go to", "Steer toward an X/Z and stop there", false), "Walking");
	public final BlockPosSetting target = group(new BlockPosSetting("Target", "Where to walk to (Y is ignored)", 0, 0, 0, -30_000_000, 30_000_000), "Walking");
	public final EnumSetting<Arrival> arrival = group(new EnumSetting<>("On arrival", "What to do on reaching the target", Arrival.Disable), "Walking");

	public final BooleanSetting pauseInGuis = group(new BooleanSetting("Pause in GUIs", "Let go while a screen is open", true), "Safety");
	public final BooleanSetting stopAtUnloadedChunks = group(new BooleanSetting("Stop at unloaded chunks", "Let go before walking into chunks the client has not loaded", true), "Safety");
	public final BooleanSetting disableOnInput = group(new BooleanSetting("Disable on input", "Turn off as soon as you press a movement key yourself", false), "Safety");
	public final NumberSetting lagPause = group(new NumberSetting("Pause when lagging", "Let go while the server has been silent this long (0 = off)", 1.4, 0, 10, 0.1)
		.format(v -> v == 0 ? "Off" : String.format(Locale.ROOT, "%.1f s", v)), "Safety");
	public final NumberSetting maxDrop = group(new NumberSetting("Stop on drop", "Stop after falling this far below the start (0 = off)", 0, 0, 64, 1)
		.format(v -> v == 0 ? "Off" : (int) v + " blocks"), "Safety");
	public final NumberSetting minFood = group(new NumberSetting("Stop on hunger", "Stop at or below this food level (0 = off)", 0, 0, 20, 1)
		.format(v -> v == 0 ? "Off" : String.valueOf((int) v)), "Safety");
	public final NumberSetting minDurability = group(new NumberSetting("Stop on tool wear", "Stop when the held tool has this much durability left (0 = off)", 0, 0, 100, 1)
		.format(v -> v == 0 ? "Off" : (int) v + " left"), "Safety");

	/** Keys this module is holding down; only these are ever released by it. */
	private final Set<KeyMapping> held = new HashSet<>();
	/** Line lock's line: a point on it and its unit heading. */
	private Vec3 lineOrigin, lineDir;
	private double startY;
	private ResourceKey<Level> startDimension;

	public AutoWalk() {
		super("AutoWalk", Category.MOVEMENT, "Holds a movement key (and optional modifiers) down for you.");
		for (Setting<?> s : List.of(lookahead, renderLine)) s.visibleWhen(lineLock::get);
		lineColor.visibleWhen(() -> lineLock.get() && renderLine.get());
		for (Setting<?> s : List.of(target, arrival)) s.visibleWhen(goTo::get);
		lineLock.onChange(v -> lineOrigin = null);
	}

	@Override
	protected void onEnable() {
		LocalPlayer p = mc().player;
		lineOrigin = null;
		startY = p == null ? 0 : p.getY();
		startDimension = mc().level == null ? null : mc().level.dimension();
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.KeyPress.class, this::onKey);
		listen(Events.MouseClick.class, e -> {
			if (!disableOnInput.get() || e.action != 1) return;
			if (isMovementKey(KeyNames.mouseKey(e.button))) toggle();
		});
		// Releasing a held key physically sets it up between ticks; re-press before the player reads input.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (isEnabled()) for (KeyMapping key : held) key.setDown(true);
		});
	}

	@Override
	protected void onDisable() {
		for (KeyMapping key : held) key.setDown(KeyNames.isPhysicallyDown(key));
		held.clear();
	}

	@Override
	public void onTick() {
		if (!Player.canUpdate()) return;
		Options o = mc().options;
		LocalPlayer player = mc().player;
		String stop = stopReason(player);
		if (stop != null) {
			info(stop);
			toggle();
			return;
		}
		if (goTo.get() && arrived(player)) {
			if (arrival.get() == Arrival.Disconnect) AutoLog.disconnect(Component.literal("[AutoWalk] Arrived at " + target.x() + ", " + target.z()));
			else info("arrived at " + target.x() + ", " + target.z());
			toggle();
			return;
		}
		boolean walking = shouldWalk();
		if (walking) steer(player);
		KeyMapping walkKey = switch (direction.get()) {
			case Forwards -> o.keyUp;
			case Backwards -> o.keyDown;
			case Left -> o.keyLeft;
			case Right -> o.keyRight;
		};
		for (KeyMapping key : List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight)) hold(key, walking && key == walkKey);
		hold(o.keySprint, walking && sprint.get());
		hold(o.keyShift, walking && sneak.get());
		hold(o.keyJump, walking && switch (jump.get()) {
			case Off -> false;
			case Hold -> true;
			case Auto -> stepAhead(player);
		});
	}

	private void hold(KeyMapping key, boolean down) {
		if (down) {
			key.setDown(true);
			held.add(key);
		} else if (held.remove(key)) {
			key.setDown(KeyNames.isPhysicallyDown(key));
		}
	}

	private boolean shouldWalk() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || !player.isAlive()) return false;
		if (pauseInGuis.get() && mc.gui.screen() != null) return false;
		if (lagPause.get() > 0 && serverSilentFor() > lagPause.get()) return false;
		return !stopAtUnloadedChunks.get() || chunkAheadLoaded(player);
	}

	private static double serverSilentFor() {
		long last = HudStats.lastServerUpdate();
		return last == 0 ? 0 : (System.currentTimeMillis() - last) / 1000.0;
	}

	/** Why the walk should end now, or null to keep going. */
	private String stopReason(LocalPlayer p) {
		if (startDimension != null && mc().level.dimension() != startDimension) return "stopped: changed dimension";
		if (maxDrop.get() > 0 && p.getY() < startY - maxDrop.get()) return "stopped: fell " + (int) (startY - p.getY()) + " blocks";
		if (minFood.get() > 0 && p.getFoodData().getFoodLevel() <= minFood.intValue()) return "stopped: food is low";
		ItemStack tool = p.getMainHandItem();
		if (minDurability.get() > 0 && tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= minDurability.intValue())
			return "stopped: tool is nearly broken";
		return null;
	}

	private boolean arrived(LocalPlayer p) {
		double dx = target.x() + 0.5 - p.getX(), dz = target.z() + 0.5 - p.getZ();
		return dx * dx + dz * dz < 1;
	}

	/** Go to aims at the target; line lock aims at a point ahead on the locked line. */
	private void steer(LocalPlayer p) {
		if (goTo.get()) {
			face(p, target.x() + 0.5, target.z() + 0.5);
			return;
		}
		if (!lineLock.get()) return;
		if (lineOrigin == null) {
			double heading = Math.round((p.getYRot() + headingOffset()) / 45.0) * 45.0;
			double rad = Math.toRadians(heading);
			lineDir = new Vec3(-Math.sin(rad), 0, Math.cos(rad)).normalize();
			lineOrigin = new Vec3(p.getX(), p.getY(), p.getZ());
		}
		double along = (p.getX() - lineOrigin.x) * lineDir.x + (p.getZ() - lineOrigin.z) * lineDir.z + lookahead.get();
		face(p, lineOrigin.x + lineDir.x * along, lineOrigin.z + lineDir.z * along);
	}

	/** Turns so the walk direction (not necessarily the view) points at x/z. */
	private void face(LocalPlayer p, double x, double z) {
		double walkYaw = Math.toDegrees(Math.atan2(-(x - p.getX()), z - p.getZ()));
		p.setYRot((float) (walkYaw - headingOffset()));
	}

	/** Degrees from your view yaw to the configured walk direction. */
	private double headingOffset() {
		return switch (direction.get()) {
			case Forwards -> 0;
			case Backwards -> 180;
			case Left -> -90;
			case Right -> 90;
		};
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		if (!lineLock.get() || !renderLine.get() || goTo.get() || lineOrigin == null || mc().player == null) return;
		LocalPlayer p = mc().player;
		double along = (p.getX() - lineOrigin.x) * lineDir.x + (p.getZ() - lineOrigin.z) * lineDir.z;
		Vec3 a = lineOrigin.add(lineDir.scale(along - 64)), b = lineOrigin.add(lineDir.scale(along + 64));
		float y = (float) (p.getY() + 0.05 - cam.y);
		VertexConsumer vc = OverlayRenderer.buffer(WorldOverlays.outlineType(false));
		WorldOverlays.line(vc, (float) (a.x - cam.x), y, (float) (a.z - cam.z), (float) (b.x - cam.x), y, (float) (b.z - cam.z),
			(float) lineDir.x, 0, (float) lineDir.z, lineColor.get(), 2);
	}

	/** True when a block the player can step up to sits in front of them. */
	private boolean stepAhead(LocalPlayer player) {
		Level level = mc().level;
		if (level == null || !player.onGround()) return false;

		Vec3 dir = walkVector(player.getYRot());
		BlockPos feet = BlockPos.containing(
			player.getX() + dir.x * STEP_PROBE, player.getY(), player.getZ() + dir.z * STEP_PROBE);

		// Only jump when the block in the way is one high, otherwise we would hop against a wall forever.
		return !isPassable(level, feet) && isPassable(level, feet.above());
	}

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private boolean chunkAheadLoaded(LocalPlayer player) {
		Level level = mc().level;
		if (level == null) return true;

		Vec3 dir = walkVector(player.getYRot());
		int x = SectionPos.blockToSectionCoord(player.getX() + dir.x * CHUNK_PROBE);
		int z = SectionPos.blockToSectionCoord(player.getZ() + dir.z * CHUNK_PROBE);
		return level.getChunkSource().hasChunk(x, z);
	}

	/** Horizontal unit vector of the configured direction, from the player's yaw. */
	private Vec3 walkVector(float yaw) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		return switch (direction.get()) {
			case Forwards -> new Vec3(fx, 0, fz);
			case Backwards -> new Vec3(-fx, 0, -fz);
			case Left -> new Vec3(fz, 0, -fx);
			case Right -> new Vec3(-fz, 0, fx);
		};
	}

	private void onKey(Events.KeyPress e) {
		if (!isEnabled() || !disableOnInput.get()) return;
		if (e.screenOpen() || e.action() != 1) return;
		if (isMovementKey(e.key())) toggle();
	}

	private boolean isMovementKey(int key) {
		Options o = mc().options;
		if (o == null || !KeyNames.isBound(key)) return false;
		for (KeyMapping m : List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint)) {
			if (!m.isUnbound() && KeyNames.boundKey(m) == key) return true;
		}
		return false;
	}

	@Override
	public String infoString() {
		if (goTo.get() && mc().player != null) {
			LocalPlayer p = mc().player;
			return String.format(Locale.ROOT, "%.0fm", Math.hypot(target.x() + 0.5 - p.getX(), target.z() + 0.5 - p.getZ()));
		}
		return direction.display();
	}
}
