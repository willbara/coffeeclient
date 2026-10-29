package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.InvPacer;
import dev.coffeeclient.util.ItemIds;
import dev.coffeeclient.util.KeyNames;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DecoratedPotBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Inventory organising from InventoryTweaks: anti-drop, automatic dropping, cursor-based
 * stack sorting and a one-key dump of filtered items into an open shulker box. Shift-drag moving, steal and dump are deliberately not included.
 *
 * <p>Sorting runs one swap per delay tick through {@link Inv}, so it survives server-side click
 * validation.
 */
public final class Organizer extends Module {
	public final BooleanSetting sorting = group(new BooleanSetting("Sorting", "Sort a region with the sort key", true), "Sorting");
	public final KeybindSetting sortKey = group(new KeybindSetting("Sort key", "Key or mouse button that sorts", KeyNames.mouseKey(2)), "Sorting");
	public final NumberSetting sortDelay = group(new NumberSetting("Sort delay", "Ticks between swaps", 1, 0, 10, 1), "Sorting");
	public final BooleanSetting disableCreative = group(new BooleanSetting("Disable in creative", "Do not sort in the creative inventory", true), "Sorting");

	public final ItemListSetting protect = group(new ItemListSetting("Protect items", "Items that cannot be dropped", List.of()), "Anti drop");
	public final BooleanSetting protectFrames = group(new BooleanSetting("Protect frames and pots", "Also block putting these items into frames and decorated pots", true), "Anti drop");
	public final KeybindSetting overrideDrop = group(new KeybindSetting("Override key", "Hold to allow dropping protected items", KeybindSetting.UNBOUND), "Anti drop");

	public final BooleanSetting autoDrop = group(new BooleanSetting("Auto drop", "Drop matching items from your inventory", false), "Auto drop");
	public final ItemListSetting autoDropItems = group(new ItemListSetting("Items to drop", "Items dropped automatically", List.of()), "Auto drop");
	public final BooleanSetting onlyFull = group(new BooleanSetting("Only full stacks", "Drop only full stacks", false), "Auto drop");
	public final BooleanSetting skipEquipped = group(new BooleanSetting("Keep equipped", "Never drop armour or the offhand item", true), "Auto drop");

	public final BooleanSetting cleaner = group(new BooleanSetting("Cleaner", "Drop what the plan below says is surplus (protected items never go)", false), "Cleaner");
	public final NumberSetting keepBlocks = group(new NumberSetting("Block stacks", "Stacks of blocks kept, fullest first (0 = no cap)", 4, 0, 36, 1), "Cleaner");
	public final NumberSetting keepArrows = group(new NumberSetting("Arrow stacks", "Stacks of arrows kept (0 = no cap)", 2, 0, 36, 1), "Cleaner");
	public final NumberSetting keepFood = group(new NumberSetting("Food stacks", "Stacks of food kept, most filling first (0 = no cap)", 2, 0, 36, 1), "Cleaner");
	public final NumberSetting keepBuckets = group(new NumberSetting("Empty buckets", "Empty buckets kept (0 = no cap)", 1, 0, 36, 1), "Cleaner");
	public final BooleanSetting bestTools = group(new BooleanSetting("Best tools only", "Keep only the best pickaxe, axe, shovel and hoe", true), "Cleaner");
	public final BooleanSetting bestArmour = group(new BooleanSetting("Best armour only", "Drop armour no better than what you wear (or a better spare)", true), "Cleaner");
	public final NumberSetting cleanDelay = group(new NumberSetting("Clean delay", "Ticks between drops", 2, 0, 20, 1), "Cleaner");

	public enum SlotKind implements dev.coffeeclient.setting.Labeled {
		NONE("Anything"), SWORD("Sword"), AXE("Axe"), PICKAXE("Pickaxe"), SHOVEL("Shovel"), HOE("Hoe"), BOW("Bow"), CROSSBOW("Crossbow"),
		BLOCKS("Blocks"), FOOD("Food"), GOLDEN_APPLE("Golden apple"), PEARL("Ender pearl"), TOTEM("Totem"), WATER("Water bucket"),
		FIREWORK("Firework"), CRYSTAL("End crystal");

		private final String label;

		SlotKind(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}

		boolean matches(ItemStack s) {
			return switch (this) {
				case NONE -> true;
				case SWORD -> s.is(ItemTags.SWORDS);
				case AXE -> s.is(ItemTags.AXES);
				case PICKAXE -> s.is(ItemTags.PICKAXES);
				case SHOVEL -> s.is(ItemTags.SHOVELS);
				case HOE -> s.is(ItemTags.HOES);
				case BOW -> s.is(Items.BOW);
				case CROSSBOW -> s.is(Items.CROSSBOW);
				case BLOCKS -> s.getItem() instanceof BlockItem b && !(b.getBlock() instanceof ShulkerBoxBlock);
				case FOOD -> s.has(DataComponents.FOOD) && !s.is(Items.GOLDEN_APPLE) && !s.is(Items.ENCHANTED_GOLDEN_APPLE);
				case GOLDEN_APPLE -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE);
				case PEARL -> s.is(Items.ENDER_PEARL);
				case TOTEM -> s.is(Items.TOTEM_OF_UNDYING);
				case WATER -> s.is(Items.WATER_BUCKET);
				case FIREWORK -> s.is(Items.FIREWORK_ROCKET);
				case CRYSTAL -> s.is(Items.END_CRYSTAL);
			};
		}
	}

	public final BooleanSetting hotbarLayout = group(new BooleanSetting("Hotbar layout", "Keep a kind of item in each hotbar slot, refilled from your inventory", false), "Hotbar layout");
	public final NumberSetting layoutDelay = group(new NumberSetting("Layout delay", "Ticks between moves", 4, 0, 20, 1), "Hotbar layout");
	private final List<dev.coffeeclient.setting.EnumSetting<SlotKind>> layout = new ArrayList<>();

	public final BooleanSetting quickShulker = group(new BooleanSetting("Quick shulker", "Press the dump key in an open shulker box to move the filtered items into it", false), "Quick shulker");
	public final KeybindSetting dumpKey = group(new KeybindSetting("Dump key", "Key or mouse button that dumps", KeyNames.mouseKey(3)), "Quick shulker");
	public final ItemListSetting dumpItems = group(new ItemListSetting("Items to dump", "Items moved into the shulker box (shulker boxes themselves never are)", List.of()), "Quick shulker");
	public final NumberSetting dumpDelay = group(new NumberSetting("Dump delay", "Ticks between moves", 1, 0, 10, 1), "Quick shulker");

	private static final Comparator<ItemStack> SORT_ORDER = Comparator
		.comparingInt((ItemStack stack) -> stack.isEmpty() ? 1 : 0)
		.thenComparing(stack -> stack.isEmpty() ? "" : ItemIds.id(stack.getItem()))
		.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed())
		.thenComparing(stack -> stack.isEmpty() ? "" : stack.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);

	private List<Integer> sortSlots;
	private List<ItemStack> sortTarget;
	private int sortIndex;
	/** Menu slot ids still to be shift-clicked into the open shulker box. */
	private final java.util.ArrayDeque<Integer> dumpQueue = new java.util.ArrayDeque<>();
	private final InvPacer pacer = new InvPacer();

	public Organizer() {
		super("Organizer", List.of("InventoryTweaks", "Inventory Tweaks"), Category.PLAYER, "Sorting, anti-drop, automatic dropping and shulker dumping.");
		protectFrames.visibleWhen(() -> !protect.get().isEmpty());
		overrideDrop.visibleWhen(() -> !protect.get().isEmpty());
		for (Setting<?> s : pacer.settings()) group(s, "Timing");
		for (Setting<?> s : List.of(dumpKey, dumpItems, dumpDelay)) s.visibleWhen(quickShulker::get);
		autoDropItems.visibleWhen(autoDrop::get);
		for (Setting<?> s : List.of(keepBlocks, keepArrows, keepFood, keepBuckets, bestTools, bestArmour, cleanDelay)) s.visibleWhen(cleaner::get);
		layoutDelay.visibleWhen(hotbarLayout::get);
		for (int i = 0; i < 9; i++) {
			var slot = group(new dev.coffeeclient.setting.EnumSetting<>("Slot " + (i + 1), "What this hotbar slot holds", SlotKind.NONE), "Hotbar layout");
			slot.visibleWhen(hotbarLayout::get);
			layout.add(slot);
		}
		onlyFull.visibleWhen(autoDrop::get);
		skipEquipped.visibleWhen(autoDrop::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.DropItems.class, event -> {
			if (protect.get().isEmpty()) return;
			if (held(overrideDrop.key())) return;
			if (protect.has(event.stack)) event.cancel();
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!isEnabled() || !protectFrames.get() || player != mc().player) return InteractionResult.PASS;
			if (entity instanceof ItemFrame && protect.has(player.getItemInHand(hand))) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!isEnabled() || !protectFrames.get() || player != mc().player) return InteractionResult.PASS;
			if (level.getBlockState(hit.getBlockPos()).getBlock() instanceof DecoratedPotBlock
				&& protect.has(player.getItemInHand(hand))) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});
	}

	@Override
	protected void onDisable() {
		sortSlots = null;
		sortTarget = null;
		dumpQueue.clear();
	}

	/** Called by the container-screen mixin for key presses and clicks. */
	public boolean onSortKey(AbstractContainerScreen<?> screen, Slot hovered, int key) {
		if (onDumpKey(screen, key)) return true;
		if (!isEnabled() || !sorting.get() || hovered == null) return false;
		if (!keyMatches(sortKey.key(), key)) return false;
		return startSort(screen, hovered);
	}

	/** Called by the container-screen mixin when the screen closes. */
	public static void onScreenClosed() {
		Organizer module = instance();
		if (module != null) {
			module.sortSlots = null;
			module.sortTarget = null;
			module.dumpQueue.clear();
		}
	}

	/** Queues every inventory stack on the dump list for the open shulker box. */
	private boolean onDumpKey(AbstractContainerScreen<?> screen, int key) {
		if (!isEnabled() || !quickShulker.get() || !keyMatches(dumpKey.key(), key)) return false;
		Minecraft mc = mc();
		if (mc.player == null || !(screen.getMenu() instanceof ShulkerBoxMenu menu) || menu != mc.player.containerMenu) return false;
		if (dumpItems.get().isEmpty() || !menu.getCarried().isEmpty()) return true;
		dumpQueue.clear();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (!(slot.container instanceof Inventory) || stack.isEmpty() || !dumpItems.has(stack)) continue;
			if (Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock) continue;
			dumpQueue.add(i);
		}
		pacer.start();
		return true;
	}

	private void tickDump(Minecraft mc) {
		if (dumpQueue.isEmpty()) return;
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof ShulkerBoxMenu)
			|| screen.getMenu() != mc.player.containerMenu) {
			dumpQueue.clear();
			return;
		}
		if (!pacer.ready(dumpDelay.intValue())) return;
		Inv.quickMove(dumpQueue.poll());
	}

	public static Organizer instance() {
		if (CoffeeClient.modules() == null) return null;
		try {
			return CoffeeClient.modules().get(Organizer.class);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private boolean startSort(AbstractContainerScreen<?> screen, Slot hovered) {
		Minecraft mc = mc();
		if (mc.player == null || sortSlots != null) return false;
		if (disableCreative.get() && screen instanceof CreativeModeInventoryScreen) return false;
		var menu = screen.getMenu();
		if (menu != mc.player.containerMenu || !menu.getCarried().isEmpty()) return false;
		int hoveredId = menu.slots.indexOf(hovered);
		if (hoveredId < 0) return false;

		List<Integer> ids = new ArrayList<>();
		Container container = hovered.container;
		if (container instanceof Inventory) {
			int base = hovered.index < 9 ? 0 : hovered.index < 36 ? 9 : -1;
			if (base < 0) return false;
			int end = base == 0 ? 9 : 36;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (slot.container == container && slot.index >= base && slot.index < end) ids.add(i);
			}
		} else {
			for (int i = 0; i < menu.slots.size(); i++) {
				if (menu.slots.get(i).container == container) ids.add(i);
			}
		}
		if (ids.size() < 2) return false;

		List<ItemStack> target = new ArrayList<>(ids.size());
		for (int id : ids) target.add(menu.slots.get(id).getItem().copy());
		target.sort(SORT_ORDER);
		sortSlots = ids;
		sortTarget = target;
		sortIndex = 0;
		pacer.start();
		return true;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.player == null) return;
		pacer.tick();
		tickSort(mc);
		tickDump(mc);
		tickAutoDrop(mc);
		tickCleaner(mc);
		tickLayout(mc);
	}

	private void tickSort(Minecraft mc) {
		if (sortSlots == null) return;
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != mc.player.containerMenu) {
			sortSlots = null;
			return;
		}
		if (!pacer.ready(sortDelay.intValue())) return;

		var menu = mc.player.containerMenu;
		while (sortIndex < sortSlots.size()) {
			int id = sortSlots.get(sortIndex);
			ItemStack current = menu.slots.get(id).getItem();
			if (ItemStack.matches(current, sortTarget.get(sortIndex))) {
				sortIndex++;
				continue;
			}
			int found = -1;
			for (int j = sortIndex + 1; j < sortSlots.size(); j++) {
				if (ItemStack.matches(menu.slots.get(sortSlots.get(j)).getItem(), sortTarget.get(sortIndex))) {
					found = j;
					break;
				}
			}
			if (found < 0 || !menu.getCarried().isEmpty()) {
				sortSlots = null;
				return;
			}
			Inv.move(sortSlots.get(sortIndex), sortSlots.get(found));
			sortIndex++;
			return;
		}
		sortSlots = null;
	}

	/** One move per delay: the first slot whose item is the wrong kind gets the right one from elsewhere. */
	private void tickLayout(Minecraft mc) {
		if (!hotbarLayout.get() || mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu || !pacer.ready(layoutDelay.intValue())) return;
		var inv = mc.player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			SlotKind kind = layout.get(slot).get();
			if (kind == SlotKind.NONE || kind.matches(inv.getItem(slot))) continue;
			int from = -1;
			for (int i = 0; i < 36 && from < 0; i++) {
				if (i == slot || i < 9 && layout.get(i).get() != SlotKind.NONE && layout.get(i).get().matches(inv.getItem(i))) continue;
				if (!inv.getItem(i).isEmpty() && kind.matches(inv.getItem(i))) from = i;
			}
			if (from < 0) continue;
			Inv.move(Inv.inventoryId(from), Inv.inventoryId(slot));
			return;
		}
	}

	private void tickCleaner(Minecraft mc) {
		if (!cleaner.get() || mc.gui.screen() != null || !pacer.ready(cleanDelay.intValue())) return;
		int slot = surplus(mc.player.getInventory().getNonEquipmentItems());
		if (slot >= 0) Inv.drop(Inv.inventoryId(slot));
	}

	private static final List<TagKey<Item>> TOOLS = List.of(ItemTags.PICKAXES, ItemTags.AXES, ItemTags.SHOVELS, ItemTags.HOES);
	private static final BlockState[] TOOL_TEST = {Blocks.STONE.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.HAY_BLOCK.defaultBlockState()};
	private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** The first main-inventory slot the plan drops, or -1. Within a category the weakest goes first. */
	private int surplus(List<ItemStack> inv) {
		List<Integer> blocks = new ArrayList<>(), arrows = new ArrayList<>(), food = new ArrayList<>(), buckets = new ArrayList<>();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.get(i);
			if (s.isEmpty() || protect.has(s)) continue;
			if (s.is(ItemTags.ARROWS)) arrows.add(i);
			else if (s.has(DataComponents.FOOD)) food.add(i);
			else if (s.is(Items.BUCKET)) buckets.add(i);
			else if (s.getItem() instanceof BlockItem b && !(b.getBlock() instanceof ShulkerBoxBlock)) blocks.add(i);
		}
		int drop = over(inv, blocks, keepBlocks.intValue(), Comparator.comparingInt(i -> -inv.get(i).getCount()));
		if (drop < 0) drop = over(inv, arrows, keepArrows.intValue(), Comparator.comparingInt(i -> -inv.get(i).getCount()));
		if (drop < 0) drop = over(inv, food, keepFood.intValue(), Comparator.comparingInt(i -> -inv.get(i).get(DataComponents.FOOD).nutrition()));
		if (drop < 0 && keepBuckets.intValue() > 0) {
			int seen = 0;
			for (int i : buckets) if ((seen += inv.get(i).getCount()) > keepBuckets.intValue()) return i;
		}
		if (drop < 0 && bestTools.get()) drop = worseTool(inv);
		if (drop < 0 && bestArmour.get()) drop = worseArmour(inv);
		return drop;
	}

	/** With a cap, the slot after the first {@code keep} in {@code order}; else -1. */
	private static int over(List<ItemStack> inv, List<Integer> slots, int keep, Comparator<Integer> order) {
		if (keep <= 0 || slots.size() <= keep) return -1;
		slots.sort(order);
		return slots.getLast();
	}

	/** A tool of a kind where a better one is carried. */
	private int worseTool(List<ItemStack> inv) {
		for (int t = 0; t < TOOLS.size(); t++) {
			int best = -1;
			double bestScore = -1;
			List<Integer> all = new ArrayList<>();
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.get(i);
				if (!s.is(TOOLS.get(t)) || protect.has(s)) continue;
				all.add(i);
				double score = s.getDestroySpeed(TOOL_TEST[t]) * (1 + 0.3 * EnchantmentIds.level(s, Enchantments.EFFICIENCY))
					+ (s.isDamageableItem() ? 1 - s.getDamageValue() / (double) s.getMaxDamage() : 1);
				if (score > bestScore) {
					bestScore = score;
					best = i;
				}
			}
			for (int i : all) if (i != best) return i;
		}
		return -1;
	}

	/** Spare armour no better than the best of that slot, worn or carried. */
	private int worseArmour(List<ItemStack> inv) {
		for (EquipmentSlot slot : ARMOUR) {
			ItemStack worn = mc().player.getItemBySlot(slot);
			double bestScore = armourScore(worn, slot);
			int best = -1;
			for (int i = 0; i < inv.size(); i++) {
				double score = armourScore(inv.get(i), slot);
				if (score > bestScore && !protect.has(inv.get(i))) {
					bestScore = score;
					best = i;
				}
			}
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.get(i);
				if (i != best && armourScore(s, slot) >= 0 && !protect.has(s)) return i;
			}
		}
		return -1;
	}

	/** Armour, toughness and Protection for that slot; -1 when the item isn't armour for it (an elytra isn't). */
	private static double armourScore(ItemStack s, EquipmentSlot slot) {
		Equippable eq = s.get(DataComponents.EQUIPPABLE);
		if (eq == null || eq.slot() != slot || s.has(DataComponents.GLIDER)) return -1;
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR, 0, slot) * 5 + mods.compute(Attributes.ARMOR_TOUGHNESS, 0, slot) * 2
			+ EnchantmentIds.level(s, Enchantments.PROTECTION) * 3;
	}

	private void tickAutoDrop(Minecraft mc) {
		if (!autoDrop.get() || mc.gui.screen() != null || autoDropItems.get().isEmpty()) return;
		int limit = skipEquipped.get() ? 36 : 41;
		for (int i = 0; i < limit; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!autoDropItems.has(stack)) continue;
			if (onlyFull.get() && stack.getCount() < stack.getMaxStackSize()) continue;
			Inv.drop(Inv.inventoryId(i));
			return;
		}
	}

	private static boolean keyMatches(int stored, int eventKey) {
		if (!KeyNames.isBound(stored)) return false;
		if (KeyNames.isMouse(stored)) return eventKey >= 0 && KeyNames.mouseKey(eventKey) == stored;
		return eventKey == stored;
	}

	private static boolean held(int key) {
		return KeyNames.isBound(key) && KeyNames.isHeld(key);
	}
}
