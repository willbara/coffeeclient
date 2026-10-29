package dev.coffeeclient.module.modules.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Sounds;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds ender-pearl stasis chambers: a soul sand block under a column of bubble columns, the water
 * shaft that holds a pearl in place. Soul sand comes from the shared {@link BlockSearch}; the
 * column above each one is checked on every refresh.
 */
public final class StasisFinder extends Module {
	private static final int COLUMN = 4;
	private static final Set<Block> BASE = Set.of(Blocks.SOUL_SAND);

	public final NumberSetting radius = group(new NumberSetting("Radius", "Scan radius in blocks", 96, 16, 256, 8), "General");
	public final NumberSetting limit = group(new NumberSetting("Limit", "Most chambers shown; the nearest are kept", 5, 1, 50, 1), "General");
	public final NumberSetting refresh = group(new NumberSetting("Refresh (ticks)", "How often the chambers are checked", 20, 5, 100, 5), "General");
	public final BooleanSetting chatAlerts = group(new BooleanSetting("Chat alerts", "Say so when a new chamber is found", true), "Alerts");
	public final BooleanSetting soundAlerts = group(new BooleanSetting("Sound alerts", "Play a chime when a new chamber is found", true), "Alerts");
	public final ColorSetting color = group(new ColorSetting("Color", "Chamber colour", 0xFF9932CC), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each chamber is drawn", WorldOverlays.Shape.Both), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 48, 0, 255, 4), "Render");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Draw tracers to chambers", false), "Render");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth)
		.visibleWhen(tracers::get), "Render");

	private BlockSearch.Handle search;
	private List<BlockPos> chambers = List.of();
	private final Set<Long> alerted = new HashSet<>();
	private int ticks;

	public StasisFinder() {
		super("StasisFinder", List.of("Stasis detector", "Pearl stasis"), Category.WORLD, "Finds ender-pearl stasis chambers (soul sand under a bubble column).");
		opacity.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Outline);
		listen(Events.WorldChange.class, e -> {
			alerted.clear();
			chambers = List.of();
		});
	}

	@Override
	protected void onDisable() {
		if (search != null) search.release();
		search = null;
		chambers = List.of();
		alerted.clear();
	}

	@Override
	public String infoString() {
		return chambers.isEmpty() ? null : Integer.toString(chambers.size());
	}

	@Override
	public void onTick() {
		var level = mc().level;
		if (level == null || mc().player == null || ++ticks % refresh.intValue() != 0) return;
		// Acquired here, not in onEnable: the config switches modules on at startup without it.
		if (search == null) search = BlockSearch.acquire(BASE, state -> BASE.contains(state.getBlock()));
		BlockPos origin = mc().player.blockPosition();
		List<BlockPos> bases = search.collect(origin, radius.intValue(), Integer.MAX_VALUE);
		bases.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos base : bases) {
			if (found.size() >= limit.intValue()) break;
			if (bubbleColumn(base)) found.add(base);
		}
		for (BlockPos base : found) {
			if (!alerted.add(base.asLong())) continue;
			if (chatAlerts.get()) info("Stasis chamber at " + Coords.format(base.getX()) + ", " + Coords.format(base.getY()) + ", " + Coords.format(base.getZ()));
			if (soundAlerts.get()) Sounds.play("minecraft:block.note_block.chime", 1f, 1f);
		}
		chambers = found;
	}

	private boolean bubbleColumn(BlockPos base) {
		for (int dy = 1; dy <= COLUMN; dy++) {
			if (!mc().level.getBlockState(base.above(dy)).is(Blocks.BUBBLE_COLUMN)) return false;
		}
		return true;
	}

	private static Vec3 center(BlockPos base) {
		return new Vec3(base.getX() + 0.5, base.getY() + 0.5 + (COLUMN + 1) / 2.0, base.getZ() + 0.5);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		List<BlockPos> list = chambers;
		if (list.isEmpty()) return;
		int rgb = color.argb() & 0xFFFFFF;
		List<AABB> boxes = new ArrayList<>();
		int[] colors = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			BlockPos b = list.get(i);
			boxes.add(new AABB(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1 + COLUMN, b.getZ() + 1));
			colors[i] = rgb;
		}
		EspRender.boxes(boxes, colors, shape.get(), true, 1.5f, opacity.intValue(), cameraPos);
		if (!tracers.get() || tracerStyle.get() != Tracers.Style.Smooth) return;
		VertexConsumer lines = EspRender.tracerBuffer();
		for (BlockPos b : list) {
			Vec3 c = center(b);
			EspRender.tracer(lines, c.x, c.y, c.z, 0x80000000 | rgb, 1f, cameraPos);
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		int argb = 0xFF000000 | (color.argb() & 0xFFFFFF);
		for (BlockPos b : chambers) {
			Vec3 c = center(b);
			if (tracers.get() && tracerStyle.get() == Tracers.Style.Classic) Tracers.pixel(ui, projector, c.x, c.y, c.z, 0x80000000 | (argb & 0xFFFFFF), 1);
			float[] point = projector.project(c.x, b.getY() + COLUMN + 1.3, c.z);
			if (!projector.onScreen(point, 30)) continue;
			Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line("Stasis [" + Math.round(mc().player.position().distanceTo(c)) + "]", argb)),
				1f, true, ui.theme.panel, -1, 0, null);
		}
	}
}
