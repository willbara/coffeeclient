package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.friend.Friend;
import dev.coffeeclient.friend.FriendManager;
import dev.coffeeclient.friend.FriendTrait;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Friend rules: what being a friend means for every friend (each friend can override them in the
 * Friends window). The list itself lives in {@link FriendManager} ({@code friends.json}); the static
 * helpers here are what modules call, and work whether or not this module is enabled. Middle-clicking
 * a player adds or removes them.
 */
public final class Friends extends Module {
	/** Pre-2026-10 friend names, kept only to move them into {@link FriendManager} once. */
	public final StringListSetting friends = setting(new StringListSetting("Friends", "Old friend list (moved to the Friends window)", List.of(), 32)
		.visibleWhen(() -> false));

	private final Map<FriendTrait, BooleanSetting> rules = new EnumMap<>(FriendTrait.class);

	public final BooleanSetting middleClick = setting(new BooleanSetting("Middle click",
		"Middle-click a player to add or remove them as a friend (works whether or not Friends is on)", true));
	public final NumberSetting middleClickRange = setting(new NumberSetting("Middle click range", "How far away the player can be", 6, 1, 64, 1)
		.visibleWhen(middleClick::get));

	public Friends() {
		super("Friends", List.of("MiddleClickFriend", "FriendClicker"), Category.MISC, "Friend rules; edit the list in the Friends window.");
		for (FriendTrait trait : FriendTrait.values()) {
			rules.put(trait, setting(new BooleanSetting(trait.label(), trait.description(), true)));
		}
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.MouseClick.class, e -> {
			if (!e.isCancelled() && e.button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && e.action == GLFW.GLFW_PRESS && middleClick.get()) toggleLookedAt();
		});
	}

	/** After MiddleClickAction (FriendClicker): the player under the crosshair joins or leaves the list. */
	private void toggleLookedAt() {
		Player target = lookedAt(middleClickRange.get());
		FriendManager manager = manager();
		if (target == null || manager == null) return;
		String name = target.getName().getString();
		if (manager.isFriend(name)) {
			manager.remove(name);
			info("Removed " + name);
		} else {
			manager.add(name, target.getUUID(), Friend.PERMANENT);
			info("Added " + name);
		}
	}

	/** The nearest other player along your view within {@code range}, not behind a block. */
	private static Player lookedAt(double range) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 end = eyes.add(mc.player.getViewVector(1).scale(range));
		BlockHitResult wall = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		double limit = wall.getType() == HitResult.Type.MISS ? range * range : eyes.distanceToSqr(wall.getLocation());
		Player best = null;
		for (Player player : mc.level.players()) {
			if (player == mc.player) continue;
			var hit = player.getBoundingBox().inflate(player.getPickRadius()).clip(eyes, end);
			if (hit.isEmpty()) continue;
			double distance = eyes.distanceToSqr(hit.get());
			if (distance <= limit) {
				limit = distance;
				best = player;
			}
		}
		return best;
	}

	/** The default for {@code trait} for all friends. */
	public BooleanSetting rule(FriendTrait trait) {
		return rules.get(trait);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public net.minecraft.client.gui.screens.Screen configScreen(net.minecraft.client.gui.screens.Screen parent) {
		return new dev.coffeeclient.gui.screen.FriendsScreen(parent);
	}

	/** Moves the old list into the friend store and wires the rule defaults. Call after both have loaded. */
	public static void attach(FriendManager manager) {
		Friends module = CoffeeClient.modules().get(Friends.class);
		manager.defaults(trait -> module.rules.get(trait).get());
		if (!module.friends.get().isEmpty()) {
			manager.importNames(module.friends.get());
			module.friends.set(List.of());
		}
	}

	private static FriendManager manager() {
		return CoffeeClient.friends();
	}

	/** Case-insensitive friend check (any active friend, temporary ones included). */
	public static boolean isFriend(String name) {
		FriendManager m = manager();
		return m != null && m.isFriend(name);
	}

	/** True when {@code name} is a friend and {@code trait} applies to them. */
	public static boolean is(String name, FriendTrait trait) {
		FriendManager m = manager();
		return m != null && m.has(name, trait);
	}

	/** The friend's own colour (opaque ARGB), else {@code fallback}. */
	public static int color(String name, int fallback) {
		FriendManager m = manager();
		Friend f = m == null ? null : m.get(name);
		return f == null || f.color() < 0 ? fallback : 0xFF000000 | f.color();
	}
}
