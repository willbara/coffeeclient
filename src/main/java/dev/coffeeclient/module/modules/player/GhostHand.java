package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.BlockActions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** Opens containers through walls. */
public final class GhostHand extends Module {
	private final Set<BlockPos> posList = new HashSet<>();

	public GhostHand() {
		super("GhostHand", Category.PLAYER, "Opens containers through walls.");
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) return;
		if (!mc().options.keyUse.isDown() || mc().player.isShiftKeyDown()) return;

		double r = mc().player.blockInteractionRange();
		var hr = mc().player.pick(r, mc().getDeltaTracker().getGameTimeDeltaPartialTick(true), false);
		BlockPos target = BlockPos.containing(hr.getLocation());
		if (mc().level.getBlockState(target).hasBlockEntity()) return;

		Vec3 direction = new Vec3(0, 0, 0.1)
			.xRot(-(float) Math.toRadians(mc().player.getXRot()))
			.yRot(-(float) Math.toRadians(mc().player.getYRot()));

		posList.clear();

		for (int i = 1; i < r * 10; i++) {
			BlockPos pos = BlockPos.containing(mc().player.getEyePosition(mc().getDeltaTracker().getGameTimeDeltaPartialTick(true)).add(direction.scale(i)));

			if (posList.contains(pos)) continue;
			posList.add(pos);

			if (mc().level.getBlockState(pos).hasBlockEntity()) {
				for (InteractionHand hand : InteractionHand.values()) {
					InteractionResult result = BlockActions.interact(
						new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), Direction.UP, pos, true), hand, true);
					if (result instanceof InteractionResult.Success || result instanceof InteractionResult.Fail) return;
				}
			}
		}
	}
}
