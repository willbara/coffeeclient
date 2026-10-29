package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Blows up respawn anchors next to enemies (outside the Nether, where an anchor explodes when used):
 * places one where the blast hurts the target most and you least, then charges it with glowstone and
 * sets it off with any other item in the same click round. Box intercept throws an anchor into the
 * last open side of an enemy walling themselves in, before the wall closes.
 */
public final class AnchorAura extends Module {
	/** Explosion power of a respawn anchor. */
	private static final float POWER = 5;

	public final NumberSetting targetRange = group(new NumberSetting("Target range", "Farthest enemy to fight", 10, 3, 16, 0.5), "Targeting");
	public final EnumSetting<Targets.SortPriority> priority = group(new EnumSetting<>("Priority", "Which enemy in range comes first", Targets.SortPriority.LowestDistance), "Targeting");
	public final NumberSetting minDamage = group(new NumberSetting("Min damage", "Least damage to the target worth an anchor", 6, 0, 36, 0.5), "Damage");
	public final NumberSetting maxSelf = group(new NumberSetting("Max self damage", "Most damage you will take from one anchor", 8, 0, 36, 0.5), "Damage");
	public final BooleanSetting antiSuicide = group(new BooleanSetting("Anti suicide", "Never set off an anchor that would kill you", true), "Damage");
	public final BooleanSetting boxIntercept = group(new BooleanSetting("Box intercept", "When an enemy has walled in three sides, put an anchor in the last one before they close it", true), "Damage");
	public final NumberSetting placeRange = group(new NumberSetting("Place range", "Farthest an anchor is placed or used", 4.5, 1, 6, 0.1), "Place");
	public final NumberSetting placeDelay = group(new NumberSetting("Place delay", "Ticks between anchor placements", 0, 0, 20, 1), "Place");
	public final NumberSetting breakDelay = group(new NumberSetting("Break delay", "Ticks between detonations", 0, 0, 20, 1), "Place");
	public final BooleanSetting rotate = group(new BooleanSetting("Rotate", "Face the block before clicking it", true), "Place");
	public final BooleanSetting swing = group(new BooleanSetting("Swing", "Swing your hand when clicking", true), "Place");
	public final BooleanSetting pauseOnUse = group(new BooleanSetting("Pause on use", "Wait while you eat or use an item", true), "Place");
	public final BooleanSetting pauseOnCrystalAura = group(new BooleanSetting("Pause on CrystalAura", "Wait while CrystalAura is placing or breaking", true), "Place");
	public final BooleanSetting render = group(new BooleanSetting("Render", "Box the anchor being used", true), "Render");
	public final ColorSetting color = group(new ColorSetting("Color", "Box colour", 0xFFFF9020), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "Box style", WorldOverlays.Shape.Both), "Render");

	private Player target;
	private BlockPos anchor;
	private int placeTimer, breakTimer;

	public AnchorAura() {
		super("AnchorAura", List.of("Anchor Aura", "AutoAnchor"), Category.COMBAT, "Blows up respawn anchors next to enemies.");
		for (Setting<?> s : List.of(color, shape)) s.visibleWhen(render::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		if (mc().level != null && mc().level.dimension() == Level.NETHER) {
			info("Anchors don't explode in the Nether");
			setEnabled(false);
		}
	}

	@Override
	protected void onDisable() {
		target = null;
		anchor = null;
		placeTimer = breakTimer = 0;
		CombatTick.release(this);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null || mc().gameMode == null) return;
		if (placeTimer > 0) placeTimer--;
		if (breakTimer > 0) breakTimer--;
		target = Targets.playerTarget(targetRange.get(), priority.get());
		anchor = null;
		if (target == null || pauseOnUse.get() && player.isUsingItem() || CombatTick.lagging()
			|| pauseOnCrystalAura.get() && CombatTick.busy(CombatTick.Kind.CRYSTAL, this)) return;

		Inv.FindItemResult glowstone = Inv.findInHotbar(s -> s.is(Items.GLOWSTONE));
		Inv.FindItemResult anchors = Inv.findInHotbar(s -> s.is(Items.RESPAWN_ANCHOR));
		// Detonating takes a main-hand item that isn't glowstone (glowstone would only charge it further).
		Inv.FindItemResult trigger = Inv.find(s -> !s.is(Items.GLOWSTONE), 0, 8);
		if (!glowstone.found() || !trigger.found()) return;

		BlockPos gap = boxIntercept.get() && anchors.found() && placeTimer <= 0 ? boxGap(player, level) : null;
		if (gap != null && BlockActions.place(gap, anchors, rotate.get(), Rotations.HIGH, swing.get(), true, true)) {
			anchor = gap;
			CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 2);
			placeTimer = placeDelay.intValue();
			return;
		}
		anchor = best(player, level);
		if (anchor == null) return;
		BlockState state = level.getBlockState(anchor);
		if (state.is(Blocks.RESPAWN_ANCHOR)) {
			if (breakTimer <= 0) detonate(player, state, glowstone, trigger);
		} else if (placeTimer <= 0 && anchors.found() && BlockActions.place(anchor, anchors, rotate.get(), Rotations.HIGH, swing.get(), true, true)) {
			CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 2);
			placeTimer = placeDelay.intValue();
		}
	}

	/** Charges the anchor if it is empty and sets it off, both in one click round on the same face. */
	private void detonate(LocalPlayer player, BlockState state, Inv.FindItemResult glowstone, Inv.FindItemResult trigger) {
		BlockActions.BreakParams face = BlockActions.breakParams(player.getEyePosition(), anchor);
		if (face == null) return;
		BlockHitResult hit = face.hitResult();
		boolean charged = state.getValue(RespawnAnchorBlock.CHARGE) > 0;
		Runnable click = () -> PacketBatch.run(() -> {
			if (!charged) Inv.withItem(glowstone.slot(), true, () -> BlockActions.interact(hit, InteractionHand.MAIN_HAND, swing.get()));
			Inv.withItem(trigger.slot(), true, () -> BlockActions.interact(hit, InteractionHand.MAIN_HAND, swing.get()));
		});
		if (rotate.get()) {
			if (!Rotations.rotate(hit.getLocation(), Rotations.HIGH, click)) return;
		} else {
			click.run();
		}
		CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 2);
		breakTimer = breakDelay.intValue();
	}

	/** The one open side of a target walling themselves in, if an anchor there is in reach and safe; else null. */
	private BlockPos boxGap(LocalPlayer player, ClientLevel level) {
		BlockPos feet = target.blockPosition(), gap = null;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (!level.getBlockState(feet.relative(side)).canBeReplaced()) continue;
			if (gap != null) return null;
			gap = feet.relative(side);
		}
		if (gap == null || BlockActions.placeParams(gap) == null || !BlockActions.canPlace(gap, Blocks.RESPAWN_ANCHOR, true)) return null;
		Vec3 center = Vec3.atCenterOf(gap);
		if (player.getEyePosition().distanceToSqr(center) > placeRange.get() * placeRange.get()) return null;
		float self = Damage.mitigate(player, Damage.explosionRaw(player, player.position(), player.getBoundingBox(), center, POWER,
			new Damage.View(level, gap, Blocks.AIR.defaultBlockState())));
		return self > maxSelf.get() || antiSuicide.get() && self >= Damage.health(player) ? null : gap;
	}

	/** The best cell for an anchor near the target (an anchor already there counts), or null. */
	private BlockPos best(LocalPlayer player, ClientLevel level) {
		Vec3 eyes = player.getEyePosition();
		float health = Damage.health(player);
		BlockPos feet = target.blockPosition();
		BlockPos best = null;
		float bestDamage = 0;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 2, 2))) {
			Vec3 center = Vec3.atCenterOf(pos);
			if (eyes.distanceToSqr(center) > placeRange.get() * placeRange.get()) continue;
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.RESPAWN_ANCHOR) && (!BlockActions.canPlace(pos, Blocks.RESPAWN_ANCHOR, true) || BlockActions.placeParams(pos) == null)) continue;
			// The anchor is removed before it explodes, so its own cell doesn't shield anyone.
			Damage.View view = new Damage.View(level, pos, Blocks.AIR.defaultBlockState());
			float self = Damage.mitigate(player, Damage.explosionRaw(player, player.position(), player.getBoundingBox(), center, POWER, view));
			if (self > maxSelf.get() || antiSuicide.get() && self >= health) continue;
			float damage = Damage.mitigate(target, Damage.explosionRaw(target, target.position(), target.getBoundingBox(), center, POWER, view));
			if (damage < minDamage.get() && damage < Damage.health(target)) continue;
			if (damage > bestDamage) {
				best = pos.immutable();
				bestDamage = damage;
			}
		}
		return best;
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (!render.get() || anchor == null) return;
		EspRender.boxes(List.of(new AABB(anchor)), new int[] {color.argb() & 0xFFFFFF}, shape.get(), false, 1.5f, (color.argb() >>> 24) / 3, cameraPos);
	}

	@Override
	public String infoString() {
		return target == null ? null : target.getName().getString();
	}
}
