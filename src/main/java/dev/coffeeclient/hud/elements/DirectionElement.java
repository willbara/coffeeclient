package dev.coffeeclient.hud.elements;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/** The way you are facing, as a compass direction and the horizontal axes it follows. */
public final class DirectionElement extends TextLinesElement {
	private static final String[] NAMES = {"South", "South West", "West", "North West", "North", "North East", "East", "South East"};
	private static final String[] AXES = {"Z+", "X- Z+", "X-", "X- Z-", "Z-", "X+ Z-", "X+", "X+ Z+"};

	public DirectionElement() {
		super("direction", "Direction");
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().direction.get();
	}

	@Override
	public void toggle() {
		hud().direction.toggle();
	}

	@Override
	protected List<String[]> lines() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return List.<String[]>of(new String[] {"South", "Z+"});
		int index = ((int) Math.floor(player.getYRot() * 8.0 / 360.0 + 0.5)) & 7;
		return List.<String[]>of(new String[] {NAMES[index], AXES[index]});
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, TextLinesElement.stackY(0)};
	}
}
