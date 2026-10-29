package dev.coffeeclient.util;

import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks one block by packets: sends the start of the break, counts the server's progress the way
 * the server does (with the chosen tool, haste, fatigue, water and air, and scaled by the server's
 * TPS), and sends the finish once it reaches {@code finishAt}. The server accepts a finish from 70%
 * on, which is what lets a break end early.
 */
public final class MiningManager {
	public static final double SERVER_FINISH = 0.7;

	private BlockPos pos;
	private Direction face = Direction.UP;
	private double progress;
	private boolean finished;
	private int sinceFinish;

	public BlockPos target() {
		return pos;
	}

	public double progress() {
		return progress;
	}

	public void clear() {
		pos = null;
		progress = 0;
		finished = false;
	}

	public void start(BlockPos pos, Direction face) {
		this.pos = pos.immutable();
		this.face = face;
		progress = 0;
		finished = false;
		send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
	}

	/**
	 * Sends the finish early and lets go of the block: the server keeps breaking it on its own and
	 * destroys it once its progress reaches 100%, counted with the tool held each tick. The server keeps
	 * one such break per player, next to the one being mined.
	 */
	public void handOff() {
		if (pos != null) send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
		clear();
	}

	/**
	 * Sends the finish again for a block this manager already broke and that was put back: the server
	 * still counts from the old start, so the new block breaks at once (instant rebreak).
	 */
	public void rebreak() {
		if (pos != null) send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
	}

	public void abort() {
		if (pos != null) send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
		clear();
	}

	/**
	 * One tick of progress with hotbar {@code tool}. Returns true on the tick the finish should be
	 * sent. After a finish the server gets 20 ticks to break the block before the break starts over.
	 */
	public boolean tick(LocalPlayer player, int tool, double finishAt) {
		if (pos == null) return false;
		BlockState state = player.level().getBlockState(pos);
		if (finished) {
			if (++sinceFinish > 20) start(pos, face);
			return false;
		}
		double tps = HudStats.tps();
		double scale = Double.isNaN(tps) ? 1 : Math.min(1, tps / 20);
		progress += perTick(player, player.getInventory().getItem(tool), state, pos) * scale;
		if (progress < finishAt) return false;
		finished = true;
		sinceFinish = 0;
		return true;
	}

	/** Sends the finish, holding {@code tool} for it (and going back after when {@code silent}). */
	public void finish(int tool, boolean silent) {
		if (pos == null) return;
		PacketBatch.run(() -> {
			if (tool >= 0) Inv.swap(tool, silent);
			send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
			if (silent) Inv.swapBack();
		});
	}

	private void send(ServerboundPlayerActionPacket.Action action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null || mc.level == null || mc.getConnection() == null) return;
		BlockPos at = pos;
		Direction side = face;
		((MultiPlayerGameModeAccessor) mc.gameMode).coffeeclient$startPrediction(mc.level, seq -> new ServerboundPlayerActionPacket(action, at, side, seq));
		mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}

	/** Server progress per tick for breaking {@code state} with {@code stack}, as the server counts it. */
	static double perTick(LocalPlayer p, ItemStack stack, BlockState state, BlockPos pos) {
		float hardness = state.getDestroySpeed(p.level(), pos);
		int haste = MobEffectUtil.hasDigSpeed(p) ? MobEffectUtil.getDigSpeedAmplification(p) : -1;
		int fatigue = p.hasEffect(MobEffects.MINING_FATIGUE) ? p.getEffect(MobEffects.MINING_FATIGUE).getAmplifier() : -1;
		double water = p.isEyeInFluid(FluidTags.WATER) ? p.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED) : 1;
		boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
		return progress(Inv.toolScore(stack, state), correct, hardness, haste, fatigue, p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), water, p.onGround());
	}

	/** Vanilla's break speed over hardness; {@code haste} and {@code fatigue} are amplifiers, -1 for none. */
	static double progress(double toolSpeed, boolean correctTool, float hardness, int haste, int fatigue, double breakSpeed, double water, boolean onGround) {
		if (hardness < 0) return 0;
		if (hardness == 0) return 1;
		double speed = toolSpeed;
		if (haste >= 0) speed *= 1 + (haste + 1) * 0.2;
		if (fatigue >= 0) speed *= switch (fatigue) {
			case 0 -> 0.3;
			case 1 -> 0.09;
			case 2 -> 0.0027;
			default -> 8.1E-4;
		};
		speed *= breakSpeed * water;
		if (!onGround) speed /= 5;
		return speed / hardness / (correctTool ? 30 : 100);
	}
}
