package dev.coffeeclient.module.modules.render;

import net.minecraft.world.entity.Entity;

/** Shared outline hook for the glow mixins: Item ESP for dropped items, Entity ESP for everything else. */
public final class Outlines {
	private Outlines() {
	}

	/** RGB outline colour, or -1 when no ESP module outlines this entity. */
	public static int color(Entity entity) {
		int c = ItemESP.outlineColor(entity);
		return c >= 0 ? c : EntityESP.outlineColor(entity);
	}
}
