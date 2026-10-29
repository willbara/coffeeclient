package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * A tiny pixel-art companion: an angel or a devil hovering by your shoulder, or a creeper perched on it.
 * Models are built from boxes painted one texel at a time, with each face shaded like a block.
 */
public final class LilBuddy extends CosmeticModule {
	public enum Kind { ANGEL, DEVIL, CREEPER }

	/** Shade per face: top, bottom, front, back, +x side, -x side. */
	private static final float[] FACE_LIGHT = {1f, 0.55f, 0.9f, 0.68f, 0.8f, 0.76f};
	private static final int TOP = 0, BOTTOM = 1, FRONT = 2, BACK = 3, SIDE_X = 4, SIDE_NX = 5;

	private static final int[] CREEPER_GREENS = {0xFF4FAE3C, 0xFF5CC447, 0xFF3F9330, 0xFF73D75E, 0xFF35802A, 0xFF62B851, 0xFF9BDB8C};
	private static final int CREEPER_FACE = 0xFF0E120E;
	/** The creeper face on its 8x8 head front, top row first. */
	private static final String[] CREEPER_FACE_ROWS = {
		"........", "........", ".##..##.", ".##..##.", "...##...", "..####..", "..####..", "..#..#.."
	};
	private static final String[] ANGEL_WING = {"##....", "####..", "######", ".#####", "..###.", "...#.."};
	private static final String[] DEVIL_WING = {"#.....", "##....", "####..", "######", "#.##.#", "#..#.."};

	public final EnumSetting<Kind> kind = setting(new EnumSetting<>("Buddy", "Who keeps you company", Kind.ANGEL));
	public final NumberSetting size = setting(new NumberSetting("Size", "Buddy size", 1.0, 0.6, 1.6, 0.05));

	public LilBuddy() {
		super("LilBuddy", "A tiny angel, devil or creeper that keeps you company (only you can see it).");
	}

	@Override
	public String infoString() {
		return kind.display();
	}

	@Override
	protected void render(PoseStack pose, Player player, float pt, float bodyYaw) {
		float t = time() + player.getId() * 0.53f;
		float headYaw = Mth.rotLerp(pt, player.yHeadRotO, player.yHeadRot);
		// the buddy looks where you look, within a comfortable neck turn
		float look = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -55, 55);
		float pitch = Mth.lerp(pt, player.xRotO, player.getXRot()) * 0.5f;
		toNeck(pose, player, pt, bodyYaw);
		float s = size.floatValue();
		if (kind.get() == Kind.CREEPER) {
			// standing on the right shoulder (the body's -x side)
			pose.translate(-0.34f, 0.003f, 0.02f);
			pose.scale(s, s, s);
			creeper(pose, t, look, pitch, player.hurtTime > 0);
			return;
		}
		pose.translate(-0.62f, 0.2f + (float) Math.sin(t * 2.1f) * 0.035f, 0.05f);
		pose.mulPose(Axis.YP.rotationDegrees(-look * 0.8f + (float) Math.sin(t * 0.9f) * 6));
		pose.mulPose(Axis.XP.rotationDegrees(pitch * 0.4f + (float) Math.sin(t * 1.3f) * 3));
		pose.scale(s, s, s);
		if (kind.get() == Kind.ANGEL) angel(pose, t);
		else devil(pose, t);
	}

	// ---- angel: a little white cube with rosy cheeks, feathered wings and a glowing halo ----

	private void angel(PoseStack pose, float t) {
		float px = 1 / 40f;
		pose.pushPose();
		pose.scale(px, px, px);
		boolean blink = blink(t);
		solids(pose, (p, vc) -> {
			box(p, vc, -3, 0, -3, 6, 6, 6, (face, u, v) -> {
				if (face != FRONT) return speckle(0xFFF8F5EE, 0xFFEDE8DE, face, u, v);
				if ((u == 1 || u == 4) && (blink ? v == 3 : v == 2 || v == 3)) return 0xFF2A2230;
				if ((u == 0 || u == 5) && v == 4) return 0xFFF4A3B5;
				if (v == 4 && (u == 2 || u == 3)) return 0xFFE88A9C;
				return speckle(0xFFF8F5EE, 0xFFEDE8DE, face, u, v);
			});
		});
		float flap = (float) Math.sin(t * 5.5f) * 22;
		for (int side = -1; side <= 1; side += 2) {
			wing(pose, side, flap, ANGEL_WING, 0xFFF4F7FC, 0xFFD5DEEB, 0.3f);
		}
		pose.popPose();
		pose.pushPose();
		pose.translate(0, 8.6f * px, 0);
		pose.mulPose(Axis.XP.rotationDegrees(-8));
		ring(pose, 2.4f * px, 3.4f * px, 0xF0, 0);
		ring(pose, 3.4f * px, 4.6f * px, 0x40, 0.002f);
		pose.popPose();
	}

	// ---- devil: a red cube with a fanged grin, glowing eyes, horns, bat wings and a swishing tail ----

	private void devil(PoseStack pose, float t) {
		float px = 1 / 40f;
		pose.pushPose();
		pose.scale(px, px, px);
		boolean blink = blink(t + 2.3f);
		int eye = Colors.lerp(color(0, 0xFF), 0xFFFFE066, 0.35f);
		solids(pose, (p, vc) -> {
			box(p, vc, -3, 0, -3, 6, 6, 6, (face, u, v) -> {
				if (face == FRONT) {
					if ((u == 1 && v == 1) || (u == 4 && v == 1)) return 0xFF5A0E16;
					if ((u == 1 || u == 4) && v == 2) return blink ? 0xFF5A0E16 : eye;
					if (v == 4 && (u >= 1 && u <= 4)) return u == 3 ? 0xFFF5EFE6 : 0xFF3A0A10;
				}
				return speckle(0xFFC9303B, 0xFFB52632, face, u, v);
			});
			// horns: a block on each top corner and a darker tip leaning out
			for (int side = -1; side <= 1; side += 2) {
				float hx = side < 0 ? -3 : 2;
				box(p, vc, hx, 6, 0, 1, 2, 1, (face, u, v) -> 0xFF3A1418);
				box(p, vc, hx + side * 0.5f, 7.6f, 0.2f, 1, 1.4f, 0.6f, (face, u, v) -> 0xFF1C0709);
			}
			// tail: a curve of small links swishing behind, ending in a spade
			float tx = 0, ty = 1.5f, tz = -3;
			for (int k = 0; k < 6; k++) {
				float a = (float) Math.sin(t * 3.2f - k * 0.6f) * 0.5f;
				tx += (float) Math.sin(a) * 1.1f;
				tz -= 0.9f;
				ty += k < 3 ? -0.4f : 0.7f;
				box(p, vc, tx - 0.35f, ty - 0.35f, tz - 0.35f, 0.7f, 0.7f, 0.7f, (face, u, v) -> 0xFFA01E28);
			}
			int spade = 0xFF3A0A10;
			quad(p, vc, spade, tx, ty + 1.6f, tz - 0.2f, tx + 1f, ty + 0.5f, tz - 0.2f, tx, ty - 0.2f, tz - 0.2f, tx - 1f, ty + 0.5f, tz - 0.2f);
		});
		float flap = (float) Math.sin(t * 8f) * 26;
		for (int side = -1; side <= 1; side += 2) {
			wing(pose, side, flap, DEVIL_WING, 0xFF6E1520, 0xFF4A0C14, 0.4f);
		}
		pose.popPose();
	}

	/** Eyes shut for a moment every few seconds. */
	private static boolean blink(float t) {
		return t % 4.2f < 0.13f;
	}

	/**
	 * A flat pixel wing hinged at the back edge of the 6-wide body, swept back and flapping. {@code inner}
	 * shades the texels nearest the body so the wing reads as layered feathers or membrane.
	 */
	private void wing(PoseStack pose, int side, float flap, String[] rows, int color, int inner, float innerShare) {
		pose.pushPose();
		pose.translate(side * 2.4f, 2.2f, -3.05f);
		pose.mulPose(Axis.YP.rotationDegrees(side * (35 + flap)));
		solids(pose, (p, vc) -> {
			int h = rows.length;
			for (int v = 0; v < h; v++) {
				for (int u = 0; u < rows[v].length(); u++) {
					if (rows[v].charAt(u) != '#') continue;
					int c = u < rows[v].length() * innerShare ? inner : color;
					float x0 = side * u, x1 = side * (u + 1), y0 = h - v - 1, y1 = h - v;
					quad(p, vc, c, x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0);
				}
			}
		});
		pose.popPose();
	}

	// ---- creeper: the real proportions (8x8 head, 8x12x4 body, four 4x6x4 legs) at a quarter size ----

	private void creeper(PoseStack pose, float t, float look, float pitch, boolean hurt) {
		float px = 1 / 64f;
		pose.pushPose();
		pose.scale(px, px, px);
		// when you get hurt it swells and flashes as if about to go off
		float swell = hurt ? 1 + 0.12f * (float) Math.abs(Math.sin(t * 18)) : 1;
		boolean flash = hurt && Math.sin(t * 18) > 0;
		solids(pose, (p, vc) -> {
			for (int lx = -1; lx <= 1; lx += 2) {
				for (int lz = -1; lz <= 1; lz += 2) {
					final int legId = lx * 2 + lz;
					box(p, vc, lx < 0 ? -4 : 0, 0, lz < 0 ? -6 : 2, 4, 6, 4, (face, u, v) -> creeperSkin(face, u, v, legId, flash));
				}
			}
		});
		pose.pushPose();
		pose.scale(swell, swell, swell);
		solids(pose, (p, vc) -> box(p, vc, -4, 6, -2, 8, 12, 4, (face, u, v) -> creeperSkin(face, u, v, 7, flash)));
		pose.translate(0, 18, 0);
		pose.mulPose(Axis.YP.rotationDegrees(-look + (float) Math.sin(t * 0.7f) * 8));
		pose.mulPose(Axis.XP.rotationDegrees(pitch));
		solids(pose, (p, vc) -> box(p, vc, -4, 0, -4, 8, 8, 8, (face, u, v) -> {
			if (face == FRONT && CREEPER_FACE_ROWS[v].charAt(u) == '#') return flash ? 0xFFDDDDDD : CREEPER_FACE;
			return creeperSkin(face, u, v, 11, flash);
		}));
		pose.popPose();
		pose.popPose();
	}

	/** Mottled creeper green, fixed per texel. */
	private static int creeperSkin(int face, int u, int v, int part, boolean flash) {
		if (flash) return 0xFFF2F2F2;
		int h = hash(face * 31 + part * 977, u, v);
		return CREEPER_GREENS[Math.floorMod(h, CREEPER_GREENS.length)];
	}

	/** {@code base} with a sparse, fixed sprinkle of {@code fleck}, so plain colours read as a texture. */
	private static int speckle(int base, int fleck, int face, int u, int v) {
		return Math.floorMod(hash(face, u, v), 5) == 0 ? fleck : base;
	}

	private static int hash(int a, int u, int v) {
		int h = a * 73856093 ^ u * 19349663 ^ v * 83492791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		return h ^ (h >>> 15);
	}

	/** Paint for one texel: {@code face} as in {@link #FACE_LIGHT}, (u, v) from the face's top-left as you look at it. */
	@FunctionalInterface
	private interface Paint {
		int at(int face, int u, int v);
	}

	/**
	 * A box from (x, y, z) sized w x h x d texels, each face painted texel by texel and shaded per face.
	 * Fractional sizes draw one texel per face, for small parts.
	 */
	private static void box(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float w, float h, float d, Paint paint) {
		int nw = Math.max(1, Math.round(w)), nh = Math.max(1, Math.round(h)), nd = Math.max(1, Math.round(d));
		float sw = w / nw, sh = h / nh, sd = d / nd;
		float x2 = x + w, y2 = y + h, z2 = z + d;
		for (int u = 0; u < nw; u++) {
			for (int v = 0; v < nh; v++) {
				float xa = x + u * sw, xb = xa + sw, yb = y2 - v * sh, ya = yb - sh;
				quad(p, vc, shade(paint, FRONT, u, v), xa, ya, z2, xb, ya, z2, xb, yb, z2, xa, yb, z2);
				float bx = x2 - u * sw;
				quad(p, vc, shade(paint, BACK, u, v), bx, ya, z, bx - sw, ya, z, bx - sw, yb, z, bx, yb, z);
			}
		}
		for (int u = 0; u < nd; u++) {
			for (int v = 0; v < nh; v++) {
				float yb = y2 - v * sh, ya = yb - sh;
				float za = z2 - u * sd;
				quad(p, vc, shade(paint, SIDE_X, u, v), x2, ya, za, x2, ya, za - sd, x2, yb, za - sd, x2, yb, za);
				float zb = z + u * sd;
				quad(p, vc, shade(paint, SIDE_NX, u, v), x, ya, zb, x, ya, zb + sd, x, yb, zb + sd, x, yb, zb);
			}
		}
		for (int u = 0; u < nw; u++) {
			for (int v = 0; v < nd; v++) {
				float xa = x + u * sw, za = z + v * sd;
				quad(p, vc, shade(paint, TOP, u, v), xa, y2, za, xa + sw, y2, za, xa + sw, y2, za + sd, xa, y2, za + sd);
				quad(p, vc, shade(paint, BOTTOM, u, v), xa, y, za, xa + sw, y, za, xa + sw, y, za + sd, xa, y, za + sd);
			}
		}
	}

	private static int shade(Paint paint, int face, int u, int v) {
		return Colors.shade(paint.at(face, u, v), FACE_LIGHT[face]);
	}
}
