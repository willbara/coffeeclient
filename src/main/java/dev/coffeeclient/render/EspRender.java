package dev.coffeeclient.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.util.WorldProjector;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Boxes, merged block groups and tracers for the ESP modules, written straight into the
 * {@link OverlayRenderer} buffers (camera-relative, no allocation per box).
 *
 * <p>Block sets can be drawn merged: each block carries a neighbour mask, faces
 * shared with a neighbour are skipped, and an edge is drawn only where it is part of the group's
 * silhouette, so a vein or a double chest reads as one shape. {@link #groups} joins touching blocks so
 * a vein gets one tracer. Entities can be frustum-culled and capped.
 *
 * <p>Tracers are drawn in the world from the exact screen centre, which is found by unprojecting it
 * through this frame's level matrix (bobbing and hurt shake included), so they start at the crosshair
 * and still point the right way at things behind you. Render thread only.
 */
public final class EspRender {
	/** Neighbour bits 0-5: the six faces, by {@link Direction#ordinal()}. Bits 6-17: the twelve edge diagonals. */
	private static final int FACE_BITS = 6;

	/** The 12 cube edges: the two faces they join and their unit-cube endpoints. */
	private static final Direction[][] EDGE_FACES = new Direction[12][];
	private static final float[][] EDGE_LINES = new float[12][];

	static {
		int i = 0;
		Direction[] dirs = Direction.values();
		for (int a = 0; a < dirs.length; a++) {
			for (int b = a + 1; b < dirs.length; b++) {
				Direction da = dirs[a], db = dirs[b];
				if (da.getAxis() == db.getAxis()) continue;
				EDGE_FACES[i] = new Direction[] {da, db};
				// The edge runs along the third axis, at the side of the cube both faces point to.
				float[] lo = new float[3], hi = new float[3];
				for (int axis = 0; axis < 3; axis++) {
					int step = component(da, axis) + component(db, axis);
					lo[axis] = step > 0 ? 1 : 0;
					hi[axis] = step == 0 ? 1 : lo[axis];
				}
				EDGE_LINES[i] = new float[] {lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
				i++;
			}
		}
	}

	private static Vec3 tracerStart;
	private static boolean tracerStartValid;

	private EspRender() {
	}

	private static int component(Direction d, int axis) {
		return axis == 0 ? d.getStepX() : axis == 1 ? d.getStepY() : d.getStepZ();
	}

	/** Called once per frame before the modules render (resets per-frame caches). */
	public static void beginFrame() {
		tracerStartValid = false;
	}

	// ---- block groups ----

	/**
	 * Neighbour masks for {@code positions} (index-aligned), counting only blocks in the same list.
	 * Compute this when the set changes, not every frame.
	 */
	public static int[] neighbourMasks(List<BlockPos> positions) {
		LongOpenHashSet set = new LongOpenHashSet(positions.size());
		for (BlockPos pos : positions) set.add(pos.asLong());
		int[] masks = new int[positions.size()];
		for (int i = 0; i < masks.length; i++) {
			BlockPos pos = positions.get(i);
			int mask = 0;
			for (Direction d : Direction.values()) {
				if (set.contains(BlockPos.offset(pos.asLong(), d.getStepX(), d.getStepY(), d.getStepZ()))) mask |= 1 << d.ordinal();
			}
			for (int e = 0; e < 12; e++) {
				Direction a = EDGE_FACES[e][0], b = EDGE_FACES[e][1];
				long diagonal = BlockPos.offset(pos.asLong(), a.getStepX() + b.getStepX(), a.getStepY() + b.getStepY(), a.getStepZ() + b.getStepZ());
				if (set.contains(diagonal)) mask |= 1 << (FACE_BITS + e);
			}
			masks[i] = mask;
		}
		return masks;
	}

	/** Touching blocks (sharing a face) joined into groups; each group lists indexes into {@code positions}. */
	public static List<int[]> groups(List<BlockPos> positions) {
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap index = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(positions.size());
		index.defaultReturnValue(-1);
		for (int i = 0; i < positions.size(); i++) index.put(positions.get(i).asLong(), i);
		boolean[] seen = new boolean[positions.size()];
		List<int[]> out = new ArrayList<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		it.unimi.dsi.fastutil.ints.IntArrayList members = new it.unimi.dsi.fastutil.ints.IntArrayList();
		for (int start = 0; start < seen.length; start++) {
			if (seen[start]) continue;
			seen[start] = true;
			queue.add(start);
			members.clear();
			while (!queue.isEmpty()) {
				int i = queue.poll();
				members.add(i);
				long packed = positions.get(i).asLong();
				for (Direction d : Direction.values()) {
					int j = index.get(BlockPos.offset(packed, d.getStepX(), d.getStepY(), d.getStepZ()));
					if (j >= 0 && !seen[j]) {
						seen[j] = true;
						queue.add(j);
					}
				}
			}
			out.add(members.toIntArray());
		}
		return out;
	}

	/** Centre of a group of blocks (the average of their centres). */
	public static Vec3 center(List<BlockPos> positions, int[] group) {
		double x = 0, y = 0, z = 0;
		for (int i : group) {
			BlockPos p = positions.get(i);
			x += p.getX();
			y += p.getY();
			z += p.getZ();
		}
		return new Vec3(x / group.length + 0.5, y / group.length + 0.5, z / group.length + 0.5);
	}

	/** Silhouette edges of a unit block at camera-relative {@code x,y,z}, given its neighbour mask (0 = a plain box). */
	public static void blockLines(VertexConsumer vc, float x, float y, float z, int mask, int argb, float width) {
		for (int e = 0; e < 12; e++) {
			boolean a = (mask & 1 << EDGE_FACES[e][0].ordinal()) != 0;
			boolean b = (mask & 1 << EDGE_FACES[e][1].ordinal()) != 0;
			boolean diagonal = (mask & 1 << (FACE_BITS + e)) != 0;
			// Of the four blocks around the edge, it shows unless exactly two share a flat face, or all four are set.
			boolean visible = a == b ? (a ? !diagonal : true) : diagonal;
			if (!visible) continue;
			float[] l = EDGE_LINES[e];
			float nx = l[3] - l[0], ny = l[4] - l[1], nz = l[5] - l[2];
			WorldOverlays.line(vc, x + l[0], y + l[1], z + l[2], x + l[3], y + l[4], z + l[5], nx, ny, nz, argb, width);
		}
	}

	/** Faces of a unit block not shared with a neighbour in {@code mask}. */
	public static void blockFaces(VertexConsumer vc, float x, float y, float z, int mask, int argb) {
		float x2 = x + 1, y2 = y + 1, z2 = z + 1;
		if ((mask & 1 << Direction.DOWN.ordinal()) == 0) quad(vc, x, y, z, x2, y, z, x2, y, z2, x, y, z2, argb);
		if ((mask & 1 << Direction.UP.ordinal()) == 0) quad(vc, x, y2, z, x, y2, z2, x2, y2, z2, x2, y2, z, argb);
		if ((mask & 1 << Direction.NORTH.ordinal()) == 0) quad(vc, x, y, z, x, y2, z, x2, y2, z, x2, y, z, argb);
		if ((mask & 1 << Direction.SOUTH.ordinal()) == 0) quad(vc, x, y, z2, x2, y, z2, x2, y2, z2, x, y2, z2, argb);
		if ((mask & 1 << Direction.WEST.ordinal()) == 0) quad(vc, x, y, z, x, y, z2, x, y2, z2, x, y2, z, argb);
		if ((mask & 1 << Direction.EAST.ordinal()) == 0) quad(vc, x2, y, z, x2, y2, z, x2, y2, z2, x2, y, z2, argb);
	}

	private static void quad(VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
		vc.addVertex(ax, ay, az).setColor(argb);
		vc.addVertex(bx, by, bz).setColor(argb);
		vc.addVertex(cx, cy, cz).setColor(argb);
		vc.addVertex(dx, dy, dz).setColor(argb);
	}

	/**
	 * Draws blocks as boxes in one pass per buffer type.
	 *
	 * @param masks   from {@link #neighbourMasks}, or null to draw every block as a separate box
	 * @param colors  ARGB per block (index-aligned), or null to use {@code argb} for all
	 */
	public static void blocks(List<BlockPos> positions, int[] masks, int[] colors, int argb, WorldOverlays.Shape shape,
							  boolean seeThrough, float width, int fillAlpha, Vec3 cam) {
		if (positions.isEmpty()) return;
		if (shape != WorldOverlays.Shape.Filled) {
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough));
			for (int i = 0; i < positions.size(); i++) {
				BlockPos p = positions.get(i);
				int c = 0xFF000000 | (colors == null ? argb : colors[i]);
				blockLines(lines, (float) (p.getX() - cam.x), (float) (p.getY() - cam.y), (float) (p.getZ() - cam.z), masks == null ? 0 : masks[i], c, width);
			}
		}
		if (shape != WorldOverlays.Shape.Outline) {
			VertexConsumer fill = OverlayRenderer.buffer(WorldOverlays.fillType(seeThrough));
			int alpha = Math.max(0, Math.min(255, fillAlpha)) << 24;
			for (int i = 0; i < positions.size(); i++) {
				BlockPos p = positions.get(i);
				int c = alpha | ((colors == null ? argb : colors[i]) & 0xFFFFFF);
				blockFaces(fill, (float) (p.getX() - cam.x), (float) (p.getY() - cam.y), (float) (p.getZ() - cam.z), masks == null ? 0 : masks[i], c);
			}
		}
	}

	/**
	 * A ready-to-draw set of highlighted blocks: buckets of same-kind blocks with their neighbour masks,
	 * colours, and one marker per touching group (for a tracer and a label). Build it when the matches
	 * change, draw it every frame.
	 */
	public static final class BlockSet {
		/** One group of touching blocks of the same kind. */
		public record Marker(Vec3 center, double top, String label, int color, int count) {
		}

		private final List<BlockPos> positions = new ArrayList<>();
		private final it.unimi.dsi.fastutil.ints.IntArrayList masks = new it.unimi.dsi.fastutil.ints.IntArrayList();
		private final it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
		private final List<Marker> markers = new ArrayList<>();
		/** Array views of masks/colors for drawing, rebuilt after an add. */
		private int[] maskArray;
		private int[] colorArray;

		/** Adds blocks of one kind; with {@code merge}, touching ones are drawn as one shape. */
		public BlockSet add(List<BlockPos> bucket, int rgb, String label, boolean merge) {
			if (bucket.isEmpty()) return this;
			int[] bucketMasks = merge ? neighbourMasks(bucket) : null;
			for (int i = 0; i < bucket.size(); i++) {
				positions.add(bucket.get(i));
				masks.add(bucketMasks == null ? 0 : bucketMasks[i]);
				colors.add(rgb);
			}
			if (merge) {
				for (int[] group : groups(bucket)) {
					double top = Double.NEGATIVE_INFINITY;
					for (int i : group) top = Math.max(top, bucket.get(i).getY() + 1);
					markers.add(new Marker(center(bucket, group), top, label, rgb, group.length));
				}
			} else {
				for (BlockPos p : bucket) markers.add(new Marker(Vec3.atCenterOf(p), p.getY() + 1, label, rgb, 1));
			}
			maskArray = null;
			colorArray = null;
			return this;
		}

		public boolean isEmpty() {
			return positions.isEmpty();
		}

		public int size() {
			return positions.size();
		}

		public List<Marker> markers() {
			return markers;
		}

		public void render(WorldOverlays.Shape shape, boolean seeThrough, float width, int fillAlpha, Vec3 cam) {
			if (maskArray == null) {
				maskArray = masks.toIntArray();
				colorArray = colors.toIntArray();
			}
			blocks(positions, maskArray, colorArray, 0, shape, seeThrough, width, fillAlpha, cam);
		}

		/** {@link Tracers.Style#Classic} tracers, one per marker; call from {@code renderOverlay}. */
		public void screenTracers(dev.coffeeclient.gui.Ui ui, dev.coffeeclient.util.WorldProjector projector, int alpha, float width) {
			screenTracers(ui, projector, alpha, width, false);
		}

		/** As above; {@code nearestOnly} keeps just the marker closest to the camera. */
		public void screenTracers(dev.coffeeclient.gui.Ui ui, dev.coffeeclient.util.WorldProjector projector, int alpha, float width, boolean nearestOnly) {
			int a = Math.max(0, Math.min(255, alpha)) << 24;
			for (Marker m : Tracers.nearestOnly(markers, nearestOnly, m -> m.center().distanceToSqr(projector.camera()))) {
				Tracers.pixel(ui, projector, m.center().x, m.center().y, m.center().z, a | (m.color() & 0xFFFFFF), Math.max(1, Math.round(width)));
			}
		}

		/** One tracer per marker, in each marker's colour at {@code alpha}. */
		public void tracers(int alpha, float width, Vec3 cam) {
			tracers(alpha, width, cam, false);
		}

		/** As above; {@code nearestOnly} keeps just the marker closest to the camera. */
		public void tracers(int alpha, float width, Vec3 cam, boolean nearestOnly) {
			if (markers.isEmpty()) return;
			VertexConsumer lines = tracerBuffer();
			int a = Math.max(0, Math.min(255, alpha)) << 24;
			for (Marker m : Tracers.nearestOnly(markers, nearestOnly, m -> m.center().distanceToSqr(cam))) {
				tracer(lines, m.center().x, m.center().y, m.center().z, a | (m.color() & 0xFFFFFF), width, cam);
			}
		}

		/** Short stubs out of each block's eight corners, instead of full edges ("Highlight corners"). */
		public void corners(boolean seeThrough, float width, Vec3 cam) {
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough));
			final float stub = 0.25f;
			for (int i = 0; i < positions.size(); i++) {
				BlockPos p = positions.get(i);
				float x = (float) (p.getX() - cam.x), y = (float) (p.getY() - cam.y), z = (float) (p.getZ() - cam.z);
				int argb = 0xFF000000 | colors.getInt(i);
				for (int corner = 0; corner < 8; corner++) {
					float cx = x + (corner & 1), cy = y + (corner >> 1 & 1), cz = z + (corner >> 2 & 1);
					float dx = (corner & 1) == 0 ? stub : -stub, dy = (corner >> 1 & 1) == 0 ? stub : -stub, dz = (corner >> 2 & 1) == 0 ? stub : -stub;
					WorldOverlays.line(lines, cx, cy, cz, cx + dx, cy, cz, dx, 0, 0, argb, width);
					WorldOverlays.line(lines, cx, cy, cz, cx, cy + dy, cz, 0, dy, 0, argb, width);
					WorldOverlays.line(lines, cx, cy, cz, cx, cy, cz + dz, 0, 0, dz, argb, width);
				}
			}
		}
	}

	// ---- boxes ----

	/** World-coordinate boxes, outline pass then fill pass (one buffer type at a time). */
	public static void boxes(List<AABB> boxes, int[] colors, WorldOverlays.Shape shape, boolean seeThrough, float width, int fillAlpha, Vec3 cam) {
		if (boxes.isEmpty()) return;
		if (shape != WorldOverlays.Shape.Filled) {
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough));
			for (int i = 0; i < boxes.size(); i++) {
				AABB box = boxes.get(i);
				WorldOverlays.boxLines(lines, (float) (box.minX - cam.x), (float) (box.minY - cam.y), (float) (box.minZ - cam.z),
					(float) box.getXsize(), (float) box.getYsize(), (float) box.getZsize(), 0xFF000000 | colors[i], width);
			}
		}
		if (shape != WorldOverlays.Shape.Outline) {
			VertexConsumer fill = OverlayRenderer.buffer(WorldOverlays.fillType(seeThrough));
			int alpha = Math.max(0, Math.min(255, fillAlpha)) << 24;
			for (int i = 0; i < boxes.size(); i++) {
				AABB box = boxes.get(i);
				WorldOverlays.boxFaces(fill, (float) (box.minX - cam.x), (float) (box.minY - cam.y), (float) (box.minZ - cam.z),
					(float) box.getXsize(), (float) box.getYsize(), (float) box.getZsize(), alpha | (colors[i] & 0xFFFFFF));
			}
		}
	}

	/** The entity's hitbox at this frame's interpolated position. */
	public static AABB entityBox(Entity entity, float partialTick) {
		Vec3 now = entity.position();
		Vec3 at = entity.getPosition(partialTick);
		return entity.getBoundingBox().move(at.x - now.x, at.y - now.y, at.z - now.z);
	}

	/** The camera's view frustum for this frame, for culling. */
	public static Frustum frustum() {
		return Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
	}

	// ---- tracers ----

	/** A line from the screen centre to world point {@code x,y,z}. */
	public static void tracer(VertexConsumer lines, double x, double y, double z, int argb, float width, Vec3 cam) {
		Vec3 start = tracerStart();
		float ex = (float) (x - cam.x), ey = (float) (y - cam.y), ez = (float) (z - cam.z);
		float sx = (float) start.x, sy = (float) start.y, sz = (float) start.z;
		float nx = ex - sx, ny = ey - sy, nz = ez - sz;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-4f) return;
		WorldOverlays.line(lines, sx, sy, sz, ex, ey, ez, nx / len, ny / len, nz / len, argb, width);
	}

	/** Tracer line buffer (always drawn through terrain, like the HUD tracers it replaces). */
	public static VertexConsumer tracerBuffer() {
		return OverlayRenderer.buffer(WorldOverlays.outlineType(true));
	}

	/**
	 * A point one block out along the ray through the screen centre, camera-relative. Unprojecting
	 * through the level's own matrix keeps the start under the crosshair with view bobbing on.
	 */
	private static Vec3 tracerStart() {
		if (tracerStartValid) return tracerStart;
		tracerStartValid = true;
		Minecraft mc = Minecraft.getInstance();
		Vec3 fallback = Vec3.directionFromRotation(mc.gameRenderer.mainCamera().xRot(), mc.gameRenderer.mainCamera().yRot());
		try {
			Matrix4f inverse = WorldProjector.levelViewProjection(mc, new Matrix4f()).invert();
			Vector4f near = inverse.transform(new Vector4f(0, 0, 0, 1));
			Vector4f far = inverse.transform(new Vector4f(0, 0, 0.5f, 1));
			if (near.w == 0 || far.w == 0) return tracerStart = fallback;
			Vec3 a = new Vec3(near.x / near.w, near.y / near.w, near.z / near.w);
			Vec3 b = new Vec3(far.x / far.w, far.y / far.w, far.z / far.w);
			Vec3 dir = b.subtract(a);
			if (dir.lengthSqr() < 1e-12 || !Double.isFinite(dir.x + a.x)) return tracerStart = fallback;
			dir = dir.normalize();
			// Depth conventions differ (reversed or zero-to-one); point the ray forwards whichever is used.
			if (dir.dot(fallback) < 0) dir = dir.scale(-1);
			// One block out from the point of the ray nearest the camera.
			Vec3 nearest = a.subtract(dir.scale(a.dot(dir)));
			return tracerStart = nearest.add(dir);
		} catch (Throwable t) {
			return tracerStart = fallback;
		}
	}
}
