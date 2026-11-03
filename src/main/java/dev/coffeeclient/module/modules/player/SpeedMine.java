package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.mixin.MultiPlayerGameModeAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.MiningManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Mines faster while you hold attack as usual. Damage finishes each break once the server's own count
 * reaches Finish at (it accepts 70% and up), and breaks on the click any block one tick gets that far.
 * Haste mines as if you had Haste, without giving you the effect.
 */
public final class SpeedMine extends Module {
	public enum Mode { Damage, Haste }

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Damage: finish the break early. Haste: mine as if you had Haste", Mode.Damage));
	public final NumberSetting finishAt = setting(new NumberSetting("Finish at", "Progress at which the break finishes (the server accepts 70% and up)", MiningManager.SERVER_FINISH, MiningManager.SERVER_FINISH, 1, 0.05)
		.format(v -> Math.round(v * 100) + "%"));
	public final NumberSetting amplifier = setting(new NumberSetting("Amplifier", "Haste level to mine with; above 2 the server may reject breaks", 2, 1, 5, 1));
	public final BooleanSetting grim = setting(new BooleanSetting("Grim", "Also aborts a break on the block above each finish (Grim fast-break check)", false));

	public SpeedMine() {
		super("SpeedMine", List.of("FastBreak", "Speed Mine"), Category.PLAYER, "Mines blocks faster.");
		finishAt.visibleWhen(() -> mode.get() == Mode.Damage);
		amplifier.visibleWhen(() -> mode.get() == Mode.Haste);
		listen(Events.PacketSend.class, e -> {
			if (grim.get() && !e.isCancelled() && e.packet instanceof ServerboundPlayerActionPacket p
				&& p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK && mc().getConnection() != null)
				mc().getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, p.getPos().above(), p.getDirection()));
		});
	}

	private static SpeedMine on(Mode mode) {
		SpeedMine m = ModuleManager.ifOn(SpeedMine.class);
		return m != null && m.mode.get() == mode ? m : null;
	}

	/** PlayerMixin: Haste mode counts the player as having Haste. */
	public static boolean haste() {
		return on(Mode.Haste) != null;
	}

	/** PlayerMixin: the Haste amplifier to mine with, never below a real one. */
	public static int hasteAmp(int original) {
		SpeedMine m = on(Mode.Haste);
		return m == null ? original : Math.max(original, m.amplifier.get().intValue() - 1);
	}

	/**
	 * MultiPlayerGameModeMixin: one tick of break progress, scaled so vanilla's 100% lands at Finish at.
	 * A lagging server counts fewer ticks, so progress slows with its TPS.
	 */
	public static float scale(float delta) {
		SpeedMine m = on(Mode.Damage);
		if (m == null) return delta;
		double tps = HudStats.tps();
		return (float) (delta * (Double.isNaN(tps) ? 1 : Math.min(1, tps / 20)) / m.finishAt.get());
	}

	/**
	 * MultiPlayerGameModeMixin: a click on a block one tick breaks past Finish at sends the start and the
	 * finish together (the server checks the finish against one tick of progress). True when vanilla
	 * should stand aside.
	 */
	public static boolean instamine(BlockPos pos, Direction dir) {
		Minecraft mc = mc();
		LocalPlayer p = mc.player;
		if (on(Mode.Damage) == null || p == null || mc.level == null || mc.gameMode == null || p.getAbilities().instabuild) return false;
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !mc.level.getWorldBorder().isWithinBounds(pos) || p.blockActionRestricted(mc.level, pos, mc.gameMode.getPlayerMode())) return false;
		float delta = state.getDestroyProgress(p, mc.level, pos);
		if (delta >= 1 || scale(delta) < 1) return false;
		var gameMode = mc.gameMode;
		var prediction = (MultiPlayerGameModeAccessor) gameMode;
		gameMode.stopDestroyBlock();
		prediction.coffeeclient$startPrediction(mc.level, seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir, seq));
		prediction.coffeeclient$startPrediction(mc.level, seq -> {
			gameMode.destroyBlock(pos);
			return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir, seq);
		});
		return true;
	}

	@Override
	public String infoString() {
		return mode.get() == Mode.Damage ? Math.round(finishAt.get() * 100) + "%" : "Haste " + amplifier.get().intValue();
	}
}
