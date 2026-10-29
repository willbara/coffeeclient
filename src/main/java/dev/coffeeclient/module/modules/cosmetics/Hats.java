package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Headwear that follows your head: a cone hat, crown, cat ears, top hat, wizard hat, devil horns or propeller beanie. */
public final class Hats extends CosmeticModule {
	public enum Style { CHINA_HAT, CROWN, CAT_EARS, TOP_HAT, WIZARD_HAT, DEVIL_HORNS, PROPELLER_BEANIE }

	private static final int CONE_SEGMENTS = 32;
	/** Top of the head above the eyes, in blocks. */
	private static final float HEAD_TOP = 0.255f;
	/** How much higher hats sit over a helmet, in blocks. */
	private static final float HELMET_LIFT = 0.065f;
	private static final int TOP_HAT_BLACK = 0xFF2A262E, GOLD = 0xFFE3B64C, STAR = 0xFFFFF0A6;
	/** The propeller beanie keeps the classic primaries rather than the theme. */
	private static final int BEANIE_RED = 0xFFE0352B, BEANIE_YELLOW = 0xFFF7C928, BEANIE_GREEN = 0xFF3DAA4A;
	private static final int EAR_PINK = 0xFFF2A7B8;
	/** Stars on the wizard hat as {height share, angle in radians}. */
	private static final float[][] STARS = {{0.18f, 0.5f}, {0.3f, 2.4f}, {0.42f, 4.3f}, {0.55f, 1.3f}, {0.66f, 3.5f}, {0.24f, 5.6f}, {0.78f, 0.2f}};

	/** Per player: propeller angle in radians. */
	private final Map<Integer, Float> spin = new HashMap<>();
	private long lastNanos = System.nanoTime();

	public final EnumSetting<Style> style = setting(new EnumSetting<>("Style", "Which hat to wear", Style.CHINA_HAT));
	public final NumberSetting size = setting(new NumberSetting("Size", "Hat size", 1.0, 0.5, 1.6, 0.05));
	public final NumberSetting opacity = setting(new NumberSetting("Opacity", "Hat transparency", 0.55, 0.15, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%"));
	public final NumberSetting points = setting(new NumberSetting("Points", "How many spikes", 5, 3, 9, 1));
	public final BooleanSetting gems = setting(new BooleanSetting("Gems", "A twinkling gem on each spike", true));

	public Hats() {
		// the ChinaHat alias keeps settings saved before the hats were merged
		super("Hats", List.of("ChinaHat"), "Headwear that follows your head (only you can see it).");
		opacity.visibleWhen(() -> style.get() == Style.CHINA_HAT);
		points.visibleWhen(() -> style.get() == Style.CROWN);
		gems.visibleWhen(() -> style.get() == Style.CROWN);
	}

	@Override
	public String infoString() {
		return style.display();
	}

	@Override
	protected void onDisable() {
		spin.clear();
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (style.get() == Style.PROPELLER_BEANIE) {
			for (Player p : wearers()) {
				// idles lazily, and whirs up with speed (about 0.28 blocks a tick when sprinting)
				float speed = (float) Math.sqrt(p.distanceToSqr(p.xo, p.yo, p.zo));
				spin.put(p.getId(), (spin.getOrDefault(p.getId(), 0f) + dt * (2.5f + Math.min(speed, 1.2f) * 55)) % (float) (Math.PI * 2));
			}
		}
		super.renderWorld(pose, cam);
	}

	@Override
	protected void render(PoseStack pose, Player player, float pt, float bodyYaw) {
		float headYaw = Mth.rotLerp(pt, player.yHeadRotO, player.yHeadRot);
		float pitch = Mth.lerp(pt, player.xRotO, player.getXRot());
		// the head turns about the base of the neck, not the eyes
		pose.translate(0, player.getEyeHeight() - NECK_BELOW_EYE, 0);
		pose.mulPose(Axis.YP.rotationDegrees(-headYaw));
		pose.mulPose(Axis.XP.rotationDegrees(pitch));
		pose.translate(0, NECK_BELOW_EYE, 0);
		// a helmet adds about a texel all round the head, so sit on top of it
		// (the beanie a touch more, since its dome comes down the sides where the helmet is wider too)
		if (!player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
			pose.translate(0, HELMET_LIFT + (style.get() == Style.PROPELLER_BEANIE ? 0.03f : 0), 0);
		}
		float s = size.floatValue();
		switch (style.get()) {
			case CHINA_HAT -> chinaHat(pose, s);
			case CROWN -> crown(pose, s);
			case CAT_EARS -> catEars(pose, player, s);
			default -> {
				pose.translate(0, HEAD_TOP, 0);
				pose.scale(s, s, s);
				switch (style.get()) {
					case TOP_HAT -> topHat(pose);
					case WIZARD_HAT -> wizardHat(pose);
					case DEVIL_HORNS -> devilHorns(pose);
					default -> beanie(pose, spin.getOrDefault(player.getId(), 0f));
				}
			}
		}
	}

	// ---- the solid hats, modelled with y = 0 at the top of the head ----

	private void topHat(PoseStack pose) {
		// worn at a slight jaunty angle
		pose.mulPose(Axis.ZP.rotationDegrees(-6));
		pose.mulPose(Axis.XP.rotationDegrees(-4));
		int band = color(0, 0xFF);
		solids(pose, (p, vc) -> {
			for (int i = 0; i < CONE_SEGMENTS; i++) {
				float a0 = angle(i), a1 = angle(i + 1);
				// brim: flat at front and back, curling up at the sides
				float c0 = 0.035f * sq((float) Math.sin(a0)), c1 = 0.035f * sq((float) Math.sin(a1));
				int top = lit(TOP_HAT_BLACK, 0, 1, 0), under = Colors.shade(TOP_HAT_BLACK, 0.55f);
				ringQuad(p, vc, top, a0, a1, 0.2f, 0.38f, 0.012f, 0.012f + c0, 0.012f + c1);
				ringQuad(p, vc, under, a0, a1, 0.2f, 0.38f, -0.01f, -0.01f + c0, -0.01f + c1);
				wall(p, vc, TOP_HAT_BLACK, a0, a1, 0.38f, 0.38f, -0.01f + c0, -0.01f + c1, 0.012f + c0, 0.012f + c1);
				// crown, flaring a little toward the top, with the band at its foot
				wall(p, vc, TOP_HAT_BLACK, a0, a1, 0.2f, 0.215f, 0.012f, 0.012f, 0.36f, 0.36f);
				wall(p, vc, band, a0, a1, 0.204f, 0.206f, 0.02f, 0.02f, 0.075f, 0.075f);
				ringQuad(p, vc, lit(Colors.shade(TOP_HAT_BLACK, 1.15f), 0, 1, 0), a0, a1, 0, 0.215f, 0.36f, 0.36f, 0.36f);
			}
		});
	}

	private void wizardHat(PoseStack pose) {
		final int rings = 12;
		// raised so the drooping brim clears the corners of the head
		pose.translate(0, 0.045f, 0);
		float t = time();
		// midnight cloth with a hint of the accent; the band and stars carry the accent itself
		int cloth = Colors.lerp(color(0.3f, 0xFF), 0xFF1C2060, 0.72f);
		int band = Colors.lerp(color(0, 0xFF), GOLD, 0.5f);
		solids(pose, (p, vc) -> {
			for (int i = 0; i < CONE_SEGMENTS; i++) {
				float a0 = angle(i), a1 = angle(i + 1);
				// brim drooping at the edge, a little more at the sides
				float d0 = -0.04f - 0.025f * sq((float) Math.sin(a0)), d1 = -0.04f - 0.025f * sq((float) Math.sin(a1));
				ringQuad(p, vc, lit(cloth, 0, 1, 0), a0, a1, 0.2f, 0.4f, 0.012f, d0, d1);
				ringQuad(p, vc, Colors.shade(cloth, 0.5f), a0, a1, 0.2f, 0.4f, 0.0f, d0 - 0.012f, d1 - 0.012f);
				wall(p, vc, Colors.shade(cloth, 0.8f), a0, a1, 0.4f, 0.4f, d0 - 0.012f, d1 - 0.012f, d0, d1);
				// cone: rings that shrink and lean back, so the tip flops over behind
				for (int k = 0; k < rings; k++) {
					float f0 = k / (float) rings, f1 = (k + 1) / (float) rings;
					coneQuad(p, vc, k < 1 ? band : cloth, a0, a1, f0, f1);
				}
			}
			// stars dotted over the cone, twinkling
			for (int k = 0; k < STARS.length; k++) {
				float f = STARS[k][0], a = STARS[k][1];
				float r = coneRadius(f) + 0.008f;
				float tw = 0.6f + 0.4f * (float) Math.sin(t * 2.6f + k * 1.7f);
				cube(p, vc, STAR, (float) Math.sin(a) * r, coneY(f), coneZ(f) + (float) Math.cos(a) * r, 0.014f * tw + 0.006f);
			}
			cube(p, vc, STAR, 0, coneY(1), coneZ(1), 0.022f);
		});
	}

	private static float coneRadius(float f) {
		return 0.205f * (float) Math.pow(1 - f, 1.15) + 0.004f;
	}

	private static float coneY(float f) {
		return 0.012f + 0.6f * f - 0.13f * f * f * f;
	}

	private static float coneZ(float f) {
		return -0.2f * (float) Math.pow(f, 2.4);
	}

	private static void coneQuad(PoseStack.Pose p, VertexConsumer vc, int c, float a0, float a1, float f0, float f1) {
		float r0 = coneRadius(f0), r1 = coneRadius(f1), y0 = coneY(f0), y1 = coneY(f1), z0 = coneZ(f0), z1 = coneZ(f1);
		float mid = (a0 + a1) / 2;
		int lc = lit(c, (float) Math.sin(mid), (r0 - r1) / Math.max(0.01f, y1 - y0) + 0.1f, (float) Math.cos(mid));
		quad(p, vc, lc, (float) Math.sin(a0) * r0, y0, z0 + (float) Math.cos(a0) * r0, (float) Math.sin(a1) * r0, y0, z0 + (float) Math.cos(a1) * r0,
			(float) Math.sin(a1) * r1, y1, z1 + (float) Math.cos(a1) * r1, (float) Math.sin(a0) * r1, y1, z1 + (float) Math.cos(a0) * r1);
	}

	private void devilHorns(PoseStack pose) {
		final int rings = 14, sides = 10;
		int base = color(0, 0xFF), tip = 0xFF1E0A0D;
		solids(pose, (p, vc) -> {
			for (int side = -1; side <= 1; side += 2) {
				for (int k = 0; k < rings; k++) {
					float s0 = k / (float) rings, s1 = (k + 1) / (float) rings;
					float[] c0 = hornPoint(side, s0), c1 = hornPoint(side, s1);
					float r0 = hornRadius(s0), r1 = hornRadius(s1);
					int c = Colors.lerp(Colors.shade(base, 0.85f), tip, (float) Math.pow(s1, 1.4));
					for (int j = 0; j < sides; j++) {
						float a0 = (float) (j * Math.PI * 2 / sides), a1 = (float) ((j + 1) * Math.PI * 2 / sides);
						float mid = (a0 + a1) / 2;
						// faint growth rings
						int lc = lit(k % 3 == 2 ? Colors.shade(c, 0.86f) : c, (float) Math.sin(mid), 0.3f, (float) Math.cos(mid));
						quad(p, vc, lc,
							c0[0] + (float) Math.sin(a0) * r0, c0[1], c0[2] + (float) Math.cos(a0) * r0,
							c0[0] + (float) Math.sin(a1) * r0, c0[1], c0[2] + (float) Math.cos(a1) * r0,
							c1[0] + (float) Math.sin(a1) * r1, c1[1], c1[2] + (float) Math.cos(a1) * r1,
							c1[0] + (float) Math.sin(a0) * r1, c1[1], c1[2] + (float) Math.cos(a0) * r1);
					}
				}
			}
		});
	}

	/** A point along a horn: out from the temple, sweeping up and curling forward at the tip. */
	private static float[] hornPoint(int side, float s) {
		float u = 1 - s;
		// quadratic curve from the root through a control point out to the side
		float x = u * u * 0.15f + 2 * u * s * 0.36f + s * s * 0.27f;
		float y = 2 * u * s * 0.12f + s * s * 0.42f - 0.03f;
		float z = u * u * 0.05f + 2 * u * s * 0.07f + s * s * 0.2f;
		return new float[] {side * x, y, z};
	}

	private static float hornRadius(float s) {
		return 0.066f * (float) Math.pow(1 - s, 0.8) + 0.003f;
	}

	private void beanie(PoseStack pose, float angle) {
		final int rows = 6;
		final int[] panels = {BEANIE_YELLOW, BEANIE_RED, BEANIE_GREEN};
		// sits a little high so the blocky head's corners stay inside the dome
		pose.translate(0, 0.05f, 0);
		solids(pose, (p, vc) -> {
			for (int i = 0; i < CONE_SEGMENTS; i++) {
				float a0 = angle(i), a1 = angle(i + 1);
				// six panels, one centred on the front
				int panel = panels[Math.floorMod(Math.round((i + 0.5f) * 6 / CONE_SEGMENTS), 6) % 3];
				// dome: a rounded square at the brow so it sits on the blocky head, rounding off on top
				for (int k = 0; k < rows; k++) {
					float l0 = (float) (k * Math.PI / 2 / rows), l1 = (float) ((k + 1) * Math.PI / 2 / rows);
					float y0 = -0.09f + 0.21f * (float) Math.sin(l0), y1 = -0.09f + 0.21f * (float) Math.sin(l1);
					float r0 = 0.268f * (float) Math.cos(l0), r1 = 0.268f * (float) Math.cos(l1);
					float mid = (a0 + a1) / 2;
					int c = lit(panel, (float) Math.sin(mid) * (float) Math.cos(l1), (float) Math.sin(l1) + 0.1f, (float) Math.cos(mid) * (float) Math.cos(l1));
					float[] p00 = squircle(a0, r0), p10 = squircle(a1, r0), p11 = squircle(a1, r1), p01 = squircle(a0, r1);
					quad(p, vc, c, p00[0], y0, p00[1], p10[0], y0, p10[1], p11[0], y1, p11[1], p01[0], y1, p01[1]);
				}
				// a visor over the eyes, angled down, with a visible edge
				if (Math.cos((a0 + a1) / 2) > 0.6) {
					float[] i0 = squircle(a0, 0.262f), i1 = squircle(a1, 0.262f);
					float[] o0 = squircle(a0, 0.41f), o1 = squircle(a1, 0.41f);
					int visor = Colors.shade(BEANIE_RED, 0.9f);
					quad(p, vc, lit(visor, 0, 1, 0.3f), i0[0], -0.08f, i0[1], i1[0], -0.08f, i1[1], o1[0], -0.125f, o1[1], o0[0], -0.125f, o0[1]);
					quad(p, vc, Colors.shade(visor, 0.5f), i0[0], -0.1f, i0[1], i1[0], -0.1f, i1[1], o1[0], -0.145f, o1[1], o0[0], -0.145f, o0[1]);
					quad(p, vc, lit(visor, 0, 0, 1), o0[0], -0.145f, o0[1], o1[0], -0.145f, o1[1], o1[0], -0.125f, o1[1], o0[0], -0.125f, o0[1]);
				}
			}
			// button and stem up to the rotor
			cube(p, vc, lit(BEANIE_YELLOW, 0, 1, 0), 0, 0.125f, 0, 0.026f);
			box(p, vc, 0xFF8E8E98, -0.007f, 0.13f, -0.007f, 0.007f, 0.2f, 0.007f);
		});
		pose.pushPose();
		pose.translate(0, 0.205f, 0);
		pose.mulPose(Axis.YP.rotation(angle));
		solids(pose, (p, vc) -> {
			cube(p, vc, lit(BEANIE_RED, 0, 1, 0), 0, 0, 0, 0.02f);
			for (int b = 0; b < 3; b++) {
				// three wide blades, each pitched like a real rotor so they catch the light as they turn
				float a = (float) (b * Math.PI * 2 / 3);
				float cx = (float) Math.cos(a), cz = (float) Math.sin(a), nx = -cz, nz = cx;
				int blade = panels[b];
				float r0 = 0.02f, r1 = 0.23f, w = 0.05f, tilt = 0.03f;
				quad(p, vc, lit(blade, nx * 0.5f, 1, nz * 0.5f), cx * r0 + nx * w, tilt, cz * r0 + nz * w, cx * r1 + nx * w, tilt, cz * r1 + nz * w,
					cx * r1 - nx * w, -tilt, cz * r1 - nz * w, cx * r0 - nx * w, -tilt, cz * r0 - nz * w);
			}
		});
		pose.popPose();
	}

	/** A point at angle {@code a} (0 = front) on a rounded square of half-size {@code r}. */
	private static float[] squircle(float a, float r) {
		float s = (float) Math.sin(a), c = (float) Math.cos(a);
		return new float[] {Math.signum(s) * (float) Math.pow(Math.abs(s), 0.35) * r, Math.signum(c) * (float) Math.pow(Math.abs(c), 0.35) * r};
	}

	private static float angle(int i) {
		return (float) (i * Math.PI * 2 / CONE_SEGMENTS);
	}

	private static float sq(float v) {
		return v * v;
	}

	/** A flat ring piece between angles a0 and a1 from radius ri (at height yi) to ro (at heights yo0, yo1). */
	private static void ringQuad(PoseStack.Pose p, VertexConsumer vc, int c, float a0, float a1, float ri, float ro, float yi, float yo0, float yo1) {
		float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
		quad(p, vc, c, s0 * ri, yi, c0 * ri, s1 * ri, yi, c1 * ri, s1 * ro, yo1, c1 * ro, s0 * ro, yo0, c0 * ro);
	}

	/** An upright wall piece between angles a0 and a1, radius rb at the bottom and rt at the top, lit by its facing. */
	private static void wall(PoseStack.Pose p, VertexConsumer vc, int c, float a0, float a1, float rb, float rt,
							 float yb0, float yb1, float yt0, float yt1) {
		float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
		float mid = (a0 + a1) / 2;
		int lc = lit(c, (float) Math.sin(mid), 0.15f, (float) Math.cos(mid));
		quad(p, vc, lc, s0 * rb, yb0, c0 * rb, s1 * rb, yb1, c1 * rb, s1 * rt, yt1, c1 * rt, s0 * rt, yt0, c0 * rt);
	}

	private void chinaHat(PoseStack pose, float s) {
		pose.translate(0, 0.3f, 0);
		float r = 0.62f * s, h = 0.28f * s;
		int alpha = Math.round(255 * opacity.floatValue());
		quads(pose, (p, vc) -> {
			for (int i = 0; i < CONE_SEGMENTS; i++) {
				double a0 = i * Math.PI * 2 / CONE_SEGMENTS, a1 = (i + 1) * Math.PI * 2 / CONE_SEGMENTS;
				float x0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r;
				float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
				int c = color(i / (float) CONE_SEGMENTS, alpha);
				// a triangle as a quad with the apex doubled
				quad(p, vc, c, 0, h, 0, x0, 0, z0, x1, 0, z1, 0, h, 0);
				// brim edge
				quad(p, vc, color(i / (float) CONE_SEGMENTS, Math.min(255, alpha + 70)),
					x0, 0, z0, x1, 0, z1, x1 * 1.04f, -0.02f, z1 * 1.04f, x0 * 1.04f, -0.02f, z0 * 1.04f);
			}
		});
	}

	private void crown(PoseStack pose, float s) {
		pose.translate(0, 0.24f, 0);
		int n = points.intValue();
		float r = 0.28f * s, band = 0.08f, spike = 0.13f * s;
		float t = time();
		quads(pose, (p, vc) -> {
			for (int i = 0; i < n; i++) {
				double a0 = i * Math.PI * 2 / n, am = (i + 0.5) * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
				float x0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r;
				float xm = (float) Math.cos(am) * r, zm = (float) Math.sin(am) * r;
				float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
				int c = color(i / (float) n, 0xE8);
				// band, split at the spike's middle so it follows the circle more closely
				quad(p, vc, c, x0, 0, z0, xm, 0, zm, xm, band, zm, x0, band, z0);
				quad(p, vc, c, xm, 0, zm, x1, 0, z1, x1, band, z1, xm, band, zm);
				quad(p, vc, c, x0, band, z0, x1, band, z1, xm, band + spike, zm, xm, band + spike, zm);
				if (gems.get()) {
					float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 3 + i * 1.9f);
					cube(p, vc, color(i / (float) n, 0xFF), xm, band + spike, zm, 0.018f + 0.008f * twinkle);
					cube(p, vc, color(i / (float) n, Math.round(0x50 * twinkle)), xm, band + spike, zm, 0.045f);
				}
			}
		});
	}

	private void catEars(PoseStack pose, Player player, float s) {
		// rooted just into the top of the head, a little behind the middle
		pose.translate(0, HEAD_TOP - 0.01f, -0.01f);
		for (int side = -1; side <= 1; side += 2) {
			// a quick flick every few seconds, offset per ear and per player
			float phase = (time() * 0.6f + player.getId() * 0.37f + (side > 0 ? 0.5f : 0)) % 3f;
			float twitch = phase < 0.15f ? (float) Math.sin(phase / 0.15f * Math.PI) * 25 : 0;
			int fur = color(side > 0 ? 1 : 0, 0xFF);
			pose.pushPose();
			pose.translate(side * 0.15f, 0, 0);
			// turned a little outward, leaning out and slightly back
			pose.mulPose(Axis.YP.rotationDegrees(side * 14));
			pose.mulPose(Axis.ZP.rotationDegrees(-side * (10 + twitch)));
			pose.mulPose(Axis.XP.rotationDegrees(-8));
			pose.scale(s, s, s);
			final int sd = side;
			solids(pose, (p, vc) -> ear(p, vc, sd, fur));
			pose.popPose();
		}
	}

	/**
	 * One ear: a thick, rounded triangle whose outer edge is steeper and whose tip leans out, fur darkening
	 * toward the tip, and a pink inside that deepens toward its middle.
	 */
	private static void ear(PoseStack.Pose p, VertexConsumer vc, int side, int fur) {
		final float w = 0.12f, h = 0.25f, t = 0.035f;
		float[][] rim = {{-w, 0}, {w, 0}, {w * 0.8f, h * 0.48f}, {w * 0.3f, h}, {-w * 0.55f, h * 0.56f}};
		for (float[] v : rim) v[0] *= side;
		float cy = h * 0.32f;
		for (int i = 0; i < rim.length; i++) {
			float[] a = rim[i], b = rim[(i + 1) % rim.length];
			// front and back faces as fans from the middle (a triangle is a quad with a doubled corner)
			for (float z : new float[] {0, -t}) {
				int mid = furAt(fur, cy, h, z == 0 ? 1 : -1);
				vc.addVertex(p, 0, cy, z).setColor(mid);
				vc.addVertex(p, a[0], a[1], z).setColor(furAt(fur, a[1], h, z == 0 ? 1 : -1));
				vc.addVertex(p, b[0], b[1], z).setColor(furAt(fur, b[1], h, z == 0 ? 1 : -1));
				vc.addVertex(p, b[0], b[1], z).setColor(furAt(fur, b[1], h, z == 0 ? 1 : -1));
			}
			// the edge between them, lit by which way it faces
			float ex = b[1] - a[1], ey = a[0] - b[0];
			if (ex * (a[0] + b[0]) + ey * (a[1] + b[1] - 2 * cy) < 0) {
				ex = -ex;
				ey = -ey;
			}
			quad(p, vc, lit(Colors.shade(fur, 0.9f), ex, ey, 0), a[0], a[1], 0, b[0], b[1], 0, b[0], b[1], -t, a[0], a[1], -t);
		}
		// the inside: a smaller copy of the outline, sitting just in front, deeper pink at its heart
		float iy = h * 0.06f;
		int deep = Colors.shade(EAR_PINK, 0.8f), edge = lit(EAR_PINK, 0, 0.3f, 1);
		for (int i = 0; i < rim.length; i++) {
			float[] a = rim[i], b = rim[(i + 1) % rim.length];
			float ax = a[0] * 0.6f, ay = iy + (a[1] - iy) * 0.66f, bx = b[0] * 0.6f, by = iy + (b[1] - iy) * 0.66f;
			vc.addVertex(p, 0, cy * 0.85f, 0.004f).setColor(deep);
			vc.addVertex(p, ax, ay, 0.004f).setColor(edge);
			vc.addVertex(p, bx, by, 0.004f).setColor(edge);
			vc.addVertex(p, bx, by, 0.004f).setColor(edge);
		}
	}

	/** Fur at height {@code y} of an ear {@code h} tall, darker toward the tip, on the face pointing {@code facing} (1 front, -1 back). */
	private static int furAt(int fur, float y, float h, int facing) {
		return lit(Colors.shade(fur, 1 - 0.28f * y / h), 0, 0.25f, facing);
	}
}
