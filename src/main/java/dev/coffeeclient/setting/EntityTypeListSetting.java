package dev.coffeeclient.setting;

import dev.coffeeclient.util.EntityIds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Set;

/** A selection of entity types, picked from every registered type. */
public class EntityTypeListSetting extends IdListSetting {
	public EntityTypeListSetting(String name, String description, List<String> defaults) {
		super(name, description, defaults, EntityIds::all, false);
		labels(EntityIds::name);
		icons(EntityIds::icon);
	}

	public Set<EntityType<?>> types() {
		return resolve(EntityIds::of);
	}

	public boolean has(EntityType<?> type) {
		return types().contains(type);
	}

	public boolean has(Entity entity) {
		return has(entity.getType());
	}
}
