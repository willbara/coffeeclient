package dev.coffeeclient.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Collects the world-space overlay geometry the ESP-style modules draw and flushes it in its own pass
 * after the level has been rendered.
 *
 * <p>Vanilla draws submitted feature geometry inside the level's main pass, where translucent terrain
 * (water) is painted <em>after</em> the overlays and blends over them. Over an ocean that washes the
 * highlights out or hides them entirely. So the boxes are drawn after the whole level render
 * instead: geometry is staged while {@code LevelRenderer#submitFeatures} runs and then drawn straight
 * into the main target (with the world depth buffer) just before the hand and HUD.
 *
 * <p>The staging buffer plus {@link RenderType#prepare()} handle vertex formats, pipelines and uniforms
 * exactly like vanilla's own feature dispatcher, so the overlays keep their unlit, unfogged look.
 */
public final class OverlayRenderer {
	private static final StagedVertexBuffer BUFFER =
		new StagedVertexBuffer(() -> "coffeeclient overlay", RenderType.BIG_BUFFER_SIZE);
	/** Draws in the order they were staged; a type used again after another gets a new draw (see {@link #buffer}). */
	private static final List<Map.Entry<RenderType, StagedVertexBuffer.Draw>> DRAWS = new ArrayList<>();

	/** The level's model-view (camera-rotation only) captured while the world was being submitted. */
	private static Matrix4f modelView;
	private static boolean uploaded;

	private OverlayRenderer() {
	}

	/** Starts collecting this frame's overlays; call before the modules' {@code renderWorld}. */
	public static void begin() {
		reset();
		// Right now the level's model-view is on the stack, which is the space the overlays are built in.
		modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
	}

	/** Stages {@code geometry} for one of the overlay render types. */
	public static void write(RenderType type, Consumer<VertexConsumer> geometry) {
		geometry.accept(buffer(type));
	}

	/**
	 * The vertex sink for {@code type}, for modules that write many shapes at once (camera-relative
	 * coordinates, no pose). Switching types finishes the previous builder for good, so a type asked
	 * for again after another one starts a fresh draw instead of writing into the finished one.
	 */
	public static VertexConsumer buffer(RenderType type) {
		StagedVertexBuffer.Draw draw;
		if (!DRAWS.isEmpty() && DRAWS.getLast().getKey() == type) {
			draw = DRAWS.getLast().getValue();
		} else {
			draw = BUFFER.appendDraw(type.format(), type.primitiveTopology());
			DRAWS.add(Map.entry(type, draw));
		}
		return BUFFER.getVertexBuilder(draw);
	}

	/** Uploads the geometry collected since {@link #begin()}. */
	public static void end() {
		if (DRAWS.isEmpty()) return;
		// upload() also sizes the shared sequential index buffers the draws will use.
		BUFFER.upload();
		uploaded = true;
	}

	/**
	 * Draws the staged overlays into the main render target using the world depth buffer, after the
	 * level (including its translucent terrain) has been rendered.
	 */
	public static void draw() {
		if (!uploaded) return;

		Minecraft minecraft = Minecraft.getInstance();
		if (modelView == null || minecraft.level == null) {
			reset();
			return;
		}

		Matrix4fStack stack = RenderSystem.getModelViewStack();
		stack.pushMatrix();
		stack.set(modelView);
		try {
			// Each prepared type opens its own pass on the main target (colour + world depth).
			for (Map.Entry<RenderType, StagedVertexBuffer.Draw> entry : DRAWS) {
				StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(entry.getValue());
				if (info == null || info.indexBuffer() == null) continue;
				entry.getKey().prepare().drawFromBuffer(info);
			}
		} catch (Throwable t) {
			// Logged once: a draw that fails fails every frame.
			ErrorGuard.report("overlay-draw", t);
		} finally {
			stack.popMatrix();
			reset();
		}
	}

	private static void reset() {
		BUFFER.endFrame();
		DRAWS.clear();
		modelView = null;
		uploaded = false;
	}
}
