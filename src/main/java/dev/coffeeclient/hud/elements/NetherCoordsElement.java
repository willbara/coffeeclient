package dev.coffeeclient.hud.elements;

import dev.coffeeclient.util.Coords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/** The linked position in the other dimension: Nether coords in the Overworld and vice versa. */
public final class NetherCoordsElement extends TextLinesElement {
	public NetherCoordsElement() {
		super("nether_coords", "Nether coords");
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().netherCoords.get();
	}

	@Override
	public void toggle() {
		hud().netherCoords.toggle();
	}

	@Override
	protected List<String[]> lines() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return List.<String[]>of(new String[] {"Nether", "0 0"});
		if (dev.coffeeclient.module.modules.client.StreamerMode.hideCoords()) return List.<String[]>of(new String[] {"Nether", dev.coffeeclient.module.modules.client.StreamerMode.HIDDEN});
		String dim = mc.level.dimension().identifier().toString();
		if (dim.equals("minecraft:the_nether")) {
			return List.<String[]>of(new String[] {"Overworld", Coords.format(p.getBlockX() * 8) + " " + Coords.format(p.getBlockZ() * 8)});
		}
		if (dim.equals("minecraft:overworld")) {
			return List.<String[]>of(new String[] {"Nether", Coords.format(Math.floorDiv(p.getBlockX(), 8)) + " " + Coords.format(Math.floorDiv(p.getBlockZ(), 8))});
		}
		return List.<String[]>of(new String[] {"Nether", "n/a"});
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, stackY(1)};
	}
}
