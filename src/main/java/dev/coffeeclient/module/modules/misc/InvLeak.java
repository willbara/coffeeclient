package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps your items off the screen for screenshots and screen shares: the hotbar, your slots in any
 * container, the item on the cursor and the one being dragged show as totems, a random everyday
 * loadout, or nothing. Tooltips, the item-name popup and the selected-slot frame can be hidden, and
 * your model in the inventory screen goes without its gear. Other containers' slots stay as they are.
 */
public final class InvLeak extends Module {
	public enum Style implements Labeled {
		TOTEM("Totem"), RANDOM("Random"), EMPTY("Empty");

		private final String label;

		Style(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Inventory indices: 0-8 hotbar, 9-35 main, 36-39 armour (feet to head), 40 offhand, 41 anything else. */
	private static final int OFFHAND = 40, OTHER = 41;
	private static final Item[] HOTBAR = {Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
		Items.NETHERITE_PICKAXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.BOW, Items.WATER_BUCKET,
		Items.COBBLESTONE, Items.TORCH, Items.COOKED_BEEF, Items.GOLDEN_CARROT, Items.BREAD, Items.ENDER_PEARL, Items.OAK_PLANKS, Items.DIRT,
		Items.FLINT_AND_STEEL};
	private static final Item[] MAIN = {Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.COAL,
		Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.REDSTONE, Items.LAPIS_LAZULI, Items.ARROW, Items.STRING, Items.BONE,
		Items.ROTTEN_FLESH, Items.GUNPOWDER, Items.SAND, Items.GRAVEL, Items.WHEAT_SEEDS, Items.WHEAT, Items.LADDER, Items.GLASS, Items.LEATHER,
		Items.FEATHER, Items.FLINT, Items.APPLE, Items.COOKED_PORKCHOP, Items.BUCKET, Items.BOOK, Items.PAPER, Items.SUGAR_CANE,
		Items.CRAFTING_TABLE, Items.CHEST, Items.FURNACE};
	private static final Item[] OFFHANDS = {Items.SHIELD, Items.TORCH, Items.WATER_BUCKET, Items.ARROW, Items.COOKED_BEEF};
	/** Feet, legs, chest, head (inventory indices 36-39); one tier is picked for the whole set. */
	private static final Item[][] ARMOR = {
		{Items.IRON_BOOTS, Items.CHAINMAIL_BOOTS, Items.DIAMOND_BOOTS},
		{Items.IRON_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.DIAMOND_LEGGINGS},
		{Items.IRON_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.DIAMOND_CHESTPLATE},
		{Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.DIAMOND_HELMET}};

	public final EnumSetting<Style> style = setting(new EnumSetting<>("Style", "What your items show as: all totems, a random everyday loadout, or nothing", Style.TOTEM));
	public final BooleanSetting fillEmpty = setting(new BooleanSetting("Fill empty slots", "Empty slots show a fake item too, so the gaps give nothing away", true));
	public final BooleanSetting tooltips = setting(new BooleanSetting("Hide tooltips", "No tooltip over your own slots", true));
	public final BooleanSetting itemName = setting(new BooleanSetting("Hide item name", "No item name above the hotbar when you switch", true));
	public final BooleanSetting selectedSlot = setting(new BooleanSetting("Hide selected slot", "No frame on the selected hotbar slot", true));
	public final BooleanSetting bareModel = setting(new BooleanSetting("Bare inventory model", "Your model in the inventory screen wears and holds nothing", true));
	public final BooleanSetting hud = setting(new BooleanSetting("Hide in ArmorStatus", "ArmorStatus shows the fake items too", true));

	/** The module while it is on; read on every slot drawn, so a field rather than a lookup. */
	private static volatile InvLeak active;
	/** Set while the inventory screen builds your model: equipment reads come back empty. */
	private static boolean bare;
	/** Made on first use: modules are built before item components are bound. */
	private static ItemStack totem;
	private final ItemStack[] loadout = new ItemStack[OTHER + 1];
	private long seed;

	public InvLeak() {
		super("InvLeak", List.of("NoInvLeak", "No Inv Leak", "Inventory Leak"), Category.MISC, "Hides your items on screen for screenshots and streams.");
		fillEmpty.visibleWhen(() -> style.get() != Style.EMPTY);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		seed = ThreadLocalRandom.current().nextLong();
		Arrays.fill(loadout, null);
		active = this;
	}

	@Override
	protected void onDisable() {
		active = null;
	}

	/** GuiMixin: a hotbar slot ({@code seed} 1-9) or the offhand (10). */
	public static ItemStack hotbar(ItemStack stack, int seed) {
		InvLeak m = active;
		return m == null ? stack : m.shown(stack, seed == 10 ? OFFHAND : seed >= 1 && seed <= 9 ? seed - 1 : OTHER);
	}

	/** AbstractContainerScreenMixin: a container slot, disguised only when it is one of yours. */
	public static ItemStack container(ItemStack stack, Slot slot) {
		InvLeak m = active;
		return m == null || !ownSlot(slot) ? stack : m.shown(stack, slot.getContainerSlot());
	}

	/** The item on the cursor or a drag preview. */
	public static ItemStack carried(ItemStack stack) {
		InvLeak m = active;
		return m == null || stack.isEmpty() ? stack : m.fake(OTHER);
	}

	/** ArmorStatus: one of your worn or held items. */
	public static ItemStack hud(ItemStack stack, int index) {
		InvLeak m = active;
		return m == null || !m.hud.get() || stack.isEmpty() ? stack : m.fake(index);
	}

	public static boolean ownSlot(Slot slot) {
		return active != null && slot != null && slot.container instanceof Inventory;
	}

	public static boolean hidesTooltip(Slot hovered) {
		InvLeak m = active;
		return m != null && m.tooltips.get() && ownSlot(hovered);
	}

	public static boolean hidesItemName() {
		InvLeak m = active;
		return m != null && m.itemName.get();
	}

	public static boolean hidesSelectedSlot() {
		InvLeak m = active;
		return m != null && m.selectedSlot.get();
	}

	/** InventoryScreenMixin: whether {@code entity}'s model in the inventory screen goes bare. */
	public static boolean hidesModelOf(LivingEntity entity) {
		InvLeak m = active;
		return m != null && m.bareModel.get() && entity == Minecraft.getInstance().player;
	}

	public static void bare(boolean on) {
		bare = on;
	}

	/** LivingEntityMixin: your equipment, empty while the bare model is being built. */
	public static ItemStack modelItem(LivingEntity entity, ItemStack stack) {
		return bare && !stack.isEmpty() && entity == Minecraft.getInstance().player ? ItemStack.EMPTY : stack;
	}

	private ItemStack shown(ItemStack stack, int index) {
		return stack.isEmpty() && !fillEmpty.get() ? stack : fake(index);
	}

	private ItemStack fake(int index) {
		return switch (style.get()) {
			case EMPTY -> ItemStack.EMPTY;
			case TOTEM -> totem != null ? totem : (totem = new ItemStack(Items.TOTEM_OF_UNDYING));
			case RANDOM -> {
				int i = index < 0 || index > OTHER ? OTHER : index;
				if (loadout[i] == null) loadout[i] = roll(i);
				yield loadout[i];
			}
		};
	}

	/** A fake stack for inventory index {@code i}, the same every time until the module is turned on again. */
	private ItemStack roll(int i) {
		Item item = i < 9 ? pick(HOTBAR, i) : i < 36 || i == OTHER ? pick(MAIN, i) : i == OFFHAND ? pick(OFFHANDS, i)
			: ARMOR[i - 36][(int) Long.remainderUnsigned(mix(seed ^ 167), ARMOR[0].length)];
		ItemStack stack = new ItemStack(item);
		if (stack.getMaxStackSize() > 1) stack.setCount(1 + (int) Long.remainderUnsigned(mix(seed ^ i ^ 81), stack.getMaxStackSize()));
		else if (stack.isDamageableItem()) stack.setDamageValue((int) Long.remainderUnsigned(mix(seed ^ i ^ 82), stack.getMaxDamage() / 2));
		return stack;
	}

	private Item pick(Item[] pool, int i) {
		return pool[(int) Long.remainderUnsigned(mix(seed ^ i * 0x9E3779B97F4A7C15L), pool.length)];
	}

	/** A 64-bit hash mix (splitmix64's finaliser), so neighbouring slots get unrelated items. */
	private static long mix(long z) {
		z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
		z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
		return z ^ z >>> 31;
	}
}
