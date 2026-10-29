package dev.coffeeclient.util;

import dev.coffeeclient.mixin.MultiPlayerGameModeAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Inventory helpers: finding items, switching the held slot (synced to the server at once), and
 * cursor-based clicks for modules that reorder the player's own inventory.
 *
 * <p>Two index spaces are used. <b>Indexes</b> are {@code Inventory} indexes: 0-8 hotbar, 9-35 main,
 * 36-39 armour, 40 offhand ({@link FindItemResult#slot()} and {@link #get} use these). <b>Slot ids</b>
 * follow the vanilla {@code InventoryMenu}: 5-8 armour (helmet..boots), 9-35 main inventory, 36-44
 * hotbar, 45 offhand; the click methods take these and are only valid while no other container is
 * open. {@link #inventoryId} converts.
 *
 * <p>Finding and swapping follow {@code InvUtils} (result type, swap with swap-back, immediate
 * slot sync); {@link #select} adds fallback of pulling an item from the main inventory.
 */
public final class Inv {
	/** Index space of {@code Inventory.getNonEquipmentItems()} plus the offhand. */
	public static final int OFFHAND_INDEX = 40;

	/** Slot to return to after {@link #swap swap(slot, true)}; -1 when no swap-back is pending. */
	private static int previousSlot = -1;

	private Inv() {
	}

	/**
	 * Where a search found an item: the first matching {@code Inventory} index ({@code -1} = none) and
	 * the total count of matching items in the searched range.
	 */
	public record FindItemResult(int slot, int count) {
		public static final FindItemResult NONE = new FindItemResult(-1, 0);

		public boolean found() {
			return slot >= 0;
		}

		public boolean isHotbar() {
			return slot >= 0 && slot < 9;
		}

		public boolean isMain() {
			return slot >= 9 && slot < 36;
		}

		public boolean isOffhand() {
			return slot == OFFHAND_INDEX;
		}

		/** Held in the main hand right now. */
		public boolean isMainHand() {
			LocalPlayer player = Minecraft.getInstance().player;
			return player != null && slot == player.getInventory().getSelectedSlot();
		}

		/** The hand holding the item now, or null when it would need a swap first. */
		public InteractionHand hand() {
			if (isOffhand()) return InteractionHand.OFF_HAND;
			return isMainHand() ? InteractionHand.MAIN_HAND : null;
		}
	}

	// ---- finding ----

	public static Predicate<ItemStack> anyOf(Item... items) {
		return stack -> {
			for (Item item : items) if (stack.is(item)) return true;
			return false;
		};
	}

	/** Searches the whole inventory (hotbar, main, armour, offhand). */
	public static FindItemResult find(Predicate<ItemStack> match) {
		return find(match, 0, OFFHAND_INDEX);
	}

	/** Searches {@code Inventory} indexes {@code start..end} inclusive. */
	public static FindItemResult find(Predicate<ItemStack> match, int start, int end) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return FindItemResult.NONE;
		Inventory inventory = player.getInventory();
		int slot = -1, count = 0;
		for (int i = Math.max(0, start), last = Math.min(end, inventory.getContainerSize() - 1); i <= last; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!match.test(stack)) continue;
			if (slot < 0) slot = i;
			count += stack.getCount();
		}
		return new FindItemResult(slot, count);
	}

	/** The offhand, then the held slot, then the rest of the hotbar: the places usable without moving items. */
	public static FindItemResult findInHotbar(Predicate<ItemStack> match) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return FindItemResult.NONE;
		ItemStack offhand = player.getOffhandItem();
		if (match.test(offhand)) return new FindItemResult(OFFHAND_INDEX, offhand.getCount());
		ItemStack held = player.getMainHandItem();
		if (match.test(held)) return new FindItemResult(player.getInventory().getSelectedSlot(), held.getCount());
		return find(match, 0, 8);
	}

	/**
	 * How fast {@code stack} breaks {@code state}: its base speed plus Efficiency (level squared plus
	 * one, as vanilla adds it when the tool works on the block). Haste, fatigue and water scale every
	 * tool alike, so they don't change which one wins.
	 */
	public static double toolScore(ItemStack stack, BlockState state) {
		double speed = stack.getDestroySpeed(state);
		if (speed > 1) {
			int efficiency = EnchantmentIds.level(stack, net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
			if (efficiency > 0) speed += efficiency * efficiency + 1;
		}
		return speed;
	}

	/** The hotbar slot that breaks {@code state} fastest, the right tool for its drops first. */
	public static int bestTool(BlockState state) {
		Inventory inventory = Minecraft.getInstance().player.getInventory();
		int best = inventory.getSelectedSlot();
		double bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getItem(i);
			double score = toolScore(stack, state) + (state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state) ? 1000 : 0);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	// ---- held slot ----

	/**
	 * Selects hotbar slot {@code slot} and tells the server straight away. With {@code swapBack} the
	 * slot held before the first such swap is remembered for {@link #swapBack()}. The offhand needs no
	 * swap and returns true.
	 */
	public static boolean swap(int slot, boolean swapBack) {
		if (slot == OFFHAND_INDEX) return true;
		LocalPlayer player = Minecraft.getInstance().player;
		if (slot < 0 || slot > 8 || player == null) return false;
		Inventory inventory = player.getInventory();
		if (swapBack) {
			if (previousSlot < 0) previousSlot = inventory.getSelectedSlot();
		} else {
			previousSlot = -1;
		}
		inventory.setSelectedSlot(slot);
		syncSelectedSlot();
		return true;
	}

	/** Returns to the slot held before {@link #swap swap(slot, true)}; false when nothing was pending. */
	public static boolean swapBack() {
		if (previousSlot < 0) return false;
		int slot = previousSlot;
		previousSlot = -1;
		return swap(slot, false);
	}

	/** Uses the item at a hotbar/offhand {@code item} once (throw, bucket), selecting and restoring its slot. */
	public static void use(FindItemResult item) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null || !item.found()) return;
		boolean off = item.slot() == OFFHAND_INDEX;
		swap(item.slot(), true);
		mc.gameMode.useItem(mc.player, off ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		swapBack();
	}

	/** Sends the selected hotbar slot now if the server does not have it yet (vanilla waits for the next action). */
	public static void syncSelectedSlot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null) ((MultiPlayerGameModeAccessor) mc.gameMode).coffeeclient$syncSelectedSlot();
	}

	/**
	 * Gets a matching item into the main hand: selects it if it is in the hotbar, otherwise pulls it
	 * from the main inventory (to an empty hotbar slot, else swapped into the held slot), after
	 * {@code selectItem}. Pulling needs the inventory menu (no other container open).
	 *
	 * @return true when the item is in the main hand now
	 */
	public static boolean select(Predicate<ItemStack> match) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		if (match.test(player.getMainHandItem())) return true;
		FindItemResult hotbar = find(match, 0, 8);
		if (hotbar.found()) return swap(hotbar.slot(), false);
		FindItemResult main = find(match, 9, 35);
		if (!main.found() || player.containerMenu != player.inventoryMenu) return false;
		int free = player.getInventory().getFreeSlot();
		if (free >= 0 && free < 9) {
			// Quick-move from the main inventory fills the first empty hotbar slot; the client predicts
			// the move, so it can be selected right away when it landed where expected.
			quickMove(inventoryId(main.slot()));
			return match.test(player.getInventory().getItem(free)) && swap(free, false);
		}
		quickSwap(inventoryId(main.slot()), player.getInventory().getSelectedSlot());
		return match.test(player.getMainHandItem());
	}

	// ---- clicks (slot ids) ----

	/** Menu id of an armour slot from its {@link EquipmentSlot} index (0 = feet .. 3 = head). */
	public static int armorId(EquipmentSlot slot) {
		return 5 + (3 - slot.getIndex());
	}

	/** Menu id of an inventory index (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand). */
	public static int inventoryId(int index) {
		if (index < 9) return 36 + index;
		if (index < 36) return index;
		if (index < 40) return 5 + (index - 36);
		return 45;
	}

	public static void click(int slotId, int button, ContainerInput input) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) return;
		mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, input, mc.player);
	}

	private static boolean carriedEmpty() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.containerMenu.getCarried().isEmpty();
	}

	/** Moves (or swaps) the stack in {@code fromId} into {@code toId} using the cursor, in one packet flush. */
	public static void move(int fromId, int toId) {
		if (fromId < 0 || toId < 0 || fromId == toId) return;

		PacketBatch.run(() -> {
			boolean hadEmptyCursor = carriedEmpty();
			click(fromId, 0, ContainerInput.PICKUP);
			click(toId, 0, ContainerInput.PICKUP);
			// Leftovers on the cursor go back where they came from.
			if (hadEmptyCursor && !carriedEmpty()) click(fromId, 0, ContainerInput.PICKUP);
		});
	}

	// ---- any open screen ----

	/** How {@link #swapWithOffhand} reached the server. */
	public enum OffhandSwap {
		/** It could not (the item is not reachable now). */
		NONE,
		/** A swap click in the open menu: the server answers with slot updates. */
		CLICK,
		/** The swap-hands key: needs no menu, so it works with any screen open. */
		HANDS
	}

	/** The id of inventory index {@code index} in {@code menu} (chests and most screens include your inventory), or -1. */
	public static int menuSlot(AbstractContainerMenu menu, int index) {
		Inventory inventory = Minecraft.getInstance().player.getInventory();
		for (Slot slot : menu.slots) {
			if (slot.container == inventory && slot.getContainerSlot() == index) return slot.index;
		}
		return -1;
	}

	/**
	 * Swaps inventory index {@code index} with the offhand under whatever screen is open: a swap click
	 * when the open menu shows the slot, otherwise (hotbar only) the swap-hands key on that slot, which
	 * no screen blocks. The client copy is updated at once.
	 */
	public static OffhandSwap swapWithOffhand(int index) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		AbstractContainerMenu menu = player.containerMenu;
		int id = menuSlot(menu, index);
		if (id != -1) {
			mc.gameMode.handleContainerInput(menu.containerId, id, OFFHAND_INDEX, ContainerInput.SWAP, player);
			return OffhandSwap.CLICK;
		}
		if (index < 0 || index > 8 || player.isUsingItem()) return OffhandSwap.NONE;

		Inventory inventory = player.getInventory();
		int selected = inventory.getSelectedSlot();
		PacketBatch.run(() -> {
			if (selected != index) selectNow(index);
			player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
			if (selected != index) selectNow(selected);
		});
		ItemStack held = inventory.getItem(index);
		inventory.setItem(index, inventory.getItem(OFFHAND_INDEX));
		inventory.setItem(OFFHAND_INDEX, held);
		return OffhandSwap.HANDS;
	}

	/** Swaps inventory index {@code index} with hotbar slot {@code hotbar} in the open menu; false when the menu lacks the slot. */
	public static boolean swapWithHotbar(int index, int hotbar) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = mc.player.containerMenu;
		int id = menuSlot(menu, index);
		if (id == -1) return false;
		mc.gameMode.handleContainerInput(menu.containerId, id, hotbar, ContainerInput.SWAP, mc.player);
		return true;
	}

	/**
	 * Runs {@code action} with the item at inventory index {@code index} in the main hand, then puts
	 * things back, all in one packet flush. {@code silent} selects a hotbar item and reselects the old
	 * slot; otherwise (or for items outside the hotbar) the item is swap-clicked into the held slot and
	 * back. False when the item could not be reached.
	 */
	public static boolean withItem(int index, boolean silent, Runnable action) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		int selected = player.getInventory().getSelectedSlot();
		if (index == selected) {
			PacketBatch.run(action);
			return true;
		}
		boolean[] done = {false};
		PacketBatch.run(() -> {
			if (silent && index >= 0 && index < 9) {
				selectNow(index);
				action.run();
				selectNow(selected);
				done[0] = true;
			} else if (swapWithHotbar(index, selected)) {
				action.run();
				swapWithHotbar(index, selected);
				done[0] = true;
			}
		});
		return done[0];
	}

	/** The hotbar slot the server thinks you hold (it can lag behind the client's selection). */
	public static int serverSlot() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gameMode == null ? -1 : ((MultiPlayerGameModeAccessor) mc.gameMode).coffeeclient$carriedIndex();
	}

	private static void selectNow(int slot) {
		Minecraft.getInstance().player.getInventory().setSelectedSlot(slot);
		syncSelectedSlot();
	}

	/**
	 * Sends a harmless numbered block action and returns its number. The server answers it only after
	 * everything sent before, so when that answer arrives ({@code ClientboundBlockChangedAckPacket}),
	 * the slot updates for earlier clicks have arrived too.
	 */
	public static int barrier() {
		Minecraft mc = Minecraft.getInstance();
		BlockPos far = mc.player.blockPosition().above(64);
		int[] sent = {0};
		((MultiPlayerGameModeAccessor) mc.gameMode).coffeeclient$startPrediction(mc.level, sequence -> {
			sent[0] = sequence;
			return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, far, Direction.DOWN, sequence);
		});
		return sent[0];
	}

	/** Asks the server to resend the open menu in full (a click with a stale state id does that). */
	public static void resync() {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = mc.player.containerMenu;
		mc.player.connection.send(new ServerboundContainerClickPacket(menu.containerId, -1, (short) -1, (byte) 0, ContainerInput.CLONE,
			Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
	}

	/** Swaps the stack in {@code slotId} with hotbar slot {@code hotbar} (0-8), like pressing a number key over it. */
	public static void quickSwap(int slotId, int hotbar) {
		click(slotId, hotbar, ContainerInput.SWAP);
	}

	public static void quickMove(int slotId) {
		click(slotId, 0, ContainerInput.QUICK_MOVE);
	}

	public static void drop(int slotId) {
		click(slotId, 1, ContainerInput.THROW);
	}

	public static int findEmptySlot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return -1;
		var inventory = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inventory.getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	public static ItemStack get(int index) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().getItem(index);
	}
}
