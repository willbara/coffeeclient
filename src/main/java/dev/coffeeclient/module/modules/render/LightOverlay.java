package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks blocks where hostile mobs can spawn (block light 0 on a solid top face). Red = can spawn any
 * time, yellow = only at night (open to the sky). Rescanned every few ticks, drawn as HUD markers.
 */
public final class LightOverlay extends Module {
	public final NumberSetting radius = setting(new NumberSetting("Radius", "Horizontal scan radius", 16, 4, 32, 2));
	public final NumberSetting height = setting(new NumberSetting("Height", "Blocks above/below you to scan", 6, 2, 16, 1));
	public final BooleanSetting showNight = setting(new BooleanSetting("Night-only spots", "Also mark spots that spawn only at night", true));
	public final NumberSetting markerSize = setting(new NumberSetting("Marker size", "Cross size", 5, 3, 9, 1));
	public final NumberSetting refresh = setting(new NumberSetting("Refresh (ticks)", "How often to rescan", 10, 2, 40, 2));
	public final ColorSetting alwaysColor = setting(new ColorSetting("Always color", "Spots dark enough at any time", 0xFFE0584F));
	public final ColorSetting nightColor = setting(new ColorSetting("Night color", "Spots that spawn only at night", 0xFFF0C040));

	private record Spot(int x, int y, int z, boolean always) {
	}

	private List<Spot> spots = List.of();
	private int ticks;

	public LightOverlay() {
		super("LightOverlay", Category.RENDER, "Highlights blocks where mobs can spawn.");
	}

	@Override
	protected void onDisable() {
		spots = List.of();
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.level == null || mc.player == null || ++ticks % refresh.intValue() != 0) return;
		var level = mc.level;
		BlockPos origin = mc.player.blockPosition();
		int r = radius.intValue(), h = height.intValue();
		List<Spot> found = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				for (int dy = -h; dy <= h; dy++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState state = level.getBlockState(pos);
					if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) continue;
					below.set(pos.getX(), pos.getY() - 1, pos.getZ());
					BlockState ground = level.getBlockState(below);
					if (ground.is(Blocks.BEDROCK) || ground.is(Blocks.BARRIER) || !ground.isFaceSturdy(level, below, Direction.UP)) continue;
					if (level.getBrightness(LightLayer.BLOCK, pos) > 0) continue;
					boolean always = level.getBrightness(LightLayer.SKY, pos) == 0;
					if (!always && !showNight.get()) continue;
					found.add(new Spot(pos.getX(), pos.getY(), pos.getZ(), always));
					if (found.size() >= 3000) break;
				}
			}
		}
		spots = found;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		int base = markerSize.intValue();
		for (Spot s : spots) {
			float[] p = projector.project(s.x() + 0.5, s.y() + 0.02, s.z() + 0.5);
			if (!projector.onScreen(p, 4)) continue;
			double d = projector.distance(s.x() + 0.5, s.y(), s.z() + 0.5);
			int size = (int) Math.max(3, Math.min(base * 2, base * 6 / Math.max(1, d)));
			int c = Colors.withAlpha(s.always() ? alwaysColor.argb() : nightColor.argb(), 0xD0);
			Draw.cross(ui.g, Math.round(p[0]) - size / 2, Math.round(p[1]) - size / 2, size, c);
		}
	}
}
