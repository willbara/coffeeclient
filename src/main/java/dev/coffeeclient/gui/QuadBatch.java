package dev.coffeeclient.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Many filled quads (solid or vertical gradients, under any pose) submitted to the GUI as one element.
 * Vanilla places every GUI element by checking its bounds against the elements already added, so
 * thousands of small fills cost quadratic time per frame; a backdrop collected here is one element.
 * Corners are transformed when added, so the pose may change between quads. Quads keep the order
 * they were added in, so later ones still paint over earlier ones.
 */
final class QuadBatch implements GuiElementRenderState {
	private final @Nullable ScreenRectangle scissor;
	/** x, y per corner, 4 corners per quad. */
	private float[] pos = new float[256 * 8];
	/** Colour per corner. */
	private int[] col = new int[256 * 4];
	private int quads;
	private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

	QuadBatch(@Nullable ScreenRectangle scissor) {
		this.scissor = scissor;
	}

	/** Rect from (x0, y0) to (x1, y1), x0 &lt; x1 and y0 &lt; y1, fading from {@code top} to {@code bottom}. */
	void add(Matrix3x2fc pose, int x0, int y0, int x1, int y1, int top, int bottom) {
		if (quads * 8 + 8 > pos.length) {
			pos = Arrays.copyOf(pos, pos.length * 2);
			col = Arrays.copyOf(col, col.length * 2);
		}
		// same corner order (and so winding) as vanilla's ColoredRectangleRenderState
		corner(pose, x0, y0, top, 0);
		corner(pose, x0, y1, bottom, 1);
		corner(pose, x1, y1, bottom, 2);
		corner(pose, x1, y0, top, 3);
		quads++;
	}

	private void corner(Matrix3x2fc pose, float x, float y, int color, int k) {
		float tx = pose.m00() * x + pose.m10() * y + pose.m20();
		float ty = pose.m01() * x + pose.m11() * y + pose.m21();
		int i = quads * 4 + k;
		pos[i * 2] = tx;
		pos[i * 2 + 1] = ty;
		col[i] = color;
		minX = Math.min(minX, tx);
		minY = Math.min(minY, ty);
		maxX = Math.max(maxX, tx);
		maxY = Math.max(maxY, ty);
	}

	boolean isEmpty() {
		return quads == 0;
	}

	@Override
	public void buildVertices(VertexConsumer vc) {
		for (int i = 0, n = quads * 4; i < n; i++) {
			vc.addVertex(pos[i * 2], pos[i * 2 + 1], 0).setColor(col[i]);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.noTexture();
	}

	@Override
	public @Nullable ScreenRectangle scissorArea() {
		return scissor;
	}

	@Override
	public @Nullable ScreenRectangle bounds() {
		if (quads == 0) return null;
		int x = (int) Math.floor(minX), y = (int) Math.floor(minY);
		ScreenRectangle bounds = new ScreenRectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y);
		return scissor != null ? scissor.intersection(bounds) : bounds;
	}
}
