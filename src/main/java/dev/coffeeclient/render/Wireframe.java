package dev.coffeeclient.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** A standing player drawn as the outlines of its six model boxes, for ghosts such as Blink's. */
public final class Wireframe {
	/** Head, body, arms and legs in model pixels: {minX, minY, minZ, maxX, maxY, maxZ}. */
	private static final float[][] PARTS = {
		{-4, 24, -4, 4, 32, 4}, {-4, 12, -2, 4, 24, 2},
		{-8, 12, -2, -4, 24, 2}, {4, 12, -2, 8, 24, 2},
		{-4, 0, -2, 0, 12, 2}, {0, 0, -2, 4, 12, 2}};
	/** Player model pixels to blocks (the model is drawn at 15/16 scale). */
	private static final float PIXEL = 0.9375f / 16;
	private static final int[][] EDGES = {
		{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

	private Wireframe() {
	}

	public static void draw(VertexConsumer vc, Vec3 feet, float yaw, Vec3 cam, int argb, float width) {
		double rad = Math.toRadians(yaw);
		float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
		float ox = (float) (feet.x - cam.x), oy = (float) (feet.y - cam.y), oz = (float) (feet.z - cam.z);
		float[] c = new float[24];
		for (float[] p : PARTS) {
			for (int i = 0; i < 8; i++) {
				float x = ((i & 1) == 0 ? p[0] : p[3]) * PIXEL, y = ((i & 2) == 0 ? p[1] : p[4]) * PIXEL, z = ((i & 4) == 0 ? p[2] : p[5]) * PIXEL;
				c[i * 3] = ox + x * cos - z * sin;
				c[i * 3 + 1] = oy + y;
				c[i * 3 + 2] = oz + x * sin + z * cos;
			}
			for (int[] e : EDGES) {
				int a = e[0] * 3, b = e[1] * 3;
				float dx = c[b] - c[a], dy = c[b + 1] - c[a + 1], dz = c[b + 2] - c[a + 2];
				float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				WorldOverlays.line(vc, c[a], c[a + 1], c[a + 2], c[b], c[b + 1], c[b + 2], dx / len, dy / len, dz / len, argb, width);
			}
		}
	}
}
