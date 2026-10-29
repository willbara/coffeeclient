package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Fires bows as soon as they reach a set charge, and shoots loaded crossbows. Aim turns the shot
 * (server side) onto the nearest enemy, with the drop and its movement over the flight worked out;
 * Reload draws an empty crossbow you hold until it is loaded.
 */
public final class BowSpam extends Module {
	public final NumberSetting charge = group(new NumberSetting("Charge", "Ticks to draw the bow before releasing", 5, 4, 20, 1), "Bow");
	public final BooleanSetting onlyHoldingUse = group(new BooleanSetting("Only holding use", "Only while you hold right click", false), "Bow");
	public final BooleanSetting aim = group(new BooleanSetting("Aim", "Turn each shot onto the nearest enemy, allowing for drop and its movement", false), "Aim");
	public final NumberSetting aimRange = group(new NumberSetting("Aim range", "Farthest enemy to aim at", 40, 5, 100, 1), "Aim");
	public final BooleanSetting reload = group(new BooleanSetting("Reload", "Draw an empty crossbow you hold until it is loaded", false), "Crossbows");
	public final BooleanSetting crossbows = group(new BooleanSetting("Spam crossbows", "Shoot loaded crossbows first", true), "Crossbows");
	public final NumberSetting crossbowDelay = group(new NumberSetting("Crossbow delay", "Ticks between crossbow shots", 10, 0, 20, 1), "Crossbows");
	public final BooleanSetting searchInventory = group(new BooleanSetting("Search inventory", "Also use loaded crossbows from the main inventory", true), "Crossbows");

	/** Arrow flight: gravity per tick and drag (AbstractArrow), and a crossbow's arrow speed. */
	private static final double ARROW_GRAVITY = 0.05, ARROW_DRAG = 0.99, CROSSBOW_POWER = 3.15;

	private boolean wasBow, wasHoldingUse, reloading;
	private int ticks;

	public BowSpam() {
		super("BowSpam", List.of("AutoBow", "CrossbowSpam", "FastBow"), Category.COMBAT, "Releases bows at a set charge and fires loaded crossbows.");
		crossbowDelay.visibleWhen(crossbows::get);
		aimRange.visibleWhen(aim::get);
		searchInventory.visibleWhen(crossbows::get);
	}

	@Override
	protected void onEnable() {
		wasBow = wasHoldingUse = false;
	}

	@Override
	protected void onDisable() {
		mc().options.keyUse.setDown(false);
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) return;

		Inv.FindItemResult crossbow = searchInventory.get() ? Inv.find(BowSpam::loadedCrossbow, 0, 35) : Inv.find(BowSpam::loadedCrossbow, 0, 8);
		if (crossbows.get() && crossbow.found()) {
			if (ticks++ < crossbowDelay.intValue()) return;
			int slot = crossbow.slot();
			if (!crossbow.isHotbar()) {
				Inv.FindItemResult spare = Inv.find(stack -> stack.isEmpty() || stack.is(Items.CROSSBOW) || stack.is(Items.ARROW), 0, 8);
				if (!spare.found() || player.containerMenu != player.inventoryMenu) return;
				Inv.quickSwap(Inv.inventoryId(slot), spare.slot());
				slot = spare.slot();
			}
			int fire = slot;
			shoot(CROSSBOW_POWER, () -> {
				Inv.swap(fire, true);
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				Inv.swapBack();
			});
			ticks = 0;
			return;
		}
		ItemStack held = player.getMainHandItem();
		if (reload.get() && held.getItem() instanceof CrossbowItem && !CrossbowItem.isCharged(held) && Inv.find(stack -> stack.getItem() instanceof ArrowItem).found()) {
			// Vanilla stops drawing the moment use is not held, so hold it until loaded.
			boolean loaded = player.isUsingItem() && player.getTicksUsingItem() >= CrossbowItem.getChargeDuration(held, player);
			mc.options.keyUse.setDown(!loaded);
			if (loaded) mc.gameMode.releaseUsingItem(player);
			reloading = true;
			return;
		}
		if (reloading) {
			reloading = false;
			mc.options.keyUse.setDown(false);
		}

		if (!player.getAbilities().instabuild && !Inv.find(stack -> stack.getItem() instanceof ArrowItem).found()) return;
		if (!onlyHoldingUse.get() || mc.options.keyUse.isDown()) {
			boolean isBow = player.getMainHandItem().is(Items.BOW) || player.getOffhandItem().is(Items.BOW);
			if (!isBow && wasBow) mc.options.keyUse.setDown(false);
			wasBow = isBow;
			if (!isBow) return;
			if (player.getTicksUsingItem() >= charge.intValue()) {
				shoot(BowItem.getPowerForTime(player.getTicksUsingItem()) * 3, () -> mc.gameMode.releaseUsingItem(player));
			} else {
				mc.options.keyUse.setDown(true);
				// Keep the look on the target while drawing, so the release is no sudden snap.
				shoot(BowItem.getPowerForTime(player.getTicksUsingItem() + 1) * 3, null);
			}
			wasHoldingUse = mc.options.keyUse.isDown();
		} else if (wasHoldingUse) {
			mc.options.keyUse.setDown(false);
			wasHoldingUse = false;
		}
	}

	/** Runs {@code fire} (may be null) turned onto the aim target, or straight away without Aim or a target. */
	private void shoot(double speed, Runnable fire) {
		Player target = aim.get() ? Targets.playerTarget(aimRange.get(), Targets.SortPriority.LowestDistance) : null;
		Rotations.Rotation look = target == null ? null : Rotations.ballistic(target, speed, ARROW_GRAVITY, ARROW_DRAG);
		if (look != null) Rotations.rotate(look, Rotations.HIGH, fire);
		else if (fire != null) fire.run();
	}

	private static boolean loadedCrossbow(ItemStack stack) {
		return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
	}
}
