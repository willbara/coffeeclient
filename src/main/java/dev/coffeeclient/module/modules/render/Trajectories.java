package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.friend.FriendTrait;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.ItemIds;
import dev.coffeeclient.util.ProjectileSimulator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws where held throwables and drawn bows will land, for you and (optionally) other players and
 * projectiles already in flight. The
 * physics live in {@link ProjectileSimulator}.
 */
public final class Trajectories extends Module {
	/** Crossbow multishot spreads its side arrows by about 10 degrees. */
	private static final double MULTISHOT_OFFSET = Math.toRadians(10);

	public final IdListSetting items = group(new IdListSetting("Items", "Items to show trajectories for",
		throwableIds(), Trajectories::throwableIds, false), "General");
	public final BooleanSetting otherPlayers = group(new BooleanSetting("Other players", "Also show the paths of other players' held items", true), "General");
	public final BooleanSetting firedProjectiles = group(new BooleanSetting("Fired projectiles", "Show the paths of projectiles already flying", false), "General");
	public final BooleanSetting ignoreWitherSkulls = group(new BooleanSetting("Ignore wither skulls", "Skip fired wither skulls", false), "General");
	public final BooleanSetting accurate = group(new BooleanSetting("Accurate", "Add the shooter's own movement to the launch", false), "General");
	public final NumberSetting simulationSteps = group(new NumberSetting("Simulation steps", "Ticks to simulate each path (0 = no limit)", 500, 0, 5000, 50), "General");

	public final NumberSetting ignoreFirstTicks = group(new NumberSetting("Ignore first ticks", "Skip drawing your own path's first ticks so the rest is clearer", 3, 0, 20, 1), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How hit markers are drawn", WorldOverlays.Shape.Both), "Render");
	public final ColorSetting lineColor = group(new ColorSetting("Line color", "Path and outline colour when Color by owner is off", 0xFFFF9600), "Render");
	public final ColorSetting sideColor = group(new ColorSetting("Side color", "Fill colour of hit markers (alpha used)", 0x23FF9600), "Render");
	public final BooleanSetting colorByOwner = group(new BooleanSetting("Color by owner", "Colour each path by who fired it; off uses Line color", false), "Owner colors");
	public final ColorSetting selfColor = group(new ColorSetting("Your color", "Paths you fire", 0xFF55FF55), "Owner colors");
	public final ColorSetting friendColor = group(new ColorSetting("Friend color", "Paths friends fire; a friend's own colour wins", 0xFF55FFFF), "Owner colors");
	public final ColorSetting playerColor = group(new ColorSetting("Other player color", "Paths other players fire", 0xFFFF5555), "Owner colors");
	public final ColorSetting mobColor = group(new ColorSetting("Mob color", "Paths mobs, dispensers or unknown owners fire", 0xFFFFAA00), "Owner colors");
	public final NumberSetting width = group(new NumberSetting("Line width", "Path thickness", 1.5, 0.5, 5, 0.5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Draw paths through terrain", false), "Render");
	public final BooleanSetting positionBoxes = group(new BooleanSetting("Position boxes", "Mark the projectile's position at each tick", false), "Render");
	public final NumberSetting positionBoxSize = group(new NumberSetting("Position box size", "Half-size of the per-tick markers", 0.02, 0.01, 0.1, 0.01), "Render");
	public final ColorSetting positionLineColor = group(new ColorSetting("Position line color", "Outline colour of the per-tick markers when Color by owner is off", 0xFFFF9600), "Render");
	public final ColorSetting positionSideColor = group(new ColorSetting("Position side color", "Fill colour of the per-tick markers when Color by owner is off", 0x23FF9600), "Render");

	private final ProjectileSimulator simulator = new ProjectileSimulator();

	/** One simulated path: its points, where it struck a block, and the entities it hits. */
	private static final class Path {
		final List<Vec3> points = new ArrayList<>();
		BlockHitResult blockHit;
		final List<Entity> entities = new ArrayList<>();
		int start;
		/** RGB of the line and markers, resolved from the owner when the path is built. */
		int color;
	}

	public Trajectories() {
		super("Trajectories", Category.RENDER, "Predicts where throwables, arrows and tridents will land.");
		items.labels(ItemIds::name);
		items.icons(ItemIds::stack);
		ignoreWitherSkulls.visibleWhen(firedProjectiles::get);
		for (Setting<?> s : List.of(positionBoxSize, positionLineColor, positionSideColor)) s.visibleWhen(positionBoxes::get);
		for (Setting<?> s : List.of(selfColor, friendColor, playerColor, mobColor)) s.visibleWhen(colorByOwner::get);
		sideColor.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Outline);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return items;
	}

	private static boolean throwable(Item item) {
		return item instanceof ProjectileWeaponItem || item instanceof FishingRodItem || item instanceof TridentItem
			|| item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem
			|| item instanceof ExperienceBottleItem || item instanceof ThrowablePotionItem || item instanceof WindChargeItem;
	}

	private static List<String> throwableIds() {
		List<String> ids = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) if (throwable(item)) ids.add(ItemIds.id(item));
		return ids;
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		float tickDelta = mc.level.tickRateManager().isFrozen() ? 1 : mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<Path> paths = new ArrayList<>();

		for (Player player : mc.level.players()) {
			if (player != mc.player && !otherPlayers.get()) continue;
			ItemStack stack = player.getMainHandItem();
			if (!selected(stack)) {
				stack = player.getOffhandItem();
				if (!selected(stack)) continue;
			}
			if (!simulator.set(player, stack, 0, accurate.get(), tickDelta)) continue;
			paths.add(simulate(player, player == mc.player));
			if (stack.getItem() instanceof CrossbowItem && EnchantmentIds.level(stack, Enchantments.MULTISHOT) > 0) {
				if (simulator.set(player, stack, MULTISHOT_OFFSET, accurate.get(), tickDelta)) paths.add(simulate(player, player == mc.player));
				if (simulator.set(player, stack, -MULTISHOT_OFFSET, accurate.get(), tickDelta)) paths.add(simulate(player, player == mc.player));
			}
		}

		if (firedProjectiles.get()) {
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (!(entity instanceof Projectile projectile)) continue;
				if (ignoreWitherSkulls.get() && entity instanceof WitherSkull) continue;
				// A loyal trident on its way back has no ballistic path.
				if (entity instanceof ThrownTrident trident && trident.isNoPhysics()) continue;
				if (!simulator.set(projectile)) continue;
				Path path = simulate(projectile.getOwner(), false);
				// Start from where the projectile is drawn this frame, not its last tick position.
				path.points.addFirst(projectile.getPosition(tickDelta));
				paths.add(path);
			}
		}
		if (!paths.isEmpty()) draw(paths, cam);
	}

	private boolean selected(ItemStack stack) {
		return !stack.isEmpty() && items.contains(ItemIds.id(stack.getItem()));
	}

	private Path simulate(Entity owner, boolean own) {
		Path path = new Path();
		path.color = ownerColor(owner);
		path.points.add(new Vec3(simulator.pos.x, simulator.pos.y, simulator.pos.z));
		int limit = simulationSteps.intValue() > 0 ? simulationSteps.intValue() : Integer.MAX_VALUE;
		for (int i = 0; i < limit; i++) {
			ProjectileSimulator.Step step = simulator.tick();
			for (int h = 0; h < step.hits().size(); h++) {
				HitResult hit = step.hits().get(h);
				if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
					path.blockHit = block;
					path.points.add(block.getLocation());
				} else if (hit instanceof EntityHitResult entityHit) {
					path.entities.add(entityHit.getEntity());
					if (step.stop() && h == step.hits().size() - 1) path.points.add(entityHit.getLocation());
				}
			}
			if (step.stop()) break;
			path.points.add(new Vec3(simulator.pos.x, simulator.pos.y, simulator.pos.z));
		}
		if (own) path.start = path.points.size() <= ignoreFirstTicks.intValue() ? 0 : ignoreFirstTicks.intValue();
		return path;
	}

	/** One path colour (RGB): the fixed Line color, or the owner's category colour. */
	private int ownerColor(Entity owner) {
		if (!colorByOwner.get()) return lineColor.argb() & 0xFFFFFF;
		if (owner == mc().player) return selfColor.argb() & 0xFFFFFF;
		if (owner instanceof Player player) {
			String name = player.getName().getString();
			return Friends.is(name, FriendTrait.HIGHLIGHTED)
				? Friends.color(name, friendColor.argb()) & 0xFFFFFF
				: playerColor.argb() & 0xFFFFFF;
		}
		return mobColor.argb() & 0xFFFFFF;
	}

	private void draw(List<Path> paths, Vec3 cam) {
		float w = width.floatValue();
		boolean see = seeThrough.get();

		VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(see));
		for (Path path : paths) {
			int line = 0xFF000000 | path.color;
			for (int i = path.start + 1; i < path.points.size(); i++) {
				Vec3 a = path.points.get(i - 1), b = path.points.get(i);
				Vec3 d = b.subtract(a);
				double len = d.length();
				if (len < 1e-6) continue;
				WorldOverlays.line(lines, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z),
					(float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z),
					(float) (d.x / len), (float) (d.y / len), (float) (d.z / len), line, w);
			}
		}

		// Hit markers: a half-block plate on the struck face, and the hitboxes of entities in the way.
		List<AABB> boxes = new ArrayList<>();
		List<Integer> boxColors = new ArrayList<>();
		for (Path path : paths) {
			if (path.blockHit != null) {
				boxes.add(plate(path.blockHit));
				boxColors.add(path.color);
			}
			float pt = mc().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			for (Entity entity : path.entities) {
				boxes.add(EspRender.entityBox(entity, pt));
				boxColors.add(path.color);
			}
		}
		int[] colors = new int[boxColors.size()];
		for (int i = 0; i < colors.length; i++) colors[i] = boxColors.get(i);
		EspRender.boxes(boxes, colors, shape.get(), see, w, sideColor.argb() >>> 24, cam);

		if (positionBoxes.get()) {
			double s = positionBoxSize.get();
			boolean byOwner = colorByOwner.get();
			int fallbackColor = positionLineColor.argb() & 0xFFFFFF;
			List<AABB> markers = new ArrayList<>();
			List<Integer> markerColorList = new ArrayList<>();
			for (Path path : paths) {
				int markerColor = byOwner ? path.color : fallbackColor;
				for (int i = Math.max(1, path.start); i < path.points.size(); i++) {
					Vec3 p = path.points.get(i);
					markers.add(new AABB(p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s));
					markerColorList.add(markerColor);
				}
			}
			int[] markerColors = new int[markerColorList.size()];
			for (int i = 0; i < markerColors.length; i++) markerColors[i] = markerColorList.get(i);
			// Outline and fill take different colours here, so draw them as two passes.
			EspRender.boxes(markers, markerColors, WorldOverlays.Shape.Outline, see, 1f, 0, cam);
			if (!byOwner) java.util.Arrays.fill(markerColors, positionSideColor.argb() & 0xFFFFFF);
			EspRender.boxes(markers, markerColors, WorldOverlays.Shape.Filled, see, 1f, positionSideColor.argb() >>> 24, cam);
		}
	}

	/** A half-block square lying on the face that was hit, centred on the hit point. */
	private static AABB plate(BlockHitResult hit) {
		Vec3 at = hit.getLocation();
		double t = 0.01;
		Direction.Axis axis = hit.getDirection().getAxis();
		double hx = axis == Direction.Axis.X ? t : 0.25, hy = axis == Direction.Axis.Y ? t : 0.25, hz = axis == Direction.Axis.Z ? t : 0.25;
		return new AABB(at.x - hx, at.y - hy, at.z - hz, at.x + hx, at.y + hy, at.z + hz);
	}
}
