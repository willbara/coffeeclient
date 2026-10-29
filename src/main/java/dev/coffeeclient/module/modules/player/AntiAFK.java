package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.RandomWalk;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * Keeps you moving so servers do not kick you for being AFK. Walk wanders
 * around where you turned it on.
 */
public final class AntiAFK extends Module {
	public final BooleanSetting jump = group(new BooleanSetting("Jump", "Jump randomly", true), "Actions");
	public final BooleanSetting swing = group(new BooleanSetting("Swing", "Swing your hand", false), "Actions");
	public final BooleanSetting sneak = group(new BooleanSetting("Sneak", "Sneak and unsneak quickly", false), "Actions");
	public final NumberSetting sneakTime = group(new NumberSetting("Sneak time", "Ticks to stay sneaked", 5, 1, 40, 1), "Actions");
	public final BooleanSetting strafe = group(new BooleanSetting("Strafe", "Strafe right and left", false), "Actions");
	public final BooleanSetting spin = group(new BooleanSetting("Spin", "Spin the player in place", true), "Actions");
	public final NumberSetting spinSpeed = group(new NumberSetting("Spin speed", "Degrees to spin per tick", 7, 1, 90, 1), "Actions");

	public final BooleanSetting walk = group(new BooleanSetting("Walk around", "Wander to random spots near where you turned AntiAFK on", false), "Walk");
	public final NumberSetting walkRadius = group(new NumberSetting("Radius", "How far from the start point to wander", 5, 1, 32, 1), "Walk");
	public final NumberSetting waitMin = group(new NumberSetting("Min wait (s)", "Shortest pause between walks", 2, 0, 30, 1), "Walk");
	public final NumberSetting waitMax = group(new NumberSetting("Max wait (s)", "Longest pause between walks", 6, 0, 60, 1), "Walk");
	public final BooleanSetting avoidLedges = group(new BooleanSetting("Avoid ledges", "Don't walk off drops of more than 2 blocks or into liquids", true), "Walk");

	public final BooleanSetting sendMessages = group(new BooleanSetting("Send messages", "Send messages to avoid AFK kicks", false), "Messages");
	public final BooleanSetting randomMessage = group(new BooleanSetting("Random", "Pick a random message from the list", false), "Messages");
	public final NumberSetting delay = group(new NumberSetting("Delay", "Seconds between messages", 15, 0, 60, 1), "Messages");
	public final StringListSetting messages = group(new StringListSetting("Messages", "Messages to choose from",
		List.of("Coffee time!"), 256), "Messages");

	private final Random random = new Random();
	private int messageTimer;
	private int messageIndex;
	private int sneakTimer;
	private int strafeTimer;
	private boolean strafeLeft;
	private float yaw;
	private final RandomWalk walker = new RandomWalk();
	private boolean walking, walkJumped;

	public AntiAFK() {
		super("AntiAFK", Category.PLAYER, "Performs different actions to prevent getting kicked while AFK.");
		sneakTime.visibleWhen(sneak::get);
		spinSpeed.visibleWhen(spin::get);
		for (Setting<?> s : List.of(walkRadius, waitMin, waitMax, avoidLedges)) s.visibleWhen(walk::get);
		randomMessage.visibleWhen(sendMessages::get);
		delay.visibleWhen(sendMessages::get);
		messages.visibleWhen(sendMessages::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		if (sendMessages.get() && messages.get().isEmpty()) {
			info("Message list is empty, disabling messages");
			sendMessages.set(false);
		}
		if (mc().player != null) {
			yaw = mc().player.getYRot();
			walker.reset(mc().player.position());
		}
		messageTimer = delay.get().intValue() * 20;
	}

	@Override
	protected void onDisable() {
		if (walking) mc().options.keyUp.setDown(false);
		walking = false;
		if (strafe.get()) {
			mc().options.keyLeft.setDown(false);
			mc().options.keyRight.setDown(false);
		}
	}

	/** Faces the walk target and holds forward; jumps over one-block steps. */
	private void walk() {
		var player = mc().player;
		if (walkJumped) {
			mc().options.keyJump.setDown(false);
			walkJumped = false;
		}
		Vec3 target = walker.target(player.position(), walkRadius.get(), waitMin.intValue() * 20, Math.max(waitMin.intValue(), waitMax.intValue()) * 20);
		Vec3 dir = target == null ? null : new Vec3(target.x - player.getX(), 0, target.z - player.getZ());
		if (dir != null && avoidLedges.get() && player.onGround() && RandomWalk.ledgeAhead(player.level(), player.position(), dir, 2)) {
			walker.blocked();
			dir = null;
		}
		walking = dir != null;
		mc().options.keyUp.setDown(walking);
		if (!walking) return;
		player.setYRot((float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90f);
		if (player.horizontalCollision && player.onGround()) {
			mc().options.keyJump.setDown(true);
			walkJumped = true;
		}
	}

	@Override
	public void onTick() {
		if (!Player.canUpdate()) return;

		if (jump.get()) {
			if (mc().options.keyJump.isDown()) mc().options.keyJump.setDown(false);
			else if (random.nextInt(99) == 0) mc().options.keyJump.setDown(true);
		}

		if (swing.get() && random.nextInt(99) == 0) {
			var player = mc().player;
			player.swing(player.getUsedItemHand(), false);
		}

		if (sneak.get()) {
			if (sneakTimer++ >= sneakTime.get().intValue()) {
				mc().options.keyShift.setDown(false);
				if (random.nextInt(99) == 0) sneakTimer = 0;
			} else {
				mc().options.keyShift.setDown(true);
			}
		}

		if (strafe.get() && strafeTimer-- <= 0) {
			strafeLeft = !strafeLeft;
			mc().options.keyLeft.setDown(strafeLeft);
			mc().options.keyRight.setDown(!strafeLeft);
			strafeTimer = 5 + random.nextInt(20);
		}

		if (walk.get()) walk();
		else if (walking) {
			mc().options.keyUp.setDown(false);
			walking = false;
		}

		if (spin.get() && !walking) {
			yaw += spinSpeed.get();
			if (yaw > 360) yaw -= 360;
			mc().player.setYRot(yaw);
			mc().player.setXRot(0);
		}

		if (sendMessages.get()) {
			if (messageTimer > 0) {
				messageTimer--;
				return;
			}
			messageTimer = delay.get().intValue() * 20;

			List<String> list = messages.get();
			if (list.isEmpty() || mc().getConnection() == null) return;

			String message = randomMessage.get() ? list.get(random.nextInt(list.size())) : list.get(messageIndex);
			if (!randomMessage.get()) messageIndex = (messageIndex + 1) % list.size();
			mc().getConnection().sendChat(message);
		}
	}
}
