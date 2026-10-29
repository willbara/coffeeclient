package dev.coffeeclient.module.modules.client;

import com.mojang.authlib.GameProfile;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.History;
import dev.coffeeclient.util.Sounds;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tells you (only you) what other players do: totem pops with a running count and the death that
 * follows (was TotemNotifier), joins and leaves, game-mode changes, finishing a watched food or
 * potion, and picking up a watched item. Everything but pops is off by default. After
 * Notifier. Watched players are always reported joining, leaving and dying, with a sound and an
 * optional command. The vanish probe asks the server to complete {@code /msg} now and then and
 * reports names it offers that the tab list hides.
 */
public final class Notifier extends Module {
	public enum Output { TOAST, CHAT, BOTH }

	/** Tab entries with other names are server decorations (fake rows), not players. */
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** The server lists everyone right after you join; those are not joins. */
	private static final long JOIN_GRACE_MS = 3000;

	// Totem pops
	public final BooleanSetting pops = group(new BooleanSetting("Totem pops", "Report totem pops, with a running count per player", true), "Totem pops");
	public final BooleanSetting includeSelf = group(new BooleanSetting("Include self", "Also report your own pops", false), "Totem pops");
	public final BooleanSetting playersOnly = group(new BooleanSetting("Players only", "Ignore mobs using totems", true), "Totem pops");
	public final NumberSetting range = group(new NumberSetting("Range", "Only within this many blocks (0 = any)", 0, 0, 256, 8), "Totem pops");
	public final BooleanSetting announceDeaths = group(new BooleanSetting("Announce deaths", "Report when a popped player dies", true), "Totem pops");
	public final ActionSetting reset = group(new ActionSetting("Reset counts", "Forget all pop counts", () -> this.popCounts.clear()), "Totem pops");

	// Players
	public final BooleanSetting joins = group(new BooleanSetting("Joins", "Report players joining the server", false), "Players");
	public final BooleanSetting leaves = group(new BooleanSetting("Leaves", "Report players leaving the server", false), "Players");
	public final BooleanSetting gameModes = group(new BooleanSetting("Game modes", "Report players changing game mode (staff going into spectator)", false), "Players");
	public final BooleanSetting ignoreFriends = group(new BooleanSetting("Ignore friends", "Skip reports about friends", false), "Players");

	// Items
	public final BooleanSetting consumed = group(new BooleanSetting("Consumed", "Report a nearby player finishing a watched food or potion", false), "Items");
	public final BooleanSetting held = group(new BooleanSetting("Held", "Report a nearby player taking out a watched item", false), "Items");
	public final ItemListSetting watched = group(new ItemListSetting("Watched items", "Items Consumed and Held report",
		List.of("minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:end_crystal")), "Items");

	// Watched players
	public final StringListSetting watchedPlayers = group(new StringListSetting("Watched players",
		"One per line: a name, then optionally which events (join leave death), sound or nosound, and | a command. A bare name uses the settings below",
		List.of(), 160), "Watched players");
	public final BooleanSetting watchSound = group(new BooleanSetting("Sound", "Ping when a watched player does something", true), "Watched players");
	public final NumberSetting watchVolume = group(new NumberSetting("Volume", "How loud the ping is", 1, 0.1, 2, 0.1), "Watched players");
	public final StringSetting watchCommand = group(new StringSetting("Command", "Run this when it happens ({name} and {event} are filled in; empty = off)", "", 128), "Watched players");
	public final NumberSetting commandDelay = group(new NumberSetting("Command delay", "Ticks before the command runs", 20, 0, 400, 5), "Watched players");

	// Vanish probe
	public final BooleanSetting vanishProbe = group(new BooleanSetting("Vanish probe", "Ask the server to complete /msg and report names missing from the tab list", false), "Vanish");
	public final NumberSetting probeInterval = group(new NumberSetting("Probe every (s)", "Seconds between probes", 15, 5, 300, 5), "Vanish");

	// Output
	public final EnumSetting<Output> output = group(new EnumSetting<>("Output", "Where to show reports", Output.CHAT), "Output");
	public final ColorSetting textColor = group(new ColorSetting("Text color", "Colour of reports", 0xFFF0B030), "Output");

	private final Map<UUID, Integer> popCounts = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, GameType> modes = new HashMap<>();
	/** Per player: the watched item being used, and the most ticks it was used for. */
	private final Map<UUID, Use> uses = new HashMap<>();
	private final Map<UUID, ItemStack[]> holding = new HashMap<>();
	private long joinedAt;
	private final Set<UUID> deathReported = new HashSet<>();
	private final Set<String> vanished = new HashSet<>();
	private int probeId = -1, probeTicks;
	private String pendingCommand;
	private int commandIn;

	private static final class Use {
		final ItemStack stack;
		final int duration;
		int ticks;

		Use(ItemStack stack, int duration, int ticks) {
			this.stack = stack;
			this.duration = duration;
			this.ticks = ticks;
		}
	}

	public Notifier() {
		super("Notifier", List.of("TotemNotifier", "Totem Notifier"), Category.CLIENT,
			"Reports totem pops, joins and leaves, game-mode changes and watched items other players use.");
		for (Setting<?> s : List.of(includeSelf, playersOnly, range, announceDeaths, reset)) s.visibleWhen(pops::get);
		watchVolume.visibleWhen(watchSound::get);
		probeInterval.visibleWhen(vanishProbe::get);
		listen(Events.PacketApply.Pre.class, e -> {
			if (e.packet instanceof ClientboundCommandSuggestionsPacket s && s.id() == probeId) {
				probeId = -1;
				e.cancel();
				onProbe(s);
			}
		});

		listen(Events.TotemPop.class, e -> onPop(e.entity()));
		listen(Events.PacketApply.Post.class, e -> {
			switch (e.packet()) {
				case ClientboundPlayerInfoUpdatePacket packet -> onInfoUpdate(packet);
				case ClientboundPlayerInfoRemovePacket packet -> onInfoRemove(packet);
				default -> {
				}
			}
		});
		listen(Events.WorldChange.class, e -> {
			joinedAt = System.currentTimeMillis();
			uses.clear();
			holding.clear();
		});
		listen(Events.GameLeft.class, e -> {
			names.clear();
			modes.clear();
			popCounts.clear();
		});
	}

	@Override
	protected void onEnable() {
		// Players already online count as known, so turning this on does not announce them all.
		var connection = mc().getConnection();
		if (connection == null) return;
		for (var info : connection.getOnlinePlayers()) {
			names.put(info.getProfile().id(), info.getProfile().name());
			modes.put(info.getProfile().id(), info.getGameMode());
		}
	}

	@Override
	protected void onDisable() {
		uses.clear();
		holding.clear();
	}

	// ---- pops ----

	private void onPop(Entity entity) {
		var player = mc().player;
		if (!pops.get() || player == null) return;
		if (entity == player && !includeSelf.get()) return;
		if (playersOnly.get() && !(entity instanceof Player)) return;
		if (range.get() > 0 && entity.distanceTo(player) > range.get()) return;
		int count = popCounts.merge(entity.getUUID(), 1, Integer::sum);
		report(entity.getName().getString() + " popped " + (count == 1 ? "a totem" : count + " totems"));
	}

	// ---- tab list ----

	private void onInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		boolean adding = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
		boolean grace = System.currentTimeMillis() - joinedAt < JOIN_GRACE_MS;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			if (adding) {
				GameProfile profile = entry.profile();
				if (profile == null || !PLAYER_NAME.matcher(profile.name()).matches()) continue;
				boolean known = names.put(entry.profileId(), profile.name()) != null;
				if (!known && !grace && watched(profile.name())) watchEvent(profile.name(), "joined");
				else if (joins.get() && !known && !grace && !skip(profile.name())) report(profile.name() + " joined");
			}
			if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
				GameType before = modes.put(entry.profileId(), entry.gameMode());
				String name = names.get(entry.profileId());
				if (gameModes.get() && !adding && before != null && before != entry.gameMode() && name != null && !skip(name)) {
					report(name + " is now in " + entry.gameMode().getName().toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	private void onInfoRemove(ClientboundPlayerInfoRemovePacket packet) {
		for (UUID id : packet.profileIds()) {
			String name = names.remove(id);
			modes.remove(id);
			if (name != null && watched(name)) watchEvent(name, "left");
			else if (leaves.get() && name != null && !skip(name)) report(name + " left");
		}
	}

	/** One watched player's rule: which events, whether to ping, and a command (null = the shared settings). */
	record Rule(String name, Set<String> events, Boolean sound, String command) {
		static final Set<String> ALL = Set.of("joined", "left", "died");

		/** "Name join death sound | /msg {name} hi"; unknown words are ignored. */
		static Rule parse(String line) {
			String[] split = line.split("\\|", 2);
			String[] words = split[0].trim().split("\\s+");
			Set<String> events = new HashSet<>();
			Boolean sound = null;
			for (int i = 1; i < words.length; i++) {
				switch (words[i].toLowerCase(Locale.ROOT)) {
					case "join", "joins", "joined" -> events.add("joined");
					case "leave", "leaves", "left" -> events.add("left");
					case "death", "deaths", "died" -> events.add("died");
					case "sound" -> sound = true;
					case "nosound", "silent" -> sound = false;
					default -> {
					}
				}
			}
			String command = split.length > 1 && !split[1].isBlank() ? split[1].trim() : null;
			return new Rule(words[0], events.isEmpty() ? ALL : events, sound, command);
		}
	}

	private Rule rule(String name) {
		for (String line : watchedPlayers.get()) {
			if (line.isBlank()) continue;
			Rule r = Rule.parse(line);
			if (r.name().equalsIgnoreCase(name)) return r;
		}
		return null;
	}

	private boolean watched(String name) {
		return rule(name) != null;
	}

	private void watchEvent(String name, String event) {
		Rule r = rule(name);
		if (r == null || !r.events().contains(event)) return;
		report(name + " " + event);
		if (r.sound() != null ? r.sound() : watchSound.get()) Sounds.play("minecraft:block.note_block.pling", 1.5f, watchVolume.floatValue());
		String command = r.command() != null ? r.command() : watchCommand.get();
		if (!command.isBlank()) {
			pendingCommand = command.replace("{name}", name).replace("{event}", event);
			commandIn = commandDelay.intValue();
		}
	}

	private void tickWatch() {
		var player = mc().player;
		if (pendingCommand != null && player != null && commandIn-- <= 0) {
			String c = pendingCommand.startsWith("/") ? pendingCommand.substring(1) : pendingCommand;
			pendingCommand = null;
			player.connection.sendCommand(c);
		}
		if (vanishProbe.get() && player != null && ++probeTicks >= probeInterval.intValue() * 20) {
			probeTicks = 0;
			probeId = (int) (System.nanoTime() & 0x7FFFFFFF);
			player.connection.send(new ServerboundCommandSuggestionPacket(probeId, "/msg "));
		}
	}

	/** Names the server completes but the tab list doesn't show. */
	private void onProbe(ClientboundCommandSuggestionsPacket packet) {
		var player = mc().player;
		if (player == null) return;
		Set<String> listed = new HashSet<>();
		for (String n : names.values()) listed.add(n.toLowerCase(Locale.ROOT));
		Set<String> now = new HashSet<>();
		for (ClientboundCommandSuggestionsPacket.Entry entry : packet.suggestions()) {
			String n = entry.text();
			if (!PLAYER_NAME.matcher(n).matches() || n.equalsIgnoreCase(player.getName().getString()) || listed.contains(n.toLowerCase(Locale.ROOT))) continue;
			now.add(n);
			if (vanished.add(n)) report(n + " may be vanished (the server knows them, the tab list hides them)");
		}
		vanished.retainAll(now);
	}

	private boolean skip(String name) {
		return ignoreFriends.get() && Friends.isFriend(name);
	}

	// ---- per tick: deaths, consumption, held items ----

	@Override
	public void onTick() {
		var level = mc().level;
		if (level == null) return;
		Set<UUID> seen = new HashSet<>();
		tickWatch();
		for (Player p : level.players()) {
			if (p.isDeadOrDying() && watched(p.getName().getString())) {
				if (deathReported.add(p.getUUID())) watchEvent(p.getName().getString(), "died");
			} else {
				deathReported.remove(p.getUUID());
			}
			Integer count = popCounts.get(p.getUUID());
			if (count != null && announceDeaths.get() && p.isDeadOrDying()) {
				popCounts.remove(p.getUUID());
				report(p.getName().getString() + " died after popping " + count + (count == 1 ? " totem" : " totems"));
			}
			if (!(p instanceof RemotePlayer remote) || skip(p.getName().getString())) continue;
			seen.add(p.getUUID());
			if (consumed.get()) trackUse(remote);
			if (held.get()) trackHeld(remote);
		}
		uses.keySet().retainAll(seen);
		holding.keySet().retainAll(seen);
	}

	/** A use counts when it ran (almost) to the end: the item was eaten or drunk, not just raised. */
	private void trackUse(RemotePlayer player) {
		Use use = uses.get(player.getUUID());
		if (!player.isUsingItem()) {
			uses.remove(player.getUUID());
			if (use != null && use.duration > 0 && use.ticks >= use.duration - 1) {
				report(player.getName().getString() + " used " + use.stack.getHoverName().getString());
			}
			return;
		}
		ItemStack item = player.getUseItem();
		ItemUseAnimation animation = item.getUseAnimation();
		if (!watched.has(item) || animation != ItemUseAnimation.EAT && animation != ItemUseAnimation.DRINK) {
			uses.remove(player.getUUID());
			return;
		}
		if (use == null || !ItemStack.isSameItemSameComponents(use.stack, item)) {
			uses.put(player.getUUID(), new Use(item.copy(), item.getUseDuration(player), player.getTicksUsingItem()));
		} else {
			use.ticks = Math.max(use.ticks, player.getTicksUsingItem());
		}
	}

	private void trackHeld(RemotePlayer player) {
		ItemStack main = watched.has(player.getMainHandItem()) ? player.getMainHandItem() : ItemStack.EMPTY;
		ItemStack off = watched.has(player.getOffhandItem()) ? player.getOffhandItem() : ItemStack.EMPTY;
		ItemStack[] before = holding.get(player.getUUID());
		if (!main.isEmpty() && (before == null || !main.is(before[0].getItem()))) {
			report(player.getName().getString() + " holds " + main.getHoverName().getString());
		}
		if (!off.isEmpty() && (before == null || !off.is(before[1].getItem()))) {
			report(player.getName().getString() + " holds " + off.getHoverName().getString() + " in the offhand");
		}
		if (main.isEmpty() && off.isEmpty()) holding.remove(player.getUUID());
		else holding.put(player.getUUID(), new ItemStack[] {main.copy(), off.copy()});
	}

	private void report(String text) {
		if (output.get() != Output.CHAT) CoffeeClient.hud().toast(text, Colors.withAlpha(textColor.argb(), 255));
		if (output.get() != Output.TOAST) chat(Component.literal(text).withColor(textColor.argb() & 0xFFFFFF));
		else History.add(name(), text); // chat() records the lines it sends
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
