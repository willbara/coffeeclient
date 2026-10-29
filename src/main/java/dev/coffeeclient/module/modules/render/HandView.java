package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Moves, scales and tilts the first-person hands/held items, both together and each hand on its own,
 * and restyles the hand model itself: opacity, tint, an enchant glint, or a cham-style fill (solid,
 * gradient or rainbow, optionally pulsing and unlit).
 */
public final class HandView extends Module {
	public final NumberSetting offsetX = setting(new NumberSetting("Offset X", "Left/right", 0.0, -1, 1, 0.05));
	public final NumberSetting offsetY = setting(new NumberSetting("Offset Y", "Down/up", 0.0, -1, 1, 0.05));
	public final NumberSetting offsetZ = setting(new NumberSetting("Offset Z", "Toward/away from the camera", 0.0, -1, 1, 0.05));
	public final NumberSetting scale = setting(new NumberSetting("Scale", "Size of the hands", 1.0, 0.3, 1.5, 0.05));
	public final NumberSetting tilt = setting(new NumberSetting("Tilt", "Rotation around the view axis (degrees)", 0, -45, 45, 1));

	/** How the hand model is filled. Off keeps the skin texture. */
	public enum Fill { OFF, SOLID, GRADIENT, RAINBOW }

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("coffeeclient", "textures/hand/white.png");

	public final BooleanSetting swing = group(new BooleanSetting("Swing modifier", "Change how your hands swing", false), "Swing");
	public final NumberSetting mainSwingSpeed = group(new NumberSetting("Main hand speed", "Swing speed (1 = vanilla)", 1, 0.25, 4, 0.05), "Swing");
	public final NumberSetting offSwingSpeed = group(new NumberSetting("Offhand speed", "Swing speed (1 = vanilla)", 1, 0.25, 4, 0.05), "Swing");
	public final NumberSetting swingStart = group(new NumberSetting("Swing start", "Where the swing animation starts (0 = vanilla)", 0, 0, 1, 0.05), "Swing");
	public final NumberSetting swingEnd = group(new NumberSetting("Swing end", "Where it ends (1 = vanilla)", 1, 0, 1, 0.05), "Swing");
	public final NumberSetting swingY = group(new NumberSetting("Swing height", "Lift or drop the hand during a swing", 0, -0.5, 0.5, 0.01), "Swing");
	public final NumberSetting opacity = group(new NumberSetting("Opacity", "How solid the hand model is", 1.0, 0.05, 1, 0.05), "Look");
	public final BooleanSetting tint = group(new BooleanSetting("Tint", "Colour the skin texture", false), "Look");
	public final ColorSetting tintColor = group(new ColorSetting("Tint colour", "Multiplied onto the skin", 0xFFFF8080), "Look");
	public final BooleanSetting glint = group(new BooleanSetting("Enchant glint", "Draw the enchantment shimmer over the hand", false), "Look");
	public final ColorSetting glintColor = group(new ColorSetting("Glint colour", "Colour of the shimmer", 0xFFB070FF), "Look");

	public final EnumSetting<Fill> fill = group(new EnumSetting<>("Fill", "Replace the skin with a flat colour, a gradient along the arm, or a rainbow", Fill.OFF), "Cham");
	public final ColorSetting colorA = group(new ColorSetting("Colour", "Fill colour, or the start of the gradient", 0xFF55AAFF), "Cham");
	public final ColorSetting colorB = group(new ColorSetting("Second colour", "End of the gradient", 0xFFFF55AA), "Cham");
	public final NumberSetting animSpeed = group(new NumberSetting("Animation speed", "Cycles per second for the scrolling gradient, rainbow and pulse (0 = still)", 0.3, 0, 3, 0.05), "Cham");
	public final BooleanSetting pulse = group(new BooleanSetting("Pulse", "Breathe the opacity in and out", false), "Cham");
	public final BooleanSetting shaded = group(new BooleanSetting("Shaded", "Light the fill like the world; off = flat and always bright", false), "Cham");
	public final BooleanSetting hideSleeve = group(new BooleanSetting("Hide sleeve", "Drop the sleeve layer so the fill is one clean shape", true), "Cham");

	private final Hand main = new Hand("Main hand");
	private final Hand off = new Hand("Off hand");

	/** One hand's extra offset and rotation, applied on top of the shared ones. */
	private final class Hand {
		final NumberSetting x, y, z, pitch, yaw, roll;

		Hand(String name) {
			String g = name;
			String prefix = name.startsWith("Main") ? "Main " : "Off ";
			x = group(new NumberSetting(prefix + "X", name + " left/right", 0.0, -1, 1, 0.05), g);
			y = group(new NumberSetting(prefix + "Y", name + " down/up", 0.0, -1, 1, 0.05), g);
			z = group(new NumberSetting(prefix + "Z", name + " toward/away", 0.0, -1, 1, 0.05), g);
			pitch = group(new NumberSetting(prefix + "pitch", name + " rotation around the left-right axis (degrees)", 0, -180, 180, 5), g);
			yaw = group(new NumberSetting(prefix + "yaw", name + " rotation around the up axis (degrees)", 0, -180, 180, 5), g);
			roll = group(new NumberSetting(prefix + "roll", name + " rotation around the view axis (degrees)", 0, -180, 180, 5), g);
		}

		void apply(PoseStack pose) {
			pose.translate(x.floatValue(), y.floatValue(), z.floatValue());
			if (pitch.get() != 0) pose.mulPose(Axis.XP.rotationDegrees(pitch.floatValue()));
			if (yaw.get() != 0) pose.mulPose(Axis.YP.rotationDegrees(yaw.floatValue()));
			if (roll.get() != 0) pose.mulPose(Axis.ZP.rotationDegrees(roll.floatValue()));
		}
	}

	public HandView() {
		super("HandView", Category.RENDER, "Adjusts the first-person hand position and look.");
		tintColor.visibleWhen(tint::get);
		glintColor.visibleWhen(glint::get);
		for (Setting<?> s : List.of(colorA, animSpeed, pulse, shaded, hideSleeve)) s.visibleWhen(() -> fill.get() != Fill.OFF);
		colorB.visibleWhen(() -> fill.get() == Fill.GRADIENT);
		for (Setting<?> s : List.of(mainSwingSpeed, offSwingSpeed, swingStart, swingEnd, swingY)) s.visibleWhen(swing::get);
	}

	private static HandView swinging() {
		HandView h = ModuleManager.ifOn(HandView.class);
		return h != null && h.swing.get() ? h : null;
	}

	/** LivingEntityMixin: the local player's swing lasts this many ticks instead. */
	public static int swingDuration(int ticks, InteractionHand hand) {
		HandView h = swinging();
		if (h == null) return ticks;
		double speed = (hand == InteractionHand.OFF_HAND ? h.offSwingSpeed : h.mainSwingSpeed).get();
		return Math.max(1, (int) Math.round(ticks / speed));
	}

	/** ItemInHandRendererMixin: where in its animation a swing at {@code attack} (0-1) is drawn. */
	public static float swingProgress(float attack) {
		HandView h = swinging();
		if (h == null || attack <= 0) return attack;
		return (float) (h.swingStart.get() + attack * (h.swingEnd.get() - h.swingStart.get()));
	}

	/** Mixin hook at the start of first-person hand rendering. */
	public static void apply(PoseStack pose) {
		if (!ModuleManager.on(HandView.class)) return;
		HandView h = CoffeeClient.modules().get(HandView.class);
		pose.translate(h.offsetX.floatValue(), h.offsetY.floatValue(), h.offsetZ.floatValue());
		if (h.tilt.get() != 0) pose.mulPose(Axis.ZP.rotationDegrees(h.tilt.floatValue()));
		float s = h.scale.floatValue();
		if (s != 1f) pose.scale(s, s, s);
	}

	/** Mixin hook inside one hand's own pose, so it doesn't carry over to the other hand. */
	public static void applyHand(PoseStack pose, InteractionHand hand, float attack) {
		if (!ModuleManager.on(HandView.class)) return;
		HandView h = CoffeeClient.modules().get(HandView.class);
		(hand == InteractionHand.MAIN_HAND ? h.main : h.off).apply(pose);
		if (h.swing.get() && attack > 0 && h.swingY.get() != 0) pose.translate(0, h.swingY.floatValue() * Mth.sin(attack * Mth.PI), 0);
	}

	/** True when the hand model needs the custom draw instead of vanilla's. */
	public static boolean restylesHand() {
		if (!ModuleManager.on(HandView.class)) return false;
		HandView h = CoffeeClient.modules().get(HandView.class);
		return h.opacity.get() < 1 || h.tint.get() || h.glint.get() || h.fill.get() != Fill.OFF;
	}

	/** Mixin hook in place of vanilla's hand submit: draws the arm per the settings. */
	public static void submitHand(SubmitNodeCollector collector, ModelPart arm, com.mojang.blaze3d.vertex.PoseStack pose,
								  RenderType skinType, int light, int overlay) {
		HandView h = CoffeeClient.modules().get(HandView.class);
		float alpha = h.opacity.floatValue();
		if (h.pulse.get() && h.fill.get() != Fill.OFF) alpha *= (float) (0.65 + 0.35 * Math.sin(seconds() * h.animSpeed.get() * 2 * Math.PI));
		if (h.fill.get() == Fill.OFF) {
			int tint = h.tint.get() ? h.tintColor.argb() : -1;
			collector.submitModelPart(arm, pose, skinType, light, overlay, null, scaleAlpha(tint, alpha), null);
		} else {
			if (h.hideSleeve.get()) {
				for (String sleeve : List.of("left_sleeve", "right_sleeve")) {
					if (arm.hasChild(sleeve)) arm.getChild(sleeve).visible = false;
				}
			}
			boolean lit = h.shaded.get();
			RenderType type = lit ? RenderTypes.entityTranslucent(WHITE) : RenderTypes.entityTranslucentEmissive(WHITE);
			int fillLight = lit ? light : LightCoordsUtil.FULL_BRIGHT;
			float finalAlpha = alpha;
			collector.submitCustomGeometry(pose, type, (p, buffer) -> {
				var stack = new com.mojang.blaze3d.vertex.PoseStack();
				stack.mulPose(p.pose());
				arm.render(stack, new FillConsumer(buffer, h, armAxis(arm, p.pose()), finalAlpha), fillLight, overlay, -1);
			});
		}
		if (h.glint.get()) {
			collector.submitModelPart(arm, pose, RenderTypes.entityGlint(), light, overlay, null, scaleAlpha(h.glintColor.argb(), alpha), null);
		}
	}

	private static double seconds() {
		return System.nanoTime() / 1_000_000_000.0;
	}

	private static int scaleAlpha(int argb, float factor) {
		return Colors.withAlpha(argb, Math.round(Colors.alpha(argb) * Math.max(0, Math.min(1, factor))));
	}

	/** Shoulder and hand ends of the arm in the space its vertices are submitted in. */
	private static Vector3f[] armAxis(ModelPart arm, Matrix4f pose) {
		var stack = new com.mojang.blaze3d.vertex.PoseStack();
		stack.mulPose(pose);
		arm.translateAndRotate(stack);
		Matrix4f m = stack.last().pose();
		return new Vector3f[] {m.transformPosition(0, -2 / 16f, 0, new Vector3f()), m.transformPosition(0, 10 / 16f, 0, new Vector3f())};
	}

	/** Passes the arm's vertices through, replacing each colour with the fill colour at that point along the arm. */
	private static final class FillConsumer implements com.mojang.blaze3d.vertex.VertexConsumer {
		private final com.mojang.blaze3d.vertex.VertexConsumer out;
		private final Fill fill;
		private final int a, b;
		private final float alpha;
		private final double phase;
		private final Vector3f top, axis = new Vector3f();
		private final float axisLengthSquared;
		private int color;

		FillConsumer(com.mojang.blaze3d.vertex.VertexConsumer out, HandView h, Vector3f[] ends, float alpha) {
			this.out = out;
			this.fill = h.fill.get();
			this.a = h.colorA.argb();
			this.b = h.colorB.argb();
			this.alpha = alpha;
			this.phase = seconds() * h.animSpeed.get();
			this.top = ends[0];
			ends[1].sub(ends[0], axis);
			this.axisLengthSquared = Math.max(axis.lengthSquared(), 1e-6f);
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
			float t = Math.max(0, Math.min(1, ((x - top.x) * axis.x + (y - top.y) * axis.y + (z - top.z) * axis.z) / axisLengthSquared));
			color = switch (fill) {
				case SOLID -> a;
				case GRADIENT -> {
					double p = (t * 0.5 + phase) % 1.0;
					yield Colors.lerp(a, b, (float) (1 - Math.abs(2 * p - 1)));
				}
				case RAINBOW -> Colors.fromHsv((float) (((t * 0.6 + phase) % 1.0) * 360), 0.85f, 1f, Colors.alpha(a));
				case OFF -> -1;
			};
			out.addVertex(x, y, z);
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int bl, int al) {
			return setColor(Colors.argb(al, r, g, bl));
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setColor(int ignored) {
			out.setColor(Colors.red(color), Colors.green(color), Colors.blue(color), Math.round(255 * Math.max(0, Math.min(1, alpha * Colors.alpha(color) / 255f))));
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
			out.setUv(u, v);
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
			out.setUv1(u, v);
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
			out.setUv2(u, v);
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
			out.setNormal(x, y, z);
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
			out.setLineWidth(width);
			return this;
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
