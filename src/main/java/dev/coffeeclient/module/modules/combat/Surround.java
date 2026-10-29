package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.MiningTracker;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walls in the cells around your feet so crystals can't reach you from the side. Cells someone is
 * breaking come first; Blocker also covers the cells around such a cell, so a broken wall can't be
 * replaced with a crystal straight away. Reactive places the moment a wall goes (block update,
 * explosion, crystal spawn) instead of on the next tick, and HClip leans your hitbox into a wall that
 * starts being broken so no crystal fits there once it's gone.
 */
public final class Surround extends PlaceModule {
	/** Fastest a centring nudge moves you per tick. */
	private static final double CENTER_SPEED = 0.2;
	/** HClip shift from the block middle: the 0.6-wide hitbox then pokes a hair into the next cell. */
	private static final double CLIP_OFFSET = 0.2000001;

	public final BooleanSetting center = group(new BooleanSetting("Center", "Walk to the middle of your block first, so one ring of four blocks is enough", true), "Surround");
	public final BooleanSetting floor = group(new BooleanSetting("Floor", "Also fill the block under your feet if it is missing", false), "Surround");
	public final BooleanSetting doubleHeight = group(new BooleanSetting("Double height", "Also wall in the cells at head height", false), "Surround");
	public final BooleanSetting onlyOnGround = group(new BooleanSetting("Only on ground", "Wait until you stand on something", true), "Surround");
	public final BooleanSetting disableOnMove = group(new BooleanSetting("Disable on Y change", "Turn off when you move up or down a block", true), "Surround");
	public final BooleanSetting disableOnComplete = group(new BooleanSetting("Disable when done", "Turn off once every cell is filled", false), "Surround");
	public final BooleanSetting reactive = group(new BooleanSetting("Reactive", "Place the moment a wall breaks, blows up or gets a crystal, instead of on the next tick", true), "Reactive");
	public final BooleanSetting hClip = group(new BooleanSetting("HClip", "When someone starts breaking a wall beside you, lean into that wall's corner so no crystal fits there once it's gone", false), "Reactive");
	public final BooleanSetting blocker = group(new BooleanSetting("Blocker", "When someone breaks one of your walls, also block the cells around it", false), "Blocker");
	public final BooleanSetting blockerTop = group(new BooleanSetting("Blocker top", "Also block the cell above a wall being broken", false), "Blocker");

	private final MiningTracker mining = new MiningTracker();
	private int anchorY;
	private int open;

	public Surround() {
		super("Surround", List.of("Surround+", "Feet trap"), "Surrounds your feet with blocks.", List.of("minecraft:obsidian"), false);
		blockerTop.visibleWhen(blocker::get);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketApply.Post.class, e -> {
			switch (e.packet()) {
				case ClientboundBlockDestructionPacket p -> {
					mining.progress(p.getId(), p.getPos(), p.getProgress(), System.currentTimeMillis());
					if (p.getProgress() == 0) clip(p);
					if (reactive.get() && near(Vec3.atCenterOf(p.getPos()))) pass();
				}
				case ClientboundExplodePacket p when reactive.get() && near(p.center()) -> pass();
				default -> {
				}
			}
		});
		listen(Events.BlockUpdate.class, e -> {
			if (reactive.get() && e.newState().canBeReplaced() && near(Vec3.atCenterOf(e.pos()))) pass();
		});
		listen(Events.EntityAdded.class, e -> {
			if (reactive.get() && e.entity() instanceof EndCrystal && near(e.entity().position())) pass();
		});
		listen(Events.PlayerMove.class, EventBus.HIGH, e -> {
			if (e.type == MoverType.SELF) e.movement = centerMove(e.movement);
		});
		listen(Events.WorldChange.class, e -> mining.clear());
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		anchorY = Footprint.level(player.getBoundingBox(), Footprint.blocks(mc().level));
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		mining.clear();
		open = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return;
		mining.prune(System.currentTimeMillis());
		int level = Footprint.level(player.getBoundingBox(), Footprint.blocks(mc().level));
		if (level != anchorY) {
			if (disableOnMove.get()) {
				setEnabled(false);
				return;
			}
			anchorY = level;
		}
		pass();
		if (open == 0 && disableOnComplete.get() && (!onlyOnGround.get() || player.onGround())) {
			info("Done");
			setEnabled(false);
		}
	}

	/** One placing round; runs each tick and, with Reactive, whenever a wall may just have gone. */
	private void pass() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || onlyOnGround.get() && !player.onGround()) return;
		List<BlockPos> cells = cells(player);
		place(cells, Rotations.HIGH);
		open = (int) cells.stream().filter(c -> mc().level.getBlockState(c).canBeReplaced()).count();
	}

	/** Within reach of the surround: worth a reactive round. */
	private boolean near(Vec3 pos) {
		LocalPlayer player = mc().player;
		return player != null && player.position().distanceToSqr(pos) <= 9;
	}

	/** HClip: someone else started breaking the wall beside your feet; lean into its corner. */
	private void clip(ClientboundBlockDestructionPacket p) {
		LocalPlayer player = mc().player;
		if (!hClip.get() || player == null || p.getId() == player.getId()) return;
		BlockPos feet = player.blockPosition(), pos = p.getPos();
		int dx = pos.getX() - feet.getX(), dz = pos.getZ() - feet.getZ();
		if (pos.getY() != feet.getY() || Math.abs(dx) + Math.abs(dz) != 1) return;
		if (mc().level.getEntity(p.getId()) instanceof Player breaker && Targets.isProtectedFriend(breaker)) return;
		Vec3 middle = Vec3.atBottomCenterOf(feet);
		// Toward the wall on its axis; on the other axis, toward whichever side you already lean.
		double x = dx != 0 ? dx : player.getX() >= middle.x ? 1 : -1;
		double z = dz != 0 ? dz : player.getZ() >= middle.z ? 1 : -1;
		player.setPos(middle.x + CLIP_OFFSET * x, player.getY(), middle.z + CLIP_OFFSET * z);
	}

	/** The cells to fill, most urgent first: walls being broken, the floor, the ring, head height, blockers. */
	private List<BlockPos> cells(LocalPlayer player) {
		LongSet columns = columns(player);
		Set<BlockPos> threatened = new LinkedHashSet<>();
		for (MiningTracker.Entry e : mining.active()) threatened.add(e.pos());

		List<BlockPos> walls = new ArrayList<>(Footprint.ring(columns));
		if (doubleHeight.get()) for (BlockPos wall : List.copyOf(walls)) walls.add(wall.above());
		List<BlockPos> urgent = new ArrayList<>(), rest = new ArrayList<>();
		for (BlockPos wall : walls) (threatened.contains(wall) ? urgent : rest).add(wall);
		if (floor.get()) for (long key : columns) urgent.add(BlockPos.of(key).below());

		List<BlockPos> cells = new ArrayList<>(urgent);
		cells.addAll(rest);
		if (blocker.get()) {
			AABB box = player.getBoundingBox();
			for (BlockPos wall : urgent) {
				if (!threatened.contains(wall)) continue;
				for (Direction d : Direction.Plane.HORIZONTAL) addBlocker(cells, columns, box, wall.relative(d));
				if (blockerTop.get()) addBlocker(cells, columns, box, wall.above());
			}
		}
		return cells;
	}

	private static void addBlocker(List<BlockPos> cells, LongSet columns, AABB box, BlockPos pos) {
		if (!columns.contains(pos.asLong()) && !cells.contains(pos) && !box.intersects(new AABB(pos))) cells.add(pos);
	}

	/** With Center on, the one column under your middle; otherwise every column you stand in. */
	private LongSet columns(LocalPlayer player) {
		if (!center.get()) return Footprint.columns(player.getBoundingBox(), Footprint.blocks(mc().level));
		LongSet one = new LongOpenHashSet();
		one.add(BlockPos.asLong(Mth.floor(player.getX()), anchorY, Mth.floor(player.getZ())));
		return one;
	}

	/** Nudges you to the middle of your block while you stand still. */
	private Vec3 centerMove(Vec3 movement) {
		LocalPlayer player = mc().player;
		if (!center.get() || player == null || !player.onGround() || player.input.getMoveVector().lengthSquared() > 0) return movement;
		if (hClip.get() && wallBeingBroken(player)) return movement;
		double dx = Mth.floor(player.getX()) + 0.5 - player.getX();
		double dz = Mth.floor(player.getZ()) + 0.5 - player.getZ();
		double fit = 0.5 - player.getBbWidth() / 2 - 0.01;
		if (Math.abs(dx) <= fit && Math.abs(dz) <= fit) return movement;
		player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
		return new Vec3(Mth.clamp(dx, -CENTER_SPEED, CENTER_SPEED), movement.y, Mth.clamp(dz, -CENTER_SPEED, CENTER_SPEED));
	}

	/** Someone is breaking a wall beside your feet (HClip's lean must not be centred away). */
	private boolean wallBeingBroken(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		for (MiningTracker.Entry e : mining.active()) {
			BlockPos pos = e.pos();
			if (e.breaker() != player.getId() && pos.getY() == feet.getY() && pos.distManhattan(feet) == 1) return true;
		}
		return false;
	}

	@Override
	public String infoString() {
		return open > 0 ? open + " open" : null;
	}
}
