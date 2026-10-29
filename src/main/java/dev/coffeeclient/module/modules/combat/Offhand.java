package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.player.AutoMend;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;

import java.util.List;
import java.util.function.Predicate;

/**
 * Keeps crystals or golden apples in the offhand: a default item, keys that toggle one in, and a
 * golden apple while you hold a weapon and the use key. AutoTotem comes first: while it wants the
 * offhand for a totem, or AutoMend is on, this waits.
 */
public final class Offhand extends Module {
	public enum Item implements Labeled {
		NONE("None"), CRYSTAL("Crystal"), GAPPLE("Gapple");

		private final String label;

		Item(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Apple implements Labeled {
		ENCHANTED("Enchanted"), GOLDEN("Golden");

		private final String label;

		Apple(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Ticks between swaps, so a swap the server refuses is not resent every tick. */
	private static final int SWAP_WAIT = 4;

	public final EnumSetting<Item> item = setting(new EnumSetting<>("Item", "Kept in the offhand when no key or weapon asks for something else", Item.CRYSTAL));
	public final KeybindSetting crystalKey = setting(new KeybindSetting("Crystal key", "Toggle crystals in the offhand", KeybindSetting.UNBOUND));
	public final KeybindSetting gappleKey = setting(new KeybindSetting("Gapple key", "Toggle golden apples in the offhand", KeybindSetting.UNBOUND));
	public final BooleanSetting weaponGapple = setting(new BooleanSetting("Weapon gapple", "Golden apple in the offhand while you hold a sword, axe or mace and the use key", true));
	public final EnumSetting<Apple> apple = setting(new EnumSetting<>("Apple", "Enchanted (gapple) or plain (crapple) apples first; the other is the fallback", Apple.ENCHANTED));

	/** The item a key toggled in, or null. */
	private Item toggled;
	private int wait;

	public Offhand() {
		super("Offhand", List.of("Off Hand", "OffhandPlus"), Category.COMBAT, "Keeps crystals or golden apples in your offhand, after AutoTotem.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.KeyPress.class, e -> {
			if (!e.screenOpen() && e.action() == 1) onKey(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action == 1) onKey(KeyNames.mouseKey(e.button));
		});
	}

	@Override
	protected void onDisable() {
		toggled = null;
		CombatTick.release(this);
	}

	private void onKey(int key) {
		if (crystalKey.isBound() && key == crystalKey.key()) toggled = toggled == Item.CRYSTAL ? null : Item.CRYSTAL;
		else if (gappleKey.isBound() && key == gappleKey.key()) toggled = toggled == Item.GAPPLE ? null : Item.GAPPLE;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || player.isCreative() || player.isSpectator()) return;
		if (wait > 0) {
			wait--;
			return;
		}
		AutoTotem totem = ModuleManager.ifOn(AutoTotem.class);
		if (totem != null && totem.wantsOffhand()) return;
		// AutoMend is borrowing the offhand to repair gear.
		if (ModuleManager.ifOn(AutoMend.class) != null) return;
		// Never pull an apple out from under a bite.
		if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND) return;
		Item want = wanted();
		if (want == Item.NONE || matches(want, player.getOffhandItem()) || CombatTick.busy(CombatTick.Kind.SWAP, this)) return;
		int source = source(want);
		if (source < 0) return;
		if (Inv.swapWithOffhand(source) == Inv.OffhandSwap.NONE) return;
		CombatTick.claim(CombatTick.Kind.SWAP, this, 0, 2);
		wait = SWAP_WAIT;
	}

	private Item wanted() {
		LocalPlayer player = mc().player;
		if (weaponGapple.get() && mc().options.keyUse.isDown() && weapon(player.getMainHandItem())) return Item.GAPPLE;
		return toggled != null ? toggled : item.get();
	}

	private static boolean weapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.getItem() instanceof MaceItem;
	}

	private static boolean matches(Item want, ItemStack stack) {
		return want == Item.CRYSTAL ? stack.is(Items.END_CRYSTAL) : stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
	}

	/** The inventory index to swap in: the main inventory before the hotbar, the preferred apple before the other. */
	private int source(Item want) {
		Predicate<ItemStack> first = want == Item.CRYSTAL ? s -> s.is(Items.END_CRYSTAL)
			: s -> s.is(apple.get() == Apple.ENCHANTED ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE);
		Predicate<ItemStack> any = s -> matches(want, s);
		for (Predicate<ItemStack> match : List.of(first, any)) {
			Inv.FindItemResult found = Inv.find(match, 9, 35);
			if (!found.found()) found = Inv.find(match, 0, 8);
			if (found.found()) return found.slot();
		}
		return -1;
	}

	@Override
	public String infoString() {
		return isEnabled() && mc().player != null ? wanted().label() : null;
	}
}
