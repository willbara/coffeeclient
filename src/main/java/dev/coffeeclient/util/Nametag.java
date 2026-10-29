package dev.coffeeclient.util;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Floating labels drawn on the HUD at a projected world position (centred, bottom edge at the point). */
public final class Nametag {
	public record Line(String text, int color) {
	}

	public record Segment(String text, int color) {
	}

	/** Icons are scaled to roughly text height so they never make the label taller. */
	private static final int ICON = 9;

	private Nametag() {
	}

	/**
	 * @param bar     optional 0..1 health bar under the text (negative = none)
	 * @param barColor colour of the bar fill
	 * @param icon    optional item drawn to the left
	 */
	public static void draw(Ui ui, float px, float py, List<Line> lines, float scale, boolean background,
							double bar, int barColor, ItemStack icon) {
		draw(ui.g, px, py, lines, scale, background, ui.theme.panel, bar, barColor, icon);
	}

	public static int height(List<Line> lines, double bar) {
		return lines.size() * 10 + 2 + (bar >= 0 ? 3 : 0);
	}

	public static void drawSegments(Ui ui, float px, float py, List<Segment> segments, float scale,
									boolean background, int backgroundColor, ItemStack icon) {
		drawSegments(ui.g, px, py, segments, scale, background, backgroundColor, icon);
	}

	public static void drawSegments(GuiGraphicsExtractor g, float px, float py, List<Segment> segments, float scale,
									boolean background, int backgroundColor, ItemStack icon) {
		if (segments.isEmpty()) return;
		int textWidth = segments.stream().mapToInt(segment -> Draw.width(segment.text())).sum();
		boolean hasIcon = icon != null && !icon.isEmpty();
		int width = textWidth + 6 + (hasIcon ? ICON + 2 : 0);
		int height = 12;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(px, py);
		pose.scale(scale, scale);
		int x = -width / 2, y = -height;
		if (background) Draw.rect(g, x, y, width, height, Colors.withAlpha(backgroundColor, Math.min(0xC0, Colors.alpha(backgroundColor))));
		int textX = x + 3;
		if (hasIcon) {
			pose.pushMatrix();
			pose.translate(x + 2, y + 1);
			pose.scale(ICON / 16f, ICON / 16f);
			g.item(icon, 0, 0);
			pose.popMatrix();
			textX += ICON + 2;
		}
		for (Segment segment : segments) {
			Draw.text(g, segment.text(), textX, y + 2, segment.color());
			textX += Draw.width(segment.text());
		}
		pose.popMatrix();
	}

	public static int equipmentHeight(List<ItemStack> items, List<List<Line>> enchantments, boolean showDurability) {
		int maxEnchantLines = enchantments.stream().mapToInt(List::size).max().orElse(0);
		boolean anyItems = items.stream().anyMatch(stack -> !stack.isEmpty());
		return anyItems ? 18 + maxEnchantLines * 7 + (showDurability ? 4 : 0) : 0;
	}

	public static void draw(Ui ui, float px, float py, List<Line> lines, float scale, boolean background,
							int backgroundColor, double bar, int barColor, ItemStack icon) {
		draw(ui.g, px, py, lines, scale, background, backgroundColor, bar, barColor, icon);
	}

	public static void draw(GuiGraphicsExtractor g, float px, float py, List<Line> lines, float scale, boolean background,
							int backgroundColor, double bar, int barColor, ItemStack icon) {
		if (lines.isEmpty()) return;
		int textW = 0;
		for (Line l : lines) textW = Math.max(textW, Draw.width(l.text()));
		boolean hasIcon = icon != null && !icon.isEmpty();
		int w = textW + 6 + (hasIcon ? ICON + 2 : 0);
		int h = height(lines, bar);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(px, py);
		pose.scale(scale, scale);
		int x = -w / 2, y = -h;
		if (background) Draw.rect(g, x, y, w, h, Colors.withAlpha(backgroundColor, Math.min(0xC0, Colors.alpha(backgroundColor))));
		int tx = x + 3;
		if (hasIcon) {
			// beside the first line, same height as the text
			pose.pushMatrix();
			pose.translate(x + 2, y + 1);
			pose.scale(ICON / 16f, ICON / 16f);
			g.item(icon, 0, 0);
			pose.popMatrix();
			tx += ICON + 2;
		}
		int ly = y + 2;
		for (Line l : lines) {
			Draw.text(g, l.text(), tx + (textW - Draw.width(l.text())) / 2, ly, l.color());
			ly += 10;
		}
		if (bar >= 0) {
			Draw.rect(g, tx, ly, textW, 2, Colors.withAlpha(0, 0xA0));
			Draw.rect(g, tx, ly, (int) Math.round(textW * Math.max(0, Math.min(1, bar))), 2, barColor);
		}
		pose.popMatrix();
	}

	public static void drawEquipment(Ui ui, float centerX, float bottomY, List<ItemStack> items, List<List<Line>> enchantments,
									  List<String> durability, float scale, float spacing, boolean ignoreEmpty,
									  boolean enchantsOnTop, float enchantScale) {
		drawEquipment(ui.g, centerX, bottomY, items, enchantments, durability, scale, spacing, ignoreEmpty, enchantsOnTop, enchantScale);
	}

	public static void drawEquipment(GuiGraphicsExtractor g, float centerX, float bottomY, List<ItemStack> items, List<List<Line>> enchantments,
									  List<String> durability, float scale, float spacing, boolean ignoreEmpty,
									  boolean enchantsOnTop, float enchantScale) {
		int slots = items.size();
		int totalWidth = 0;
		for (ItemStack item : items) if (!ignoreEmpty || !item.isEmpty()) totalWidth += 16 + Math.round(spacing);
		if (totalWidth == 0) return;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(centerX, bottomY);
		pose.scale(scale, scale);
		int x = -totalWidth / 2;
		for (int i = 0; i < slots; i++) {
			ItemStack item = items.get(i);
			if (item.isEmpty() && ignoreEmpty) continue;
			int slotWidth = 16 + Math.round(spacing);
			if (!item.isEmpty()) {
				g.item(item, x, -16);
				if (i < durability.size() && !durability.get(i).isEmpty()) {
					String text = durability.get(i);
					Draw.textScaled(g, text, x + (slotWidth - Draw.width(text) * 0.55f) / 2f, -5, item.getBarColor() | 0xFF000000, 0.55f);
				}
				if (i < enchantments.size()) {
					List<Line> lines = enchantments.get(i);
					for (int j = 0; j < lines.size(); j++) {
						Line line = lines.get(j);
						float y = enchantsOnTop
							? -12 + (j - (lines.size() - 1) / 2f) * 7
							: -19 - (lines.size() - j - 1) * 7;
						float textScale = 0.5f * enchantScale;
						Draw.textScaled(g, line.text(), x + (slotWidth - Draw.width(line.text()) * textScale) / 2f, y, line.color(), textScale);
					}
				}
			}
			x += slotWidth;
		}
		pose.popMatrix();
	}
}
