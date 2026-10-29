package dev.coffeeclient.gui;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Tween;
import dev.coffeeclient.gui.fx.Backdrop;
import dev.coffeeclient.gui.fx.FxTextures;
import dev.coffeeclient.gui.fx.ScreenTransitions;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Container;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Tooltip;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/**
 * Base for every client screen: hosts a widget tree, routes input to it (overlay → focused → tree),
 * draws the themed backdrop and tooltips, and returns to the parent screen on close.
 */
public abstract class CoffeeScreen extends Screen {
	protected final @Nullable Screen parent;
	protected final Container root = new Container();
	private float builtScale = 1f;
	private boolean mouseHeld;
	private static final float OPEN_SECONDS = 0.42f;
	private static final float CLOSE_SECONDS = 0.24f;
	private final Tween openTween = new Tween();
	private final Tween closeTween = new Tween();
	private boolean closing;

	protected CoffeeScreen(String title, @Nullable Screen parent) {
		super(Component.literal(title));
		this.parent = parent;
	}

	protected Theme theme() {
		return CoffeeClient.theme();
	}

	/** Build widgets for the current {@link #width}/{@link #height}. Called on open and on resize. */
	protected abstract void build();

	/** Key mapping that toggles this screen closed, if any. */
	protected @Nullable KeyMapping toggleKey() {
		return null;
	}

	/**
	 * Content scale (the Interface scale option), reduced if needed so the layout keeps at least
	 * 320x200 units. Layout uses {@link #layoutWidth()} / {@link #layoutHeight()}; drawing is scaled once
	 * through the pose and mouse input is mapped back with {@link #toLayout}.
	 */
	protected float uiScale() {
		float wanted = CoffeeClient.options().interfaceScale.floatValue();
		return Math.max(0.25f, Math.min(wanted, Math.min(width / 320f, height / 200f)));
	}

	protected int layoutWidth() {
		return (int) Math.floor(width / builtScale);
	}

	protected int layoutHeight() {
		return (int) Math.floor(height / builtScale);
	}

	protected double toLayout(double v) {
		return v / builtScale;
	}

	// ---- movable window ----

	/** Window offset in layout units; widgets stay put and the whole drawing is shifted by this. */
	protected float windowX, windowY;
	private boolean windowDragging;

	/** True where a left-press starts dragging the window (its title bar), in window-local layout coordinates. */
	protected boolean windowHandle(double mx, double my) {
		return false;
	}

	/** Keep the dragged window reachable; called after every move. */
	protected void clampWindow() {
	}

	private double toLayoutX(double v) {
		return toLayout(v) - windowX;
	}

	private double toLayoutY(double v) {
		return toLayout(v) - windowY;
	}

	@Override
	protected void init() {
		UiState.reset();
		root.clear();
		builtScale = uiScale();
		root.setBounds(0, 0, layoutWidth(), layoutHeight());
		UiState.setScreenSize(layoutWidth(), layoutHeight());
		ErrorGuard.run("build:" + getClass().getSimpleName(), this::build);
		minecraft.textInputManager().startTextInput();
		if (!openTween.started()) {
			openTween.start(OPEN_SECONDS / theme().animationSpeed.floatValue());
			UiSounds.open();
		}
	}

	private boolean animated() {
		return theme().motion() && theme().transition.get() != Theme.Transition.NONE;
	}

	/** Raw transition progress: 0 → 1 while opening, 1 → 0 while closing. */
	protected float transition() {
		if (!animated()) return 1f;
		if (closing) return 1f - closeTween.progress();
		return openTween.progress();
	}

	/**
	 * Standard header: accent title on the left, Back button on the right.
	 *
	 * @return the y just below the header
	 */
	protected int addHeader(int x, int y, int w, String title) {
		root.add(new Label(title, Label.Style.TITLE)).setBounds(x, y, Draw.width(title) + 2, 14);
		root.add(new Button("Back", this::onClose)).setBounds(x + w - 56, y - 1, 56, 16);
		return y + 19;
	}

	/**
	 * The largest 4:3 frame centred on the screen, as {@code {x, y, width, height}}. The width is capped
	 * at {@code capW} (then trimmed 10%) and the height follows for a 4:3 ratio, so windows never fill the
	 * screen. Shrinks to fit when the screen is too short.
	 */
	protected int[] centred43(int capW) {
		int w = layoutWidth(), h = layoutHeight();
		int available = w - 20;
		int fw = Math.min(available, capW) * 9 / 10;
		int fh = fw * 3 / 4;
		int maxH = h - 12;
		if (fh > maxH) {
			fh = Math.max(120, maxH);
			fw = Math.min(available, fh * 4 / 3);
			fh = fw * 3 / 4;
		}
		return new int[]{(w - fw) / 2, (h - fh) / 2, fw, fh};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Draw.resetAlpha();
		boolean menu = minecraft.level == null;
		if (menu) {
			// title-screen panorama, with the theme's effects over it
			super.extractBackground(g, mouseX, mouseY, a);
		} else if (theme().blur.get()) {
			ErrorGuard.run("screen-blur", () -> extractBlurredBackground(g));
		}
		Theme t = theme();
		Draw.pushAlpha(Ease.OUT_CUBIC.apply(transition()) * (menu ? 0.6f : 1f));
		try {
			Draw.gradientV(g, 0, 0, width, height, t.background, t.backgroundBottom);
			if (t.motion()) Backdrop.draw(g, t, t.backdrop.get(), width, height, t.backdropStrength.floatValue(), mouseX, mouseY);
			FxTextures.vignette(g, 0, 0, width, height, t.vignette.floatValue());
		} finally {
			Draw.popAlpha();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (closing && (closeTween.done() || !animated())) {
			finishClose();
			return;
		}
		// scale changed (e.g. from a settings control): rebuild once the mouse is released
		if (uiScale() != builtScale && !mouseHeld) rebuildWidgets();
		ErrorGuard.run("render:" + getClass().getSimpleName(), () -> {
			float scale = builtScale;
			int mx = (int) Math.floor(toLayoutX(mouseX)), my = (int) Math.floor(toLayoutY(mouseY));
			if (scale != 1f) {
				g.pose().pushMatrix();
				g.pose().scale(scale, scale);
			}
			g.pose().pushMatrix();
			g.pose().translate(windowX, windowY);
			Draw.textShadow(!theme().light);
			try {
				Ui ui = new Ui(g, mx, my);
				ui.screenWidth = layoutWidth();
				ui.screenHeight = layoutHeight();
				Widget overlay = UiState.overlay();
				ui.hoverAllowed = !closing && (overlay == null || !overlay.contains(mx, my));
				float p = transition();
				root.stagger(theme().motion() ? p : 1f);
				ScreenTransitions.draw(ui, theme().transition.get(), p, closing, theme().motionStyle.get().ease, () -> {
					renderContent(ui);
					if (overlay != null) {
						ui.clearTooltip();
						g.nextStratum();
						ui.hoverAllowed = !closing;
						overlay.render(ui);
					}
				});
				if (!closing) Tooltip.draw(ui, ui.pendingTooltip(), mx, my);
			} finally {
				Draw.textShadow(true);
				g.pose().popMatrix();
				if (scale != 1f) g.pose().popMatrix();
			}
		});
	}

	/** Top-level widgets (read-only use; e.g. tooling). */
	public List<Widget> rootChildren() {
		return root.children();
	}

	/** Draw the screen. Default draws the widget tree. */
	protected void renderContent(Ui ui) {
		root.render(ui);
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) return true;
		mouseHeld = true;
		double mx = toLayoutX(event.x()), my = toLayoutY(event.y());
		int button = event.button();
		Widget overlay = UiState.overlay();
		if (overlay != null) {
			if (overlay.contains(mx, my)) overlay.mouseClicked(mx, my, button);
			else UiState.closeOverlay();
			return true;
		}
		Widget focused = UiState.focused();
		if (focused != null && focused.mouseBinding(mx, my, button)) return true;
		if (focused != null && !focused.contains(mx, my)) UiState.clearFocus();
		if (root.mouseClicked(mx, my, button)) return true;
		if (button == Widget.LEFT && windowHandle(mx, my)) {
			windowDragging = true;
			return true;
		}
		return onBackgroundClick(mx, my, button);
	}

	protected boolean onBackgroundClick(double mx, double my, int button) {
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		mouseHeld = false;
		windowDragging = false;
		return root.mouseReleased(toLayoutX(event.x()), toLayoutY(event.y()), event.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (UiState.overlay() != null) return true;
		if (windowDragging) {
			windowX += (float) toLayout(dx);
			windowY += (float) toLayout(dy);
			clampWindow();
			return true;
		}
		return root.mouseDragged(toLayoutX(event.x()), toLayoutY(event.y()), event.button(), toLayout(dx), toLayout(dy));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (UiState.overlay() != null) {
			UiState.closeOverlay();
			return true;
		}
		return root.mouseScrolled(toLayoutX(mx), toLayoutY(my), scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (closing) {
			if (key == InputConstants.KEY_ESCAPE) finishClose();
			return true;
		}
		Widget focused = UiState.focused();
		if (focused != null && focused.keyPressed(key, event.scancode(), event.modifiers())) return true;
		if (UiState.overlay() != null && key == InputConstants.KEY_ESCAPE) {
			UiState.closeOverlay();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE) {
			onClose();
			return true;
		}
		KeyMapping toggle = toggleKey();
		if (toggle != null && toggle.matches(event)) {
			onClose();
			return true;
		}
		if (root.keyPressed(key, event.scancode(), event.modifiers())) return true;
		return onKey(key, event.modifiers());
	}

	/** Unhandled key press hook for screen shortcuts. */
	protected boolean onKey(int key, int modifiers) {
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		Widget focused = UiState.focused();
		if (focused != null) return focused.charTyped(event.codepointAsString());
		return false;
	}

	/** Plays the close transition, then returns to the parent screen. */
	@Override
	public void onClose() {
		if (closing) {
			finishClose();
			return;
		}
		if (!animated()) {
			finishClose();
			return;
		}
		closing = true;
		UiState.reset();
		closeTween.start(CLOSE_SECONDS / theme().animationSpeed.floatValue());
	}

	private void finishClose() {
		closing = false;
		closeTween.stop();
		UiState.reset();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		// coming back to this screen later plays the open transition again
		openTween.stop();
		closing = false;
		UiState.reset();
		minecraft.textInputManager().stopTextInput();
		super.removed();
	}
}
