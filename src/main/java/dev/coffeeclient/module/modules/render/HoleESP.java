package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights 1x1, 2x1 and 2x2 holes around you, coloured by their weakest wall or floor: all
 * bedrock, blast-proof (obsidian and the like) or ordinary blocks. The scan only runs again after
 * you move a block or a block near you changes, so standing in a hole costs nothing.
 */
public final class HoleESP extends Module {
	public final NumberSetting radius = group(new NumberSetting("Radius", "Horizontal scan radius", 16, 4, 64, 2), "General");
	public final NumberSetting vertical = group(new NumberSetting("Vertical range", "Blocks above and below your feet to search", 4, 0, 32, 1), "General");
	public final NumberSetting depth = group(new NumberSetting("Depth", "Open blocks a hole needs above its floor", 2, 1, 5, 1), "General");
	public final BooleanSetting singles = group(new BooleanSetting("Single", "1x1 holes", true), "General");
	public final BooleanSetting doubles = group(new BooleanSetting("Double", "2x1 holes", true), "General");
	public final BooleanSetting quads = group(new BooleanSetting("Quad", "2x2 holes", false), "General");
	public final BooleanSetting showUnsafe = group(new BooleanSetting("Unsafe holes", "Also holes with walls an explosion can break", true), "General");
	public final NumberSetting refresh = group(new NumberSetting("Refresh (ticks)", "Least time between rescans", 10, 2, 40, 2), "General");
	public final ColorSetting bedrock = group(new ColorSetting("Bedrock color", "Walls and floor all bedrock", 0xFF00FF66), "Render");
	public final ColorSetting safe = group(new ColorSetting("Safe color", "Walls and floor blast-proof (obsidian or mixed)", 0xFF00C8FF), "Render");
	public final ColorSetting unsafe = group(new ColorSetting("Unsafe color", "Some wall or floor an explosion can break", 0xFFFF3333), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each hole is drawn", WorldOverlays.Shape.Both), "Render");
	public final NumberSetting height = group(new NumberSetting("Height", "Height of the box drawn in the hole", 0.2, 0.05, 2, 0.05), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 60, 0, 255, 5), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Render");
	public final BooleanSetting fade = group(new BooleanSetting("Range fade", "Fade holes out toward the edge of the radius", true), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", false), "Render");

	private List<Holes.Hole> holes = List.of();
	private int ticks, sinceScan;
	private BlockPos scannedAt;
	private boolean dirty = true;

	public HoleESP() {
		super("HoleESP", Category.RENDER, "Highlights 1x1, 2x1 and 2x2 holes in the terrain.");
		for (var s : List.of(radius, vertical, depth, singles, doubles, quads, showUnsafe)) s.onChange(v -> dirty = true);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.BlockUpdate.class, e -> {
			if (scannedAt != null && e.pos().distManhattan(scannedAt) <= radius.intValue() + vertical.intValue() + 2) dirty = true;
		});
		listen(Events.ChunkLoad.class, e -> dirty = true);
	}

	@Override
	protected void onEnable() {
		dirty = true;
	}

	@Override
	protected void onDisable() {
		holes = List.of();
		scannedAt = null;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		sinceScan++;
		BlockPos origin = mc.player.blockPosition();
		if (!dirty && origin.equals(scannedAt) || sinceScan < refresh.intValue()) return;
		holes = scan(mc.level, origin);
		scannedAt = origin;
		dirty = false;
		sinceScan = 0;
	}

	private List<Holes.Hole> scan(ClientLevel level, BlockPos origin) {
		Holes.Blocks blocks = Footprint.blocks(level);
		int r = radius.intValue(), v = vertical.intValue(), d = depth.intValue();
		List<Holes.Hole> found = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				for (int dy = -v; dy <= v; dy++) {
					Holes.Hole h = Holes.find(blocks, origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz, d);
					if (h != null && wanted(h)) found.add(h);
				}
			}
		}
		return found;
	}

	private boolean wanted(Holes.Hole h) {
		if (h.safety() == Holes.Safety.UNSAFE && !showUnsafe.get()) return false;
		return switch (h.type()) {
			case SINGLE -> singles.get();
			case DOUBLE_X, DOUBLE_Z -> doubles.get();
			case QUAD -> quads.get();
		};
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		List<Holes.Hole> list = holes;
		if (list.isEmpty() || mc().player == null) return;
		double h = height.get(), r = radius.get();
		Vec3 eye = mc().player.position();
		List<AABB> boxes = new ArrayList<>(list.size());
		int[] colors = new int[list.size()];
		int fill = opacity.intValue();
		for (Holes.Hole hole : list) {
			ColorSetting c = switch (hole.safety()) {
				case BEDROCK -> bedrock;
				case MIXED -> safe;
				case UNSAFE -> unsafe;
			};
			colors[boxes.size()] = c.argb() & 0xFFFFFF;
			boxes.add(new AABB(hole.x(), hole.y(), hole.z(), hole.x() + hole.sizeX(), hole.y() + h, hole.z() + hole.sizeZ()));
		}
		if (!fade.get()) {
			EspRender.boxes(boxes, colors, shape.get(), seeThrough.get(), width.floatValue(), fill, cameraPos);
			return;
		}
		// Fading needs a fill alpha per box: draw in near / middle / far bands.
		for (int band = 0; band < 3; band++) {
			List<AABB> part = new ArrayList<>();
			List<Integer> partColors = new ArrayList<>();
			for (int i = 0; i < boxes.size(); i++) {
				double dist = Math.sqrt(boxes.get(i).getCenter().distanceToSqr(eye.x, boxes.get(i).getCenter().y, eye.z)) / r;
				if (Math.min(2, (int) (dist * 3)) != band) continue;
				part.add(boxes.get(i));
				partColors.add(colors[i]);
			}
			if (part.isEmpty()) continue;
			EspRender.boxes(part, partColors.stream().mapToInt(Integer::intValue).toArray(), shape.get(), seeThrough.get(),
				width.floatValue(), fill * (3 - band) / 3, cameraPos);
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
