package dev.coffeeclient.util;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Projects world positions onto the HUD using the camera's view-projection matrix, so world overlays
 * (labels, markers, trails) can be drawn with the 2D GUI API instead of custom world rendering.
 * Call {@link #begin} once per frame.
 */
public final class WorldProjector {
	private final Matrix4f matrix = new Matrix4f();
	private final Vector4f vec = new Vector4f();
	private Vec3 cam = Vec3.ZERO;
	private int width;
	private int height;
	private boolean ready;

	public boolean begin(int screenWidth, int screenHeight) {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.mainCamera();
		ready = camera.isInitialized() && mc.level != null;
		if (!ready) return false;
		cam = camera.position();
		levelViewProjection(mc, matrix);
		width = screenWidth;
		height = screenHeight;
		return true;
	}

	/**
	 * The view-projection the level is drawn with this frame, including the hurt shake and view bobbing.
	 * The camera's own matrix leaves those out, so labels projected with it bounce against the world
	 * while walking. Valid from HUD extraction on: the camera state is extracted just before the HUD.
	 */
	public static Matrix4f levelViewProjection(Minecraft mc, Matrix4f out) {
		var state = mc.gameRenderer.gameRenderState();
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		if (camera == null || camera.projectionMatrix == null || camera.viewRotationMatrix == null) {
			return mc.gameRenderer.mainCamera().getViewRotationProjectionMatrix(out);
		}
		PoseStack shake = new PoseStack();
		GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
		renderer.coffeeclient$bobHurt(camera, shake);
		if (state.optionsRenderState.bobView) renderer.coffeeclient$bobView(camera, shake);
		return out.set(camera.projectionMatrix).mul(shake.last().pose()).mul(camera.viewRotationMatrix);
	}

	public Vec3 camera() {
		return cam;
	}

	public double distance(double x, double y, double z) {
		return cam.distanceTo(new Vec3(x, y, z));
	}

	/** Screen position {x, y}, or null if the point is behind the camera or not ready. */
	public float[] project(double x, double y, double z) {
		if (!ready) return null;
		vec.set((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1f);
		matrix.transform(vec);
		if (vec.w <= 0.0001f) return null;
		return new float[] {(vec.x / vec.w + 1f) * 0.5f * width, (1f - vec.y / vec.w) * 0.5f * height};
	}

	/**
	 * Where a tracer to {@code x,y,z} should end: the screen position when the point is in front, else
	 * a point far off the screen edge in the direction you would turn to face it (the line is clipped
	 * to the screen when drawn). Null only when not ready.
	 */
	public float[] projectTracer(double x, double y, double z) {
		if (!ready) return null;
		vec.set((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1f);
		matrix.transform(vec);
		if (vec.w > 0.0001f) return new float[] {(vec.x / vec.w + 1f) * 0.5f * width, (1f - vec.y / vec.w) * 0.5f * height};
		// Behind: clip-space x and y keep their sideways/vertical sign whatever w is, so they give the
		// direction; a target straight behind goes to the bottom edge.
		float dx = vec.x * width * 0.5f, dy = -vec.y * height * 0.5f;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 1e-4f) {
			dx = 0;
			dy = 1;
			len = 1;
		}
		float reach = (width + height) * 2f;
		return new float[] {width * 0.5f + dx / len * reach, height * 0.5f + dy / len * reach};
	}

	public boolean onScreen(float[] p, int margin) {
		return p != null && p[0] >= -margin && p[0] <= width + margin && p[1] >= -margin && p[1] <= height + margin;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}
}
