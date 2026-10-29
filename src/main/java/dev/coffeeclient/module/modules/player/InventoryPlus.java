package dev.coffeeclient.module.modules.player;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.AbstractMountInventoryScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Inventory quality-of-life, each part switchable: XCarry, an inventory grid on the HUD, walking and
 * looking around inside chosen container screens, and using GUIs while standing in a nether portal.
 */
public final class InventoryPlus extends Module {
	public final BooleanSetting xCarry = group(new BooleanSetting("XCarry", "Keep the crafting grid when closing your inventory", false), "General");

	public final BooleanSetting showHud = group(new BooleanSetting("Show HUD grid", "Draw your inventory as a movable HUD panel", true), "HUD");
	public final BooleanSetting hotbar = group(new BooleanSetting("Include hotbar", "Add the hotbar as a fourth row", false), "HUD");
	public final BooleanSetting background = group(new BooleanSetting("Background", "Panel behind the grid", true), "HUD");
	public final BooleanSetting slotFrames = group(new BooleanSetting("Slot frames", "Outline each slot", true), "HUD");
	public final BooleanSetting hideEmpty = group(new BooleanSetting("Hide when empty", "Hide when the main inventory is empty", false), "HUD");

	public final BooleanSetting moveInGuis = group(new BooleanSetting("Move in GUIs", "Walk, jump and sprint while one of the screens below is open", true), "GUI movement");
	public final BooleanSetting sneakInGuis = group(new BooleanSetting("Sneak", "Let the sneak key work in GUIs too", true), "GUI movement");
	public final BooleanSetting arrowsRotate = group(new BooleanSetting("Arrows rotate", "Use the arrow keys to look around while a GUI is open", true), "GUI movement");
	public final NumberSetting rotateSpeed = group(new NumberSetting("Rotate speed", "Degrees the arrows turn the camera per tick", 4, 1, 20, 1), "GUI movement");

	public final BooleanSetting inventory = group(new BooleanSetting("Inventory", "Your own inventory screen", true), "Screens");
	public final BooleanSetting creative = group(new BooleanSetting("Creative inventory", "Except while typing in the search box", true), "Screens");
	public final BooleanSetting storage = group(new BooleanSetting("Chests and shulkers", "Chests, barrels, ender chests and shulker boxes", true), "Screens");
	public final BooleanSetting crafting = group(new BooleanSetting("Crafting", "Crafting tables and crafters", true), "Screens");
	public final BooleanSetting furnaces = group(new BooleanSetting("Furnaces", "Furnaces, smokers and blast furnaces", true), "Screens");
	public final BooleanSetting brewing = group(new BooleanSetting("Brewing stands", "Brewing stands", true), "Screens");
	public final BooleanSetting enchanting = group(new BooleanSetting("Enchanting tables", "Enchanting tables", true), "Screens");
	public final BooleanSetting anvils = group(new BooleanSetting("Anvils and smithing", "Anvils and smithing tables (not while renaming)", false), "Screens");
	public final BooleanSetting villagers = group(new BooleanSetting("Villager trades", "Villager and wandering trader screens", true), "Screens");
	public final BooleanSetting mounts = group(new BooleanSetting("Mount inventories", "Horse, donkey, llama and similar inventories", true), "Screens");
	public final BooleanSetting otherScreens = group(new BooleanSetting("Other containers", "Hoppers, dispensers, looms, stonecutters, beacons, ...", true), "Screens");

	public final BooleanSetting fullContainerGuard = group(new BooleanSetting("Full container guard", "Shift-clicking into a full container (or full inventory) does nothing", false), "Other");
	public final BooleanSetting noUnstackableDrag = group(new BooleanSetting("No unstackable drag", "Dragging a tool or other unstackable item doesn't spread it across slots", false), "Other");
	public final BooleanSetting portalGuis = group(new BooleanSetting("Portal GUIs", "Use inventories and GUIs normally while inside a nether portal", true), "Other");

	/** Keys this module pressed inside a GUI; released (to their physical state) when the GUI closes. */
	private boolean keysPressed;
	/** XCarry: the inventory was opened, so closing it sends the real close packet on disable. */
	private boolean invOpened;

	private final HudElement element = new Element();

	public InventoryPlus() {
		super("Inventory+", Category.PLAYER, "Inventory HUD grid, moving inside GUIs, and portal GUIs, each switchable.");
		for (Setting<?> s : List.of(hotbar, background, slotFrames, hideEmpty)) s.visibleWhen(showHud::get);
		for (Setting<?> s : List.of(sneakInGuis, arrowsRotate, inventory, creative, storage, crafting, furnaces, brewing,
			enchanting, anvils, villagers, mounts, otherScreens)) {
			s.visibleWhen(moveInGuis::get);
		}
		rotateSpeed.visibleWhen(() -> moveInGuis.get() && arrowsRotate.get());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.KeyPress.class, this::onKey);
		listen(Events.PacketSend.class, event -> {
			if (!xCarry.get()) return;
			if (!(event.packet instanceof ServerboundContainerClosePacket close)) return;
			Minecraft mc = mc();
			if (mc.player == null || close.getContainerId() != mc.player.inventoryMenu.containerId) return;
			invOpened = true;
			event.cancel();
		});
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	// ---- click guards (AbstractContainerScreenMixin) ----

	private static InventoryPlus on() {
		return ModuleManager.ifOn(InventoryPlus.class);
	}

	/** A shift-click whose stack has nowhere to go on the other side. */
	public static boolean blocksQuickMove(AbstractContainerMenu menu, Slot slot, ContainerInput input) {
		InventoryPlus m = on();
		if (m == null || !m.fullContainerGuard.get() || input != ContainerInput.QUICK_MOVE || slot == null || !slot.hasItem()) return false;
		ItemStack moving = slot.getItem();
		boolean fromPlayer = slot.container instanceof Inventory;
		for (Slot target : menu.slots) {
			if ((target.container instanceof Inventory) == fromPlayer || !target.mayPlace(moving)) continue;
			ItemStack there = target.getItem();
			if (there.isEmpty() || ItemStack.isSameItemSameComponents(there, moving) && there.getCount() < there.getMaxStackSize()) return false;
		}
		return true;
	}

	/** Dragging with an unstackable item on the cursor. */
	public static boolean blocksDrag(AbstractContainerMenu menu) {
		InventoryPlus m = on();
		return m != null && m.noUnstackableDrag.get() && !menu.getCarried().isEmpty() && !menu.getCarried().isStackable();
	}

	// ---- GUI movement ----

	/** True when the given screen is one we should let the player move in. */
	private boolean moveScreen(Screen screen) {
		if (screen == null || !isEnabled() || !moveInGuis.get()) return false;
		if (!(screen instanceof AbstractContainerScreen<?>)) return false;
		if (screen.getFocused() instanceof EditBox box && box.canConsumeInput()) return false;
		return allowed(screen);
	}

	private boolean allowed(Screen screen) {
		if (screen instanceof InventoryScreen) return inventory.get();
		if (screen instanceof CreativeModeInventoryScreen) return creative.get();
		if (screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen) return storage.get();
		if (screen instanceof CraftingScreen || screen instanceof CrafterScreen) return crafting.get();
		if (screen instanceof AbstractFurnaceScreen<?>) return furnaces.get();
		if (screen instanceof BrewingStandScreen) return brewing.get();
		if (screen instanceof EnchantmentScreen) return enchanting.get();
		if (screen instanceof ItemCombinerScreen<?>) return anvils.get();
		if (screen instanceof MerchantScreen) return villagers.get();
		if (screen instanceof AbstractMountInventoryScreen<?>) return mounts.get();
		return otherScreens.get();
	}

	private void onKey(Events.KeyPress e) {
		Minecraft mc = mc();
		if (mc.player == null || !moveScreen(mc.gui.screen()) || e.key() < 0) return;
		boolean handled = false;
		for (KeyMapping key : movementKeys(mc.options)) handled |= pass(key, e.key(), e.action());
		if (handled) {
			keysPressed = true;
			e.cancel();
		}
	}

	private List<KeyMapping> movementKeys(Options o) {
		return sneakInGuis.get()
			? List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyShift)
			: List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint);
	}

	/** Forward one key event to a mapping; @return true when the key belongs to that mapping. */
	private static boolean pass(KeyMapping bind, int key, int action) {
		if (bind.isUnbound() || KeyNames.boundKey(bind) != key) return false;
		bind.setDown(action != 0);
		return true;
	}

	private void unpress() {
		Options o = mc().options;
		if (o == null) return;
		for (KeyMapping key : List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyShift)) {
			key.setDown(KeyNames.isPhysicallyDown(key) && mc().gui.screen() == null);
		}
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		boolean moving = moveScreen(mc.gui.screen());
		if (keysPressed && !moving) {
			unpress();
			keysPressed = false;
		}
		if (moving && arrowsRotate.get() && mc.player != null) {
			float d = rotateSpeed.floatValue();
			float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
			boolean changed = false;
			if (KeyNames.isKeyDown(InputConstants.KEY_LEFT)) { yaw -= d; changed = true; }
			if (KeyNames.isKeyDown(InputConstants.KEY_RIGHT)) { yaw += d; changed = true; }
			if (KeyNames.isKeyDown(InputConstants.KEY_UP)) { pitch -= d; changed = true; }
			if (KeyNames.isKeyDown(InputConstants.KEY_DOWN)) { pitch += d; changed = true; }
			if (changed) {
				mc.player.setYRot(yaw);
				mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
			}
		}
	}

	@Override
	protected void onDisable() {
		if (keysPressed) {
			unpress();
			keysPressed = false;
		}
		Minecraft mc = mc();
		if (xCarry.get() && invOpened && mc.player != null && mc.player.connection != null) {
			mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
		}
		invOpened = false;
	}

	// ---- HUD element ----

	private final class Element extends ModuleCustomElement {
		Element() {
			super(InventoryPlus.this, "inventory_plus", "Inventory+");
		}

		@Override
		public boolean isEnabled() {
			return super.isEnabled() && showHud.get();
		}

		@Override
		public boolean showInEditor() {
			return isEnabled();
		}

		/** Right-click in the HUD editor hides the grid, not the whole module. */
		@Override
		public void toggle() {
			showHud.toggle();
		}

		private int rows() {
			return hotbar.get() ? 4 : 3;
		}

		@Override
		public boolean shouldRender() {
			if (!hideEmpty.get()) return true;
			var p = mc().player;
			if (p == null) return false;
			for (int i = 9; i < 36; i++) if (!p.getInventory().getItem(i).isEmpty()) return true;
			return false;
		}

		@Override
		public int width() {
			return 9 * 18 + 4;
		}

		@Override
		public int height() {
			return rows() * 18 + 4;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w - width() - 2, h - height() - 2};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			if (background.get()) Draw.box(ui.g, x, y, width(), height(), ui.theme.panel, ui.theme.border);
			var p = mc().player;
			if (p == null) return;
			for (int row = 0; row < rows(); row++) {
				for (int col = 0; col < 9; col++) {
					int index = row < 3 ? 9 + row * 9 + col : col;
					int sx = x + 2 + col * 18, sy = y + 2 + row * 18;
					if (slotFrames.get()) Draw.border(ui.g, sx, sy, 18, 18, Colors.withAlpha(ui.theme.border, 0x60));
					ItemStack stack = p.getInventory().getItem(index);
					if (stack.isEmpty()) continue;
					ui.g.item(stack, sx + 1, sy + 1);
					ui.g.itemDecorations(ui.font, stack, sx + 1, sy + 1);
				}
			}
		}
	}
}
