package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.ProjectileSimulator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A HUD panel for your own ender pearl in flight: how long until it lands, where, and anything
 * nasty waiting there (lava, fire, cactus, magma, powder snow, the void).
 */
public final class PearlInfo extends Module {
	private static final int MAX_TICKS = 600;

	private final ProjectileSimulator sim = new ProjectileSimulator();
	private List<String[]> rows = List.of();

	public PearlInfo() {
		super("PearlInfo", Category.MISC, "ETA and landing hazards for your ender pearl in flight.");
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "pearl_info", "Pearl info", () -> rows, (w, h) -> new int[] {w / 2 + 20, h / 2 + 20})
			.when(() -> !rows.isEmpty()));
	}

	@Override
	protected void onDisable() {
		rows = List.of();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		rows = List.of();
		if (player == null || level == null) return;
		ThrownEnderpearl pearl = null;
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof ThrownEnderpearl p && p.getOwner() == player && (pearl == null || p.tickCount < pearl.tickCount)) pearl = p;
		}
		if (pearl == null || !sim.set(pearl)) return;
		int ticks = 0;
		while (ticks < MAX_TICKS && !sim.tick().stop()) ticks++;
		Vec3 land = new Vec3(sim.pos.x, sim.pos.y, sim.pos.z);
		List<String[]> out = new ArrayList<>();
		out.add(ModuleHudElement.row("ETA", ticks >= MAX_TICKS ? "never" : String.format(Locale.ROOT, "%.1fs", (ticks + 1) / 20.0)));
		out.add(ModuleHudElement.row("Lands", Coords.xyz(land.x, land.y, land.z)));
		out.add(ModuleHudElement.row("Distance", Math.round(land.distanceTo(player.position())) + "m"));
		String hazard = hazard(level, land);
		if (hazard != null) out.add(ModuleHudElement.row("Danger", hazard));
		rows = out;
	}

	/** The worst thing at or just under the landing spot, or null. */
	private static String hazard(ClientLevel level, Vec3 land) {
		if (land.y < level.getMinY()) return "void";
		BlockPos at = BlockPos.containing(land);
		for (BlockPos p : List.of(at, at.below())) {
			BlockState s = level.getBlockState(p);
			if (s.getFluidState().is(FluidTags.LAVA)) return "lava";
			if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) return "fire";
			if (s.is(Blocks.CACTUS)) return "cactus";
			if (s.is(Blocks.MAGMA_BLOCK)) return "magma";
			if (s.is(Blocks.POWDER_SNOW)) return "powder snow";
			if (s.is(Blocks.SWEET_BERRY_BUSH)) return "berry bush";
		}
		// Nothing solid within 16 blocks under it: a long fall after landing.
		for (int dy = 1; dy <= 16; dy++) if (!level.getBlockState(at.below(dy)).getCollisionShape(level, at.below(dy)).isEmpty()) return null;
		return "long drop";
	}
}
