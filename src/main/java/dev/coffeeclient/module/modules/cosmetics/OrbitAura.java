package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glowing orbs drift around you, each leaving a trail as it moves. They are pulled toward an orbit,
 * swirl round it, follow you as you walk, bob at different heights, bounce off blocks and each
 * other, and grow in one by one. Four looks: Comet (in the cosmetic colour), Fireball, Electric and
 * Sakura. Only you see them.
 */
public final class OrbitAura extends CosmeticModule {
	public enum Effect implements Labeled {
		COMET("Comet"), FIREBALL("Fireball"), ELECTRIC("Electric"), SAKURA("Sakura");

		private final String label;

		Effect(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private static final int MAX_ORBS = 16;
	/** Ticks of past positions kept per orb, trail points drawn per tick of history, and the most points. */
	private static final int HISTORY = 62, SMOOTHING = 3, MAX_POINTS = HISTORY * SMOOTHING + 2;
	// Motion per tick: gravity, lift back to the hover height, air drag, ground friction on a bounce,
	// pull toward the orbit, swirl round it, following the wearer, a slow wander, and a speed cap.
	private static final float GRAVITY = 0.016f, BUOYANCY = 0.045f, AIR_DRAG = 0.982f, GROUND_GRIP = 0.86f;
	private static final float ORBIT_PULL = 0.012f, SWIRL = 0.0025f, FOLLOW = 0.035f, WANDER = 0.003f, MAX_SPEED = 0.9f;
	/** Ticks an orb takes to grow in, and the gap between orbs appearing. */
	private static final int APPEAR_TICKS = 12, STAGGER_TICKS = 3;
	/** A head's glow: stacked disks, widest and faintest first. */
	private static final float[] GLOW_SCALES = {2.6f, 2.0f, 1.55f, 1.2f, 0.85f, 0.5f};
	private static final float[] GLOW_ALPHAS = {0.035f, 0.05f, 0.08f, 0.13f, 0.3f, 0.95f};
	/** Sprite shapes: quads in a unit square (x, y per corner), centred on the origin. */
	private static final float[] PETAL = petal(), ROUND = round(24), SOFT = round(16);
	private static final long EPOCH = System.nanoTime();

	public final EnumSetting<Effect> effect = group(new EnumSetting<>("Effect", "How the orbs and their trails look", Effect.COMET), "Effect");
	public final NumberSetting count = group(new NumberSetting("Orbs", "How many orbs", 6, 1, MAX_ORBS, 1), "Effect");
	public final NumberSetting size = group(new NumberSetting("Size", "Orb size", 0.3, 0.15, 0.75, 0.05), "Effect");
	public final NumberSetting trailLength = group(new NumberSetting("Trail length", "How long the trails are", 1, 0.2, 3, 0.1), "Effect");
	public final NumberSetting spread = group(new NumberSetting("Spread", "Orbit radius, in blocks", 2.6, 1.2, 6, 0.1), "Motion");
	public final NumberSetting drift = group(new NumberSetting("Drift", "How fast they swirl round you", 0.5, 0, 2.5, 0.1), "Motion");
	public final NumberSetting bounce = group(new NumberSetting("Bounce", "How much speed a bounce keeps", 0.6, 0.1, 0.95, 0.05), "Motion");
	public final BooleanSetting blocks = group(new BooleanSetting("Block bounce", "Bounce off blocks instead of passing through", true), "Motion");

	private final Map<Integer, Swarm> swarms = new HashMap<>();
	private final RandomSource random = RandomSource.create();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	/** Scratch for one orb's trail: smoothed points (camera-relative) and the raw history they come from. */
	private final double[] points = new double[MAX_POINTS * 3];
	private final double[] raw = new double[HISTORY * 3];
	private final double[] near = new double[3], far = new double[3];
	private VertexConsumer vc;

	public OrbitAura() {
		super("OrbitAura", List.of("FloatingRelics", "Floating Relics"), "Glowing orbs that drift around you, trailing light (only you can see them).");
		colorMode.visibleWhen(() -> effect.get() == Effect.COMET);
		customColor.visibleWhen(() -> effect.get() == Effect.COMET && colorMode.get() == ColorMode.CUSTOM);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		swarms.clear();
	}

	/** The orbs fly round you, not on your body, so you see your own in first person too. */
	@Override
	protected List<Player> wearers() {
		List<Player> out = super.wearers();
		Player me = mc().player;
		if (me != null && !me.isSpectator() && !me.isInvisible() && !out.contains(me)) out.add(me);
		return out;
	}

	// ---- motion ----

	@Override
	public void onTick() {
		ClientLevel level = mc().level;
		if (level == null) {
			swarms.clear();
			return;
		}
		Set<Integer> seen = new HashSet<>();
		for (Player p : wearers()) {
			Swarm s = swarms.computeIfAbsent(p.getId(), id -> new Swarm());
			if (s.level != level) {
				s.level = level;
				s.live = 0;
			}
			tick(s, p, level);
			seen.add(p.getId());
		}
		swarms.keySet().retainAll(seen);
	}

	private void tick(Swarm s, Player player, ClientLevel level) {
		s.age++;
		int want = count.intValue();
		for (int i = s.live; i < want; i++) wake(s, s.orbs[i], (i - s.live) * STAGGER_TICKS);
		s.live = want;
		double px = player.getX(), py = player.getY(), pz = player.getZ();
		float moveX = (float) (px - player.xo), moveZ = (float) (pz - player.zo);
		float restitution = bounce.floatValue();
		for (int i = 0; i < s.live; i++) step(s, s.orbs[i], level, px, py, pz, player.getBbHeight(), moveX, moveZ, restitution);
		for (int i = 0; i < s.live; i++) for (int j = i + 1; j < s.live; j++) knock(s.orbs[i], s.orbs[j], restitution);
		for (int i = 0; i < s.live; i++) if (s.orbs[i].placed) s.orbs[i].remember();
	}

	private void wake(Swarm s, Orb o, int delay) {
		o.placed = false;
		o.wakeAt = s.age + delay;
		o.orbit = 0.55f + 0.45f * random.nextFloat();
		o.lift = random.nextFloat();
		o.grain = 0.82f + 0.3f * random.nextFloat();
		o.phase = random.nextFloat() * Mth.TWO_PI;
	}

	/** Puts an orb beside the wearer, rising and moving round. */
	private void place(Swarm s, Orb o, double px, double py, double pz) {
		float angle = random.nextFloat() * Mth.TWO_PI;
		double radius = spread.get() * o.orbit;
		o.x = o.lastX = px + Mth.cos(angle) * radius;
		o.y = o.lastY = py + 0.15;
		o.z = o.lastZ = pz + Mth.sin(angle) * radius;
		o.vx = -Mth.sin(angle) * 0.03f;
		o.vy = 0.14f + 0.08f * random.nextFloat();
		o.vz = Mth.cos(angle) * 0.03f;
		o.placed = true;
		o.placedAt = s.age;
		o.head = o.filled = 0;
	}

	private void step(Swarm s, Orb o, ClientLevel level, double px, double py, double pz, float height, float moveX, float moveZ, float restitution) {
		if (!o.placed) {
			if (s.age < o.wakeAt) return;
			place(s, o, px, py, pz);
		}
		o.lastX = o.x;
		o.lastY = o.y;
		o.lastZ = o.z;
		double dx = o.x - px, dz = o.z - pz;
		double flat = Math.sqrt(dx * dx + dz * dz);
		double hover = py + 0.35 + o.lift * (height + 0.2) + 0.16 * Mth.sin((s.age + o.phase * 16) * 0.045f);
		// Left far behind (a teleport, a fast elytra): start again beside the wearer.
		double leash = spread.get() * 3 + 8;
		if (flat > leash || Math.abs(o.y - hover) > leash) {
			place(s, o, px, py, pz);
			return;
		}
		float nx = flat > 1.0e-4 ? (float) (dx / flat) : 1, nz = flat > 1.0e-4 ? (float) (dz / flat) : 0;
		float pull = (float) (spread.get() * o.orbit - flat) * ORBIT_PULL;
		float swirl = drift.floatValue() * SWIRL;
		o.vx += nx * pull - nz * swirl + moveX * FOLLOW;
		o.vz += nz * pull + nx * swirl + moveZ * FOLLOW;
		float wander = (s.age + o.phase * 20) * 0.06f;
		o.vx += Mth.sin(wander) * WANDER;
		o.vz += Mth.cos(wander * 1.31f) * WANDER;
		o.vy -= GRAVITY;
		double sink = hover - o.y;
		if (sink > 0) o.vy += (float) Math.min(sink, 1.5) * BUOYANCY;
		o.vx *= AIR_DRAG;
		o.vy *= AIR_DRAG;
		o.vz *= AIR_DRAG;
		float speed = o.vx * o.vx + o.vy * o.vy + o.vz * o.vz;
		if (speed > MAX_SPEED * MAX_SPEED) {
			float k = MAX_SPEED / (float) Math.sqrt(speed);
			o.vx *= k;
			o.vy *= k;
			o.vz *= k;
		}

		boolean collide = blocks.get();
		float half = size.floatValue() * o.grain * 0.5f;
		double next = o.x + o.vx;
		if (collide && solid(level, next + Math.copySign(half, o.vx), o.y, o.z)) o.vx = -o.vx * restitution;
		else o.x = next;
		next = o.y + o.vy;
		if (collide && solid(level, o.x, next + Math.copySign(half, o.vy), o.z)) {
			if (o.vy < 0) {
				o.vx *= GROUND_GRIP;
				o.vz *= GROUND_GRIP;
			}
			o.vy = -o.vy * restitution;
		} else {
			o.y = next;
		}
		next = o.z + o.vz;
		if (collide && solid(level, o.x, o.y, next + Math.copySign(half, o.vz))) o.vz = -o.vz * restitution;
		else o.z = next;
		// Stuck inside a block (one appeared on it): pop it out upward.
		if (collide && solid(level, o.x, o.y, o.z)) {
			o.y += 0.2;
			o.lastY = o.y;
			o.vy = Math.max(o.vy, 0.08f);
		}
	}

	/** Two orbs touching push apart and trade speed along the line between them. */
	private void knock(Orb a, Orb b, float restitution) {
		if (!a.placed || !b.placed) return;
		double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		double reach = size.get() * (a.grain + b.grain) * 0.5 * 0.95;
		double d2 = dx * dx + dy * dy + dz * dz;
		if (d2 >= reach * reach || d2 < 1.0e-8) return;
		double d = Math.sqrt(d2);
		float nx = (float) (dx / d), ny = (float) (dy / d), nz = (float) (dz / d);
		double push = (reach - d) * 0.5;
		a.x -= nx * push;
		a.y -= ny * push;
		a.z -= nz * push;
		b.x += nx * push;
		b.y += ny * push;
		b.z += nz * push;
		float closing = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny + (a.vz - b.vz) * nz;
		if (closing <= 0) return;
		float impulse = closing * (1 + restitution) * 0.5f;
		a.vx -= impulse * nx;
		a.vy -= impulse * ny;
		a.vz -= impulse * nz;
		b.vx += impulse * nx;
		b.vy += impulse * ny;
		b.vz += impulse * nz;
	}

	/** Whether the point is inside a block's collision box (its overall bounds, which is close enough for orbs). */
	private boolean solid(ClientLevel level, double x, double y, double z) {
		cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
		BlockState state = level.getBlockState(cursor);
		if (state.isAir()) return false;
		VoxelShape shape = state.getCollisionShape(level, cursor);
		if (shape.isEmpty()) return false;
		double lx = x - cursor.getX(), ly = y - cursor.getY(), lz = z - cursor.getZ();
		return lx >= shape.min(Direction.Axis.X) && lx <= shape.max(Direction.Axis.X) && ly >= shape.min(Direction.Axis.Y)
			&& ly <= shape.max(Direction.Axis.Y) && lz >= shape.min(Direction.Axis.Z) && lz <= shape.max(Direction.Axis.Z);
	}

	// ---- drawing ----

	@Override
	protected void render(PoseStack pose, Player player, float partialTick, float bodyYaw) {
		// Drawn in renderWorld: the orbs live in world space, not on the body.
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		if (swarms.isEmpty() || mc().level == null) return;
		vc = OverlayRenderer.buffer(WorldOverlays.fillType(false));
		float pt = partialTick();
		float time = (System.nanoTime() - EPOCH) / 1.0e9f;
		int samples = Mth.clamp(Math.round(trailLength.floatValue() * 20), 2, HISTORY - 2);
		Effect look = effect.get();
		for (Swarm s : swarms.values()) {
			for (int i = 0; i < s.live; i++) {
				Orb o = s.orbs[i];
				if (!o.placed) continue;
				float extent = size.floatValue() * 0.5f * o.grain * appear((s.age - o.placedAt + pt) / APPEAR_TICKS);
				if (extent < 0.004f) continue;
				int n = trail(o, pt, samples, cam);
				int base = comet(i / (float) Math.max(1, s.live));
				drawTrail(look, i, n, extent, time, base);
				int h = (n - 1) * 3;
				drawHead(look, points[h], points[h + 1], points[h + 2], extent, time, o.phase, base);
			}
		}
		vc = null;
	}

	/** Comet's colour: the cosmetic colour (theme accent or custom), as RGB. */
	private int comet(float pos) {
		return color(pos, 0xFF) & 0xFFFFFF;
	}

	/** Fills {@link #points} with the orb's trail, smoothed through its history and ending at its current position; returns the point count. */
	private int trail(Orb o, float pt, int samples, Vec3 cam) {
		int m = Math.min(o.filled, samples + 2);
		if (m < 2) {
			points[0] = Mth.lerp(pt, o.lastX, o.x) - cam.x;
			points[1] = Mth.lerp(pt, o.lastY, o.y) - cam.y;
			points[2] = Mth.lerp(pt, o.lastZ, o.z) - cam.z;
			return 1;
		}
		for (int k = 0; k < m; k++) {
			int slot = Math.floorMod(o.head - m + k, HISTORY) * 3;
			raw[k * 3] = o.history[slot] - cam.x;
			raw[k * 3 + 1] = o.history[slot + 1] - cam.y;
			raw[k * 3 + 2] = o.history[slot + 2] - cam.z;
		}
		double head = m - 2 + pt, tail = Math.max(0, head - samples);
		int n = Math.max(2, (int) Math.ceil((head - tail) * SMOOTHING) + 1);
		for (int j = 0; j < n; j++) {
			double u = j == n - 1 ? head : tail + (head - tail) * j / (n - 1);
			int k = Math.min(m - 2, (int) u);
			double t = u - k;
			int k0 = Math.max(0, k - 1) * 3, k1 = k * 3, k2 = (k + 1) * 3, k3 = Math.min(m - 1, k + 2) * 3;
			for (int axis = 0; axis < 3; axis++) points[j * 3 + axis] = catmull(raw[k0 + axis], raw[k1 + axis], raw[k2 + axis], raw[k3 + axis], t);
		}
		return n;
	}

	private static double catmull(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t, t3 = t2 * t;
		return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
	}

	private void drawTrail(Effect look, int orb, int n, float extent, float time, int comet) {
		if (n < 2) return;
		float width = extent * 0.7f;
		for (int k = 1; k < n; k++) {
			float t = (float) k / (n - 1), tb = (float) (k - 1) / (n - 1);
			int a = (k - 1) * 3, b = k * 3, back = n - 1 - k;
			double ax = points[a], ay = points[a + 1], az = points[a + 2];
			double bx = points[b], by = points[b + 1], bz = points[b + 2];
			switch (look) {
				case COMET -> {
					int col = lerpRgb(comet, lerpRgb(comet, 0xFFFFFF, 0.75f), t);
					strip(ax, ay, az, bx, by, bz, width * 1.9f * tb, width * 1.9f * t, withAlpha(col, 0.16f * t * t));
					strip(ax, ay, az, bx, by, bz, width * tb, width * t, withAlpha(col, 0.75f * t * t));
				}
				case FIREBALL -> {
					// Embers along the trail, and sparks drifting up off it.
					if (back % 3 == 0) glowDisk(bx, by, bz, extent * (0.35f + 0.8f * t), lerpRgb(0xC81E00, 0xFFC040, t), 0.55f * t * t);
					if (back % 4 == 0) {
						long seed = orb * 7919L + back * 104729L + (long) (time * 6);
						double sx = bx + jitter(seed, 1) * extent * 1.6, sy = by + jitter(seed, 2) * extent + (1 - t) * extent * 2.4, sz = bz + jitter(seed, 3) * extent * 1.6;
						sprite(ROUND, sx, sy, sz, extent * 0.09f, 0, withAlpha(0xFFD27A, 0.9f * t));
					}
				}
				case ELECTRIC -> {
					// Each segment re-jags several times a second.
					long seed = orb * 7919L + back * 104729L + (long) (time * 14);
					jag(far, ax, ay, az, seed + 104729L, extent * 0.7 * (1 - tb * 0.5f));
					jag(near, bx, by, bz, seed, extent * 0.7 * (1 - t * 0.5f));
					strip(far[0], far[1], far[2], near[0], near[1], near[2], width * 0.6f, width * 0.6f, withAlpha(0x5FB8FF, 0.3f * t));
					strip(far[0], far[1], far[2], near[0], near[1], near[2], width * 0.16f, width * 0.16f, withAlpha(0xE6F7FF, 0.95f * t));
				}
				case SAKURA -> {
					// Petals shed along the trail, falling and spinning as they age.
					if (back % 6 == 0) {
						long seed = orb * 7919L + back * 104729L;
						double fall = (1 - t) * extent * 3;
						double px = bx + jitter(seed, 1) * extent * (1.4f - t), pz = bz + jitter(seed, 3) * extent * (1.4f - t);
						float spin = time * 2.1f + back * 0.7f + orb;
						sprite(PETAL, px, by - fall, pz, extent * (0.45f + 0.45f * t), spin, withAlpha(lerpRgb(0xFFE4EE, 0xFF8FB8, t), 0.95f * t));
					}
				}
			}
		}
	}

	private void drawHead(Effect look, double x, double y, double z, float extent, float time, float phase, int comet) {
		switch (look) {
			case COMET -> {
				glow(x, y, z, extent * 1.2f, lerpRgb(comet, 0xFFFFFF, 0.3f), 0.85f);
				glowDisk(x, y, z, extent * 0.38f, 0xFFFFFF, 1);
			}
			case FIREBALL -> {
				glow(x, y, z, extent * 1.1f, 0xFF7A1A, 0.9f);
				glowDisk(x, y, z, extent * 0.45f, 0xFFEDB0, 1);
			}
			case ELECTRIC -> {
				glow(x, y, z, extent * 1.1f, 0x5FB8FF, 0.9f);
				glowDisk(x, y, z, extent * 0.4f, 0xFFFFFF, 1);
			}
			case SAKURA -> {
				glow(x, y, z, extent * 0.9f, 0xFFB4D1, 0.8f);
				sprite(PETAL, x, y, z, extent * 0.85f, time * 1.6f + phase, withAlpha(0xFF9FC4, 1));
			}
		}
	}

	private void glow(double x, double y, double z, float radius, int rgb, float alpha) {
		for (int layer = 0; layer < GLOW_SCALES.length; layer++) {
			int col = layer == GLOW_SCALES.length - 1 ? lerpRgb(rgb, 0xFFFFFF, 0.6f) : rgb;
			sprite(GLOW_ALPHAS[layer] < 0.1f ? SOFT : ROUND, x, y, z, radius * GLOW_SCALES[layer], 0, withAlpha(col, alpha * GLOW_ALPHAS[layer]));
		}
	}

	private void glowDisk(double x, double y, double z, float radius, int rgb, float alpha) {
		sprite(SOFT, x, y, z, radius * 1.6f, 0, withAlpha(rgb, alpha * 0.25f));
		sprite(ROUND, x, y, z, radius, 0, withAlpha(rgb, alpha));
	}

	/** A flat shape facing the camera at a camera-relative point, turned by {@code spin} radians. */
	private void sprite(float[] shape, double x, double y, double z, float radius, float spin, int argb) {
		double len = Math.sqrt(x * x + y * y + z * z);
		if (len < 1.0e-6) return;
		double fx = x / len, fy = y / len, fz = z / len;
		// right = forward x up, then up = right x forward; straight up or down, any right will do.
		double rx = -fz, rz = fx, rl = Math.sqrt(rx * rx + rz * rz);
		if (rl < 1.0e-6) {
			rx = 1;
			rz = 0;
			rl = 1;
		}
		rx /= rl;
		rz /= rl;
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		double c = Math.cos(spin), s = Math.sin(spin);
		double ax = (rx * c + ux * s) * radius, ay = uy * s * radius, az = (rz * c + uz * s) * radius;
		double bx = (ux * c - rx * s) * radius, by = uy * c * radius, bz = (uz * c - rz * s) * radius;
		for (int o = 0; o < shape.length; o += 8) {
			for (int v = 0; v < 4; v++) {
				float sx = shape[o + v * 2], sy = shape[o + v * 2 + 1];
				vc.addVertex((float) (x + ax * sx + bx * sy), (float) (y + ay * sx + by * sy), (float) (z + az * sx + bz * sy)).setColor(argb);
			}
		}
	}

	/** A ribbon from a to b, {@code wa} and {@code wb} wide at its ends, turned to face the camera. */
	private void strip(double ax, double ay, double az, double bx, double by, double bz, float wa, float wb, int argb) {
		if (wa <= 0 && wb <= 0) return;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double tx = -(ax + bx) * 0.5, ty = -(ay + by) * 0.5, tz = -(az + bz) * 0.5;
		double sx = dy * tz - dz * ty, sy = dz * tx - dx * tz, sz = dx * ty - dy * tx;
		double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (length < 1.0e-9) return;
		sx /= length;
		sy /= length;
		sz /= length;
		vertex(ax + sx * wa, ay + sy * wa, az + sz * wa, argb);
		vertex(bx + sx * wb, by + sy * wb, bz + sz * wb, argb);
		vertex(bx - sx * wb, by - sy * wb, bz - sz * wb, argb);
		vertex(ax - sx * wa, ay - sy * wa, az - sz * wa, argb);
	}

	private void vertex(double x, double y, double z, int argb) {
		vc.addVertex((float) x, (float) y, (float) z).setColor(argb);
	}

	private static void jag(double[] out, double x, double y, double z, long seed, double reach) {
		out[0] = x + jitter(seed, 1) * reach;
		out[1] = y + jitter(seed, 2) * reach;
		out[2] = z + jitter(seed, 3) * reach;
	}

	/** A repeatable random number in [-1, 1) for {@code seed} and {@code axis}. */
	private static double jitter(long seed, int axis) {
		long h = seed * 0x9E3779B97F4A7C15L + axis * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) / 9.007199254740992E15 * 2 - 1;
	}

	/** A disk as a fan of quads, two segments each. */
	private static float[] round(int segments) {
		float[] out = new float[segments / 2 * 8];
		for (int k = 0; k < segments; k += 2) {
			int o = k / 2 * 8;
			for (int v = 0; v < 3; v++) {
				float angle = (k + v) * Mth.TWO_PI / segments;
				out[o + 2 + v * 2] = Mth.cos(angle);
				out[o + 3 + v * 2] = Mth.sin(angle);
			}
		}
		return out;
	}

	/** A cherry-blossom petal, narrow at the base with a notch at the tip, as a fan of triangles. */
	private static float[] petal() {
		int n = 16;
		double[] px = new double[n], py = new double[n];
		for (int k = 0; k < n; k++) {
			double t = (double) k / n * Math.PI * 2;
			double notch = 1 - 0.28 * Math.pow(Math.max(0, Math.cos(t - Math.PI / 2)), 12);
			px[k] = Math.cos(t) * 0.62 * (0.75 + 0.25 * Math.sin(t)) * notch;
			py[k] = Math.sin(t) * notch;
		}
		float[] out = new float[n * 8];
		for (int k = 0; k < n; k++) {
			int j = (k + 1) % n, o = k * 8;
			out[o + 2] = (float) px[k];
			out[o + 3] = (float) py[k];
			out[o + 4] = out[o + 6] = (float) px[j];
			out[o + 5] = out[o + 7] = (float) py[j];
		}
		return out;
	}

	/** Grow-in curve: eases out with a small overshoot. */
	private static float appear(float t) {
		if (t >= 1) return 1;
		if (t <= 0) return 0;
		float inv = 1 - t;
		return 1 - inv * inv * inv + 0.22f * Mth.sin(t * Mth.PI) * inv;
	}

	private static int lerpRgb(int a, int b, float t) {
		t = Mth.clamp(t, 0, 1);
		int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
		int g = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return r << 16 | g << 8 | bl;
	}

	private static int withAlpha(int rgb, float alpha) {
		return (int) (Mth.clamp(alpha, 0, 1) * 255 + 0.5f) << 24 | rgb & 0xFFFFFF;
	}

	/** One wearer's orbs. */
	private static final class Swarm {
		final Orb[] orbs = new Orb[MAX_ORBS];
		int live, age;
		ClientLevel level;

		Swarm() {
			for (int i = 0; i < MAX_ORBS; i++) orbs[i] = new Orb();
		}
	}

	private static final class Orb {
		double x, y, z, lastX, lastY, lastZ;
		float vx, vy, vz;
		/** Share of the orbit radius, share of the body height it hovers at, size factor, and bob phase. */
		float orbit, lift, grain, phase;
		boolean placed;
		int wakeAt, placedAt;
		final double[] history = new double[HISTORY * 3];
		int head, filled;

		void remember() {
			history[head * 3] = x;
			history[head * 3 + 1] = y;
			history[head * 3 + 2] = z;
			head = (head + 1) % HISTORY;
			filled = Math.min(HISTORY, filled + 1);
		}
	}
}
