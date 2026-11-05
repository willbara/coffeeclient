package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.modules.combat.PlaceModule;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds a small preset structure in front of you when switched on, then turns itself off. Blocks
 * come from the hotbar, offhand or main inventory; Air place builds where nothing is there to click.
 */
public final class AutoBuild extends PlaceModule {
	public enum Preset { PORTAL, WITHER, IRON_GOLEM, SNOW_GOLEM }

	/** A build's cells: body in build order, heads set on the top face of the block below, and cells that must be air. */
	record Layout(List<BlockPos> body, List<BlockPos> heads, List<BlockPos> clear) {
		List<BlockPos> all() {
			List<BlockPos> all = new ArrayList<>(body);
			all.addAll(heads);
			return all;
		}
	}

	/** The body item, and the finishing item: the heads, or the portal's igniter. */
	private record Materials(Predicate<ItemStack> body, String bodyName, Predicate<ItemStack> finish, String finishName) {
	}

	/** Ticks before giving up (a block or the light that never lands); the default pace needs about 20. */
	private static final int TIMEOUT = 100;

	public final EnumSetting<Preset> preset = group(new EnumSetting<>("Preset", "What to build", Preset.PORTAL), "AutoBuild");
	public final BooleanSetting light = group(new BooleanSetting("Light", "Light the portal once the frame is built", true), "AutoBuild");

	/** Null until planned on the first tick. */
	private Layout layout;
	private Materials materials;
	/** The next cell of {@code layout.all()} to place; cells already sent are never looked at again. */
	private int step;
	/** Placing heads (the body is done) rather than body blocks. */
	private boolean heads, lit;
	private int ticks;

	public AutoBuild() {
		super("AutoBuild", List.of("Auto Build"), Category.WORLD, "Builds a nether portal, wither or golem in front of you, then turns off.",
			// One block every other tick: faster leaves ghost blocks the server never got, and the portal won't light.
			List.of("minecraft:obsidian"), true, false, 1, 1);
		// Each preset picks its own blocks, and the build refuses cells with entities, so crystals never matter.
		blocks.visibleWhen(() -> false);
		breakCrystals.visibleWhen(() -> false);
		light.visibleWhen(() -> preset.get() == Preset.PORTAL);
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		layout = null;
		materials = null;
		heads = lit = false;
		step = ticks = 0;
	}

	@Override
	protected Inv.FindItemResult findBlock() {
		Predicate<ItemStack> want = heads ? materials.finish() : materials.body();
		Inv.FindItemResult held = Inv.findInHotbar(want);
		return held.found() ? held : Inv.find(want, 9, 35);
	}

	@Override
	protected boolean placeOne(BlockPos pos, Inv.FindItemResult item, int priority, boolean checkEntities) {
		if (!heads) return super.placeOne(pos, item, priority, checkEntities);
		// Click the top face: a skull placed against a side becomes a wall skull, which doesn't count.
		return BlockActions.placeAt(topOf(pos.below()), item, rotate.get(), priority, swing.get(), true);
	}

	private static BlockHitResult topOf(BlockPos pos) {
		return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
	}

	/**
	 * The cells for {@code preset} in front of {@code feet}, facing you, bottom row at {@code feet}'s Y.
	 * Portal: 4x5 frame without corners two blocks ahead. Mobs: a T (or a column for the snow golem)
	 * three blocks ahead, with the cells vanilla's pattern needs empty.
	 */
	static Layout layout(Preset preset, BlockPos feet, Direction facing) {
		Direction right = facing.getClockWise();
		if (preset == Preset.PORTAL) {
			BlockPos o = feet.relative(facing, 2);
			List<BlockPos> frame = new ArrayList<>(List.of(o, o.relative(right)));
			List<BlockPos> inside = new ArrayList<>();
			for (int y = 1; y <= 3; y++) {
				// Pillars one column outside the 2-wide opening on each side.
				frame.add(o.relative(right.getOpposite()).above(y));
				frame.add(o.relative(right, 2).above(y));
				inside.add(o.above(y));
				inside.add(o.relative(right).above(y));
			}
			frame.add(o.above(4));
			frame.add(o.relative(right).above(4));
			return new Layout(frame, List.of(), inside);
		}
		BlockPos o = feet.relative(facing, 3);
		if (preset == Preset.SNOW_GOLEM) return new Layout(List.of(o, o.above()), List.of(o.above(2)), List.of());
		BlockPos l = o.relative(right.getOpposite()), r = o.relative(right);
		List<BlockPos> body = List.of(o, o.above(), l.above(), r.above());
		if (preset == Preset.IRON_GOLEM) return new Layout(body, List.of(o.above(2)), List.of(l, r, l.above(2), r.above(2)));
		return new Layout(body, List.of(l.above(2), o.above(2), r.above(2)), List.of(l, r));
	}

	private Materials materials() {
		Predicate<ItemStack> pumpkin = Inv.anyOf(Items.CARVED_PUMPKIN, Items.JACK_O_LANTERN);
		return switch (preset.get()) {
			case PORTAL -> new Materials(Inv.anyOf(Items.OBSIDIAN), "obsidian",
				Inv.anyOf(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE), "flint and steel or a fire charge");
			case WITHER -> new Materials(Inv.anyOf(Items.SOUL_SAND, Items.SOUL_SOIL), "soul sand",
				Inv.anyOf(Items.WITHER_SKELETON_SKULL), "wither skeleton skulls");
			case IRON_GOLEM -> new Materials(Inv.anyOf(Items.IRON_BLOCK), "iron blocks", pumpkin, "carved pumpkin");
			case SNOW_GOLEM -> new Materials(Inv.anyOf(Items.SNOW_BLOCK), "snow blocks", pumpkin, "carved pumpkin");
		};
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) return;
		if (layout == null && !plan(player, level)) {
			setEnabled(false);
			return;
		}
		if (++ticks > TIMEOUT) {
			info("Couldn't finish the build");
			setEnabled(false);
			return;
		}

		// Steps advance on a sent placement, not on what the world shows: a spawning mob clears its blocks,
		// which must not read as cells still to fill.
		List<BlockPos> steps = layout.all();
		while (step < steps.size()) {
			BlockPos cell = steps.get(step);
			if (!level.getBlockState(cell).canBeReplaced()) {
				step++; // was already there when we got to it
				continue;
			}
			heads = step >= layout.body().size();
			// Without a face to click (and Air place off), place() puts a support block in first: the cell is still to do.
			boolean direct = heads || airPlace.get() || BlockActions.placeParams(cell) != null;
			if (place(List.of(cell), Rotations.NORMAL) == 0 || !direct) return;
			step++;
		}
		if (preset.get() != Preset.PORTAL) {
			info(preset.get() == Preset.WITHER ? "Wither built" : "Golem built");
			setEnabled(false);
			return;
		}
		BlockPos inside = layout.clear().get(0);
		if (!light.get()) {
			info("Portal built");
			setEnabled(false);
		} else if (level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
			info("Portal lit");
			setEnabled(false);
		} else if (!lit) {
			Inv.FindItemResult item = Inv.findInHotbar(materials.finish());
			if (!item.found()) item = Inv.find(materials.finish(), 9, 35);
			lit = BlockActions.placeAt(topOf(inside.below()), item, rotate.get(), Rotations.NORMAL, swing.get(), true);
		}
	}

	/** Works out the cells and checks they can be built; tells you why not and returns false otherwise. */
	private boolean plan(LocalPlayer player, ClientLevel level) {
		Layout cells = layout(preset.get(), player.blockPosition(), player.getDirection());
		Materials m = materials();
		int bodyNeeded = missing(level, cells.body(), m.body());
		int headsNeeded = missing(level, cells.heads(), m.finish());
		if (bodyNeeded < 0 || headsNeeded < 0) {
			info("Something is in the way");
			return false;
		}
		if (!cells.clear().stream().allMatch(p -> level.getBlockState(p).isAir())) {
			info(preset.get() == Preset.PORTAL ? "The inside of the portal isn't clear" : "Something is in the way");
			return false;
		}
		int have = Inv.find(m.body()).count();
		if (have < bodyNeeded) {
			info("Need " + bodyNeeded + " " + m.bodyName() + ", have " + have);
			return false;
		}
		int finishNeeded = preset.get() == Preset.PORTAL ? (light.get() ? 1 : 0) : headsNeeded;
		if (Inv.find(m.finish()).count() < finishNeeded) {
			info("Need " + (finishNeeded > 1 ? finishNeeded + " " : "") + m.finishName());
			return false;
		}
		if (preset.get() == Preset.WITHER && level.getDifficulty() == Difficulty.PEACEFUL) {
			info("Withers can't spawn on Peaceful");
			return false;
		}
		layout = cells;
		materials = m;
		return true;
	}

	/** How many of {@code cells} still need {@code item}, or -1 when one holds something else or an entity. */
	private static int missing(ClientLevel level, List<BlockPos> cells, Predicate<ItemStack> item) {
		int missing = 0;
		for (BlockPos cell : cells) {
			if (item.test(new ItemStack(level.getBlockState(cell).getBlock()))) continue;
			if (!BlockActions.canPlace(cell, Blocks.OBSIDIAN, true)) return -1;
			missing++;
		}
		return missing;
	}
}
