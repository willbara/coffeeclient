package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.mixin.MultiPlayerGameModeAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the vanilla block selection outline with its own: the outline follows the block's real
 * shape (a door or bed takes in its other half, a double chest both), slides smoothly from block to
 * block, fades in and out, can glow, fill, colour itself from a gradient that flows along it, and
 * shows how far a break has got. The vanilla outline is hidden while the module is on.
 */
public final class SelectOutline extends Module {
	public enum Style implements Labeled {
		BOTH("Both"), OUTLINE("Outline"), FILL("Fill");

		private final String label;

		Style(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum ColorMode implements Labeled {
		SOLID("Solid"), ACCENT("Accent"), GRADIENT("Gradient"), RAINBOW("Rainbow");

		private final String label;

		ColorMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Seconds to fade in and out, and how far (in blocks) a jump still slides instead of snapping. */
	private static final float FADE_IN = 0.12f, FADE_OUT = 0.2f;
	private static final double SNAP_DISTANCE = 6;
	/** Line pieces are at most this long when the colour changes along them, and at most this many per edge. */
	private static final double SEGMENT = 0.125;
	private static final int MAX_PIECES = 16;
	/** How far toward the eye lines and fills are nudged so they sit on top of the block's faces. */
	private static final double LINE_LIFT = 0.004, GLOW_LIFT = 0.002, FILL_INFLATE = 0.002;
	private static final float FLOW_SPEED = 0.35f;

	public final EnumSetting<Style> style = group(new EnumSetting<>("Style", "Outline, translucent fill, or both", Style.BOTH), "General");
	public final ColorSetting color = group(new ColorSetting("Color", "Outline colour (alpha = opacity)", 0xCCE7C88B), "General");
	public final EnumSetting<ColorMode> colorMode = group(new EnumSetting<>("Color mode",
		"Solid: one colour. Accent: the UI accent. Gradient: from Color to Second color. Rainbow: hues along the shape", ColorMode.SOLID), "General");
	public final ColorSetting secondColor = group(new ColorSetting("Second color", "Gradient end colour", 0xCCFFE9B0), "General");
	public final BooleanSetting flow = group(new BooleanSetting("Flow", "Let the gradient or rainbow drift along the shape", true), "General");
	public final NumberSetting width = group(new NumberSetting("Width", "Line width", 2, 0.5, 5, 0.25), "General");
	public final NumberSetting fillOpacity = group(new NumberSetting("Fill opacity", "Opacity of the fill, in percent", 20, 2, 60, 1), "General");
	public final BooleanSetting glow = group(new BooleanSetting("Glow", "Soft halo around the lines", true), "General");
	public final BooleanSetting pulse = group(new BooleanSetting("Pulse", "Gently pulse the opacity", false), "General");
	public final BooleanSetting smooth = group(new BooleanSetting("Smooth", "Slide from one block to the next instead of jumping", true), "Motion");
	public final NumberSetting smoothSpeed = group(new NumberSetting("Smooth speed", "How fast it catches up", 16, 4, 40, 1), "Motion");
	public final BooleanSetting miningProgress = group(new BooleanSetting("Mining progress", "Fill the shape from the bottom as you break the block", true), "Motion");
	public final BooleanSetting faceOnly = group(new BooleanSetting("Face only", "Only mark the face you are looking at", false), "Motion");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through", "Draw over terrain in front of it", false), "Motion");

	/** Set from LevelRendererMixin each frame: vanilla would draw an outline now (not hidden by F1, a screen, ...). */
	private static volatile boolean vanillaOutline;

	private ClientLevel level;
	private BlockPos pos, partner;
	private BlockState state, partnerState;
	private final List<double[]> edges = new ArrayList<>();
	private List<AABB> boxes = List.of();
	private AABB localBounds, goal, box, faceBox;
	private Direction face;
	private float presence, progress, fade;
	private long lastFrame;
	private Vec3 cam = Vec3.ZERO;
	private double time;
	/** Maps shape-local coordinates to the world: a + b * local on each axis (slide and pop-in applied). */
	private double ax, bx, ay, by, az, bz;

	public SelectOutline() {
		super("SelectOutline", List.of("BlockOutline", "BlockOverlay"), Category.RENDER, "Replaces the block selection outline with a smooth, animated one.");
		secondColor.visibleWhen(() -> colorMode.get() == ColorMode.GRADIENT);
		color.visibleWhen(() -> colorMode.get() != ColorMode.ACCENT);
		flow.visibleWhen(() -> colorMode.get() == ColorMode.GRADIENT || colorMode.get() == ColorMode.RAINBOW);
		fillOpacity.visibleWhen(() -> style.get() != Style.OUTLINE);
		width.visibleWhen(() -> style.get() != Style.FILL);
		glow.visibleWhen(() -> style.get() != Style.FILL);
		smoothSpeed.visibleWhen(smooth::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		reset();
	}

	/** LevelRendererMixin: records whether vanilla would outline the target this frame. */
	public static void vanillaOutline(boolean allowed) {
		vanillaOutline = allowed;
	}

	/** LevelRendererMixin: true while this module draws the outline in place of vanilla's. */
	public static boolean replacesVanilla() {
		return ModuleManager.on(SelectOutline.class);
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cameraPos) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) {
			reset();
			return;
		}
		if (mc.level != level) {
			reset();
			level = mc.level;
		}
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1.0e9f, 0.1f);
		lastFrame = now;

		BlockHitResult hit = target(mc);
		if (hit != null && adopt(mc, hit)) {
			if (box != null && presence >= 0.02f && smooth.get() && box.getCenter().distanceTo(goal.getCenter()) <= SNAP_DISTANCE) {
				box = lerp(box, goal, 1 - (float) Math.exp(-smoothSpeed.get() * dt));
			} else {
				box = goal;
			}
			presence = Math.min(1, presence + dt / FADE_IN);
		} else {
			hit = null;
			presence = Math.max(0, presence - dt / FADE_OUT);
			if (presence == 0) {
				reset();
				level = mc.level;
				return;
			}
		}
		if (box == null) return;
		updateProgress(mc, dt, hit != null);
		cam = cameraPos;
		time = now % 3_600_000_000_000L / 1.0e9;
		draw();
	}

	private static BlockHitResult target(Minecraft mc) {
		if (!vanillaOutline || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.isInside()) return null;
		BlockPos at = hit.getBlockPos();
		return !mc.level.getBlockState(at).isAir() && mc.level.getWorldBorder().isWithinBounds(at) ? hit : null;
	}

	/** Takes the looked-at block's shape (rebuilt only when the block or its state changes); false for an empty shape. */
	private boolean adopt(Minecraft mc, BlockHitResult hit) {
		ClientLevel world = mc.level;
		BlockPos at = hit.getBlockPos();
		BlockPos other = partner(world, at, world.getBlockState(at));
		BlockPos ref = other != null && other.compareTo(at) < 0 ? other : at;
		BlockPos second = other == null ? null : (ref.equals(at) ? other : at);
		BlockState refState = world.getBlockState(ref);
		BlockState secondState = second == null ? null : world.getBlockState(second);
		if (!ref.equals(pos) || refState != state || secondState != partnerState) {
			Entity viewer = mc.getCameraEntity();
			CollisionContext context = viewer == null ? CollisionContext.empty() : CollisionContext.of(viewer);
			VoxelShape shape = refState.getShape(world, ref, context);
			if (second != null) shape = Shapes.or(shape, secondState.getShape(world, second, context).move(second.subtract(ref)));
			shape = shape.optimize();
			if (shape.isEmpty()) return false;
			if (!ref.equals(pos)) progress = 0;
			pos = ref;
			state = refState;
			partner = second;
			partnerState = secondState;
			edges.clear();
			shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> edges.add(new double[] {x0, y0, z0, x1, y1, z1}));
			boxes = shape.toAabbs();
			localBounds = shape.bounds();
			goal = localBounds.move(ref);
		}
		face = hit.getDirection();
		faceBox = boxOnFace(hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
		return true;
	}

	/** The other half of a door, bed or double chest, so both outline as one. */
	private static BlockPos partner(ClientLevel world, BlockPos at, BlockState state) {
		BlockPos other = null;
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? at.above() : at.below();
		} else if (state.getBlock() instanceof BedBlock) {
			other = at.relative(BedBlock.getConnectedDirection(state));
		} else if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			other = ChestBlock.getConnectedBlockPos(at, state);
		}
		return other != null && world.getBlockState(other).is(state.getBlock()) ? other : null;
	}

	/** The box of the shape that holds the looked-at point on the looked-at face. */
	private AABB boxOnFace(Vec3 local) {
		double e = 0.001;
		for (AABB b : boxes) {
			boolean onPlane = switch (face) {
				case EAST -> Math.abs(local.x - b.maxX) < e;
				case WEST -> Math.abs(local.x - b.minX) < e;
				case UP -> Math.abs(local.y - b.maxY) < e;
				case DOWN -> Math.abs(local.y - b.minY) < e;
				case SOUTH -> Math.abs(local.z - b.maxZ) < e;
				case NORTH -> Math.abs(local.z - b.minZ) < e;
			};
			if (onPlane && b.inflate(e).contains(local)) return b;
		}
		return localBounds;
	}

	private void updateProgress(Minecraft mc, float dt, boolean targeted) {
		float want = 0;
		if (targeted && miningProgress.get() && mc.gameMode != null && mc.gameMode.isDestroying()) {
			MultiPlayerGameModeAccessor mining = (MultiPlayerGameModeAccessor) mc.gameMode;
			BlockPos breaking = mining.coffeeclient$destroyBlockPos();
			if (breaking != null && (breaking.equals(pos) || breaking.equals(partner))) want = Mth.clamp(mining.coffeeclient$destroyProgress(), 0, 1);
		}
		progress += (want - progress) * (1 - (float) Math.exp(-18 * dt));
	}

	private void reset() {
		level = null;
		pos = partner = null;
		state = partnerState = null;
		edges.clear();
		boxes = List.of();
		localBounds = goal = box = faceBox = null;
		face = null;
		presence = progress = 0;
		lastFrame = 0;
	}

	// ---- drawing ----

	private void draw() {
		fade = presence * presence * (3 - 2 * presence);
		if (pulse.get()) fade *= (float) (0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 300.0));
		double pop = 0.94 + 0.06 * fade;
		double[] x = axis(box.minX, box.maxX, goal.minX, goal.maxX, pos.getX() - goal.minX, pop);
		double[] y = axis(box.minY, box.maxY, goal.minY, goal.maxY, pos.getY() - goal.minY, pop);
		double[] z = axis(box.minZ, box.maxZ, goal.minZ, goal.maxZ, pos.getZ() - goal.minZ, pop);
		ax = x[0];
		bx = x[1];
		ay = y[0];
		by = y[1];
		az = z[0];
		bz = z[1];

		if (style.get() != Style.OUTLINE) {
			VertexConsumer fill = OverlayRenderer.buffer(WorldOverlays.fillType(seeThrough.get()));
			float alpha = fillOpacity.floatValue() / 100;
			if (faceOnly.get()) {
				faceFill(fill, faceBox, face, alpha, FILL_INFLATE);
			} else {
				for (AABB b : boxes) for (Direction d : Direction.values()) faceFill(fill, b, d, alpha, FILL_INFLATE);
			}
			if (progress > 0.01f) progressFill(fill, Math.min(0.45f, alpha * 1.4f + 0.16f));
		}
		if (style.get() != Style.FILL) outline(faceOnly.get() ? faceEdges(faceBox, face) : edges);
	}

	/** One axis of the local-to-world map: the shape slides and scales with {@link #box}, popping in by {@code pop}. */
	private static double[] axis(double boxMin, double boxMax, double goalMin, double goalMax, double offset, double pop) {
		double goalSize = goalMax - goalMin;
		double scale = goalSize > 1.0e-6 ? (boxMax - boxMin) / goalSize : 1;
		double centre = (boxMin + boxMax) * 0.5;
		return new double[] {centre * (1 - pop) + (boxMin + offset * scale) * pop, scale * pop};
	}

	/** Camera-relative position of a shape-local point. */
	private Vec3 world(double lx, double ly, double lz) {
		return new Vec3(ax + bx * lx - cam.x, ay + by * ly - cam.y, az + bz * lz - cam.z);
	}

	/** Lines are cut into short pieces when their colour changes along them; each is drawn with a halo under it. */
	private void outline(List<double[]> list) {
		boolean varies = colorMode.get() != ColorMode.SOLID;
		List<double[]> pieces = new ArrayList<>(list.size() * 4);
		List<Integer> colors = new ArrayList<>(list.size() * 4);
		for (double[] e : list) {
			double length = Math.sqrt(sq(e[3] - e[0]) + sq(e[4] - e[1]) + sq(e[5] - e[2]));
			int n = varies ? Mth.clamp((int) Math.ceil(length / SEGMENT), 1, MAX_PIECES) : 1;
			for (int i = 0; i < n; i++) {
				double t0 = (double) i / n, t1 = (double) (i + 1) / n, tm = (t0 + t1) * 0.5;
				pieces.add(new double[] {Mth.lerp(t0, e[0], e[3]), Mth.lerp(t0, e[1], e[4]), Mth.lerp(t0, e[2], e[5]),
					Mth.lerp(t1, e[0], e[3]), Mth.lerp(t1, e[1], e[4]), Mth.lerp(t1, e[2], e[5])});
				colors.add(colorAt(Mth.lerp(tm, e[0], e[3]), Mth.lerp(tm, e[1], e[4]), Mth.lerp(tm, e[2], e[5])));
			}
		}
		VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough.get()));
		float w = width.floatValue();
		if (glow.get()) {
			for (int i = 0; i < pieces.size(); i++) {
				line(lines, pieces.get(i), Colors.fade(colors.get(i), 0.34f * fade), w + 3, GLOW_LIFT);
				line(lines, pieces.get(i), Colors.fade(colors.get(i), 0.13f * fade), w + 8, GLOW_LIFT);
			}
		}
		for (int i = 0; i < pieces.size(); i++) line(lines, pieces.get(i), Colors.fade(colors.get(i), fade), w, LINE_LIFT);
	}

	private void line(VertexConsumer vc, double[] p, int argb, float w, double lift) {
		Vec3 a = lift(world(p[0], p[1], p[2]), lift), b = lift(world(p[3], p[4], p[5]), lift);
		Vec3 n = b.subtract(a).normalize();
		WorldOverlays.line(vc, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, (float) n.x, (float) n.y, (float) n.z, argb, w);
	}

	/** {@code point} (camera-relative) moved {@code by} blocks toward the eye. */
	private static Vec3 lift(Vec3 point, double by) {
		double length = point.length();
		return length < 1.0e-6 ? point : point.scale(1 - by / length);
	}

	private void faceFill(VertexConsumer vc, AABB b, Direction d, float alpha, double inflate) {
		Vec3 min = world(b.minX, b.minY, b.minZ).subtract(inflate, inflate, inflate);
		Vec3 max = world(b.maxX, b.maxY, b.maxZ).add(inflate, inflate, inflate);
		Vec3 c = faceCentre(b, d);
		int argb = Colors.withAlpha(colorAt(c.x, c.y, c.z), Mth.clamp(Math.round(alpha * fade * 255), 0, 255));
		float x = (float) min.x, y = (float) min.y, z = (float) min.z, x2 = (float) max.x, y2 = (float) max.y, z2 = (float) max.z;
		switch (d) {
			case DOWN -> quad(vc, x, y, z, x2, y, z, x2, y, z2, x, y, z2, argb);
			case UP -> quad(vc, x, y2, z, x, y2, z2, x2, y2, z2, x2, y2, z, argb);
			case NORTH -> quad(vc, x, y, z, x, y2, z, x2, y2, z, x2, y, z, argb);
			case SOUTH -> quad(vc, x, y, z2, x2, y, z2, x2, y2, z2, x, y2, z2, argb);
			case WEST -> quad(vc, x, y, z, x, y, z2, x, y2, z2, x, y2, z, argb);
			case EAST -> quad(vc, x2, y, z, x2, y2, z, x2, y2, z2, x2, y, z2, argb);
		}
	}

	private static void quad(VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
		vc.addVertex(ax, ay, az).setColor(argb);
		vc.addVertex(bx, by, bz).setColor(argb);
		vc.addVertex(cx, cy, cz).setColor(argb);
		vc.addVertex(dx, dy, dz).setColor(argb);
	}

	/** Mining progress: the shape (or the looked-at face) filled up to {@link #progress}. */
	private void progressFill(VertexConsumer vc, float alpha) {
		double inflate = 0.004;
		if (faceOnly.get()) {
			AABB b = faceBox;
			AABB part = face.getAxis() == Direction.Axis.Y
				? b.deflate(b.getXsize() * (1 - progress) * 0.5, 0, b.getZsize() * (1 - progress) * 0.5)
				: new AABB(b.minX, b.minY, b.minZ, b.maxX, b.minY + b.getYsize() * progress, b.maxZ);
			faceFill(vc, part, face, alpha, inflate);
			return;
		}
		double top = localBounds.minY + localBounds.getYsize() * progress;
		for (AABB b : boxes) {
			if (b.minY >= top) continue;
			AABB part = new AABB(b.minX, b.minY, b.minZ, b.maxX, Math.min(b.maxY, top), b.maxZ);
			for (Direction d : Direction.values()) faceFill(vc, part, d, alpha, inflate);
		}
	}

	/** The colour at a shape-local point, by colour mode; its alpha is the line opacity. */
	private int colorAt(double lx, double ly, double lz) {
		boolean accent = colorMode.get() == ColorMode.ACCENT;
		int base = accent ? Colors.withAlpha(CoffeeClient.theme().accent, Colors.alpha(color.argb())) : color.argb();
		if (colorMode.get() == ColorMode.SOLID || accent) return base;
		double u = (norm(lx, localBounds.minX, localBounds.maxX) + norm(ly, localBounds.minY, localBounds.maxY) + norm(lz, localBounds.minZ, localBounds.maxZ)) / 3;
		double shift = flow.get() ? time * FLOW_SPEED : 0;
		if (colorMode.get() == ColorMode.RAINBOW) {
			double hue = u * 0.5 - shift;
			return Colors.fromHsv((float) (hue - Math.floor(hue)) * 360, 0.65f, 1, Colors.alpha(base));
		}
		double k = flow.get() ? 0.5 - 0.5 * Math.cos(2 * Math.PI * (u - shift)) : u;
		return Colors.lerp(base, secondColor.argb(), (float) k);
	}

	private static double norm(double v, double min, double max) {
		return max - min < 1.0e-6 ? 0.5 : (v - min) / (max - min);
	}

	private static Vec3 faceCentre(AABB b, Direction d) {
		Vec3 c = b.getCenter();
		return switch (d) {
			case EAST -> new Vec3(b.maxX, c.y, c.z);
			case WEST -> new Vec3(b.minX, c.y, c.z);
			case UP -> new Vec3(c.x, b.maxY, c.z);
			case DOWN -> new Vec3(c.x, b.minY, c.z);
			case SOUTH -> new Vec3(c.x, c.y, b.maxZ);
			case NORTH -> new Vec3(c.x, c.y, b.minZ);
		};
	}

	/** The four edges of one face of {@code b}. */
	private static List<double[]> faceEdges(AABB b, Direction d) {
		double[][] c = switch (d.getAxis()) {
			case X -> {
				double x = d == Direction.EAST ? b.maxX : b.minX;
				yield new double[][] {{x, b.minY, b.minZ}, {x, b.maxY, b.minZ}, {x, b.maxY, b.maxZ}, {x, b.minY, b.maxZ}};
			}
			case Y -> {
				double y = d == Direction.UP ? b.maxY : b.minY;
				yield new double[][] {{b.minX, y, b.minZ}, {b.maxX, y, b.minZ}, {b.maxX, y, b.maxZ}, {b.minX, y, b.maxZ}};
			}
			case Z -> {
				double z = d == Direction.SOUTH ? b.maxZ : b.minZ;
				yield new double[][] {{b.minX, b.minY, z}, {b.maxX, b.minY, z}, {b.maxX, b.maxY, z}, {b.minX, b.maxY, z}};
			}
		};
		List<double[]> out = new ArrayList<>(4);
		for (int i = 0; i < 4; i++) {
			double[] p = c[i], q = c[(i + 1) % 4];
			out.add(new double[] {p[0], p[1], p[2], q[0], q[1], q[2]});
		}
		return out;
	}

	private static AABB lerp(AABB from, AABB to, float k) {
		return new AABB(Mth.lerp(k, from.minX, to.minX), Mth.lerp(k, from.minY, to.minY), Mth.lerp(k, from.minZ, to.minZ),
			Mth.lerp(k, from.maxX, to.maxX), Mth.lerp(k, from.maxY, to.maxY), Mth.lerp(k, from.maxZ, to.maxZ));
	}

	private static double sq(double v) {
		return v * v;
	}
}
