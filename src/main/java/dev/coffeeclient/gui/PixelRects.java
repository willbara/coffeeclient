package dev.coffeeclient.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Many solid rectangles submitted to the GUI as one element. Each {@code fill} becomes its own GUI
 * element (a pose copy, bounds and sorting work each), which made a pixel line of 50 squares cost 50
 * elements; this packs them into one.
 */
final class PixelRects implements GuiElementRenderState {
	private final Matrix3x2fc pose;
	private final @Nullable ScreenRectangle scissor;
	/** x0, y0, x1, y1, colour per rectangle. */
	private int[] data = new int[40];
	private int size;
	private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

	PixelRects(Matrix3x2fc pose, @Nullable ScreenRectangle scissor) {
		this.pose = new Matrix3x2f(pose);
		this.scissor = scissor;
	}

	void add(int x, int y, int w, int h, int color) {
		if (size + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
		data[size++] = x;
		data[size++] = y;
		data[size++] = x + w;
		data[size++] = y + h;
		data[size++] = color;
		minX = Math.min(minX, x);
		minY = Math.min(minY, y);
		maxX = Math.max(maxX, x + w);
		maxY = Math.max(maxY, y + h);
	}

	boolean isEmpty() {
		return size == 0;
	}

	@Override
	public void buildVertices(VertexConsumer vc) {
		for (int i = 0; i < size; i += 5) {
			int x0 = data[i], y0 = data[i + 1], x1 = data[i + 2], y1 = data[i + 3], c = data[i + 4];
			vc.addVertexWith2DPose(pose, x0, y0).setColor(c);
			vc.addVertexWith2DPose(pose, x0, y1).setColor(c);
			vc.addVertexWith2DPose(pose, x1, y1).setColor(c);
			vc.addVertexWith2DPose(pose, x1, y0).setColor(c);
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
		if (size == 0) return null;
		ScreenRectangle bounds = new ScreenRectangle(minX, minY, maxX - minX, maxY - minY).transformMaxBounds(pose);
		return scissor != null ? scissor.intersection(bounds) : bounds;
	}
}
