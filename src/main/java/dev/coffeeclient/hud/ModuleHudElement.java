package dev.coffeeclient.hud;

import dev.coffeeclient.hud.elements.TextLinesElement;
import dev.coffeeclient.module.Module;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A "label value" panel owned by a module: shown while the module is on, toggled with it, and listed in
 * the HUD editor only while the module is enabled.
 */
public class ModuleHudElement extends TextLinesElement {
	private final Module module;
	private final Supplier<List<String[]>> lines;
	private final BiFunction<Integer, Integer, int[]> defaults;
	private Supplier<Boolean> condition = () -> true;
	private Supplier<Boolean> background = () -> true;
	private boolean stacks = true;

	public ModuleHudElement(Module module, String id, String name, Supplier<List<String[]>> lines,
							BiFunction<Integer, Integer, int[]> defaults) {
		super(id, name);
		this.module = module;
		this.lines = lines;
		this.defaults = defaults;
	}

	/** Only draw while this is true (e.g. while gliding). The editor always previews. */
	public ModuleHudElement when(Supplier<Boolean> condition) {
		this.condition = condition;
		return this;
	}

	/** Keep this panel at its own default spot instead of the auto-stacked column. */
	public ModuleHudElement fixed() {
		this.stacks = false;
		return this;
	}

	@Override
	public boolean autoStacks() {
		return stacks;
	}

	public ModuleHudElement background(Supplier<Boolean> background) {
		this.background = background;
		return this;
	}

	@Override
	protected boolean background() {
		return background.get();
	}

	@Override
	public boolean isEnabled() {
		return module.isEnabled();
	}

	@Override
	public boolean shouldRender() {
		return condition.get();
	}

	@Override
	public boolean showInEditor() {
		return module.isEnabled();
	}

	@Override
	public void toggle() {
		module.toggle();
	}

	@Override
	protected List<String[]> lines() {
		List<String[]> l = lines.get();
		return l.isEmpty() ? List.<String[]>of(row(displayName(), "-")) : l;
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return defaults.apply(screenW, screenH);
	}

	/** Shorthand for a label/value row. */
	public static String[] row(String label, Object value) {
		return new String[] {label, String.valueOf(value)};
	}
}
