package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;

import java.util.ArrayList;
import java.util.List;

/** Shows vanilla chunk boundary lines (same as F3+G) plus an optional chunk/region info panel. */
public final class ChunkBorders extends Module {
	public final BooleanSetting lines = setting(new BooleanSetting("Border lines", "Vanilla chunk boundary lines", true));
	public final BooleanSetting info = setting(new BooleanSetting("Chunk info panel", "Chunk, position in chunk, region file", true));
	public final BooleanSetting showSection = setting(new BooleanSetting("Section Y", "Show the chunk section (Y / 16)", true));

	private boolean weTurnedOn;

	public ChunkBorders() {
		super("ChunkBorders", Category.WORLD, "Draws chunk boundaries around you.");
		lines.onChange(v -> {
			if (isEnabled()) apply(v);
		});
	}

	private void apply(boolean on) {
		var entries = mc().debugEntries;
		boolean current = entries.isCurrentlyEnabled(DebugScreenEntries.CHUNK_BORDERS);
		if (on && !current) {
			entries.toggleStatus(DebugScreenEntries.CHUNK_BORDERS);
			weTurnedOn = true;
		} else if (!on && current && weTurnedOn) {
			entries.toggleStatus(DebugScreenEntries.CHUNK_BORDERS);
			weTurnedOn = false;
		}
	}

	@Override
	protected void onEnable() {
		if (lines.get()) apply(true);
	}

	@Override
	protected void onDisable() {
		apply(false);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "chunk_info", "Chunk info", () -> {
			List<String[]> rows = new ArrayList<>();
			var p = mc().player;
			if (p == null) return rows;
			int bx = p.getBlockX(), by = p.getBlockY(), bz = p.getBlockZ();
			rows.add(ModuleHudElement.row("Chunk", Coords.format(bx >> 4) + " " + Coords.format(bz >> 4)));
			rows.add(ModuleHudElement.row("In chunk", (bx & 15) + " " + (bz & 15)));
			if (showSection.get()) rows.add(ModuleHudElement.row("Section", Integer.toString(by >> 4)));
			rows.add(ModuleHudElement.row("Region", "r." + (bx >> 9) + "." + (bz >> 9) + ".mca"));
			return rows;
		}, (w, h) -> new int[] {w - 110, h - 140}).when(info::get));
	}
}
