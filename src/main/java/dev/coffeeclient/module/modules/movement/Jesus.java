package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Walk on water and lava like Jesus. The
 * anticheat modes are tries kept for testing: NCP swims along the surface held level, Matrix zoom
 * skims over water at a set speed, Matrix bob does the same with a tiny up-down bob, Vulcan pushes
 * up out of water with the game slightly sped up.
 */
public final class Jesus extends Module {
	public enum Mode {
		Solid, Ignore
	}

	public enum AcMode implements dev.coffeeclient.setting.Labeled {
		OFF("Off"), NCP("NCP swim"), MATRIX_ZOOM("Matrix zoom"), MATRIX_BOB("Matrix bob"), VULCAN("Vulcan 2.9.1");

		private final String label;

		AcMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<AcMode> acMode = group(new EnumSetting<>("Anticheat mode", "Try a water trick tuned for one anticheat (for testing; may flag)", AcMode.OFF), "Anticheat");
	public final NumberSetting acSpeed = group(new NumberSetting("Anticheat speed", "Speed for the anticheat modes", 0.6, 0.1, 3, 0.05), "Anticheat");
	private int bob;

	public final BooleanSetting powderSnow = setting(new BooleanSetting("Powder snow", "Walk on powder snow", true));
	public final BooleanSetting ncpBypass = setting(new BooleanSetting("NCP bypass", "Extra bypass for NoCheatPlus servers", false));
	public final BooleanSetting slowDown = setting(new BooleanSetting("Slow down", "Extra movement option for the NCP bypass", false).visibleWhen(ncpBypass::get));
	public final EnumSetting<Mode> waterMode = setting(new EnumSetting<>("Water mode", "How to treat water", Mode.Solid));
	public final BooleanSetting dipIfBurning = setting(new BooleanSetting("Dip if burning", "Enter water while on fire", true).visibleWhen(() -> waterMode.get() == Mode.Solid));
	public final BooleanSetting dipOnSneakWater = setting(new BooleanSetting("Dip on sneak", "Enter water while sneaking", true).visibleWhen(() -> waterMode.get() == Mode.Solid));
	public final BooleanSetting dipOnFallWater = setting(new BooleanSetting("Dip on fall", "Enter water after a long fall", true).visibleWhen(() -> waterMode.get() == Mode.Solid));
	public final NumberSetting dipFallHeightWater = setting(new NumberSetting("Water fall height", "Fall height that lets you into the water", 4, 1, 255, 1).visibleWhen(() -> waterMode.get() == Mode.Solid && dipOnFallWater.get()));
	public final EnumSetting<Mode> lavaMode = setting(new EnumSetting<>("Lava mode", "How to treat lava", Mode.Solid));
	public final BooleanSetting dipIfFireResistant = setting(new BooleanSetting("Dip if resistant", "Enter lava with fire resistance", true).visibleWhen(() -> lavaMode.get() == Mode.Solid));
	public final BooleanSetting dipOnSneakLava = setting(new BooleanSetting("Dip on sneak", "Enter lava while sneaking", true).visibleWhen(() -> lavaMode.get() == Mode.Solid));
	public final BooleanSetting dipOnFallLava = setting(new BooleanSetting("Dip on fall", "Enter lava after a long fall", true).visibleWhen(() -> lavaMode.get() == Mode.Solid));
	public final NumberSetting dipFallHeightLava = setting(new NumberSetting("Lava fall height", "Fall height that lets you into the lava", 4, 1, 255, 1).visibleWhen(() -> lavaMode.get() == Mode.Solid && dipOnFallLava.get()));

	/** Set by the bubble-column mixin. */
	public boolean isInBubbleColumn = false;

	private int ascending = 10;
	private int swimmingTicks;

	public Jesus() {
		super("Jesus", Category.MOVEMENT, "Walk on liquids and powder snow like Jesus.");
		acSpeed.visibleWhen(() -> acMode.get() != AcMode.OFF);
		listen(Events.CanWalkOnFluid.class, e -> e.canWalk = canWalkOnFluid(e.fluid));
		listen(Events.PlayerMove.class, e -> e.movement = modifyMove(e.movement));
		// After NoFall, which may have rewritten the packet.
		listen(Events.PacketSend.class, EventBus.HIGH, e -> {
			if (!e.isCancelled()) modifyPacket(e.packet);
		});
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public void onEnable() {
		ascending = 10;
		swimmingTicks = 0;
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null) return;

		boolean bubbleColumn = isInBubbleColumn;
		isInBubbleColumn = false;

		if (player.isVisuallySwimming()) return;
		if (player.isInWater() && !waterShouldBeSolid()) return;
		if (player.isInLava() && !lavaShouldBeSolid()) return;

		Entity moving = player.isPassenger() ? player.getVehicle() : player;

		if (bubbleColumn) {
			if (mc().options.keyJump.isDown() && moving.getDeltaMovement().y < 0.11) setY(moving, 0.11);
			return;
		}

		if (moving.isInWater() || moving.isInLava()) {
			setY(moving, 0.11);
			ascending = 0;
			return;
		}

		BlockState below = mc().level.getBlockState(moving.blockPosition().below());
		boolean waterLogged = below.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

		if (ascending == 0) setY(moving, 0.11);
		else if (ascending == 1 && (below.getBlock() == Blocks.WATER || below.getBlock() == Blocks.LAVA || waterLogged)) setY(moving, 0);
		ascending++;
	}

	private static void setY(Entity entity, double y) {
		Vec3 velocity = entity.getDeltaMovement();
		entity.setDeltaMovement(velocity.x, y, velocity.z);
	}

	// ---- mixin helpers ----

	private boolean canWalkOnFluid(FluidState fluid) {
		if (mc().player != null && mc().player.isSwimming()) return false;
		if ((fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER) && waterShouldBeSolid()) return true;
		return (fluid.getType() == Fluids.LAVA || fluid.getType() == Fluids.FLOWING_LAVA) && lavaShouldBeSolid();
	}

	public VoxelShape collisionShape(BlockState state, BlockPos pos, VoxelShape shape) {
		if (state.getFluidState().isEmpty()) return shape;
		var player = mc().player;
		if (player == null) return shape;

		if ((state.getBlock() == Blocks.WATER || state.getFluidState().getType() == Fluids.WATER)
			&& !player.isInWater() && waterShouldBeSolid() && pos.getY() <= player.getY() - 1) {
			return Shapes.block();
		}
		if (state.getBlock() == Blocks.LAVA && !player.isInLava() && lavaShouldBeSolid()
			&& (isLavaDangerous() || pos.getY() <= player.getY() - 1)) {
			return Shapes.block();
		}
		return shape;
	}

	private Vec3 modifyMove(Vec3 movement) {
		if (acMode.get() != AcMode.OFF) return anticheat(movement);
		if (!ncpBypass.get()) return movement;

		Over over = overLiquid();
		boolean water = over.water && waterShouldBeSolid();
		boolean lava = over.lava && lavaShouldBeSolid();
		if (!water && !lava) {
			swimmingTicks = 0;
			return movement;
		}

		var player = mc().player;
		if (++swimmingTicks < 15) {
			if (player.onGround()) {
				Vec3 strafe = strafe(0.2873);
				return new Vec3(strafe.x, movement.y, strafe.z);
			}
			return movement;
		}

		swimmingTicks = 0;
		if (slowDown.get()) {
			player.setDeltaMovement(player.getDeltaMovement().x, 0.08, player.getDeltaMovement().z);
			return new Vec3(0, movement.y, 0);
		}
		player.setDeltaMovement(player.getDeltaMovement().x, 0.08, player.getDeltaMovement().z);
		return movement;
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
	}

	private Vec3 anticheat(Vec3 movement) {
		var p = mc().player;
		if (p == null) return movement;
		Vec3 dir = Player.moveDir();
		double s = 0.5 * acSpeed.get();
		var opts = mc().options;
		if (acMode.get() != AcMode.VULCAN) Timer.release(this);
		switch (acMode.get()) {
			case NCP -> {
				if (!p.isInWater() && !p.isInLava()) return movement;
				p.setSprinting(false);
				double y = opts.keyJump.isDown() ? 0.12 : opts.keyShift.isDown() ? -0.12 : 0;
				Vec3 out = new Vec3(dir.x * s, y, dir.z * s);
				p.setDeltaMovement(out);
				return out;
			}
			case MATRIX_ZOOM, MATRIX_BOB -> {
				if (!mc().level.getFluidState(p.blockPosition()).is(net.minecraft.tags.FluidTags.WATER) || p.horizontalCollision) return movement;
				double y = acMode.get() == AcMode.MATRIX_BOB ? (bob++ % 2 == 0 ? 0.030091 : -0.030091) : 0;
				Vec3 out = new Vec3(dir.x * s, y, dir.z * s);
				p.setDeltaMovement(out);
				return out;
			}
			case VULCAN -> {
				if (!p.isInWater()) {
					Timer.release(this);
					return movement;
				}
				Timer.override(this, 1.125);
				Vec3 out = new Vec3(movement.x, 0.8 * acSpeed.get() / 0.6, movement.z);
				p.setDeltaMovement(out);
				return out;
			}
			default -> {
				return movement;
			}
		}
	}

	/** Applies the NCP-bypass packet tweaks to outgoing movement packets. */
	private void modifyPacket(Packet<?> packet) {
		if (mc().player == null || !ncpBypass.get()) return;
		if (!(packet instanceof ServerboundMovePlayerPacket move)) return;
		if (mc().player.isInWater() && !waterShouldBeSolid()) return;
		if (mc().player.isInLava() && !lavaShouldBeSolid()) return;

		Over over = overLiquid();
		boolean shouldWork = (over.water && waterShouldBeSolid()) || (over.lava && lavaShouldBeSolid());
		if (mc().player.isInWater() || mc().player.isInLava() || mc().player.fallDistance > 3f || !shouldWork) return;

		ServerboundMovePlayerPacketAccessor accessor = (ServerboundMovePlayerPacketAccessor) move;
		accessor.coffeeclient$setOnGround(false);
		if (!mc().player.onGround() || !move.hasPosition()) return;
		accessor.coffeeclient$setY(move.getY(0) - (0.02 + 0.0001 * swimmingTicks));
	}

	private Vec3 strafe(double speed) {
		var player = mc().player;
		double forward = player.zza;
		double strafe = player.xxa;
		float yaw = player.getYRot();
		if (forward == 0 && strafe == 0) return Vec3.ZERO;
		if (forward != 0) {
			if (strafe > 0) yaw += forward > 0 ? -45 : 45;
			else if (strafe < 0) yaw += forward > 0 ? 45 : -45;
		}
		double radians = Math.toRadians(yaw + 90);
		return new Vec3(forward * speed * Math.cos(radians) + strafe * speed * Math.sin(radians), 0, forward * speed * Math.sin(radians) + strafe * speed * Math.cos(radians));
	}

	private boolean waterShouldBeSolid() {
		var player = mc().player;
		if (player == null) return false;
		if (player.isSpectator() || player.getAbilities().flying) return false;
		if (player.getVehicle() instanceof AbstractBoat) return false;
		if (ModuleManager.on(Flight.class)) return false;
		if (dipIfBurning.get() && player.isOnFire()) return false;
		if (dipOnSneakWater.get() && mc().options.keyShift.isDown()) return false;
		if (dipOnFallWater.get() && player.fallDistance > dipFallHeightWater.get()) return false;
		return waterMode.get() == Mode.Solid;
	}

	private boolean lavaShouldBeSolid() {
		var player = mc().player;
		if (player == null) return false;
		if (player.isSpectator() || player.getAbilities().flying) return false;
		if (player.getVehicle() instanceof Strider) return false;
		if (isLavaDangerous() && lavaMode.get() == Mode.Solid) return true;
		if (dipOnSneakLava.get() && mc().options.keyShift.isDown()) return false;
		if (dipOnFallLava.get() && player.fallDistance > dipFallHeightLava.get()) return false;
		return lavaMode.get() == Mode.Solid;
	}

	private boolean isLavaDangerous() {
		var player = mc().player;
		if (!dipIfFireResistant.get()) return true;
		var effect = player.getEffect(MobEffects.FIRE_RESISTANCE);
		if (effect == null) return true;
		return effect.getDuration() <= (int) (15 * 20 * player.getAttributeValue(Attributes.BURNING_TIME));
	}

	private Over overLiquid() {
		var player = mc().player;
		AABB box = player.isPassenger() ? player.getBoundingBox().minmax(player.getVehicle().getBoundingBox()) : player.getBoundingBox();
		BlockState[] states = mc().level.getBlockStatesIfLoaded(box.move(0, -0.01, 0)).toArray(BlockState[]::new);

		boolean water = false;
		boolean lava = false;
		for (BlockState state : states) {
			if (state.getBlock() == Blocks.WATER || state.getFluidState().getType() == Fluids.WATER) water = true;
			else if (state.getBlock() == Blocks.LAVA) lava = true;
			else if (!state.isAir()) return new Over(false, false);
		}
		return new Over(water, lava);
	}

	private record Over(boolean water, boolean lava) {
	}

	public boolean canWalkOnPowderSnow() {
		return isEnabled() && powderSnow.get();
	}

	/** Convenience for mixins. */
	public static Jesus instance() {
		return ModuleManager.on(Jesus.class) ? CoffeeClient.modules().get(Jesus.class) : null;
	}

	@Override
	public String infoString() {
		return waterMode.display();
	}
}
