package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

/** Fall down blocks faster (used for bed traps and crystal PvP). */
public final class ReverseStep extends Module {
	public final NumberSetting fallSpeed = setting(new NumberSetting("Fall speed", "How fast to drop in blocks per tick", 3, 0, 20, 0.1));
	public final NumberSetting fallDistance = setting(new NumberSetting("Fall distance", "Maximum gap that still snaps you down", 3, 0, 10, 0.5));
	public final BooleanSetting vehicles = setting(new BooleanSetting("Vehicles", "Also affect the vehicle you are riding", false));

	public ReverseStep() {
		super("ReverseStep", Category.MOVEMENT, "Allows you to fall down blocks at a greater speed.");
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null) return;

		Entity vehicle = player.getVehicle();
		if (vehicle != null && vehicles.get()) {
			if (canSnap(vehicle)) snap(vehicle);
			return;
		}

		if (player.isSuppressingSlidingDownLadder() || (player.zza == 0 && player.xxa == 0)) return;
		if (!isOnBed() && canSnap(player)) snap(player);
	}

	private void snap(Entity entity) {
		Vec3 velocity = entity.getDeltaMovement();
		entity.setDeltaMovement(velocity.x, -fallSpeed.get(), velocity.z);
	}

	private boolean canSnap(Entity entity) {
		if (!entity.onGround() || entity.isUnderWater() || entity.isInLava()
			|| mc().options.keyJump.isDown() || entity.noPhysics) return false;
		return !mc().level.noCollision(entity.getBoundingBox().move(0, -(fallDistance.get() + 0.01), 0));
	}

	private boolean isOnBed() {
		var player = mc().player;
		BlockPos.MutableBlockPos pos = player.blockPosition().mutable();
		if (check(pos, 0, 0)) return true;

		double xa = player.getX() - pos.getX();
		double za = player.getZ() - pos.getZ();

		if (xa >= 0 && xa <= 0.3 && check(pos, -1, 0)) return true;
		if (xa >= 0.7 && check(pos, 1, 0)) return true;
		if (za >= 0 && za <= 0.3 && check(pos, 0, -1)) return true;
		if (za >= 0.7 && check(pos, 0, 1)) return true;

		if (xa >= 0 && xa <= 0.3 && za >= 0 && za <= 0.3 && check(pos, -1, -1)) return true;
		if (xa >= 0 && xa <= 0.3 && za >= 0.7 && check(pos, -1, 1)) return true;
		if (xa >= 0.7 && za >= 0 && za <= 0.3 && check(pos, 1, -1)) return true;
		return xa >= 0.7 && za >= 0.7 && check(pos, 1, 1);
	}

	private boolean check(BlockPos.MutableBlockPos pos, int x, int z) {
		pos.move(x, 0, z);
		boolean result = mc().level.getBlockState(pos).getBlock() instanceof BedBlock;
		pos.move(-x, 0, -z);
		return result;
	}
}
