package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * A knitted scarf in the theme accent: a puffy wrap around the neck and two tails down the back that
 * lift and flutter as you speed up, streaming straight out behind you on an elytra.
 */
public final class Scarf extends CosmeticModule {
	private static final int WRAP_SEGMENTS = 28;
	private static final float SEGMENT = 0.085f, THICK = 0.012f;
	/** Rows of the wrap from bottom to top as {height, half width, half depth}: it hugs the shoulders and puffs out around the head. */
	private static final float[][] ROWS = {
		{-0.07f, 0.24f, 0.14f}, {-0.025f, 0.275f, 0.2f}, {0.035f, 0.29f, 0.275f}, {0.09f, 0.27f, 0.265f}, {0.11f, 0.24f, 0.24f}
	};

	public final NumberSetting length = setting(new NumberSetting("Length", "How far the tails hang", 1.0, 0.5, 1.6, 0.05));
	public final BooleanSetting stripes = setting(new BooleanSetting("Stripes", "Knitted stripes in a deeper shade", true));

	/** Per player: smoothed wind on the tails, 0 = still, 1 = full speed. */
	private final Map<Integer, Float> wind = new HashMap<>();
	private long lastNanos = System.nanoTime();

	public Scarf() {
		super("Scarf", "A knitted scarf in your accent colour that flutters as you move (only you can see it).");
	}

	@Override
	protected void onDisable() {
		wind.clear();
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		for (Player p : wearers()) {
			float h = (float) Math.hypot(p.getX() - p.xo, p.getZ() - p.zo), dy = (float) (p.getY() - p.yo);
			// sprinting is about 0.28 blocks a tick; a fast fall lifts the tails too
			float target = Math.max(Mth.clamp(h / 0.28f, 0, 1), Mth.clamp(-dy / 0.8f, 0, 1));
			if (p.isFallFlying()) target = 1;
			float w = wind.getOrDefault(p.getId(), target);
			wind.put(p.getId(), w + (target - w) * (1 - (float) Math.exp(-dt * 4)));
		}
		super.renderWorld(pose, cam);
	}

	@Override
	protected void render(PoseStack pose, Player player, float pt, float bodyYaw) {
		toNeck(pose, player, pt, bodyYaw);
		float w = wind.getOrDefault(player.getId(), 0f);
		float t = time() + player.getId() * 0.71f;
		boolean gliding = player.isFallFlying();
		solids(pose, (p, vc) -> {
			wrap(p, vc);
			tail(p, vc, 0.075f, 0.125f, Math.round(7 * length.floatValue()), w, t, gliding);
			tail(p, vc, -0.055f, 0.11f, Math.round(6 * length.floatValue()), w, t + 1.9f, gliding);
		});
	}

	/** Scarf colour {@code pos} of the way along it, with every other band a deeper knit shade. */
	private int yarn(float pos, int band) {
		int c = color(pos, 0xFF);
		return stripes.get() && band % 2 == 1 ? Colors.shade(c, 0.74f) : c;
	}

	private void wrap(PoseStack.Pose p, VertexConsumer vc) {
		for (int i = 0; i < WRAP_SEGMENTS; i++) {
			float a0 = (float) (i * Math.PI * 2 / WRAP_SEGMENTS), a1 = (float) ((i + 1) * Math.PI * 2 / WRAP_SEGMENTS);
			int base = yarn(i / (float) WRAP_SEGMENTS * 0.4f, i / 3);
			for (int r = 0; r + 1 < ROWS.length; r++) {
				float[] lo = ROWS[r], hi = ROWS[r + 1];
				float ny = (lo[1] - hi[1]) * 3; // a row that narrows going up faces upward
				float mid = (a0 + a1) / 2;
				int c = lit(base, (float) Math.sin(mid), ny, (float) Math.cos(mid));
				vertex(p, vc, c, a0, lo);
				vertex(p, vc, c, a1, lo);
				vertex(p, vc, c, a1, hi);
				vertex(p, vc, c, a0, hi);
			}
		}
	}

	/** A point on a wrap row at angle {@code a} (0 = front), on a rounded square so it sits snug on the blocky body. */
	private static void vertex(PoseStack.Pose p, VertexConsumer vc, int c, float a, float[] row) {
		float s = (float) Math.sin(a), co = (float) Math.cos(a);
		float x = Math.signum(s) * (float) Math.pow(Math.abs(s), 0.6) * row[1];
		float z = Math.signum(co) * (float) Math.pow(Math.abs(co), 0.6) * row[2];
		vc.addVertex(p, x, row[0], z).setColor(c);
	}

	/**
	 * One tail hanging from the back of the wrap, built segment by segment. Each segment bends further back
	 * with the wind and ripples with a wave that runs down the tail, stronger toward the free end.
	 */
	private void tail(PoseStack.Pose p, VertexConsumer vc, float x, float width, int n, float w, float t, boolean gliding) {
		// on an elytra the body is level, so "down" the body is already straight out behind
		float bend = gliding ? 8 : 4 + 68 * w, curl = gliding ? 0.5f : 1.5f + 1.5f * w;
		float freq = 2.5f + 9 * w, amp = 2.5f + 10 * w;
		float px = x, py = -0.05f, pz = -0.16f;
		for (int i = 0; i < n; i++) {
			float f = (i + 1) / (float) n;
			double ang = Math.toRadians(bend + i * curl + Math.sin(t * freq - i * 0.75f) * amp * f);
			float sway = (float) Math.sin(t * 1.7f - i * 0.6f) * 0.006f * i * (0.3f + w);
			float qx = x + sway, qy = py - (float) Math.cos(ang) * SEGMENT, qz = pz - (float) Math.sin(ang) * SEGMENT;
			float w0 = width * (1 - 0.12f * i / n), w1 = width * (1 - 0.12f * (i + 1) / n);
			// the face normal points away from the back, tilted with the segment
			float nz = -(float) Math.cos(ang), ny = (float) Math.sin(ang);
			int c = lit(yarn(0.4f + 0.6f * f, i / 2 + 1), 0, ny, nz);
			int edge = Colors.shade(c, 0.8f);
			ribbon(p, vc, c, edge, px, py, pz, qx, qy, qz, w0, w1, ny, nz);
			px = qx;
			py = qy;
			pz = qz;
			if (i == n - 1) fringe(p, vc, Colors.lerp(c, 0xFFFFFFFF, 0.18f), qx, qy, qz, w1, ang, t);
		}
	}

	/** One tail segment from p to q: front and back faces {@code THICK} apart, and the two side edges. */
	private static void ribbon(PoseStack.Pose p, VertexConsumer vc, int c, int edge, float px, float py, float pz,
							   float qx, float qy, float qz, float w0, float w1, float ny, float nz) {
		float oy = ny * THICK, oz = nz * THICK;
		for (int s = -1; s <= 1; s += 2) {
			float dy = oy * s * 0.5f, dz = oz * s * 0.5f;
			quad(p, vc, c, px - w0 / 2, py + dy, pz + dz, px + w0 / 2, py + dy, pz + dz,
				qx + w1 / 2, qy + dy, qz + dz, qx - w1 / 2, qy + dy, qz + dz);
			float ex0 = px + s * w0 / 2, ex1 = qx + s * w1 / 2;
			quad(p, vc, edge, ex0, py - oy / 2, pz - oz / 2, ex0, py + oy / 2, pz + oz / 2,
				ex1, qy + oy / 2, qz + oz / 2, ex1, qy - oy / 2, qz - oz / 2);
		}
	}

	/** Tassels at the end of a tail, flicking a little out of step with it. */
	private static void fringe(PoseStack.Pose p, VertexConsumer vc, int c, float x, float y, float z, float width, double ang, float t) {
		for (int k = 0; k < 5; k++) {
			float fx = x - width / 2 + width * (k + 0.5f) / 5;
			double a = ang + Math.sin(t * 6 + k * 1.3f) * 0.12;
			float ey = y - (float) Math.cos(a) * 0.05f, ez = z - (float) Math.sin(a) * 0.05f;
			quad(p, vc, c, fx - 0.006f, y, z, fx + 0.006f, y, z, fx + 0.006f, ey, ez, fx - 0.006f, ey, ez);
		}
	}
}
