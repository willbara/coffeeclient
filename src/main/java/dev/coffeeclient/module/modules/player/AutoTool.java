package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Switches to the fastest hotbar tool when you start breaking a block, and back afterwards. Can prefer
 * Silk Touch, and put away a held tool or weapon that is about to break.
 */
public final class AutoTool extends Module {
	public final BooleanSetting switchBack = setting(new BooleanSetting("Switch back", "Return to the previous slot when you stop mining", true));
	public final NumberSetting minDurability = setting(new NumberSetting("Protect below", "Skip tools with this few uses left (0 = never skip)", 5, 0, 100, 1));
	public final BooleanSetting preferCorrect = setting(new BooleanSetting("Require drops", "Prefer tools that make the block drop", true));
	public final BooleanSetting notSneaking = setting(new BooleanSetting("Off while sneaking", "Hold sneak to mine with your current item", false));
	public final BooleanSetting preferSilk = setting(new BooleanSetting("Prefer Silk Touch", "Pick a Silk Touch tool when it can mine the block", false));
	public final BooleanSetting useSwords = setting(new BooleanSetting("Use swords", "Let swords be picked (they are fastest on cobwebs and bamboo)", true));
	public final BooleanSetting useHands = setting(new BooleanSetting("Use hands", "Switch to an empty slot when no tool beats your bare hand, so tools keep their durability", false));
	public final BooleanSetting fromInventory = setting(new BooleanSetting("From inventory", "Pull a better tool out of the main inventory into the held slot, and put it back after", false));
	public final NumberSetting returnDelay = setting(new NumberSetting("Return delay", "Idle ticks before a pulled tool goes back", 10, 1, 100, 1));
	public final BlockListSetting ignored = setting(new BlockListSetting("Ignored blocks", "Never switch tools for these", List.of()));
	public final BooleanSetting pauseCombat = setting(new BooleanSetting("Pause in combat", "Leave your item alone while KillAura or CrystalAura is busy", true));
	public final BooleanSetting putAwayWorn = setting(new BooleanSetting("Put away worn items", "When the held tool or weapon reaches Protect below, swap to a spare (or an empty slot)", false));

	private int previousSlot = -1;
	private int idleTicks;
	/** A tool pulled from the main inventory: where it came from, and into which hotbar slot. */
	private int pulledFrom = -1, pulledInto = -1;

	public AutoTool() {
		super("AutoTool", Category.PLAYER, "Picks the best tool for the block you mine.");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackBlock.class, e -> {
			var mc = mc();
			if (mc.player == null || mc.level == null) return;
			if (notSneaking.get() && mc.player.isShiftKeyDown()) return;
			if (pauseCombat.get() && CombatTick.inCombat()) return;
			BlockState state = mc.level.getBlockState(e.pos());
			if (state.isAir() || ignored.has(state.getBlock())) return;
			var inv = mc.player.getInventory();
			int current = inv.getSelectedSlot();
			int best = current;
			double bestScore = score(inv.getItem(current), state);
			for (int i = 0; i < 9; i++) {
				double s = score(inv.getItem(i), state);
				if (s > bestScore + 0.001) {
					bestScore = s;
					best = i;
				}
			}
			if (useHands.get() && inv.getItem(current).isDamageableItem() && bestScore <= score(ItemStack.EMPTY, state) + 0.001) {
				int hand = emptyHotbarSlot(inv, current);
				if (hand >= 0) best = hand;
			}
			if (fromInventory.get() && pulledFrom < 0 && mc.player.containerMenu == mc.player.inventoryMenu) {
				for (int i = 9; i < 36; i++) {
					double s = score(inv.getItem(i), state);
					if (s > bestScore + 0.001) {
						bestScore = s;
						pulledFrom = i;
					}
				}
				if (pulledFrom >= 0) {
					// Swapped into the slot the best hotbar choice would have used, so only one item moves.
					pulledInto = best;
					Inv.move(Inv.inventoryId(pulledFrom), Inv.inventoryId(pulledInto));
				}
			}
			if (best != current) {
				if (previousSlot < 0) previousSlot = current;
				// Synced at once, so the dig packet that follows is already credited to the tool.
				Inv.swap(best, false);
			}
			idleTicks = 0;
		});
	}

	/** The held slot if it is empty, else the first empty hotbar slot, else -1. */
	private static int emptyHotbarSlot(net.minecraft.world.entity.player.Inventory inv, int current) {
		if (inv.getItem(current).isEmpty()) return current;
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	private double score(ItemStack stack, BlockState state) {
		if (worn(stack)) return -1;
		if (!useSwords.get() && stack.is(ItemTags.SWORDS)) return -1;
		double speed = Inv.toolScore(stack, state);
		if (preferCorrect.get() && state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state)) speed += 1000;
		if (preferSilk.get() && speed > 1 && EnchantmentIds.level(stack, Enchantments.SILK_TOUCH) > 0) speed += 500;
		return speed;
	}

	private boolean worn(ItemStack stack) {
		return stack.isDamageableItem() && minDurability.get() > 0 && stack.getMaxDamage() - stack.getDamageValue() <= minDurability.get();
	}

	/** Swaps a worn held item for a fresh one of the same kind, else for an empty or non-breakable slot. */
	private void putAwayWorn() {
		var inv = mc().player.getInventory();
		int current = inv.getSelectedSlot();
		ItemStack held = inv.getItem(current);
		if (!worn(held)) return;
		int spare = -1, empty = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (i == current || worn(stack)) continue;
			if (stack.is(held.getItem())) {
				spare = i;
				break;
			}
			if (empty < 0 && !stack.isDamageableItem()) empty = i;
		}
		int target = spare >= 0 ? spare : empty;
		if (target < 0) return;
		Inv.swap(target, false);
		previousSlot = -1;
		info("Put away " + held.getHoverName().getString() + " (" + (held.getMaxDamage() - held.getDamageValue()) + " uses left)");
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (putAwayWorn.get() && mc.player != null && mc.gui.screen() == null) putAwayWorn();
		if (previousSlot < 0 && pulledFrom < 0 || mc.player == null || mc.gameMode == null) return;
		if (mc.gameMode.isDestroying()) {
			idleTicks = 0;
			return;
		}
		++idleTicks;
		if (pulledFrom >= 0 && idleTicks > returnDelay.intValue() && mc.player.containerMenu == mc.player.inventoryMenu) {
			Inv.move(Inv.inventoryId(pulledInto), Inv.inventoryId(pulledFrom));
			pulledFrom = pulledInto = -1;
		}
		if (previousSlot >= 0 && idleTicks > 4) {
			if (switchBack.get()) Inv.swap(previousSlot, false);
			previousSlot = -1;
		}
	}
}
