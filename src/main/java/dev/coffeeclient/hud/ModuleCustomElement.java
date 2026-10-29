package dev.coffeeclient.hud;

import dev.coffeeclient.module.Module;

/** Base for custom-drawn HUD panels owned by a module (visible and listed in the editor while it is on). */
public abstract class ModuleCustomElement extends HudElement {
	protected final Module module;

	protected ModuleCustomElement(Module module, String id, String displayName) {
		super(id, displayName);
		this.module = module;
	}

	@Override
	public boolean isEnabled() {
		return module.isEnabled();
	}

	@Override
	public boolean showInEditor() {
		return module.isEnabled();
	}

	@Override
	public boolean autoStacks() {
		return true;
	}

	@Override
	public void toggle() {
		module.toggle();
	}
}
