package dev.coffeeclient.module;

import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.Notifications;
import dev.coffeeclient.util.ErrorGuard;
import dev.coffeeclient.util.History;
import dev.coffeeclient.util.WorldProjector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A toggleable feature. Subclasses declare settings in their constructor with {@link #setting} and
 * override {@link #onEnable}, {@link #onDisable} and {@link #onTick} as needed. Toggling is always
 * logged, so a module with no overrides is a valid stub.
 *
 * <p>Event handlers registered with {@link #listen} are attached to the bus only while the module is
 * enabled, so a disabled module costs nothing on hot events and its handlers need no
 * {@code isEnabled()} check.
 */
public abstract class Module {
	private final String name;
	private final List<String> aliases;
	private final Category category;
	private final String description;
	private final KeybindSetting keybind = new KeybindSetting("Keybind", "Key that toggles this module", KeybindSetting.UNBOUND);
	private final BooleanSetting shownInList = new BooleanSetting("In HUD list", "Show this module in the HUD module list while it is on", true);
	private final List<Setting<?>> settings = new ArrayList<>();
	private final Set<Setting<?>> shared = new HashSet<>();
	private boolean enabled;
	private boolean chatFeedback = true;
	private boolean toggleOnBindRelease = false;
	private boolean favorite = false;
	private boolean runInMainMenu = false;
	final String tickKey;
	public final String overlayKey;
	public final String worldKey;
	private final String infoKey;
	/** Time spent in this module, filled only while the performance view is open. */
	final ModuleProfiler.Stats perf = new ModuleProfiler.Stats();
	private final List<Listener<?>> listeners = new ArrayList<>();
	private boolean listening;
	/** When this module was last switched off (0 = never), for "recently active" checks. */
	private long disabledAt;

	private record Listener<E>(Class<E> type, int priority, Consumer<E> handler, String guardKey) {
		void attach(EventBus bus) {
			bus.subscribe(type, priority, handler, guardKey);
		}

		void detach(EventBus bus) {
			bus.unsubscribe(type, handler);
		}
	}

	protected Module(String name, Category category, String description) {
		this(name, List.of(), category, description);
	}

	/** @param aliases extra search terms (other names) that also match this module in the ClickGUI search */
	protected Module(String name, List<String> aliases, Category category, String description) {
		this.name = name;
		this.aliases = List.copyOf(aliases);
		this.category = category;
		this.description = description;
		this.tickKey = "tick:" + name;
		this.overlayKey = "overlay:" + name;
		this.worldKey = "world:" + name;
		this.infoKey = "info:" + name;
	}

	/** Declare a setting owned (and persisted) by this module. */
	protected <S extends Setting<?>> S setting(S setting) {
		settings.add(setting);
		return setting;
	}

	/** Declare a setting like {@link #setting}, listed under {@code group} in the config screen. */
	protected <S extends Setting<?>> S group(S setting, String group) {
		setting.group(group);
		return setting(setting);
	}

	/** Show a setting owned elsewhere (theme, client options) in this module's expansion without saving it twice. */
	protected <S extends Setting<?>> S shared(S setting) {
		settings.add(setting);
		shared.add(setting);
		return setting;
	}

	public final void toggle() {
		setEnabled(!enabled);
	}

	public final void setEnabled(boolean value) {
		if (isAction()) {
			if (value) {
				CoffeeClient.LOG.info("[Module] {} run", name);
				ErrorGuard.run("action:" + name, this::onAction);
			}
			return;
		}
		if (!isToggleable() || enabled == value) return;
		if (value && dev.coffeeclient.module.modules.client.ModuleBlocklist.blocks(this)) return;
		enabled = value;
		if (!value) disabledAt = System.currentTimeMillis();
		CoffeeClient.LOG.info("[Module] {} {}", name, value ? "enabled" : "disabled");
		if (chatFeedback && !silent && isChatFeedbackEnabled()) chatToggle(value);
		// Listeners go on before onEnable and come off after onDisable, so both hooks see them attached.
		if (value) {
			syncListeners();
			ErrorGuard.run("enable:" + name, this::onEnable);
		} else {
			ErrorGuard.run("disable:" + name, this::onDisable);
			syncListeners();
		}
		CoffeeClient.modules().onToggled(this);
		ConfigManager.markDirty();
	}

	/** Set while {@link #setEnabledSilently} runs; client thread only. */
	static boolean silent;

	/** {@link #setEnabled} without the chat line and toast (Panic switching many modules at once). */
	public final void setEnabledSilently(boolean value) {
		boolean was = silent;
		silent = true;
		try {
			setEnabled(value);
		} finally {
			silent = was;
		}
	}

	/** Apply a stored state without firing hooks or notifications (config load). */
	public final void restoreEnabled(boolean value) {
		if (!isToggleable() || isAction()) return;
		enabled = value;
		syncListeners();
	}

	/**
	 * Handles {@code type} events while this module is enabled. Call from the constructor or
	 * {@link #init}. Handlers run highest {@code priority} first ({@link EventBus#HIGH} etc.).
	 */
	protected final <E> void listen(Class<E> type, Consumer<? super E> handler) {
		listen(type, EventBus.NORMAL, handler);
	}

	protected final <E> void listen(Class<E> type, int priority, Consumer<? super E> handler) {
		Consumer<E> timed = event -> {
			if (!ModuleProfiler.active()) {
				handler.accept(event);
				return;
			}
			long start = System.nanoTime();
			try {
				handler.accept(event);
			} finally {
				ModuleProfiler.record(this, ModuleProfiler.Phase.EVENT, System.nanoTime() - start);
			}
		};
		Listener<E> listener = new Listener<>(type, priority, timed, "event:" + type.getSimpleName() + ":" + name);
		listeners.add(listener);
		EventBus bus = CoffeeClient.events();
		if (listening && bus != null) listener.attach(bus);
	}

	/** Attaches or detaches the {@link #listen} handlers to match the enabled state (no-op before the bus exists). */
	final void syncListeners() {
		EventBus bus = CoffeeClient.events();
		boolean want = enabled && bus != null;
		if (want == listening) return;
		listening = want;
		for (Listener<?> listener : listeners) {
			if (want) listener.attach(bus);
			else listener.detach(bus);
		}
	}

	/** This module's time in the last profiler window (all zero unless the performance view is open). */
	public ModuleProfiler.Stats profilerStats() {
		return perf;
	}

	/** True while enabled, or if switched off less than {@code ms} milliseconds ago. */
	public boolean activeWithin(long ms) {
		return enabled || disabledAt > 0 && System.currentTimeMillis() - disabledAt < ms;
	}

	/**
	 * Whether this module changes how the player moves or what movement the server sees, so a server
	 * setback while it was active is probably its fault. Movement modules by default.
	 */
	public boolean affectsMovement() {
		return category == Category.MOVEMENT;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/** Called at the end of every client tick while enabled. */
	public void onTick() {
	}

	/** Called once when the event bus is ready. Subscribe here; handlers should check {@link #isEnabled()}. */
	protected void init(EventBus bus) {
	}

	/**
	 * Action modules do something once when switched on (e.g. copy coordinates) and never stay enabled,
	 * so they work well on a keybind.
	 */
	public boolean isAction() {
		return false;
	}

	protected void onAction() {
	}

	/** Short status shown beside the name in the HUD module list (mode, target, count), or null. */
	public String infoString() {
		return null;
	}

	/** {@link #infoString()} for the HUD: a throwing override is reported once and shows nothing. */
	public final String safeInfoString() {
		try {
			String info = infoString();
			return info == null || info.isEmpty() ? null : info;
		} catch (Throwable t) {
			ErrorGuard.report(infoKey, t);
			return null;
		}
	}

	/** HUD elements owned by this module (shown while it is enabled, movable in the HUD editor). */
	public List<HudElement> hudElements() {
		return List.of();
	}

	/** Draw world-anchored overlays on the HUD while enabled (use the projector for positions). */
	public void renderOverlay(Ui ui, WorldProjector projector) {
	}

	/**
	 * Submit world-space geometry while enabled (called once per frame at the end of the level's
	 * feature submission; drawn after the level itself). Prefer this over {@link #renderOverlay} for
	 * boxes and large shapes: building them in world space lets the GPU clip and depth-test them,
	 * instead of projecting corners onto the HUD where a corner near the view plane smears across the
	 * screen. Positions are camera-relative: push and pop the pose you use.
	 */
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
	}

	protected static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Short on-screen toast tagged with this module's name. */
	protected void notify(String text) {
		if (!isToastNotificationsEnabled()) return;
		CoffeeClient.hud().toast(name + ": " + text, CoffeeClient.theme().accent);
		History.add(name, text);
	}

	private boolean isChatFeedbackEnabled() {
		Notifications notifs = CoffeeClient.modules().get(Notifications.class);
		return notifs != null && notifs.chatFeedback.get();
	}

	private boolean isToastNotificationsEnabled() {
		Notifications notifs = CoffeeClient.modules().get(Notifications.class);
		return notifs != null && notifs.toastNotifications.get();
	}

	/** "[Coffee] Name " in the toggle-line style: accent tag, white module name. */
	private Component chatPrefix() {
		return Component.literal("[Coffee] ").withColor(CoffeeClient.theme().lightAccent() & 0xFFFFFF)
			.append(Component.literal(name + " ").withColor(0xFFFFFF));
	}

	/**
	 * Client-side chat line (never sent to the server): "[Coffee] Name message", formatted like the
	 * toggle line. Text without a colour of its own is grey, as in "is now".
	 */
	protected void chat(Component text) {
		Minecraft mc = mc();
		if (mc.player == null) return;
		Component body = text.getStyle().getColor() == null ? text.copy().withStyle(st -> st.withColor(0xAAAAAA)) : text;
		mc.gui.hud.getChat().addClientSystemMessage(chatPrefix().copy().append(body));
		History.add(name, body.getString());
	}

	/** Informational chat line tagged with this module's name. */
	protected void info(String text) {
		chat(Component.literal(text));
	}

	/** The "[Coffee] Name is now ON/OFF" line shown when a module is toggled. */
	private void chatToggle(boolean on) {
		Minecraft mc = mc();
		if (mc.player == null) return;
		mc.gui.hud.getChat().addClientSystemMessage(chatPrefix().copy()
			.append(Component.literal("is now ").withColor(0xAAAAAA))
			.append(Component.literal(on ? "ON" : "OFF").withColor(on ? 0x55FF55 : 0xFF5555)));
		History.add(name, "is now " + (on ? "ON" : "OFF"));
	}

	/** Modules with many options open a full config window from the ClickGUI instead of expanding inline. */
	public boolean hasConfigScreen() {
		return false;
	}

	/** The window the ClickGUI opens for this module when {@link #hasConfigScreen()} is true. */
	public net.minecraft.client.gui.screens.Screen configScreen(net.minecraft.client.gui.screens.Screen parent) {
		return new dev.coffeeclient.gui.screen.ModuleScreen(parent, this);
	}

	/** A selection list shown beside the settings in the config window (e.g. which items to highlight). */
	public dev.coffeeclient.setting.Setting<?> embeddedList() {
		return null;
	}

	/** Settings-only modules (e.g. Interface) expand instead of toggling. */
	public boolean isToggleable() {
		return true;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public String name() {
		return name;
	}

	public List<String> aliases() {
		return aliases;
	}

	/** True when the query matches this module's name or one of its aliases (case-insensitive). */
	public boolean matches(String query) {
		String q = query.toLowerCase(java.util.Locale.ROOT);
		if (name.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		for (String alias : aliases) if (alias.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		return false;
	}

	/** Whether toggling prints a chat line in addition to the toast. */
	public boolean chatFeedback() {
		return chatFeedback;
	}

	public Module chatFeedback(boolean value) {
		this.chatFeedback = value;
		return this;
	}

	/** Toggle on key release instead of key press (avoids re-toggling on auto-repeat). */
	public boolean toggleOnBindRelease() {
		return toggleOnBindRelease;
	}

	public Module toggleOnBindRelease(boolean value) {
		this.toggleOnBindRelease = value;
		return this;
	}

	/** Favourites are pinned to the top of the ClickGUI. */
	public boolean favorite() {
		return favorite;
	}

	public Module favorite(boolean value) {
		this.favorite = value;
		ConfigManager.markDirty();
		return this;
	}

	/** Apply a stored favourite flag without marking the config dirty (load). */
	public void restoreFavorite(boolean value) {
		this.favorite = value;
	}

	/** Keep ticking even in the main menu (no world loaded). */
	public boolean runInMainMenu() {
		return runInMainMenu;
	}

	public Module runInMainMenu(boolean value) {
		this.runInMainMenu = value;
		return this;
	}

	public Category category() {
		return category;
	}

	public String description() {
		return description;
	}

	public KeybindSetting keybind() {
		return keybind;
	}

	/** Whether the HUD module list shows this module while it is on (saved as a module flag, like the keybind). */
	public BooleanSetting shownInList() {
		return shownInList;
	}

	/** Modules that can appear in the HUD module list at all, and so get the show/hide option. */
	public boolean listable() {
		return isToggleable() && !isAction() && category != Category.CLIENT;
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	/** Settings persisted under this module in the config. */
	public List<Setting<?>> ownSettings() {
		return settings.stream().filter(s -> !shared.contains(s)).toList();
	}
}
