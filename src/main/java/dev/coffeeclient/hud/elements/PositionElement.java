package dev.coffeeclient.hud.elements;

import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.util.Coords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/** Block X, Y, Z position, displayed separately from linked dimension coordinates. */
public final class PositionElement extends TextLinesElement {
	public PositionElement() {
		super("position", "Position");
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().position.get();
	}

	@Override
	public void toggle() {
		hud().position.toggle();
	}

	@Override
	protected List<String[]> lines() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return List.<String[]>of(new String[] {"Pos", "0, 64, 0"});
		String value = StreamerMode.hideCoords()
			? StreamerMode.HIDDEN
			: Coords.xyz(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		return List.<String[]>of(new String[] {"Pos", value});
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, TextLinesElement.stackY(2)};
	}
}
