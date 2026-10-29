package dev.coffeeclient.util;

import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Picks entities for modules that act on one: filter the
 * rendered entities, sort by a {@link SortPriority}, keep the best few. Client thread only.
 */
public final class Targets {
	/** Reused by {@link #get}; client thread only. */
	private static final List<Entity> SCRATCH = new ArrayList<>();

	private Targets() {
	}

	/** How candidates are ordered, best first. */
	public enum SortPriority implements Comparator<Entity> {
		LowestDistance(Comparator.comparingDouble(Targets::distanceSq)),
		HighestDistance(Comparator.comparingDouble(Targets::distanceSq).reversed()),
		LowestHealth(Comparator.comparingDouble(Targets::health)),
		HighestHealth(Comparator.comparingDouble(e -> e instanceof LivingEntity ? -health(e) : Double.MAX_VALUE)),
		ClosestAngle(Comparator.comparingDouble(Targets::angle));

		private final Comparator<Entity> comparator;

		SortPriority(Comparator<Entity> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Entity a, Entity b) {
			return comparator.compare(a, b);
		}
	}

	/** The best entity matching {@code isGood}, or null. */
	public static Entity get(Predicate<Entity> isGood, SortPriority sort) {
		getList(SCRATCH, isGood, sort, 1);
		Entity best = SCRATCH.isEmpty() ? null : SCRATCH.getFirst();
		SCRATCH.clear();
		return best;
	}

	/** Fills {@code out} with up to {@code max} matching entities, best first. */
	public static void getList(List<Entity> out, Predicate<Entity> isGood, SortPriority sort, int max) {
		getList(out, isGood, sort, sort, max);
	}

	/** {@link #getList} with {@code then} breaking ties of {@code sort}. */
	public static void getList(List<Entity> out, Predicate<Entity> isGood, SortPriority sort, SortPriority then, int max) {
		out.clear();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || max <= 0) return;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity != null && entity != mc.player && isGood.test(entity)) out.add(entity);
		}
		out.sort(sort.thenComparing(then));
		if (out.size() > max) out.subList(max, out.size()).clear();
	}

	/**
	 * The best player to act on within {@code range}: alive, not a friend, and in survival or
	 * adventure (creative and spectator players cannot be hurt).
	 */
	public static Player playerTarget(double range, SortPriority sort) {
		return (Player) get(entity -> entity instanceof Player player && isAttackablePlayer(player) && withinRange(player, range), sort);
	}

	public static boolean isAttackablePlayer(Player player) {
		if (player instanceof LocalPlayer || !player.isAlive() || player.isDeadOrDying()) return false;
		if (isProtectedFriend(player)) return false;
		GameType mode = gameMode(player);
		return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
	}

	/** True for friends whose "Never targeted" rule applies. */
	public static boolean isProtectedFriend(Entity entity) {
		return entity instanceof Player player && Friends.is(player.getGameProfile().name(), dev.coffeeclient.friend.FriendTrait.PROTECTED);
	}

	/** The game mode from the tab list; survival for players the tab list does not know (fake players). */
	public static GameType gameMode(Player player) {
		if (player instanceof FakePlayerEntity) return GameType.SURVIVAL;
		var connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
		return info == null ? GameType.SURVIVAL : info.getGameMode();
	}

	/** True when {@code entity} is within a {@code fov}-degree cone around where the player looks; 360 lets everything through. */
	public static boolean inFov(Entity entity, double fov) {
		return fov >= 360 || angle(entity) <= fov / 2;
	}

	/** Common skips: asleep in a bed, invisible, or elytra flying. */
	public static boolean excluded(Entity entity, boolean sleeping, boolean invisible, boolean flying) {
		if (invisible && entity.isInvisible()) return true;
		if (!(entity instanceof LivingEntity living)) return false;
		return sleeping && living.isSleeping() || flying && living.isFallFlying();
	}

	/** A player the tab list doesn't know: a server NPC or anti-cheat bot. Fake players count as real. */
	public static boolean isBot(Player player) {
		var connection = Minecraft.getInstance().getConnection();
		return !(player instanceof FakePlayerEntity) && connection != null && connection.getPlayerInfo(player.getUUID()) == null;
	}

	/** On the player's team: the same scoreboard team, or a leather helmet dyed the same colour. */
	public static boolean isTeammate(Entity entity) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		if (player.isAlliedTo(entity)) return true;
		if (!(entity instanceof LivingEntity living)) return false;
		DyedItemColor mine = player.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.DYED_COLOR);
		return mine != null && mine.equals(living.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.DYED_COLOR));
	}

	public static boolean withinRange(Entity entity, double range) {
		return distanceSq(entity) <= range * range;
	}

	/** Squared distance from the player's eyes to the nearest point of the entity's hitbox. */
	public static double distanceSq(Entity entity) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return Double.MAX_VALUE;
		return entity.getBoundingBox().distanceToSqr(player.getEyePosition());
	}

	private static double health(Entity entity) {
		// Non-living entities sort after every living one.
		return entity instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : Double.MAX_VALUE;
	}

	private static double angle(Entity entity) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return Double.MAX_VALUE;
		return new Rotations.Rotation(player.getYRot(), player.getXRot()).angleTo(Rotations.needed(entity));
	}
}
