package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Allows you to mine blocks through entities. */
public final class NoMiningTrace extends Module {
	public final BooleanSetting onlyWhenHoldingPickaxe = setting(new BooleanSetting("Only when holding a pickaxe", "Only work while holding a pickaxe", true));
	public final EntityTypeListSetting entities = setting(new EntityTypeListSetting("Blacklisted entities", "Entities you will interact with as normal", List.of()));

	public NoMiningTrace() {
		super("NoMiningTrace", Category.PLAYER, "Allows you to mine blocks through entities.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	public boolean canWork(Entity entity) {
		if (!isEnabled()) return false;
		Minecraft mc = Minecraft.getInstance();
		if (onlyWhenHoldingPickaxe.get()
			&& !mc.player.getMainHandItem().is(ItemTags.PICKAXES)
			&& !mc.player.getOffhandItem().is(ItemTags.PICKAXES)) return false;
		if (entity == null) return true;
		return !entities.has(entity);
	}

}
