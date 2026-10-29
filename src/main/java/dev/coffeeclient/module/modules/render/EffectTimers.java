package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A list of your active effects with level and time left, optionally replacing the vanilla icons. */
public final class EffectTimers extends Module {
	public enum Sort { TIME_LEFT, NAME, BENEFICIAL_FIRST }

	public final BooleanSetting icons = setting(new BooleanSetting("Icons", "Show effect icons", true));
	public final BooleanSetting hideVanilla = setting(new BooleanSetting("Hide vanilla icons", "Hide the top-right effect icons", false));
	public final BooleanSetting colorByType = setting(new BooleanSetting("Color by type", "Harmful effects in red", true));
	public final BooleanSetting blink = setting(new BooleanSetting("Blink when ending", "Blink in the last 10 seconds", true));
	public final EnumSetting<Sort> sort = setting(new EnumSetting<>("Sort", "List order", Sort.TIME_LEFT));

	private final HudElement element = new Element();

	public EffectTimers() {
		super("EffectTimers", Category.RENDER, "Shows potion effect timers.");
	}

	@Override
	protected void init(EventBus bus) {
		HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS, original -> (g, delta) -> {
			if (!(isEnabled() && hideVanilla.get())) original.extractRenderState(g, delta);
		});
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	/** True while the vanilla top-right effect icons are actually hidden. */
	public static boolean hidesVanillaIcons() {
		EffectTimers et = ModuleManager.ifOn(EffectTimers.class);
		return et != null && et.hideVanilla.get();
	}

	private List<MobEffectInstance> effects() {
		var p = mc().player;
		if (p == null) return List.of();
		List<MobEffectInstance> list = new ArrayList<>(p.getActiveEffects());
		Comparator<MobEffectInstance> c = switch (sort.get()) {
			case TIME_LEFT -> Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration());
			case NAME -> Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString());
			case BENEFICIAL_FIRST -> Comparator.comparing(e -> e.getEffect().value().getCategory() != MobEffectCategory.BENEFICIAL);
		};
		list.sort(c);
		return list;
	}

	private static String text(MobEffectInstance e) {
		String name = e.getEffect().value().getDisplayName().getString();
		int amp = e.getAmplifier();
		if (amp > 0) name += " " + (amp < 10 ? new String[] {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[amp] : Integer.toString(amp + 1));
		String time;
		if (e.isInfiniteDuration()) time = "∞";
		else {
			int s = e.getDuration() / 20;
			time = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
		}
		return name + "  " + time;
	}

	private final class Element extends ModuleCustomElement {
		Element() {
			super(EffectTimers.this, "effects", "Effect timers");
		}

		@Override
		public boolean shouldRender() {
			return !effects().isEmpty();
		}

		@Override
		public int width() {
			int w = 60;
			for (MobEffectInstance e : effects()) w = Math.max(w, Draw.width(text(e)) + (icons.get() ? 16 : 6));
			return w;
		}

		@Override
		public int height() {
			return Math.max(1, effects().size()) * 11 + 3;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w - width() - 2, h / 2 - 40};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			List<MobEffectInstance> list = effects();
			Draw.rect(ui.g, x, y, width(), height(), ui.theme.panel);
			Draw.rect(ui.g, x, y, 2, height(), ui.theme.accent);
			if (list.isEmpty()) {
				Draw.text(ui.g, "No effects", x + 5, y + 2, ui.theme.dimText);
				return;
			}
			int ly = y + 2;
			for (MobEffectInstance e : list) {
				boolean harmful = e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
				int color = colorByType.get() && harmful ? ui.theme.error : ui.theme.text;
				if (blink.get() && !e.isInfiniteDuration() && e.getDuration() < 200 && (System.currentTimeMillis() / 300) % 2 == 0) color = ui.theme.dimText;
				int tx = x + 5;
				if (icons.get()) {
					ui.g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(e.getEffect()), tx, ly, 9, 9);
					tx += 11;
				}
				Draw.text(ui.g, text(e), tx, ly + 1, color);
				ly += 11;
			}
		}
	}
}
