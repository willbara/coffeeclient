package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketCategoryTest {
	@Test
	void sortsByKeyword() {
		assertEquals(PacketCategory.NETWORK, PacketCategory.of("minecraft:keep_alive"));
		assertEquals(PacketCategory.MOVEMENT, PacketCategory.of("minecraft:move_player_pos"));
		assertEquals(PacketCategory.INVENTORY, PacketCategory.of("minecraft:container_set_slot"));
		assertEquals(PacketCategory.COMBAT, PacketCategory.of("minecraft:damage_event"));
		assertEquals(PacketCategory.CHAT, PacketCategory.of("minecraft:system_chat"));
		assertEquals(PacketCategory.WORLD, PacketCategory.of("minecraft:level_chunk_with_light"));
		assertEquals(PacketCategory.ENTITIES, PacketCategory.of("minecraft:set_entity_data"));
	}

	@Test
	void movementBeatsEntityForMoveEntity() {
		assertEquals(PacketCategory.MOVEMENT, PacketCategory.of("minecraft:move_entity_pos"));
	}

	@Test
	void unknownIdsAreOther() {
		assertEquals(PacketCategory.OTHER, PacketCategory.of("minecraft:zzz_unknown"));
	}
}
