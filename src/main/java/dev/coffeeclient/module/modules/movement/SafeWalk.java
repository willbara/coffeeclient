package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Stops you from walking off the edge of a block. */
public final class SafeWalk extends Module {
	public final NumberSetting fallDistance = group(new NumberSetting("Minimum fall distance", "Only activate when you would fall at least this far", 1, 1, 256, 1), "General");
	public final BooleanSetting sneak = group(new BooleanSetting("Sneak", "Sneak when approaching the edge of a block", false), "General");
	public final BooleanSetting safeSneak = group(new BooleanSetting("Safe sneak", "Clip anyway when sneak does not trigger", true), "General");
	public final BooleanSetting sneakSprint = group(new BooleanSetting("Sneak on sprint", "Sneak even when sprinting at the edge", true), "General");
	public final NumberSetting edgeDistance = group(new NumberSetting("Edge distance", "Distance offset before reaching an edge", 0.30, 0, 0.30, 0.01), "General");

	public SafeWalk() {
		super("SafeWalk", Category.MOVEMENT, "Prevents you from walking off blocks.");
		safeSneak.visibleWhen(sneak::get);
		sneakSprint.visibleWhen(sneak::get);
		edgeDistance.visibleWhen(sneak::get);
		listen(Events.ClipAtLedge.class, e -> {
			if (shouldClip()) e.clip = true;
		});
	}

	/** Asked at the head of {@code isStayingOnGroundSurface}. */
	private boolean shouldClip() {
		var player = mc().player;
		var level = mc().level;
		if (player == null || level == null) return false;

		if (fallDistance.get() > 1) {
			int surface = level.getChunkAt(player.blockPosition())
				.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING)
				.getFirstAvailable(player.getBlockX() & 15, player.getBlockZ() & 15);

			if (player.getBlockY() >= surface) {
				if (player.getBlockY() - surface < fallDistance.get()) return false;
			} else {
				BlockHitResult hit = level.clip(new ClipContext(player.position(),
					new Vec3(player.getX(), level.getMinY(), player.getZ()),
					ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, player));
				if (hit.getType() != HitResult.Type.MISS && (int) (player.getY() - hit.getBlockPos().above().getY()) < fallDistance.get()) return false;
			}
		}

		if (sneak.get()) {
			boolean isSprinting = !sneakSprint.get() && mc().options.keySprint.isDown();
			AABB adjusted = player.getBoundingBox()
				.expandTowards(0, -player.maxUpStep(), 0)
				.inflate(-edgeDistance.get(), 0, -edgeDistance.get());
			boolean closeToEdge = level.noCollision(player, adjusted) && player.onGround();

			if (!isSprinting) {
				if (closeToEdge) {
					Input keys = player.input.keyPresses;
					player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
				} else if (safeSneak.get()) {
					return true;
				}
			}
			return false;
		}
		return !player.isShiftKeyDown();
	}
}
