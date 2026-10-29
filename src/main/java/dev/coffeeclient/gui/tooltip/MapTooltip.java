package dev.coffeeclient.gui.tooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** The map's picture under its tooltip, once the client has its data. */
public final class MapTooltip implements ClientTooltipComponent, CoffeeTooltip {
	private static final int SIZE = 96;

	private final MapId id;

	public MapTooltip(MapId id) {
		this.id = id;
	}

	@Override
	public ClientTooltipComponent component() {
		return this;
	}

	@Override
	public int getHeight(Font font) {
		return SIZE + 2;
	}

	@Override
	public int getWidth(Font font) {
		return SIZE;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		MapItemSavedData data = mc.level == null ? null : mc.level.getMapData(id);
		if (data == null) return;
		var texture = mc.getMapTextureManager().prepareMapTexture(id, data);
		g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, SIZE, SIZE, 128, 128, 128, 128);
	}
}
