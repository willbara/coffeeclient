package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorMapSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Highlights dropped items (glow outline) and floats a label like "Diamond x3" above them. Listed
 * items can each get their own colour, and labels can name an item's enchantments.
 */
public final class ItemESP extends Module {
	public enum Filter { ALL, ONLY_SELECTED, EXCEPT_SELECTED }

	public final EnumSetting<Filter> filter = group(new EnumSetting<>("Filter", "Which items to highlight", Filter.ALL), "General");
	public final NumberSetting range = group(new NumberSetting("Range", "Max distance in blocks", 48, 4, 256, 4), "General");
	public final ItemListSetting items = group(new ItemListSetting("Items", "Items to include or exclude (see Filter)",
		List.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:ancient_debris", "minecraft:totem_of_undying")), "General");
	public final ItemListSetting ignored = group(new ItemListSetting("Ignored items", "Items that are never highlighted", List.of()), "General");

	public final ItemListSetting special = group(new ItemListSetting("Special items", "Items drawn in the special colour", List.of()), "Special");
	public final ColorSetting specialColor = group(new ColorSetting("Special color", "Colour of special items (when not rainbow)", 0xFFFF55FF), "Special");
	public final BooleanSetting specialRainbow = group(new BooleanSetting("Special rainbow", "Cycle special items through the rainbow", false), "Special");
	public final BooleanSetting specialOutlineOnly = group(new BooleanSetting("Special outline only", "Only the outline takes the special colour; the label keeps its own", false), "Special");
	public final BooleanSetting equipped = group(new BooleanSetting("Highlight equipped special", "Outline players and mobs holding or wearing a special item, armour stands included", false), "Special");
	public final BooleanSetting frames = group(new BooleanSetting("Highlight frames with special", "Outline item frames that show a special item", false), "Special");

	public final BooleanSetting outline = group(new BooleanSetting("Outline", "Glow outline around items", true), "Outline");
	public final BooleanSetting rarityColors = group(new BooleanSetting("Rarity colors", "Colour by item rarity", true), "Outline");
	public final ColorSetting outlineColor = group(new ColorSetting("Outline color", "Outline colour (when not by rarity)", 0xFFF0B030), "Outline");
	public final ColorMapSetting itemColors = group(new ColorMapSetting("Item colours", "A colour for each listed item, used for its outline and label",
		items::get, ItemESP::itemName, outlineColor::argb), "Outline");

	public final BooleanSetting nametags = group(new BooleanSetting("Nametags", "Label above each item", true), "Nametag");
	public final BooleanSetting showCount = group(new BooleanSetting("Show count", "\"x3\" after the name", true), "Nametag");
	public final BooleanSetting showDistance = group(new BooleanSetting("Show distance", "Distance in metres", false), "Nametag");
	public final NumberSetting merge = group(new NumberSetting("Merge radius", "Combine same items this close into one label (0 = off)", 1.5, 0, 6, 0.5), "Nametag");
	public final BooleanSetting rarityText = group(new BooleanSetting("Rarity text color", "Name coloured by rarity", true), "Nametag");
	public final ColorSetting textColor = group(new ColorSetting("Text color", "Label colour (when not by rarity)", 0xFFFFFFFF), "Nametag");
	public final BooleanSetting background = group(new BooleanSetting("Background", "Panel behind the label", true), "Nametag");
	public final BooleanSetting icon = group(new BooleanSetting("Icon", "Item icon in the label", true), "Nametag");
	public final NumberSetting scale = group(new NumberSetting("Scale", "Label size", 1.0, 0.5, 2, 0.05), "Nametag");
	public final NumberSetting maxLabels = group(new NumberSetting("Max labels", "Nearest labels shown", 40, 5, 200, 5), "Nametag");
	public final BooleanSetting enchantments = group(new BooleanSetting("Enchantments", "Name the item's enchantments after it", false), "Nametag");
	public final IdListSetting shownEnchantments = group(new IdListSetting("Shown enchantments", "Enchantments named (empty = all)",
		List.of(), EnchantmentIds::all, false).labels(EnchantmentIds::name), "Nametag");
	public final BooleanSetting onlyEnchanted = group(new BooleanSetting("Only enchanted", "Only highlight enchanted items and books", false), "General");

	public ItemESP() {
		super("ItemESP", Category.RENDER, "Highlights dropped items with outlines and labels (opens its own window).");
		outlineColor.visibleWhen(() -> !rarityColors.get());
		textColor.visibleWhen(() -> !rarityText.get());
		items.visibleWhen(() -> filter.get() != Filter.ALL);
		specialColor.visibleWhen(() -> !specialRainbow.get());
		shownEnchantments.visibleWhen(enchantments::get);
	}

	private static String itemName(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? id : BuiltInRegistries.ITEM.getValue(key).getDefaultInstance().getHoverName().getString();
	}

	/** The colour given to this item in Item colours, or -1. */
	private int ownColor(ItemStack stack) {
		Integer c = itemColors.get().get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		return c == null ? -1 : c & 0xFFFFFF;
	}

	/** "Sharp 5, Unb 3" for the shown enchantments (all when none are picked). */
	private String enchantText(ItemStack stack) {
		ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		StringBuilder b = new StringBuilder();
		for (Holder<Enchantment> e : enchants.keySet()) {
			String id = e.unwrapKey().map(k -> k.identifier().toString()).orElse("");
			if (!shownEnchantments.get().isEmpty() && !shownEnchantments.contains(id)) continue;
			String name = e.value().description().getString();
			if (b.length() > 0) b.append(", ");
			b.append(name, 0, Math.min(4, name.length())).append(' ').append(enchants.getLevel(e));
		}
		return b.toString();
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return items;
	}

	private boolean matches(ItemEntity e) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || e.distanceTo(mc.player) > range.get() || ignored.has(e.getItem())) return false;
		if (onlyEnchanted.get() && EnchantmentHelper.getEnchantmentsForCrafting(e.getItem()).isEmpty()) return false;
		if (filter.get() == Filter.ALL) return true;
		boolean listed = items.has(e.getItem());
		return filter.get() == Filter.ONLY_SELECTED == listed;
	}

	private static int rarityColor(ItemStack stack) {
		return switch (stack.getRarity()) {
			case UNCOMMON -> 0xFFFF55;
			case RARE -> 0x55FFFF;
			case EPIC -> 0xFF55FF;
			default -> 0xFFFFFF;
		};
	}

	/** The special colour now: the rainbow when Special rainbow is on, else the Special color setting. */
	private int specialArgb() {
		return specialRainbow.get() ? Colors.fromHsv((float) (System.currentTimeMillis() / 17 % 360), 1f, 1f, 0xFF) : specialColor.argb();
	}

	/** Whether a living entity holds or wears a special item. */
	private boolean holdsSpecial(LivingEntity living) {
		return special.has(living.getMainHandItem()) || special.has(living.getOffhandItem()) || special.has(living.getItemBySlot(EquipmentSlot.HEAD));
	}

	/** Outline colour (RGB) for an entity, or -1. */
	public static int outlineColor(Entity entity) {
		if (!ModuleManager.on(ItemESP.class)) return -1;
		ItemESP esp = CoffeeClient.modules().get(ItemESP.class);
		if (entity instanceof ItemEntity item) {
			if (!esp.outline.get() || !esp.matches(item)) return -1;
			if (esp.special.has(item.getItem())) return esp.specialArgb() & 0xFFFFFF;
			int own = esp.ownColor(item.getItem());
			if (own >= 0) return own;
			return esp.rarityColors.get() ? rarityColor(item.getItem()) : esp.outlineColor.argb() & 0xFFFFFF;
		}
		if (entity instanceof ItemFrame frame) return esp.frames.get() && esp.special.has(frame.getItem()) ? esp.specialArgb() & 0xFFFFFF : -1;
		if (entity instanceof LivingEntity living && esp.equipped.get() && entity != Minecraft.getInstance().player && esp.holdsSpecial(living)) {
			return esp.specialArgb() & 0xFFFFFF;
		}
		return -1;
	}

	private record Group(ItemStack stack, Vec3 pos, int count, double distance, double labelOffset) {
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		Minecraft mc = mc();
		if (!nametags.get() || mc.level == null || mc.player == null) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<Group> groups = new ArrayList<>();
		double radius = merge.get();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item) || !matches(item)) continue;
			Vec3 pos = item.getPosition(pt);
			ItemStack stack = item.getItem();
			boolean merged = false;
			if (radius > 0) {
				for (int i = 0; i < groups.size(); i++) {
					Group g = groups.get(i);
					if (ItemStack.isSameItemSameComponents(g.stack(), stack) && g.pos().distanceTo(pos) <= radius) {
						groups.set(i, new Group(g.stack(), g.pos(), g.count() + stack.getCount(), g.distance(), g.labelOffset()));
						merged = true;
						break;
					}
				}
			}
			if (!merged) groups.add(new Group(stack, pos, stack.getCount(), pos.distanceTo(projector.camera()), item.getEyeHeight(item.getPose()) + 0.2));
		}
		groups.sort(Comparator.comparingDouble(Group::distance));
		int shown = 0;
		for (Group g : groups) {
			if (shown++ >= maxLabels.intValue()) break;
			float[] p = projector.project(g.pos().x, g.pos().y + g.labelOffset(), g.pos().z);
			if (!projector.onScreen(p, 20)) continue;
			String text = g.stack().getHoverName().getString();
			boolean specialLabel = special.has(g.stack()) && !specialOutlineOnly.get();
			int own = ownColor(g.stack());
			int color = specialLabel ? 0xFF000000 | specialArgb() & 0xFFFFFF
				: own >= 0 ? 0xFF000000 | own
				: rarityText.get() ? 0xFF000000 | rarityColor(g.stack()) : Colors.withAlpha(textColor.argb(), 255);
			List<Nametag.Segment> segments = new ArrayList<>();
			segments.add(new Nametag.Segment(text, color));
			if (showCount.get()) segments.add(new Nametag.Segment(" x" + g.count(), 0xFFE8B923));
			if (enchantments.get()) {
				String ench = enchantText(g.stack());
				if (!ench.isEmpty()) segments.add(new Nametag.Segment(" [" + ench + "]", 0xFFB48CFF));
			}
			if (showDistance.get()) segments.add(new Nametag.Segment(String.format(Locale.ROOT, "  %.0fm", g.distance()), ui.theme.dimText));
			Nametag.drawSegments(ui, p[0], p[1], segments, scale.floatValue(), background.get(), ui.theme.panel,
				icon.get() ? g.stack() : null);
		}
	}
}
