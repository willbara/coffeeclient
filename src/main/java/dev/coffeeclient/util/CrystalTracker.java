package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the client knows about end crystals ahead of the server.
 * A crystal that was hit, or caught in a blast, counts as dead until the server removes it (or the
 * answer is overdue), so nothing wastes hits on it. Crystals being placed reserve their spot; crystals
 * that then appear there are "own". The newest entity id is kept for id prediction, and the position
 * of each removed crystal for a short while, so a damage packet can be traced to its crystal.
 */
public final class CrystalTracker {
	private static final long OWN_LIFETIME = 10_000;
	/** Smaller explosions (wind charges) do not set off crystals. */
	private static final float MIN_RADIUS = 2;
	private static final long REMOVED_LIFETIME = 2_000;
	private static final double DROP_KILL_RANGE = 6;

	private record Removed(Vec3 pos, long time) {
	}

	/** Bases with a crystal on its way, until when. */
	private static final Long2LongOpenHashMap placing = new Long2LongOpenHashMap();
	private static final Int2LongOpenHashMap own = new Int2LongOpenHashMap();
	/** Entities (crystals, drops) expected gone, until when. */
	private static final Int2LongOpenHashMap dead = new Int2LongOpenHashMap();
	private static final Int2ObjectOpenHashMap<Removed> removed = new Int2ObjectOpenHashMap<>();
	private static int highestId;

	private CrystalTracker() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.PacketApply.Pre.class, EventBus.HIGHEST, e -> {
			Minecraft mc = Minecraft.getInstance();
			switch (e.packet) {
				case ClientboundAddEntityPacket packet -> highestId = Math.max(highestId, packet.getId());
				case ClientboundRemoveEntitiesPacket packet when mc.level != null -> {
					long now = now();
					for (int id : packet.getEntityIds()) {
						if (mc.level.getEntity(id) instanceof EndCrystal crystal) removed.put(id, new Removed(crystal.position(), now));
					}
				}
				default -> {
				}
			}
		});
		bus.subscribe(Events.PacketApply.Post.class, EventBus.HIGHEST, e -> {
			if (Minecraft.getInstance().level == null) return;
			switch (e.packet()) {
				case ClientboundExplodePacket packet when packet.radius() >= MIN_RADIUS && !HurtTracker.isWind(packet) ->
					killNear(packet.center(), packet.radius() * 2.0, Latency.timeout() * 2L);
				case ClientboundRemoveEntitiesPacket packet -> {
					for (int id : packet.getEntityIds()) {
						dead.remove(id);
						own.remove(id);
					}
				}
				default -> {
				}
			}
		});
		bus.subscribe(Events.EntityAdded.class, EventBus.HIGHEST, e -> {
			if (!(e.entity() instanceof EndCrystal crystal)) return;
			if (placing.remove(crystal.blockPosition().below().asLong()) != placing.defaultReturnValue()) own.put(crystal.getId(), now());
		});
		bus.subscribe(Events.Tick.class, e -> {
			long now = now();
			placing.values().removeIf(until -> now >= until);
			dead.values().removeIf(until -> now >= until);
			own.values().removeIf(time -> now - time > OWN_LIFETIME);
			removed.values().removeIf(entry -> now - entry.time > REMOVED_LIFETIME);
		});
		bus.subscribe(Events.GameLeft.class, e -> reset());
		bus.subscribe(Events.WorldChange.class, e -> reset());
	}

	/** A crystal is being placed on {@code base}. */
	public static void placed(BlockPos base) {
		placing.put(base.asLong(), now() + Latency.timeout());
	}

	public static boolean isPlacing(BlockPos base) {
		long until = placing.getOrDefault(base.asLong(), Long.MIN_VALUE);
		return until != Long.MIN_VALUE && now() < until;
	}

	/** Any crystal still on its way. */
	public static boolean hasPlacing() {
		long now = now();
		for (long until : placing.values()) if (now < until) return true;
		return false;
	}

	/** An entity entered a pending spot (a drop landed there), so that placement will fail. */
	public static boolean cancelPlacing(AABB box) {
		return placing.keySet().removeIf(key -> box.intersects(crystalBox(BlockPos.of(key))));
	}

	/** A pending placement within one block of {@code base} (the two crystals would overlap). */
	public static boolean placingOverlaps(BlockPos base) {
		long now = now();
		for (var entry : placing.long2LongEntrySet()) {
			if (now >= entry.getLongValue()) continue;
			BlockPos other = BlockPos.of(entry.getLongKey());
			if (Math.abs(other.getX() - base.getX()) <= 1 && Math.abs(other.getY() - base.getY()) <= 1 && Math.abs(other.getZ() - base.getZ()) <= 1) return true;
		}
		return false;
	}

	/** A pending crystal within {@code radius} of {@code center}. */
	public static boolean placingNear(Vec3 center, double radius) {
		long now = now();
		for (var entry : placing.long2LongEntrySet()) {
			if (now >= entry.getLongValue()) continue;
			if (center(BlockPos.of(entry.getLongKey())).distanceToSqr(center) <= radius * radius) return true;
		}
		return false;
	}

	/** Placed by us (appeared on a spot we placed on). */
	public static boolean isOwn(Entity crystal) {
		return own.containsKey(crystal.getId());
	}

	/** Treat {@code entity} as gone for {@code ms} milliseconds. */
	public static void kill(Entity entity, long ms) {
		dead.put(entity.getId(), now() + ms);
	}

	public static boolean isDead(Entity entity) {
		long until = dead.getOrDefault(entity.getId(), Long.MIN_VALUE);
		return until != Long.MIN_VALUE && now() < until;
	}

	public static boolean isAlive(Entity entity) {
		return entity.isAlive() && !isDead(entity);
	}

	/** We just set off a crystal at {@code center}: crystals in reach and drops it would destroy are dead. */
	public static void explodeAt(Vec3 center) {
		Minecraft mc = Minecraft.getInstance();
		double range = Damage.CRYSTAL_RANGE * Damage.CRYSTAL_RANGE;
		double dropRange = DROP_KILL_RANGE * DROP_KILL_RANGE;
		long ms = Latency.timeout();
		for (Entity entity : mc.level.entitiesForRendering()) {
			double distance = entity.position().distanceToSqr(center);
			if (entity instanceof EndCrystal && distance <= range) kill(entity, ms);
			else if ((entity instanceof ItemEntity || entity instanceof ExperienceOrb) && distance <= dropRange
				&& Damage.exposure(center, entity.getBoundingBox(), mc.level) > 0) kill(entity, ms);
		}
	}

	/** Where crystal {@code id} was when the server removed it (recently), or null. */
	public static Vec3 removedPosition(int id) {
		Removed entry = removed.get(id);
		return entry == null ? null : entry.pos;
	}

	/** The highest entity id the server has sent; the next entity it spawns gets the one after. */
	public static int highestId() {
		return highestId;
	}

	/** Where a crystal on {@code base} sits. */
	public static Vec3 center(BlockPos base) {
		return new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
	}

	/** The space a crystal on {@code base} would take up (the two blocks above it). */
	public static AABB crystalBox(BlockPos base) {
		return new AABB(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
	}

	private static void killNear(Vec3 center, double radius, long ms) {
		Minecraft mc = Minecraft.getInstance();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof EndCrystal && entity.position().distanceToSqr(center) <= radius * radius) kill(entity, ms);
		}
	}

	private static void reset() {
		placing.clear();
		own.clear();
		dead.clear();
		removed.clear();
		highestId = 0;
	}

	private static long now() {
		return System.currentTimeMillis();
	}
}
