package dev.coffeeclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import java.util.List;

/** Registry helpers for entity type ids shared by entity-list settings (icons, names, lookups). */
public final class EntityIds {
	private EntityIds() {
	}

	/** Every entity type id, sorted. */
	public static List<String> all() {
		return BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Identifier::toString).sorted().toList();
	}

	public static EntityType<?> of(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
	}

	/** Display name for an entity type id, falling back to the raw id. */
	public static String name(String id) {
		EntityType<?> type = of(id);
		return type == null ? id : type.getDescription().getString();
	}

	/** The type's spawn egg (empty when it has none), used as a picker icon. */
	public static ItemStack icon(String id) {
		EntityType<?> type = of(id);
		if (type == null) return ItemStack.EMPTY;
		return SpawnEggItem.byId(type).map(holder -> new ItemStack(holder.value())).orElse(ItemStack.EMPTY);
	}
}
