package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.LivingEntityAccessor;
import dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Climb walls like a spider by holding against them, and climb ladders, vines and powder snow faster.
 * Matrix and Vulcan wall modes are anticheat-specific tries kept for testing: they climb in jump-sized
 * steps and claim ground whenever the height lines up with where the climb began (Vulcan stops after 2 blocks).
 */
public final class Spider extends Module {
	public enum WallMode { Vanilla, Matrix, Vulcan, Polar }

	public final EnumSetting<WallMode> wallMode = group(new EnumSetting<>("Wall mode",
		"Vanilla: a steady climb. Matrix: jump steps with ground claims. Vulcan: the same, up to 2 blocks per climb. Polar: higher jumps at walls (for testing)", WallMode.Vanilla), "Walls");
	public final NumberSetting climbSpeed = group(new NumberSetting("Climb speed", "How fast you go up walls", 0.2, 0, 1, 0.01), "Walls");
	public final BooleanSetting fastClimb = group(new BooleanSetting("Fast climb", "Also climb ladders, vines and powder snow faster", false), "Ladders");
	public final BooleanSetting timerMode = group(new BooleanSetting("Timer mode", "Use the Timer module instead of a fixed speed", false), "Ladders");
	public final NumberSetting ladderSpeed = group(new NumberSetting("Ladder speed", "Your climb speed on ladders", 0.2872, 0, 5, 0.001), "Ladders");
	public final NumberSetting timer = group(new NumberSetting("Timer", "Timer value used in Timer mode", 1.436, 1, 20, 0.001), "Ladders");
	public final BooleanSetting spartanLadder = group(new BooleanSetting("Spartan ladder", "On ladders against a wall, climb in jump steps with ground claims (for testing)", false), "Ladders");

	private boolean resetTimer;
	private int step;
	private double startY = Double.NaN;

	public Spider() {
		super("Spider", Category.MOVEMENT, "Allows you to climb walls like a spider, and ladders faster.");
		timerMode.visibleWhen(fastClimb::get);
		ladderSpeed.visibleWhen(() -> fastClimb.get() && !timerMode.get());
		timer.visibleWhen(() -> fastClimb.get() && timerMode.get());
		climbSpeed.visibleWhen(() -> wallMode.get() == WallMode.Vanilla);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketSend.class, EventBus.HIGH, e -> {
			if (wallMode.get() != WallMode.Matrix && wallMode.get() != WallMode.Vulcan && !spartanLadder.get() || Double.isNaN(startY) || !(e.packet instanceof ServerboundMovePlayerPacket move) || !move.hasPosition()) return;
			double frac = move.getY(0) - Math.floor(move.getY(0)), start = startY - Math.floor(startY);
			if (Math.abs(frac - start) <= 0.1) ((ServerboundMovePlayerPacketAccessor) move).coffeeclient$setOnGround(true);
		});
	}

	@Override
	protected void onEnable() {
		resetTimer = false;
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null) return;

		if (spartanLadder.get() && player.onClimbable()) {
			if (!player.horizontalCollision || player.onGround()) {
				step = 0;
				startY = player.horizontalCollision ? player.getY() : Double.NaN;
			}
			if (player.horizontalCollision) {
				stepUp(player);
				return;
			}
		}
		if (fastClimb.get()) {
			if (timerMode.get()) {
				if (climbing()) {
					resetTimer = false;
					Timer.override(this, timer.get());
				} else if (!resetTimer) {
					Timer.release(this);
					resetTimer = true;
				}
			} else if (climbing()) {
				Vec3 velocity = player.getDeltaMovement();
				player.setDeltaMovement(velocity.x, ladderSpeed.get(), velocity.z);
				return;
			}
		} else {
			Timer.release(this);
		}

		if (wallMode.get() == WallMode.Matrix || wallMode.get() == WallMode.Vulcan) {
			if (!player.horizontalCollision || player.onGround()) {
				step = 0;
				startY = player.horizontalCollision ? player.getY() : Double.NaN;
				if (!player.horizontalCollision) return;
			}
			// shortcut: Vulcan caps every climb at 2 blocks; a mid-air start should cap at 1.5, upgrade if that flags.
			if (wallMode.get() == WallMode.Vulcan && player.getY() >= startY + 2) return;
			stepUp(player);
			return;
		}
		if (wallMode.get() == WallMode.Polar) {
			Vec3 v = player.getDeltaMovement();
			if (player.horizontalCollision && player.onGround() && mc().options.keyJump.isDown()) player.setDeltaMovement(v.x, 0.55, v.z);
			return;
		}
		if (!player.horizontalCollision) return;
		Vec3 velocity = player.getDeltaMovement();
		if (velocity.y >= 0.2) return;
		player.setDeltaMovement(velocity.x, climbSpeed.get(), velocity.z);
	}

	/** The next of three jump-sized rises. */
	private void stepUp(net.minecraft.client.player.LocalPlayer player) {
		if (Double.isNaN(startY)) startY = player.getY();
		Vec3 v = player.getDeltaMovement();
		// Vulcan: keep the in-air rises just off the exact vanilla jump values.
		double off = wallMode.get() == WallMode.Vulcan ? 3.26e-11 : 0;
		double y = switch (step) {
			case 0 -> 0.41999998688698;
			case 1 -> 0.33319999363 - off;
			default -> 0.24813599862 - off;
		};
		step = (step + 1) % 3;
		player.setDeltaMovement(v.x, y, v.z);
	}

	private boolean climbing() {
		var player = mc().player;
		boolean jumping = ((LivingEntityAccessor) player).coffeeclient$isJumping();
		return (player.horizontalCollision || jumping)
			&& (player.onClimbable() || (player.getInBlockState().is(Blocks.POWDER_SNOW) && PowderSnowBlock.canEntityWalkOnPowderSnow(player)));
	}
}
