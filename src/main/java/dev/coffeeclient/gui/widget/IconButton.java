package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/** Large dashboard button: item icon in a well, title, and a dim subtitle (e.g. the bound key). */
public class IconButton extends Widget {
	public static final int HEIGHT = 26;

	/** Draws a 16x16 icon with its top-left corner at x, y. */
	public interface Icon {
		void draw(Ui ui, int x, int y);

		static Icon of(ItemStack stack) {
			return (ui, x, y) -> ui.g.item(stack, x, y);
		}

		/** A 16x16 texture file, e.g. an item's own png. */
		static Icon texture(Identifier texture) {
			return (ui, x, y) -> ui.g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, 16, 16, 16, 16);
		}
	}

	private final Icon icon;
	private final String title;
	private final Supplier<String> subtitle;
	private final Runnable action;

	public IconButton(ItemStack icon, String title, Supplier<String> subtitle, Runnable action) {
		this(Icon.of(icon), title, subtitle, action);
	}

	public IconButton(Icon icon, String title, Supplier<String> subtitle, Runnable action) {
		this.icon = icon;
		this.title = title;
		this.subtitle = subtitle;
		this.action = action;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		Draw.glow(ui.g, x, y, width, height, t.accent, hover * Math.max(0.4f, t.glow.floatValue()), 3);
		Draw.box(ui.g, x, y, width, height, t.panel, Colors.lerp(t.border, t.accent, hover));
		if (hover > 0.01f && t.glass()) {
			Shapes.roundRect(ui.g, x + 1, y + 1, width - 2, height - 2, Draw.controlRadius(width - 2, height - 2), Colors.withAlpha(0xFFFFFF, (int) (hover * 0x0C)));
		} else if (hover > 0.01f) {
			Draw.rect(ui.g, x + 1, y + 1, width - 2, height - 2, t.accent((int) (hover * 0x1C)));
			if (t.sheen.get()) Draw.sheen(ui.g, x + 1, y + 1, width - 2, height - 2, Draw.sheenPhase(ui.timeMs, 1.6f, 0), t.accent((int) (hover * 0x30)));
		}
		// the icon hops up and the title slides over a touch on hover
		int lift = Math.round(Ease.OUT_BACK.apply(hover) * 2);
		int slide = Math.round(hover * 2);
		if (t.glass()) {
			// a round icon well that picks up the accent glow on hover
			if (hover > 0.01f) Shapes.halo(ui.g, x + 4, y + 4, 18, 18, 9, 4, t.accent(Math.round(0x70 * hover)));
			Shapes.roundRect(ui.g, x + 4, y + 4, 18, 18, 9, Colors.lerp(t.control, t.accent(0xFF), hover * 0.12f));
			Shapes.roundOutline(ui.g, x + 4, y + 4, 18, 18, 9, Colors.lerp(Colors.withAlpha(0xFFFFFF, 0x30), t.accent, hover * 0.8f), Colors.withAlpha(0xFFFFFF, 0x0C));
		} else {
			Draw.box(ui.g, x + 4, y + 4, 18, 18, t.control, Colors.lerp(t.border, t.accent, hover * 0.7f));
		}
		// items ignore the interface alpha, so hold them back until a fade-in is mostly done
		if (Draw.alpha() > 0.6f) icon.draw(ui, x + 5, y + 5 - lift);
		Draw.text(ui.g, Draw.trim(title, width - 32), x + 27 + slide, y + 4, Colors.lerp(t.text, t.strongText(), hover));
		String sub = subtitle == null ? null : subtitle.get();
		if (sub != null) Draw.text(ui.g, Draw.trim(sub, width - 32), x + 27 + slide, y + 14, t.dimText);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		UiSounds.click(1.1f);
		action.run();
		return true;
	}
}
