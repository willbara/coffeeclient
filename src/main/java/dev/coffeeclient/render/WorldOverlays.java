package dev.coffeeclient.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * World-space overlay geometry shared by the ESP-style modules. Outlines mimic the vanilla block
 * outline and filled faces are plain vertex-coloured quads; both are staged in world space (camera
 * relative) through {@link OverlayRenderer}, which flushes them after the level so translucent terrain
 * cannot paint over them. Building them in world space rather than projecting onto the HUD also avoids
 * the smearing you get when a corner near the view plane flies off to a huge screen coordinate.
 */
public final class WorldOverlays {
	/** Filled geometry callback, matching vanilla's custom-geometry renderer. */
	@FunctionalInterface
	public interface Geometry {
		void write(PoseStack.Pose pose, VertexConsumer buffer);
	}

	/** How a world-space box is drawn. */
	public enum Shape {
		/** Only the edges. */
		Outline,
		/** Only the translucent faces. */
		Filled,
		/** Faces plus edges. */
		Both
	}

	/** Outlines that ignore depth, so they stay visible through terrain. */
	private static final RenderType SEE_THROUGH_LINES = create(CoffeeRenderPipelines.SEE_THROUGH_LINES,
		"coffeeclient_lines_seethrough", RenderTypes.LINES_TRANSLUCENT);

	/** Outlines drawn depth-tested against the world. */
	private static final RenderType LINES = create(CoffeeRenderPipelines.LINES,
		"coffeeclient_lines", RenderTypes.LINES_TRANSLUCENT);

	/** Filled faces that ignore depth. */
	private static final RenderType SEE_THROUGH_FILLED = create(CoffeeRenderPipelines.SEE_THROUGH_FILLED,
		"coffeeclient_filled_seethrough", RenderTypes.debugFilledBox());

	/** Filled faces drawn depth-tested against the world. */
	private static final RenderType FILLED = create(CoffeeRenderPipelines.FILLED,
		"coffeeclient_filled", RenderTypes.debugFilledBox());

	/** Opaque faces, depth-tested and writing depth. */
	private static final RenderType SOLID = create(CoffeeRenderPipelines.SOLID,
		"coffeeclient_solid", RenderTypes.debugFilledBox());

	private WorldOverlays() {
	}

	/** Outline type: depth-tested, or drawn through terrain when {@code seeThrough}. */
	public static RenderType outlineType(boolean seeThrough) {
		return seeThrough ? SEE_THROUGH_LINES : LINES;
	}

	/** Fill type: depth-tested, or drawn through terrain when {@code seeThrough}. */
	public static RenderType fillType(boolean seeThrough) {
		return seeThrough ? SEE_THROUGH_FILLED : FILLED;
	}

	/**
	 * Edges per shape as {x1,y1,z1, x2,y2,z2, nx,ny,nz} runs. Computing a shape's edges walks its voxel
	 * grid, which profiled as the main cost of drawing hundreds of ESP boxes a frame; the shapes passed
	 * here are a handful of shared constants, so each is worked out once. Render thread only.
	 */
	private static final Map<VoxelShape, float[]> EDGES = new WeakHashMap<>();

	private static float[] edgesOf(VoxelShape shape) {
		List<Float> out = new ArrayList<>();
		Vector3f normal = new Vector3f();
		shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
			normal.set((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
			for (float v : new float[] {(float) x1, (float) y1, (float) z1, (float) x2, (float) y2, (float) z2, normal.x, normal.y, normal.z}) out.add(v);
		});
		float[] edges = new float[out.size()];
		for (int i = 0; i < edges.length; i++) edges[i] = out.get(i);
		return edges;
	}

	/** Stages the vanilla block-outline style edges of {@code shape} under {@code pose}. */
	public static void outline(PoseStack pose, VoxelShape shape, int argb, float width, boolean seeThrough) {
		lines(pose, EDGES.computeIfAbsent(shape, WorldOverlays::edgesOf), argb, width, seeThrough);
	}

	/** Stages line segments given as {x1,y1,z1, x2,y2,z2, nx,ny,nz} runs under {@code pose}. */
	public static void lines(PoseStack pose, float[] e, int argb, float width, boolean seeThrough) {
		PoseStack.Pose frame = pose.last();
		OverlayRenderer.write(outlineType(seeThrough), vc -> {
			for (int i = 0; i < e.length; i += 9) {
				vc.addVertex(frame, e[i], e[i + 1], e[i + 2]).setColor(argb).setNormal(frame, e[i + 6], e[i + 7], e[i + 8]).setLineWidth(width);
				vc.addVertex(frame, e[i + 3], e[i + 4], e[i + 5]).setColor(argb).setNormal(frame, e[i + 6], e[i + 7], e[i + 8]).setLineWidth(width);
			}
		});
	}

	// ---- direct writers: camera-relative coordinates into a sink from OverlayRenderer.buffer, no allocation ----

	/** One line segment for an outline buffer. */
	public static void line(VertexConsumer vc, float x1, float y1, float z1, float x2, float y2, float z2,
							float nx, float ny, float nz, int argb, float width) {
		vc.addVertex(x1, y1, z1).setColor(argb).setNormal(nx, ny, nz).setLineWidth(width);
		vc.addVertex(x2, y2, z2).setColor(argb).setNormal(nx, ny, nz).setLineWidth(width);
	}

	/** The 4 edges of a flat {@code w} x {@code d} rectangle at height {@code y}. */
	public static void rectLines(VertexConsumer vc, float x, float y, float z, float w, float d, int argb, float width) {
		line(vc, x, y, z, x + w, y, z, 1, 0, 0, argb, width);
		line(vc, x + w, y, z, x + w, y, z + d, 0, 0, 1, argb, width);
		line(vc, x + w, y, z + d, x, y, z + d, -1, 0, 0, argb, width);
		line(vc, x, y, z + d, x, y, z, 0, 0, -1, argb, width);
	}

	/** The 12 edges of a box. */
	public static void boxLines(VertexConsumer vc, float x, float y, float z, float w, float h, float d, int argb, float width) {
		rectLines(vc, x, y, z, w, d, argb, width);
		rectLines(vc, x, y + h, z, w, d, argb, width);
		line(vc, x, y, z, x, y + h, z, 0, 1, 0, argb, width);
		line(vc, x + w, y, z, x + w, y + h, z, 0, 1, 0, argb, width);
		line(vc, x + w, y, z + d, x + w, y + h, z + d, 0, 1, 0, argb, width);
		line(vc, x, y, z + d, x, y + h, z + d, 0, 1, 0, argb, width);
	}

	/** A flat {@code w} x {@code d} quad at height {@code y} for a fill buffer. */
	public static void rectFace(VertexConsumer vc, float x, float y, float z, float w, float d, int argb) {
		vc.addVertex(x, y, z).setColor(argb);
		vc.addVertex(x + w, y, z).setColor(argb);
		vc.addVertex(x + w, y, z + d).setColor(argb);
		vc.addVertex(x, y, z + d).setColor(argb);
	}

	/** The 6 faces of a box for a fill buffer. */
	public static void boxFaces(VertexConsumer vc, float x, float y, float z, float w, float h, float d, int argb) {
		float x2 = x + w, y2 = y + h, z2 = z + d;
		quad(vc, x, y, z, x2, y, z, x2, y, z2, x, y, z2, argb);
		quad(vc, x, y2, z, x, y2, z2, x2, y2, z2, x2, y2, z, argb);
		quad(vc, x, y, z, x, y2, z, x2, y2, z, x2, y, z, argb);
		quad(vc, x, y, z2, x2, y, z2, x2, y2, z2, x, y2, z2, argb);
		quad(vc, x, y, z, x, y, z2, x, y2, z2, x, y2, z, argb);
		quad(vc, x2, y, z, x2, y2, z, x2, y2, z2, x2, y, z2, argb);
	}

	private static void quad(VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
		vc.addVertex(ax, ay, az).setColor(argb);
		vc.addVertex(bx, by, bz).setColor(argb);
		vc.addVertex(cx, cy, cz).setColor(argb);
		vc.addVertex(dx, dy, dz).setColor(argb);
	}

	/** Stages one flat quad (corners in order) as a filled face. */
	public static void quad(PoseStack pose, boolean seeThrough, int argb,
							float x0, float y0, float z0, float x1, float y1, float z1,
							float x2, float y2, float z2, float x3, float y3, float z3) {
		fill(pose, seeThrough, (frame, vc) -> {
			vc.addVertex(frame, x0, y0, z0).setColor(argb);
			vc.addVertex(frame, x1, y1, z1).setColor(argb);
			vc.addVertex(frame, x2, y2, z2).setColor(argb);
			vc.addVertex(frame, x3, y3, z3).setColor(argb);
		});
	}

	/** Stages a unit cube (0..1 on each axis) as an outline, filled faces, or both. */
	public static void cube(PoseStack pose, Shape mode, boolean seeThrough, int argb, int fillAlpha, float width) {
		if (mode != Shape.Filled) {
			outline(pose, net.minecraft.world.phys.shapes.Shapes.block(), argb, width, seeThrough);
		}
		if (mode != Shape.Outline) {
			int fill = (Math.max(0, Math.min(255, fillAlpha)) << 24) | (argb & 0xFFFFFF);
			fill(pose, seeThrough, (p, vc) -> {
				face(vc, p, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, fill);
				face(vc, p, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, fill);
				face(vc, p, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, fill);
				face(vc, p, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, fill);
				face(vc, p, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, fill);
				face(vc, p, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, fill);
			});
		}
	}

	private static void face(VertexConsumer vc, PoseStack.Pose pose,
							 float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		vc.addVertex(pose, ax, ay, az).setColor(color);
		vc.addVertex(pose, bx, by, bz).setColor(color);
		vc.addVertex(pose, cx, cy, cz).setColor(color);
		vc.addVertex(pose, dx, dy, dz).setColor(color);
	}

	/** Stages arbitrary filled geometry (quads, in the {@code pose}'s local space). */
	public static void fill(PoseStack pose, boolean seeThrough, Geometry geometry) {
		PoseStack.Pose frame = pose.last();
		OverlayRenderer.write(fillType(seeThrough), vc -> geometry.write(frame, vc));
	}

	/** Stages opaque filled geometry that writes depth, for solid models (in the {@code pose}'s local space). */
	public static void solid(PoseStack pose, Geometry geometry) {
		PoseStack.Pose frame = pose.last();
		OverlayRenderer.write(SOLID, vc -> geometry.write(frame, vc));
	}

	/** Builds a {@link RenderType} from one of our custom pipelines, falling back to a vanilla type. */
	private static RenderType create(RenderPipeline pipeline, String name, RenderType fallback) {
		try {
			RenderSetup setup = RenderSetup.builder(pipeline).createRenderSetup();
			java.lang.reflect.Constructor<RenderType> ctor = RenderType.class.getDeclaredConstructor(String.class, RenderSetup.class);
			ctor.setAccessible(true);
			return ctor.newInstance(name, setup);
		} catch (Throwable t) {
			return fallback;
		}
	}
}
