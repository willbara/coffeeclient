package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.GameStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Records where you died: chat line and clipboard. */
public final class DeathCoords extends Module {
	public final BooleanSetting chatMessage = setting(new BooleanSetting("Chat message", "Print the coordinates in chat (only you see it)", true));
	public final BooleanSetting copy = setting(new BooleanSetting("Copy to clipboard", "Copy the coordinates", false));

	private boolean wasDead;

	public DeathCoords() {
		super("DeathCoords", Category.MISC, "Remembers where you last died.");
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.player == null || mc.level == null) {
			wasDead = false;
			return;
		}
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead) onDeath(mc.player.blockPosition(), GameStats.dimensionShort(mc.level));
		wasDead = dead;
	}

	private void onDeath(BlockPos pos, String dim) {
		String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		String shown = Coords.format(pos.getX()) + " " + Coords.format(pos.getY()) + " " + Coords.format(pos.getZ());
		String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
		if (chatMessage.get()) chat(Component.literal("You died at " + shown + " in " + dim + " (" + time + ")"));
		if (copy.get()) mc().keyboardHandler.setClipboard(coords);
	}
}
