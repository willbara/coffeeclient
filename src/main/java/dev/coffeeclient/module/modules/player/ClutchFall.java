package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MoveSim;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cushions a long fall just before landing: a water bucket (picked up again afterwards; never tried
 * where water boils away), a powder snow bucket, or a slime block, hay bale, cobweb, scaffolding or
 * twisting vines placed where the simulated fall will land, aimed there rather than straight down.
 */
public final class ClutchFall extends Module {
	public final NumberSetting minFall = setting(new NumberSetting("Min fall", "Only act once you have fallen this many blocks", 5, 3, 30, 1));
	public final NumberSetting range = setting(new NumberSetting("Range", "Act when the ground is this many blocks below your eyes (capped by your reach)", 4, 2, 4.5, 0.5));
	public final BooleanSetting water = setting(new BooleanSetting("Water bucket", "Place water", true));
	public final BooleanSetting powderSnow = setting(new BooleanSetting("Powder snow", "Use a powder snow bucket when there is no water bucket", true));
	public final BooleanSetting blocks = setting(new BooleanSetting("Blocks", "Place a slime block, hay bale, cobweb, scaffolding or twisting vines when there is no bucket", true));
	public final BooleanSetting pickUp = setting(new BooleanSetting("Pick up water", "Scoop the water back up after landing", true));

	/** A cushion worked or was tried this fall; cleared on landing. */
	private boolean placed, bucketFailed;
	/** Where the water was placed while it waits to be confirmed and scooped up, and how long it has waited. */
	private BlockPos waterAt;
	private int wait;

	public ClutchFall() {
		super("ClutchFall", List.of("Clutch Fall", "MLG"), Category.PLAYER, "Places water or a soft block under you just before you hit the ground.");
	}

	@Override
	protected void onDisable() {
		placed = bucketFailed = false;
		waterAt = null;
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null || mc().gameMode == null || mc().level == null) return;
		if (waterAt != null && !checkWater(p)) return;
		if (p.onGround() || p.isInWater()) {
			placed = bucketFailed = false;
			return;
		}
		if (placed || p.fallDistance < minFall.get() || p.isFallFlying() || p.getAbilities().flying || p.isCreative() || p.isSpectator()
			|| p.hasEffect(MobEffects.SLOW_FALLING) || p.getDeltaMovement().y > -0.5) return;
		// Where the fall actually lands; water, webs and ladders on the way already catch it.
		MoveSim.Result fall = MoveSim.run(p, p.getBoundingBox(), p.getDeltaMovement(), false, 60);
		if (fall.stop() != MoveSim.Stop.LANDED) return;
		BlockPos ground = fall.below();
		// Measured from the eyes, because that is where the bucket and the block reach start.
		Vec3 eye = p.getEyePosition(), top = Vec3.atCenterOf(ground).add(0, 0.5, 0);
		double reach = Math.min(range.get(), p.blockInteractionRange());
		if (eye.distanceToSqr(top) > reach * reach) return;

		boolean boils = mc().level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, ground.above());
		var bucket = water.get() && !bucketFailed && !boils ? Inv.findInHotbar(s -> s.is(Items.WATER_BUCKET)) : Inv.FindItemResult.NONE;
		if (!bucket.found() && powderSnow.get()) bucket = Inv.findInHotbar(s -> s.is(Items.POWDER_SNOW_BUCKET));
		if (bucket.found()) {
			var use = bucket;
			boolean isWater = mc().player.getInventory().getItem(use.slot()).is(Items.WATER_BUCKET);
			if (Rotations.rotate(top, Rotations.NORMAL, () -> Inv.use(use))) {
				if (isWater) {
					waterAt = ground.above();
					wait = 0;
				} else {
					placed = true;
				}
			}
			return;
		}
		var soft = blocks.get() ? Inv.findInHotbar(Inv.anyOf(Items.SLIME_BLOCK, Items.HAY_BLOCK, Items.COBWEB, Items.SCAFFOLDING, Items.TWISTING_VINES)) : Inv.FindItemResult.NONE;
		if (soft.found() && BlockActions.place(ground.above(), soft, true, Rotations.NORMAL, true, false, true)) placed = true;
	}

	/**
	 * Follows the water placed at {@link #waterAt}: scoops it up after landing, or gives up on the bucket
	 * (the Nether dries it, a miss leaves nothing) so a soft block can be tried.
	 *
	 * @return true when the normal fall check should carry on
	 */
	private boolean checkWater(LocalPlayer p) {
		if (++wait > 100) {
			waterAt = null;
			return true;
		}
		if (mc().level.getFluidState(waterAt).is(Fluids.WATER) && mc().level.getFluidState(waterAt).isSource()) {
			placed = true;
			if (!pickUp.get()) waterAt = null;
			// Only this water is scooped, so an empty bucket is never aimed at lava.
			else if (p.fallDistance == 0 && Rotations.rotate(p.getYRot(), 90, Rotations.NORMAL, () -> Inv.use(Inv.findInHotbar(s -> s.is(Items.BUCKET))))) waterAt = null;
			return false;
		}
		if (wait <= 10 && !p.onGround()) return false;
		waterAt = null;
		bucketFailed = true;
		return true;
	}
}
