package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Makes other entities easier to hit: their pick box (what your crosshair, KillAura's raycast and
 * the aim-point picker use) grows by the set amount. The server still checks its own box.
 */
public final class Hitbox extends Module {
	public final NumberSetting expand = setting(new NumberSetting("Expand", "Blocks added on every side", 0.2, 0, 1, 0.05));
	public final EntityTypeListSetting entities = setting(new EntityTypeListSetting("Entities", "Entities whose box grows", List.of("minecraft:player")));

	public Hitbox() {
		super("Hitbox", List.of("Hitboxes", "HitBox"), Category.COMBAT, "Makes other entities easier to hit.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	/** EntityMixin: what {@code entity}'s pick radius grows by. */
	public static float expand(Entity entity) {
		Hitbox m = ModuleManager.ifOn(Hitbox.class);
		if (m == null || entity == Minecraft.getInstance().player || !m.entities.has(entity)) return 0;
		return m.expand.floatValue();
	}

	@Override
	public String infoString() {
		return expand.display();
	}
}
