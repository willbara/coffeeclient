package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Hit immunity of living entities. After a hit an entity ignores
 * any hit no stronger than it for 10 ticks, and takes only the difference from a stronger one. This
 * remembers each entity's last hit (when it landed, how strong it was before armour) from the
 * server's damage packets and from hits CrystalAura has just sent, so {@link #effective} says what a
 * new hit arriving at a given time would really deal.
 *
 * <p>Times are wall-clock milliseconds; "arrival" is when the server will apply the hit (now plus half
 * the round trip). Hits whose strength is unknown count as unknown, never as zero.
 */
public final class HurtTracker {
	private static final int IMMUNITY_TICKS = 10;
	/** Your own immunity is read conservatively (shorter), so it never hides damage you would take. */
	private static final long SELF_WINDOW = 500;
	private static final float PEARL_DAMAGE = 5;
	private static final long MARGIN = 50;
	private static final long LIFETIME = 3_000;
	/** An explosion packet this soon after the damage packet still explains it. */
	private static final long LATE_EXPLOSION = 100;
	private static final int RECEIVED_LIMIT = 256;

	private static final class Hurt {
		final long start;
		final long received;
		final boolean explosion;
		float raw;
		float speculative = Float.NaN;

		Hurt(long start, long received, boolean explosion, float raw) {
			this.start = start;
			this.received = received;
			this.explosion = explosion;
			this.raw = raw;
		}
	}

	/** A hit we sent that the server has not reported yet. */
	private static final class Expected {
		final long time;
		float amount;
		float speculative = Float.NaN;

		Expected(long time, float amount) {
			this.time = time;
			this.amount = amount;
		}
	}

	private record Window(long start, float raw, boolean explosion) {
	}

	private static final Int2ObjectOpenHashMap<Hurt> hurts = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<Expected> expected = new Int2ObjectOpenHashMap<>();
	/** When each damage or explosion packet arrived off the wire (the client thread sees it later). */
	private static final Map<Packet<?>, Long> received = Collections.synchronizedMap(new IdentityHashMap<>());

	private HurtTracker() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.PacketReceive.class, EventBus.LOWEST, e -> {
			if (e.isCancelled() || !(e.packet instanceof ClientboundDamageEventPacket) && !(e.packet instanceof ClientboundExplodePacket)) return;
			synchronized (received) {
				if (received.size() >= RECEIVED_LIMIT) received.clear();
				received.put(e.packet, now());
			}
		});
		bus.subscribe(Events.PacketApply.Post.class, EventBus.HIGHEST, e -> {
			switch (e.packet()) {
				case ClientboundDamageEventPacket packet -> onDamage(packet);
				case ClientboundExplodePacket packet -> onExplosion(packet);
				default -> {
				}
			}
		});
		bus.subscribe(Events.Tick.class, e -> {
			long now = now();
			hurts.values().removeIf(hurt -> now - hurt.start > LIFETIME);
			expected.values().removeIf(cause -> now - cause.time > LIFETIME);
		});
		bus.subscribe(Events.GameLeft.class, e -> reset());
		bus.subscribe(Events.WorldChange.class, e -> reset());
	}

	/** We just sent a hit on {@code entity} worth {@code raw} before armour. */
	public static void expect(LivingEntity entity, float raw) {
		long now = now();
		long arrival = now + Latency.rtt() / 2;
		Expected pending = freshExpected(entity.getId(), now);
		if (pending != null && arrival >= expectedStart(pending) && arrival < expectedStart(pending) + window() + MARGIN) {
			pending.amount = max(pending.amount, raw);
			return;
		}
		Hurt hurt = hurts.get(entity.getId());
		if (hurt != null && arrival >= hurt.start && arrival < hurt.start + window() + MARGIN) {
			if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, raw);
			return;
		}
		expected.put(entity.getId(), new Expected(now, raw));
	}

	/** Like {@link #expect} for a hit that may not land (a predicted crystal); {@link #confirmSpeculative} or {@link #forgetSpeculative} settles it. */
	public static void expectSpeculative(LivingEntity entity, float raw) {
		long now = now();
		long arrival = now + Latency.rtt() / 2;
		Expected pending = freshExpected(entity.getId(), now);
		if (pending != null && arrival >= expectedStart(pending) && arrival < expectedStart(pending) + window() + MARGIN) {
			pending.speculative = max(pending.speculative, raw);
			return;
		}
		Hurt hurt = hurts.get(entity.getId());
		if (hurt != null && arrival >= hurt.start && arrival < hurt.start + window() + MARGIN) {
			hurt.speculative = max(hurt.speculative, raw);
			return;
		}
		Expected created = new Expected(now, Float.NaN);
		created.speculative = raw;
		expected.put(entity.getId(), created);
	}

	public static void confirmSpeculative(LivingEntity entity) {
		Expected pending = expected.get(entity.getId());
		if (pending != null && !Float.isNaN(pending.speculative)) {
			pending.amount = max(pending.amount, pending.speculative);
			pending.speculative = Float.NaN;
		}
		Hurt hurt = hurts.get(entity.getId());
		if (hurt != null && !Float.isNaN(hurt.speculative)) {
			if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, hurt.speculative);
			hurt.speculative = Float.NaN;
		}
	}

	public static void forgetSpeculative(LivingEntity entity) {
		Expected pending = expected.get(entity.getId());
		if (pending != null) {
			pending.speculative = Float.NaN;
			if (Float.isNaN(pending.amount)) expected.remove(entity.getId());
		}
		Hurt hurt = hurts.get(entity.getId());
		if (hurt != null) hurt.speculative = Float.NaN;
	}

	/** Damage after armour that a hit worth {@code raw} would deal to {@code entity} if it lands at {@code arrival}. */
	public static float effective(LivingEntity entity, float raw, long arrival) {
		return damage(entity, raw, arrival, false);
	}

	/** {@link #effective} for yourself, reading your immunity on the short side. */
	public static float effectiveOnSelf(float raw, long arrival) {
		return damage(Minecraft.getInstance().player, raw, arrival, true);
	}

	/** When a hit sent now would first land after {@code entity}'s immunity ends (0 when it is not immune). */
	public static long releaseTime(LivingEntity entity) {
		Window latest = window(entity, Long.MAX_VALUE);
		if (latest == null) return 0;
		return latest.start + window() + targetMargin() - Latency.rtt() / 2;
	}

	private static float damage(LivingEntity entity, float raw, long arrival, boolean self) {
		float full = Damage.mitigate(entity, raw);
		Window current = window(entity, arrival);
		if (current == null) return full;
		boolean immune = self
			? arrival < current.start + SELF_WINDOW - Latency.tickMs() - MARGIN - Latency.jitter()
			: arrival < current.start + window() + targetMargin();
		if (!immune) return full;
		// Unknown strength: assume the worst for yourself, and that a target's explosion hit blocks ours.
		if (Float.isNaN(current.raw)) return self || !current.explosion ? full : 0;
		if (raw <= current.raw) return 0;
		return Damage.mitigate(entity, raw - current.raw);
	}

	private static Window window(LivingEntity entity, long arrival) {
		int id = entity.getId();
		Window best = null;
		Hurt hurt = hurts.get(id);
		if (hurt != null && hurt.start <= arrival) {
			best = new Window(hurt.start, Float.isNaN(hurt.raw) ? Float.NaN : max(hurt.raw, hurt.speculative), hurt.explosion);
		}
		Expected pending = freshExpected(id, now());
		if (pending != null) {
			long start = expectedStart(pending);
			if (start <= arrival && (best == null || start > best.start)) best = new Window(start, max(pending.amount, pending.speculative), true);
		}
		return best;
	}

	private static Expected freshExpected(int id, long now) {
		Expected pending = expected.get(id);
		return pending != null && now - pending.time <= Latency.timeout() ? pending : null;
	}

	private static long expectedStart(Expected pending) {
		return pending.time + Latency.rtt() / 2;
	}

	private static void onDamage(ClientboundDamageEventPacket packet) {
		long now = now();
		long time = receivedAt(packet, now);
		Expected cause = expected.remove(packet.entityId());
		boolean fresh = cause != null && now - cause.time <= Latency.timeout();
		float amount = fresh ? cause.amount : Float.NaN;
		amount = max(amount, crystalSource(packet));
		if (Float.isNaN(amount)) amount = fixedAmount(packet);

		Hurt hurt = new Hurt(time - Latency.rtt() / 2, time, packet.sourceType().is(DamageTypeTags.IS_EXPLOSION), amount);
		if (fresh) hurt.speculative = cause.speculative;
		hurts.put(packet.entityId(), hurt);
	}

	/** Environmental damage with a fixed amount; unknown (NaN) otherwise, and always for yourself. */
	private static float fixedAmount(ClientboundDamageEventPacket packet) {
		Holder<DamageType> type = packet.sourceType();
		if (type.is(DamageTypes.ENDER_PEARL)) return PEARL_DAMAGE;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && packet.entityId() == mc.player.getId()) return Float.NaN;
		if (type.is(DamageTypes.LAVA)) return 4;
		if (type.is(DamageTypes.IN_FIRE) || type.is(DamageTypes.CAMPFIRE) || type.is(DamageTypes.DROWN)) return 2;
		if (type.is(DamageTypes.ON_FIRE) || type.is(DamageTypes.HOT_FLOOR) || type.is(DamageTypes.CACTUS)
			|| type.is(DamageTypes.SWEET_BERRY_BUSH) || type.is(DamageTypes.STARVE) || type.is(DamageTypes.IN_WALL)) return 1;
		return Float.NaN;
	}

	/** An explosion right after a damage packet tells how strong that hit was. */
	private static void onExplosion(ClientboundExplodePacket packet) {
		Minecraft mc = Minecraft.getInstance();
		long now = receivedAt(packet, now());
		if (mc.level == null || isWind(packet)) return;
		long happened = now - Latency.rtt() / 2;
		for (Int2ObjectMap.Entry<Hurt> entry : hurts.int2ObjectEntrySet()) {
			Hurt hurt = entry.getValue();
			if (mc.player != null && entry.getIntKey() == mc.player.getId()) continue;
			if (happened < hurt.start || happened >= hurt.start + window() + MARGIN) continue;
			if (!(mc.level.getEntity(entry.getIntKey()) instanceof LivingEntity living)) continue;
			float raw = Damage.explosionRaw(living, MotionTracker.position(living), MotionTracker.box(living), packet.center(), packet.radius(), mc.level);
			if (raw <= 0) continue;
			if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, raw);
			else if (hurt.explosion && now - hurt.received <= LATE_EXPLOSION) hurt.raw = raw;
		}
	}

	/** Raw damage of the crystal that caused this hit, when it was one we saw. */
	private static float crystalSource(ClientboundDamageEventPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(packet.entityId()) instanceof LivingEntity living)) return Float.NaN;
		Vec3 crystal = CrystalTracker.removedPosition(packet.sourceDirectId());
		if (crystal == null) return Float.NaN;
		return Damage.crystalRaw(living, MotionTracker.position(living), MotionTracker.box(living), crystal, mc.level);
	}

	/** Wind charges and breeze bursts knock back but deal no blast damage. */
	public static boolean isWind(ClientboundExplodePacket packet) {
		var type = packet.explosionParticle().getType();
		return type == ParticleTypes.GUST_EMITTER_SMALL || type == ParticleTypes.GUST_EMITTER_LARGE;
	}

	private static long receivedAt(Packet<?> packet, long now) {
		Long time = received.remove(packet);
		return time != null && time <= now ? time : now;
	}

	private static long targetMargin() {
		return Math.max(2L * Latency.jitter(), MARGIN / 2);
	}

	private static long window() {
		return IMMUNITY_TICKS * Latency.tickMs();
	}

	private static float max(float a, float b) {
		if (Float.isNaN(a)) return b;
		if (Float.isNaN(b)) return a;
		return Math.max(a, b);
	}

	private static void reset() {
		hurts.clear();
		expected.clear();
		received.clear();
	}

	private static long now() {
		return System.currentTimeMillis();
	}
}
