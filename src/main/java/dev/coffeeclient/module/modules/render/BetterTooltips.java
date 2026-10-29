package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.tooltip.ContainerTooltip;
import dev.coffeeclient.gui.tooltip.MapTooltip;
import dev.coffeeclient.gui.tooltip.TextTooltip;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Adds food, effect and item-size lines to item tooltips, chest-style previews of container
 * contents and map pictures. Shulker boxes in container slots show their most common item, and
 * holding the lock key keeps the last tooltip on screen while the mouse moves.
 */
public final class BetterTooltips extends Module {
	public enum SizeFormat { Dynamic, Bytes, Kilobytes, Megabytes }

	public final BooleanSetting foodInfo = group(new BooleanSetting("Food info", "Show hunger and saturation", true), "Tooltips");
	public final BooleanSetting statusEffects = group(new BooleanSetting("Status effects", "List effects granted by food", true), "Tooltips");
	public final BooleanSetting containerPreview = group(new BooleanSetting("Container preview", "Show shulker box and container contents as a chest grid", true), "Previews");
	public final BooleanSetting enderChestPreview = group(new BooleanSetting("Ender chest preview", "Show your ender chest, remembered from the last time you opened it", true), "Previews");
	public final BooleanSetting mapPreview = group(new BooleanSetting("Map preview", "Show a map's picture", true), "Previews");
	public final BooleanSetting shulkerIcons = group(new BooleanSetting("Shulker icons", "Show each shulker box's most common item on its slot", false), "Previews");
	public final KeybindSetting lockKey = group(new KeybindSetting("Lock key", "Hold to keep the current tooltip in place", KeybindSetting.UNBOUND), "Previews");
	public final BooleanSetting byteSize = group(new BooleanSetting("Item size", "Show the encoded size of the item", true), "Tooltips");
	public final EnumSetting<SizeFormat> sizeFormat = group(new EnumSetting<>("Size format", "How item sizes are shown", SizeFormat.Dynamic), "Tooltips");

	private static final int ENDER_CHEST_TINT = 0xFF205A5A;
	private static final String ENDER_CHEST_TITLE = "container.enderchest";

	/** Last seen ender chest contents, or null until one has been opened this session. */
	private volatile List<ItemStack> enderChest;
	/** The tooltip held by the lock key: its item and where it was shown. */
	private ItemStack locked = ItemStack.EMPTY;
	private int lockedX, lockedY;

	public BetterTooltips() {
		super("BetterTooltips", List.of("Better Tooltips"), Category.RENDER, "Adds useful information to item tooltips.");
		sizeFormat.visibleWhen(byteSize::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.GameLeft.class, e -> enderChest = null);
	}

	/** Remembers the ender chest while its screen is open (the server only sends it then). */
	@Override
	public void onTick() {
		if (!(mc().gui.screen() instanceof ContainerScreen screen)) return;
		if (!(screen.getTitle().getContents() instanceof TranslatableContents title) || !ENDER_CHEST_TITLE.equals(title.getKey())) return;
		Container container = screen.getMenu().getContainer();
		List<ItemStack> items = new ArrayList<>(27);
		for (int i = 0; i < Math.min(27, container.getContainerSize()); i++) items.add(container.getItem(i).copy());
		enderChest = List.copyOf(items);
	}

	/** Image shown under an item's tooltip, or null for vanilla's. Called from {@code ItemStackMixin}. */
	public TooltipComponent preview(ItemStack stack) {
		if (mapPreview.get() && stack.has(DataComponents.MAP_ID)) return new MapTooltip(stack.get(DataComponents.MAP_ID));
		if (stack.is(Items.ENDER_CHEST)) {
			if (!enderChestPreview.get()) return null;
			List<ItemStack> items = enderChest;
			return items == null ? noData() : new ContainerTooltip(items, ENDER_CHEST_TINT);
		}
		if (!containerPreview.get()) return null;
		ShulkerBoxBlock shulker = stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock box ? box : null;
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		// Container items always carry the component; a missing one means the server withheld the contents.
		if (contents == null) return shulker != null ? noData() : null;
		if (shulker == null && contents.nonEmptyItemCopyStream().findAny().isEmpty()) return null;
		NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
		contents.copyInto(items);
		return new ContainerTooltip(items, shulker == null ? 0xFFFFFFFF : tint(shulker));
	}

	/** AbstractContainerScreenMixin, after a slot's item: a shulker box's most common item, small, in its corner. */
	public static void slotIcon(GuiGraphicsExtractor g, Slot slot) {
		BetterTooltips m = ModuleManager.ifOn(BetterTooltips.class);
		if (m == null || !m.shulkerIcons.get() || !slot.hasItem()) return;
		ItemStack stack = slot.getItem();
		if (!(stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock)) return;
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents == null) return;
		Map<net.minecraft.world.item.Item, Integer> counts = new HashMap<>();
		ItemStack top = ItemStack.EMPTY;
		int best = 0;
		for (ItemStack s : (Iterable<ItemStack>) contents.nonEmptyItemCopyStream()::iterator) {
			int n = counts.merge(s.getItem(), s.getCount(), Integer::sum);
			if (n > best) {
				best = n;
				top = s;
			}
		}
		if (top.isEmpty()) return;
		g.pose().pushMatrix();
		g.pose().translate(slot.x + 8, slot.y + 8);
		g.pose().scale(0.5f, 0.5f);
		g.item(top, 0, 0);
		g.pose().popMatrix();
	}

	/**
	 * AbstractContainerScreenMixin, instead of the vanilla tooltip: while the lock key is held, the
	 * tooltip that was showing stays where it was. True when it drew one (skip vanilla's).
	 */
	public static boolean lockedTooltip(GuiGraphicsExtractor g, Slot hovered, int mouseX, int mouseY) {
		BetterTooltips m = ModuleManager.ifOn(BetterTooltips.class);
		if (m == null || !m.lockKey.isBound() || !KeyNames.isHeld(m.lockKey.key())) {
			if (m != null) m.locked = ItemStack.EMPTY;
			return false;
		}
		if (m.locked.isEmpty()) {
			if (hovered == null || !hovered.hasItem()) return false;
			m.locked = hovered.getItem().copy();
			m.lockedX = mouseX;
			m.lockedY = mouseY;
		}
		g.setTooltipForNextFrame(m.mc().font, m.locked, m.lockedX, m.lockedY);
		return true;
	}

	private static int tint(ShulkerBoxBlock shulker) {
		DyeColor dye = shulker.getColor();
		return dye == null ? 0xFFFFFFFF : 0xFF000000 | (dye.getTextureDiffuseColor() & 0xFFFFFF);
	}

	private static TooltipComponent noData() {
		return new TextTooltip(Component.literal("No data yet.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	/** Appends this module's lines to a vanilla tooltip. */
	public List<Component> append(ItemStack stack, List<Component> original) {
		if (stack.isEmpty() || original.isEmpty()) return original;
		List<Component> lines = new ArrayList<>(original);
		if (statusEffects.get()) appendEffects(stack, lines);
		if (foodInfo.get() && stack.has(DataComponents.FOOD)) {
			FoodProperties food = stack.get(DataComponents.FOOD);
			lines.add(Component.literal(String.format(Locale.ROOT, "Hunger: %d  Saturation: %.1f", food.nutrition(), food.saturation()))
				.withStyle(ChatFormatting.GRAY));
		}
		if (byteSize.get()) appendSize(stack, lines);
		return lines;
	}

	private void appendEffects(ItemStack stack, List<Component> lines) {
		if (stack.getItem() == Items.SUSPICIOUS_STEW) {
			SuspiciousStewEffects stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
			if (stew != null) {
				for (SuspiciousStewEffects.Entry entry : stew.effects()) {
					lines.add(effectText(new MobEffectInstance(entry.effect(), entry.duration(), 0)));
				}
			}
			return;
		}
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null) return;
		consumable.onConsumeEffects().stream()
			.filter(ApplyStatusEffectsConsumeEffect.class::isInstance)
			.map(ApplyStatusEffectsConsumeEffect.class::cast)
			.flatMap(effect -> effect.effects().stream())
			.forEach(effect -> lines.add(effectText(effect)));
	}

	private Component effectText(MobEffectInstance effect) {
		MutableComponent text = Component.translatable(effect.getDescriptionId());
		float tickrate = mc().level == null ? 20f : mc().level.tickRateManager().tickrate();
		String duration = MobEffectUtil.formatDuration(effect, 1f, tickrate).getString();
		if (effect.getAmplifier() != 0) text.append(Component.literal(String.format(" %d (%s)", effect.getAmplifier() + 1, duration)));
		else text.append(Component.literal(" (" + duration + ")"));
		return effect.getEffect().value().isBeneficial() ? text.withStyle(ChatFormatting.BLUE) : text.withStyle(ChatFormatting.RED);
	}

	private void appendSize(ItemStack stack, List<Component> lines) {
		Player player = mc().player;
		if (player == null) return;
		var result = ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
		if (!(result instanceof com.mojang.serialization.DataResult.Success<Tag> success)) return;
		try {
			CountingStream counter = new CountingStream();
			success.value().write(new DataOutputStream(counter));
			int bytes = counter.count;
			String text = switch (sizeFormat.get()) {
				case Bytes -> bytes + " bytes";
				case Kilobytes -> String.format(Locale.ROOT, "%.2f kB", bytes / 1024.0);
				case Megabytes -> String.format(Locale.ROOT, "%.4f MB", bytes / 1048576.0);
				case Dynamic -> bytes >= 1048576 ? String.format(Locale.ROOT, "%.2f MB", bytes / 1048576.0)
					: bytes >= 1024 ? String.format(Locale.ROOT, "%.2f kB", bytes / 1024.0) : bytes + " bytes";
			};
			lines.add(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
		} catch (IOException ignored) {
			// encoding failed; no size line
		}
	}

	/** Counts the bytes the NBT tag writes, without allocating a buffer. */
	private static final class CountingStream extends OutputStream {
		private int count;

		@Override
		public void write(int b) {
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			count += len;
		}
	}
}
