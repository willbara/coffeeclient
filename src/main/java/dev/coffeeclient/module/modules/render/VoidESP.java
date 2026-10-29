package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/** Renders holes in bedrock layers that lead to the void. */
public final class VoidESP extends Module {
	public final BooleanSetting airOnly = group(new BooleanSetting("Air only", "Checks bedrock only for air blocks", false), "General");
	public final NumberSetting radius = group(new NumberSetting("Horizontal radius", "Horizontal radius to search", 64, 0, 256, 4), "General");
	public final NumberSetting holeHeight = group(new NumberSetting("Hole height", "Minimum hole height", 1, 1, 5, 1), "General");
	public final BooleanSetting netherRoof = group(new BooleanSetting("Nether roof", "Check for holes in the nether roof", true), "General");
	public final NumberSetting refresh = group(new NumberSetting("Refresh (ticks)", "How often to rescan", 20, 2, 60, 2), "General");
	public final ColorSetting color = group(new ColorSetting("Color", "Hole colour", 0xFFE119FF), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each hole is drawn", WorldOverlays.Shape.Both), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 60, 0, 255, 5), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", true), "Render");

	private record Spot(int x, int y, int z, boolean nether) {
	}

	private List<Spot> spots = List.of();
	private int ticks;

	public VoidESP() {
		super("VoidESP", Category.RENDER, "Renders holes in bedrock layers that lead to the void.");
	}

	@Override
	protected void onDisable() {
		spots = List.of();
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null || ++ticks % refresh.intValue() != 0) return;
		if (mc.level.dimension() == net.minecraft.world.level.Level.END) return;

		int px = mc.player.blockPosition().getX();
		int pz = mc.player.blockPosition().getZ();
		int r = radius.intValue();
		int minY = mc.level.getMinY();
		List<Spot> found = new ArrayList<>();
		for (int x = px - r; x <= px + r; x++) {
			for (int z = pz - r; z <= pz + r; z++) {
				if (isHole(mc, x, minY, z, false)) found.add(new Spot(x, minY, z, false));
				if (netherRoof.get() && mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
					if (isHole(mc, x, 127, z, true)) found.add(new Spot(x, 127, z, true));
				}
			}
		}
		spots = found;
	}

	private boolean isBlockWrong(Minecraft mc, int x, int y, int z) {
		var chunk = mc.level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
		if (chunk == null) return true;
		Block block = chunk.getBlockState(new BlockPos(x, y, z)).getBlock();
		if (airOnly.get()) return block != Blocks.AIR;
		return block == Blocks.BEDROCK;
	}

	private boolean isHole(Minecraft mc, int x, int baseY, int z, boolean nether) {
		int minY = mc.level.getMinY();
		for (int i = 0; i < holeHeight.intValue(); i++) {
			int y = nether ? 127 - i : minY + i;
			if (isBlockWrong(mc, x, y, z)) return false;
		}
		return true;
	}

	/** One block-sized box per hole column, at the bedrock layer it goes through. */
	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		List<Spot> list = spots;
		if (list.isEmpty()) return;
		int h = holeHeight.intValue();
		List<AABB> boxes = new ArrayList<>(list.size());
		int[] colors = new int[list.size()];
		int rgb = color.argb() & 0xFFFFFF;
		for (Spot s : list) {
			colors[boxes.size()] = rgb;
			// Floor holes run up from the bottom; roof holes run down from y 127.
			int y0 = s.nether() ? s.y() - h + 1 : s.y();
			boxes.add(new AABB(s.x(), y0, s.z(), s.x() + 1, y0 + h, s.z() + 1));
		}
		EspRender.boxes(boxes, colors, shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cameraPos);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
