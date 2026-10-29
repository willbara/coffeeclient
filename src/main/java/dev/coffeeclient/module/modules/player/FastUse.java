package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Uses items much faster. */
public final class FastUse extends Module {
	public enum Mode {
		All, Some
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Which items to fast use", Mode.All));
	public final ItemListSetting items = setting(new ItemListSetting("Items", "Items to fast use in \"Some\" mode", List.of()));
	public final BooleanSetting blocks = setting(new BooleanSetting("Blocks", "Fast place any block in \"Some\" mode", false));
	public final NumberSetting cooldown = setting(new NumberSetting("Cooldown", "Fast use cooldown in ticks", 0, 0, 4, 1));

	public FastUse() {
		super("FastUse", Category.PLAYER, "Allows you to use items at very high speeds.");
		items.visibleWhen(() -> mode.get() == Mode.Some);
		blocks.visibleWhen(() -> mode.get() == Mode.Some);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return items;
	}

	/** Called from {@code MinecraftMixin} while {@code startUseItem} runs. */
	public int itemUseCooldown(ItemStack stack) {
		if (mode.get() == Mode.All || shouldWorkSome(stack)) return cooldown.get().intValue();
		return 4;
	}

	private boolean shouldWorkSome(ItemStack stack) {
		if (blocks.get() && stack.getItem() instanceof BlockItem) return true;
		return items.has(stack);
	}

}
