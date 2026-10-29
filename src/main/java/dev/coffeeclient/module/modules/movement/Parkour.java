package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.world.phys.AABB;

/** Jumps automatically at the edge of a block. */
public final class Parkour extends Module {
	public final NumberSetting edgeDistance = setting(new NumberSetting("Edge distance", "How far from the edge to jump", 0.001, 0.001, 0.1, 0.001));

	public final NumberSetting minDepth = setting(new NumberSetting("Min depth", "Only jump when the drop is at least this many blocks (1.5 skips one-block steps down)", 0.5, 0.5, 10, 0.5));
	public final BooleanSetting jumpSneaking = setting(new BooleanSetting("Jump while sneaking", "Keep jumping at edges while you sneak", false));

	public Parkour() {
		super("Parkour", Category.MOVEMENT, "Automatically jumps at the edges of blocks.");
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null || mc().level == null) return;
		if (!player.onGround() || mc().options.keyJump.isDown()) return;
		if (!jumpSneaking.get() && (player.isShiftKeyDown() || mc().options.keyShift.isDown())) return;

		AABB box = player.getBoundingBox().move(0, -0.5, 0).inflate(-edgeDistance.get(), 0, -edgeDistance.get());
		if (minDepth.get() > 0.5) box = box.expandTowards(0, 0.5 - minDepth.get(), 0);
		if (mc().level.getBlockCollisions(player, box).iterator().hasNext()) return;

		player.jumpFromGround();
	}
}
