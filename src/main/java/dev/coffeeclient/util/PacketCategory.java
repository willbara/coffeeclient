package dev.coffeeclient.util;

import dev.coffeeclient.setting.Labeled;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups packet types for the packet filters, by their id. Rules are checked in order, so the first
 * group whose keyword appears in the id wins: the id {@code minecraft:move_entity_pos} is movement, not
 * entity. Anything no rule matches is {@link #OTHER}.
 */
public enum PacketCategory implements Labeled {
	NETWORK("Network and keep-alive", "keep_alive", "ping", "pong", "custom_payload", "cookie", "bundle", "disconnect",
		"resource_pack", "transfer", "server_links", "known_packs", "login", "configuration", "custom_report", "tick_end"),
	MOVEMENT("Movement", "move_player", "move_entity", "rotate", "teleport", "motion", "player_input", "player_abilities",
		"vehicle", "entity_position_sync", "player_position", "player_look"),
	INTERACTION("Interaction", "use_item", "interact", "player_action", "swing", "attack", "place", "block_break"),
	INVENTORY("Inventory", "container", "inventory", "slot", "cursor", "recipe", "trade", "merchant", "hotbar", "item", "craft"),
	COMBAT("Combat", "damage", "hurt", "animate", "death", "effect", "combat", "kill"),
	SOUND("Sound and particles", "sound", "particle", "level_event"),
	CHAT("Chat and screens", "chat", "message", "command", "title", "action_bar", "open_screen", "tab_list", "player_info",
		"scoreboard", "objective", "team", "sign", "book", "signed", "advancement", "profile"),
	WORLD("World and chunks", "chunk", "block", "light", "section", "level", "explosion", "world", "border", "time", "weather",
		"game_event"),
	ENTITIES("Entities", "entity", "spawn", "attributes", "passenger", "leash", "experience_orb", "projectile", "boss"),
	OTHER("Other");

	/** Packet ids are few and fixed, so each is classified once. */
	private static final Map<String, PacketCategory> CACHE = new ConcurrentHashMap<>();

	private final String label;
	private final String[] keywords;

	PacketCategory(String label, String... keywords) {
		this.label = label;
		this.keywords = keywords;
	}

	@Override
	public String label() {
		return label;
	}

	/** The category of a packet id such as {@code minecraft:keep_alive}. */
	public static PacketCategory of(String id) {
		return CACHE.computeIfAbsent(id, PacketCategory::classify);
	}

	private static PacketCategory classify(String id) {
		String path = id.startsWith("minecraft:") ? id.substring(10) : id;
		for (PacketCategory category : values()) {
			for (String keyword : category.keywords) {
				if (path.contains(keyword)) return category;
			}
		}
		return OTHER;
	}
}
