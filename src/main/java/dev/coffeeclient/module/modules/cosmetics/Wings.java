package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Feathered wings: a jointed arm carrying three layers of tapered feathers (long primaries at the tip,
 * secondaries along the arm, short coverts on top), each shading from the accent at the root to a pale
 * tip. At rest they breathe slowly; sprinting, gliding or flying eases them into full wingbeats.
 */
public final class Wings extends CosmeticModule {
	public final BooleanSetting replaceElytra = setting(new BooleanSetting("Replace elytra", "Hide the vanilla elytra on everyone these wings show on, so only the feathers are drawn", false));
	public final NumberSetting size = setting(new NumberSetting("Size", "Wingspan", 1.0, 0.5, 1.8, 0.05));
	public final NumberSetting flap = setting(new NumberSetting("Flap speed", "How fast the wings beat", 1.0, 0.2, 3.0, 0.1));
	public final NumberSetting opacity = setting(new NumberSetting("Opacity", "Wing transparency", 0.9, 0.2, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%"));

	/** Arm joints in the wing plane: shoulder, elbow, wrist, tip (u = outward, v = up). */
	private static final float[] JU = {0f, 0.30f, 0.60f, 0.92f};
	private static final float[] JV = {0f, 0.20f, 0.28f, 0.16f};

	/** Per player: smoothed activity, 0 = resting, 1 = flying. */
	private final Map<Integer, Float> energy = new HashMap<>();
	/** Per player: wingbeat phase, advanced at the current beat rate so speed changes never jump. */
	private final Map<Integer, Float> phase = new HashMap<>();
	private long lastNanos = System.nanoTime();

	public Wings() {
		super("Wings", "Feathered wings that beat when you sprint, glide or fly (only you can see them).");
	}

	@Override
	protected void onDisable() {
		energy.clear();
		phase.clear();
	}

	/** Whether the vanilla elytra is hidden on the entity with this id, because these wings replace it there. */
	public static boolean replacesElytra(int entityId) {
		Wings wings = ModuleManager.ifOn(Wings.class);
		if (wings == null || !wings.replaceElytra.get() || mc().level == null) return false;
		Entity entity = mc().level.getEntity(entityId);
		return entity instanceof Player p && wings.wears(p);
	}

	@Override
	public void renderWorld(PoseStack pose, net.minecraft.world.phys.Vec3 cam) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		for (Player p : wearers()) {
			boolean flying = p.getAbilities().flying || p.isFallFlying();
			float target = flying ? 1f : p.isSprinting() ? 0.45f : 0f;
			float e = energy.getOrDefault(p.getId(), target);
			e += (target - e) * (1 - (float) Math.exp(-dt * 3));
			energy.put(p.getId(), e);
			float rate = (1.4f + e * 5.5f) * flap.floatValue();
			phase.put(p.getId(), phase.getOrDefault(p.getId(), (float) p.getId()) + dt * rate);
		}
		super.renderWorld(pose, cam);
	}

	@Override
	protected void render(PoseStack pose, Player player, float pt, float bodyYaw) {
		float e = energy.getOrDefault(player.getId(), 0f);
		float ph = phase.getOrDefault(player.getId(), 0f);
		float beat = (float) Math.sin(ph);
		// resting: folded back and breathing; active: spread wide and beating
		float sweep = lerp(38, 12, e) + beat * lerp(5, 22, e);
		float lift = lerp(6, 4, e) + beat * lerp(3, 30, e);
		float trail = -beat * lerp(3, 18, e);

		pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
		if (player.isFallFlying()) fallFlightTilt(pose, player, pt);
		// the standing or crouching height: the live hitbox is 0.6 blocks while flying or swimming, which would drop the wings
		float height = player.getDimensions(player.isCrouching() ? Pose.CROUCHING : Pose.STANDING).height();
		pose.translate(0, height * 0.74f, -0.17f);
		if (player.isCrouching()) pose.mulPose(Axis.XP.rotationDegrees(-28));
		float s = size.floatValue();
		int alpha = Math.round(255 * opacity.floatValue());
		for (int side = -1; side <= 1; side += 2) {
			pose.pushPose();
			pose.translate(side * 0.07f, 0, 0);
			pose.mulPose(Axis.YP.rotationDegrees(side * sweep));
			pose.mulPose(Axis.ZP.rotationDegrees(side * lift));
			pose.scale(s, s, s);
			final int sd = side;
			quads(pose, (p, vc) -> wing(p, vc, sd, alpha, trail));
			pose.popPose();
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** Point on the arm at {@code t} in 0..1 (shoulder to tip). */
	private static float armU(float t) {
		return seg(JU, t);
	}

	private static float armV(float t) {
		return seg(JV, t);
	}

	private static float seg(float[] j, float t) {
		float x = Math.max(0, Math.min(0.9999f, t)) * (j.length - 1);
		int i = (int) x;
		return j[i] + (j[i + 1] - j[i]) * (x - i);
	}

	private void wing(com.mojang.blaze3d.vertex.PoseStack.Pose p, VertexConsumer vc, int side, int alpha, float trail) {
		// primaries: long, fanning from pointing down to pointing out along the hand
		for (int i = 0; i < 7; i++) {
			float f = i / 6f;
			float t = 0.62f + f * 0.37f;
			float angle = lerp(-100, -28, f) + trail * (0.4f + f);
			float len = lerp(0.52f, 0.78f, f) - (f > 0.85f ? 0.12f : 0);
			feather(p, vc, side, armU(t), armV(t), angle, len, 0.11f, 0f, t, alpha);
		}
		// secondaries: along the forearm and upper arm, hanging down and slightly back toward the body
		for (int i = 0; i < 8; i++) {
			float f = i / 7f;
			float t = 0.08f + f * 0.56f;
			float angle = -104 + f * 8 + trail * 0.3f;
			float len = lerp(0.36f, 0.5f, f);
			feather(p, vc, side, armU(t), armV(t), angle, len, 0.11f, 0.003f, t, alpha);
		}
		// coverts: short, lighter, overlapping the arm
		for (int i = 0; i < 11; i++) {
			float f = i / 10f;
			float t = 0.03f + f * 0.9f;
			feather(p, vc, side, armU(t), armV(t) + 0.02f, -95 + f * 30, lerp(0.16f, 0.22f, f), 0.08f, 0.006f, t,
				Math.min(255, alpha + 20));
		}
		// the leading edge of the arm
		for (int i = 0; i < 3; i++) {
			int c = color(i / 6f, Math.min(255, alpha + 30));
			float u0 = JU[i] * side, v0 = JV[i], u1 = JU[i + 1] * side, v1 = JV[i + 1];
			quad(p, vc, c, u0, v0 + 0.03f, 0.008f, u1, v1 + 0.025f, 0.008f, u1, v1 - 0.015f, 0.008f, u0, v0 - 0.02f, 0.008f);
		}
	}

	/**
	 * One feather from a root point: a tapered vane ending in a point, root colour shading to a pale tip.
	 *
	 * @param angle direction in degrees in the wing plane (0 = outward, -90 = straight down)
	 */
	private void feather(com.mojang.blaze3d.vertex.PoseStack.Pose p, VertexConsumer vc, int side, float ru, float rv,
						 float angle, float len, float width, float z, float pos, int alpha) {
		double a = Math.toRadians(angle);
		float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
		float nx = -dy, ny = dx;
		float w0 = width * 0.35f, w1 = width * 0.5f;
		float mu = ru + dx * len * 0.78f, mv = rv + dy * len * 0.78f;
		float tu = ru + dx * len, tv = rv + dy * len;
		int root = Colors.shade(color(pos * 0.5f, alpha), 0.72f);
		int mid = color(pos * 0.5f + 0.1f, alpha);
		int tip = Colors.lerp(color(pos * 0.5f + 0.2f, alpha), Colors.withAlpha(0xFFFFFF, alpha), 0.45f);
		// vane
		vc.addVertex(p, side * (ru - nx * w0), rv - ny * w0, z).setColor(root);
		vc.addVertex(p, side * (ru + nx * w0), rv + ny * w0, z).setColor(root);
		vc.addVertex(p, side * (mu + nx * w1), mv + ny * w1, z).setColor(mid);
		vc.addVertex(p, side * (mu - nx * w1), mv - ny * w1, z).setColor(mid);
		// pointed tip (a triangle: the point is doubled)
		vc.addVertex(p, side * (mu - nx * w1), mv - ny * w1, z).setColor(mid);
		vc.addVertex(p, side * (mu + nx * w1), mv + ny * w1, z).setColor(mid);
		vc.addVertex(p, side * tu, tv, z).setColor(tip);
		vc.addVertex(p, side * tu, tv, z).setColor(tip);
	}
}
