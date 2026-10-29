package dev.coffeeclient.module.modules.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MiningManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Click a block and it is broken by packets while you are free to look and act elsewhere: the break
 * is started once, its progress counted the way the server counts it, and finished as soon as the
 * server will accept it (Finish at 70% ends breaks early). The tool can be held throughout, swapped
 * to only for the finish, or left alone.
 */
public final class PacketMine extends Module {
	public enum Swap { None, Hold, Silent }

	public final NumberSetting range = setting(new NumberSetting("Range", "Abort the break beyond this distance", 5, 2, 6, 0.5));
	public final NumberSetting finishAt = setting(new NumberSetting("Finish at", "Progress at which the finish is sent (the server accepts 70% and up)", 1, MiningManager.SERVER_FINISH, 1, 0.05)
		.format(v -> Math.round(v * 100) + "%"));
	public final EnumSetting<Swap> swap = setting(new EnumSetting<>("Tool swap", "None: mine with what you hold. Hold: switch to the best tool. Silent: switch only for the finish", Swap.Silent));
	public final BooleanSetting render = setting(new BooleanSetting("Render", "A box that grows with the break", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Box colour", 0xFFFF7755));

	private final MiningManager mining = new MiningManager();

	public PacketMine() {
		super("PacketMine", List.of("SpeedMine", "Packet Mine"), Category.PLAYER, "Breaks the block you click by packets, finishing as early as the server allows.");
		color.visibleWhen(render::get);
	}

	@Override
	protected void onDisable() {
		mining.abort();
	}

	/** PacketMine is on and breaking a block. */
	public static boolean mining() {
		PacketMine m = ModuleManager.ifOn(PacketMine.class);
		return m != null && m.mining.target() != null;
	}

	/**
	 * MultiPlayerGameModeMixin: a click on a block while you hold attack starts (or keeps) the packet
	 * break instead of vanilla's. True when vanilla should stand aside.
	 */
	public static boolean takeOver(BlockPos pos, Direction face) {
		PacketMine m = ModuleManager.ifOn(PacketMine.class);
		if (m == null || !mc().options.keyAttack.isDown() || mc().player == null || mc().player.isCreative()) return false;
		if (!pos.equals(m.mining.target())) {
			m.mining.abort();
			if (m.swap.get() == Swap.Hold) Inv.swap(Inv.bestTool(mc().level.getBlockState(pos)), false);
			m.mining.start(pos, face);
		}
		return true;
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		BlockPos target = mining.target();
		if (p == null || target == null) return;
		BlockState state = p.level().getBlockState(target);
		if (state.isAir()) {
			mining.clear();
			return;
		}
		if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > range.get() * range.get()) {
			mining.abort();
			return;
		}
		int tool = swap.get() == Swap.None ? p.getInventory().getSelectedSlot() : Inv.bestTool(state);
		if (mining.tick(p, tool, finishAt.get())) mining.finish(swap.get() == Swap.Silent ? tool : -1, swap.get() == Swap.Silent);
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		BlockPos target = mining.target();
		if (!render.get() || target == null) return;
		double half = 0.5 * Math.min(1, mining.progress() / finishAt.get());
		Vec3 c = Vec3.atCenterOf(target);
		int rgb = color.argb() & 0xFFFFFF;
		EspRender.boxes(List.of(new AABB(target)), new int[]{rgb}, WorldOverlays.Shape.Outline, true, 1.5f, 0, cam);
		EspRender.boxes(List.of(new AABB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half)), new int[]{rgb},
			WorldOverlays.Shape.Filled, true, 1, 90, cam);
	}

	@Override
	public String infoString() {
		return mining.target() == null ? null : String.format(Locale.ROOT, "%.0f%%", Math.min(100, mining.progress() * 100));
	}
}
