package dev.coffeeclient.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.misc.Macros;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.JsonFiles;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Holds the macros, starts them from their triggers, and persists them to {@code macros.json}.
 * Triggers only fire while the Macros module is on; running macros keep going until they finish or
 * are stopped (Panic or turning Macros off stops them all).
 */
public final class MacroManager {
	/** An automatic trigger cannot restart the same macro sooner than this after it ended. */
	private static final long RETRIGGER_COOLDOWN_MS = 1000;
	private static final int FORMAT = 3;

	private final Path file;
	private final List<Macro> macros = new ArrayList<>();
	private final MacroRunner runner = new MacroRunner(this);
	private final Map<Macro, Long> lastEnded = new HashMap<>();
	/** Chat triggers waiting out their delay: the macro, the tick it is due, and a reply to send (or null). */
	private record Pending(Macro macro, long at, String reply) {
	}

	private final List<Pending> pending = new ArrayList<>();
	private final Map<Macro, Long> chatFiredAt = new HashMap<>();
	/** Where a sender's name sits in the usual chat and whisper formats. */
	private static final Pattern[] SENDER = {
		Pattern.compile("^<([A-Za-z0-9_]{3,16})>"), Pattern.compile("^([A-Za-z0-9_]{3,16}) whispers"),
		Pattern.compile("^\\[([A-Za-z0-9_]{3,16}) -> (?:me|you)\\]"), Pattern.compile("^From ([A-Za-z0-9_]{3,16})"),
		Pattern.compile("^([A-Za-z0-9_]{3,16}) ?[:»>]")};
	private final Map<Macro, Integer> nextInterval = new HashMap<>();
	private int revision;
	private int tick;
	private boolean wasInWorld;
	private int joinTick = -1;
	private boolean dead;
	private @Nullable ResourceKey<Level> dimension;
	private @Nullable Screen screen;
	/** Cleared on the IO thread when the save is handed to the client thread, so a hand-off a disconnect drops cannot block later saves. */
	private volatile boolean saveQueued;

	public MacroManager(Path file) {
		this.file = file;
	}

	public void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, e -> tick());
		bus.subscribe(Events.ChatReceived.class, e -> onChat(e.message().getString()));
		bus.subscribe(Events.GameLeft.class, e -> runner.stopAll());
		bus.subscribe(ModuleManager.ModuleToggled.class, e -> {
			if (e.module() instanceof Macros && !e.module().isEnabled()) runner.stopAll();
			else onModuleToggled(e.module());
		});
	}

	public List<Macro> all() {
		return macros;
	}

	/** Changes on every edit, so screens know to rebuild. */
	public int revision() {
		return revision;
	}

	public Macro byName(String name) {
		for (Macro m : macros) if (m.name.equalsIgnoreCase(name)) return m;
		return null;
	}

	public Macro add(String name) {
		Macro macro = new Macro(unique(name));
		macro.steps.add(new MacroStep(MacroStep.Type.CHAT));
		macros.add(macro);
		changed();
		return macro;
	}

	public Macro duplicate(Macro source) {
		Macro copy = Macro.fromJson(source.toJson());
		copy.name = unique(source.name + " copy");
		copy.setTriggerValue("key", "-1");
		macros.add(copy);
		changed();
		return copy;
	}

	public void remove(Macro macro) {
		runner.stop(macro);
		if (macros.remove(macro)) changed();
	}

	public void rename(Macro macro, String name) {
		if (name == null || name.isBlank() || name.equals(macro.name)) return;
		macro.name = byName(name) != null ? unique(name) : name.trim();
		changed();
	}

	/** Persist after an edit (debounced: typing in a field changes it on every key). */
	public void changed() {
		revision++;
		if (saveQueued) return;
		saveQueued = true;
		Async.ioLater(() -> {
			saveQueued = false;
			Minecraft.getInstance().execute(this::saveAsync);
		}, 750);
	}

	// ---- running ----

	public boolean isRunning(Macro macro) {
		return runner.isRunning(macro);
	}

	/** Starts {@code macro}, or stops it if it runs and stops on re-trigger. */
	public void trigger(Macro macro) {
		if (runner.isRunning(macro)) {
			if (macro.retriggerStops) runner.stop(macro);
			return;
		}
		runner.start(macro);
	}

	public void run(Macro macro) {
		runner.start(macro);
	}

	public void stop(Macro macro) {
		runner.stop(macro);
	}

	public void stopAll() {
		runner.stopAll();
	}

	void finished(Macro macro) {
		lastEnded.put(macro, System.currentTimeMillis());
	}

	private boolean active() {
		return ModuleManager.on(Macros.class);
	}

	/** Automatic triggers: not while it runs, and not again right after it ended. */
	private void autoStart(Macro macro) {
		if (runner.isRunning(macro)) return;
		Long ended = lastEnded.get(macro);
		if (ended != null && System.currentTimeMillis() - ended < RETRIGGER_COOLDOWN_MS) return;
		runner.start(macro);
	}

	private void tick() {
		tick++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		boolean inWorld = player != null && mc.level != null;
		boolean nowDead = inWorld && player.isDeadOrDying();
		ResourceKey<Level> nowDimension = inWorld ? mc.level.dimension() : null;
		Screen nowScreen = inWorld ? mc.gui.screen() : null;
		if (active() && inWorld) {
			if (!wasInWorld) joinTick = tick;
			boolean dimensionChanged = wasInWorld && dimension != null && !dimension.equals(nowDimension);
			for (Macro m : macros) {
				if (!m.enabled) continue;
				switch (m.trigger) {
					case INTERVAL -> {
						int period = Math.max(1, (int) Math.round(MacroCondition.number(m.triggerValue("seconds")) * 20));
						int due = nextInterval.getOrDefault(m, tick + period);
						if (tick >= due) {
							autoStart(m);
							due = tick + period;
						}
						nextInterval.put(m, due);
					}
					case JOIN -> {
						if (joinTick >= 0 && tick - joinTick == Math.max(0, (int) MacroCondition.number(m.triggerValue("delay")))) autoStart(m);
					}
					case LOW_HEALTH -> {
						if (lowHealthEdge(player, m)) autoStart(m);
					}
					case DEATH -> {
						if (nowDead && !dead) autoStart(m);
					}
					case RESPAWN -> {
						if (dead && !nowDead) autoStart(m);
					}
					case DIMENSION_CHANGE -> {
						String to = m.triggerValue("to").toLowerCase(Locale.ROOT);
						if (dimensionChanged && ("any".equals(to) || nowDimension.identifier().getPath().contains(to))) autoStart(m);
					}
					case SCREEN_OPEN -> {
						if (nowScreen != null && nowScreen != screen && screenMatches(nowScreen, m.triggerValue("screen"))) autoStart(m);
					}
					default -> {
					}
				}
			}
		}
		dead = nowDead;
		dimension = nowDimension;
		screen = nowScreen;
		wasInWorld = inWorld;
		for (int i = pending.size() - 1; i >= 0; i--) {
			Pending p = pending.get(i);
			if (tick < p.at()) continue;
			pending.remove(i);
			if (p.reply() != null && player != null) player.connection.sendCommand(p.reply());
			if (!p.macro().steps.isEmpty()) autoStart(p.macro());
		}
		runner.tick();
	}

	/** Container: chests, furnaces and other block or entity menus; Inventory: your own (survival or creative). */
	private static boolean screenMatches(Screen screen, String kind) {
		boolean inventory = screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
		return switch (kind) {
			case "Container" -> screen instanceof AbstractContainerScreen<?> && !inventory;
			case "Inventory" -> inventory;
			case "Chat" -> screen instanceof ChatScreen;
			default -> true;
		};
	}

	private void onModuleToggled(Module module) {
		if (!active()) return;
		boolean on = module.isEnabled();
		for (Macro m : macros) {
			if (!m.enabled || m.trigger != MacroTrigger.MODULE_TOGGLED || !module.name().equalsIgnoreCase(m.triggerValue("module").trim())) continue;
			String when = m.triggerValue("when");
			if ("Either".equals(when) || "Turned on".equals(when) == on) autoStart(m);
		}
	}

	private boolean lowHealthEdge(LocalPlayer player, Macro m) {
		double threshold = MacroCondition.number(m.triggerValue("health"));
		return player.getHealth() <= threshold && !wasBelow(m, threshold, player);
	}

	/** Remembers per macro whether health was already at or below its threshold, so it fires once per drop. */
	private final Map<Macro, Boolean> below = new HashMap<>();

	private boolean wasBelow(Macro m, double threshold, LocalPlayer player) {
		boolean was = below.getOrDefault(m, false);
		below.put(m, player.getHealth() <= threshold);
		return was;
	}

	private void onChat(String text) {
		runner.onChat(text);
		if (!active()) return;
		long now = System.currentTimeMillis();
		for (Macro m : macros) {
			if (!m.enabled || m.trigger != MacroTrigger.CHAT || !chatMatches(text, m.triggerValue("text"), m.triggerValue("match"))) continue;
			Long fired = chatFiredAt.get(m);
			if (fired != null && now - fired < MacroCondition.number(m.triggerValue("cooldown")) * 1000) continue;
			chatFiredAt.put(m, now);
			int jitter = Math.max(0, (int) MacroCondition.number(m.triggerValue("jitter")));
			long at = tick + Math.max(0, (int) MacroCondition.number(m.triggerValue("delay"))) + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
			String reply = m.triggerValue("reply").trim(), sender = sender(text);
			pending.add(new Pending(m, at, reply.isEmpty() || sender == null ? null : "msg " + sender + " " + reply));
		}
	}

	/** Contains and Exact ignore case; Regex is searched anywhere (a bad pattern never matches). */
	static boolean chatMatches(String text, String needle, String mode) {
		if (needle.isBlank()) return false;
		return switch (mode) {
			case "Exact" -> text.trim().equalsIgnoreCase(needle.trim());
			case "Regex" -> {
				try {
					yield Pattern.compile(needle, Pattern.CASE_INSENSITIVE).matcher(text).find();
				} catch (PatternSyntaxException e) {
					yield false;
				}
			}
			default -> text.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
		};
	}

	/** The player who sent a chat line, from the common formats, or null. */
	static String sender(String text) {
		for (Pattern p : SENDER) {
			Matcher m = p.matcher(text.trim());
			if (m.find()) return m.group(1);
		}
		return null;
	}

	/** Key events from the keyboard mixin. */
	public void onKey(Events.KeyPress e) {
		if (e.key() < 0) return;
		onInput(e.key(), e.action(), e.screenOpen());
	}

	/** Mouse buttons in game. */
	public void onMouseClick(Events.MouseClick e) {
		onInput(KeyNames.mouseKey(e.button), e.action, false);
	}

	private void onInput(int key, int action, boolean screenOpen) {
		if (!active()) return;
		for (Macro m : macros) {
			if (!m.enabled || !m.trigger.isKey() || m.key() != key) continue;
			if (screenOpen && !m.inScreens) continue;
			if (m.trigger == MacroTrigger.KEY_PRESS) {
				if (action == 1) trigger(m);
			} else if (action == 1) {
				runner.start(m);
			} else if (action == 0) {
				runner.stop(m);
			}
		}
	}

	private String unique(String base) {
		String name = (base == null || base.isBlank()) ? "Macro" : base.trim();
		String candidate = name;
		int i = 2;
		while (byName(candidate) != null) candidate = name + " " + i++;
		return candidate;
	}

	// ---- persistence ----

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root == null) {
			// First run: no macros file yet, so start with the example. A file that will not parse is left alone.
			if (!Files.exists(file)) {
				macros.addAll(examples());
				revision++;
				saveNow();
			}
			return;
		}
		JsonElement arr = root.get("macros");
		if (arr == null || !arr.isJsonArray()) return;
		int version = root.has("version") ? root.get("version").getAsInt() : 1;
		// Version 1 files (the 26.3 build) used SDL key codes.
		KeybindSetting.readingLegacyCodes(version < 2);
		try {
			for (JsonElement el : arr.getAsJsonArray()) {
				try {
					JsonObject o = el.getAsJsonObject();
					macros.add(version < 3 ? fromOldFormat(o) : Macro.fromJson(o));
				} catch (RuntimeException e) {
					CoffeeClient.LOG.warn("Skipping malformed macro: {}", e.toString());
				}
			}
		} finally {
			KeybindSetting.readingLegacyCodes(false);
		}
		revision++;
		if (version < FORMAT) saveNow();
	}

	/** Name of the helper the example starts with a Run macro step. */
	static final String EXAMPLE_HELPER = "Example look-around";

	/**
	 * The macros every new install starts with. "Example" uses every kind of step: bind its key (or
	 * click Run) to try it, and press the key again to stop it. It waits for menus to close, stops on
	 * low health, branches on what you hold and where you are, jumps in a Repeat block, and runs
	 * {@link #EXAMPLE_HELPER} and waits for it to finish.
	 */
	static List<Macro> examples() {
		Macro m = new Macro("Example");
		m.trigger = MacroTrigger.KEY_PRESS;
		m.steps.add(step(MacroStep.Type.NOTIFY, "text", "Started: press its key again to stop"));
		m.steps.add(step(MacroStep.Type.WAIT_UNTIL, "condition", "SCREEN_CLOSED", "timeout", "200"));
		m.steps.add(step(MacroStep.Type.STOP_IF, "condition", "SCREEN_OPEN"));
		m.steps.add(step(MacroStep.Type.STOP_IF, "condition", "HEALTH_BELOW", "arg", "6"));
		m.steps.add(step(MacroStep.Type.IF, "condition", "HOLDING", "arg", "sword"));
		m.steps.add(step(MacroStep.Type.LOCAL, "text", "Sword in hand, {player}"));
		m.steps.add(step(MacroStep.Type.ELSE_IF, "condition", "HAS_ITEM", "arg", "golden_apple"));
		m.steps.add(step(MacroStep.Type.SELECT_ITEM, "item", "golden_apple", "from", "Hotbar"));
		m.steps.add(step(MacroStep.Type.ELSE));
		m.steps.add(step(MacroStep.Type.SLOT, "slot", "1"));
		m.steps.add(step(MacroStep.Type.END));
		m.steps.add(step(MacroStep.Type.IF, "condition", "DIMENSION", "arg", "nether"));
		m.steps.add(step(MacroStep.Type.NOTIFY, "text", "In the Nether: mind the lava"));
		m.steps.add(step(MacroStep.Type.ELSE));
		m.steps.add(step(MacroStep.Type.CHAT, "text", "Hello {player}, checking in"));
		m.steps.add(step(MacroStep.Type.END));
		m.steps.add(step(MacroStep.Type.MODULE, "module", "TickMonitor", "action", "Enable"));
		m.steps.add(step(MacroStep.Type.REPEAT, "mode", "Times", "count", "3"));
		m.steps.add(step(MacroStep.Type.KEY, "key", "key.jump", "mode", "Tap"));
		m.steps.add(step(MacroStep.Type.WAIT_UNTIL, "condition", "ON_GROUND", "timeout", "40"));
		m.steps.add(step(MacroStep.Type.WAIT, "ticks", "4"));
		m.steps.add(step(MacroStep.Type.END));
		m.steps.add(step(MacroStep.Type.RUN_MACRO, "macro", EXAMPLE_HELPER));
		m.steps.add(step(MacroStep.Type.WAIT_UNTIL, "not", "Not", "condition", "MACRO_RUNNING", "arg", EXAMPLE_HELPER, "timeout", "200"));
		m.steps.add(step(MacroStep.Type.MODULE, "module", "TickMonitor", "action", "Disable"));
		m.steps.add(step(MacroStep.Type.IF, "not", "Not", "condition", "PLAYER_NEAR", "arg", "32"));
		m.steps.add(step(MacroStep.Type.LOCAL, "text", "Nobody within 32 blocks"));
		m.steps.add(step(MacroStep.Type.END));
		m.steps.add(step(MacroStep.Type.NOTIFY, "text", "Finished"));

		Macro helper = new Macro(EXAMPLE_HELPER);
		helper.trigger = MacroTrigger.MANUAL;
		helper.steps.add(step(MacroStep.Type.REPEAT, "mode", "Times", "count", "4"));
		helper.steps.add(step(MacroStep.Type.LOOK, "mode", "Turn by", "yaw", "90", "pitch", "0"));
		helper.steps.add(step(MacroStep.Type.WAIT, "ticks", "5"));
		helper.steps.add(step(MacroStep.Type.END));
		return List.of(m, helper);
	}

	private static MacroStep step(MacroStep.Type type, String... keyValues) {
		MacroStep step = new MacroStep(type);
		for (int i = 0; i < keyValues.length; i += 2) step.set(keyValues[i], keyValues[i + 1]);
		return step;
	}

	/** Old macros were a key plus chat lines: a key-press trigger with one Say step per line. */
	private static Macro fromOldFormat(JsonObject o) {
		Macro m = new Macro(o.has("name") ? o.get("name").getAsString() : "Macro");
		if (o.has("key")) {
			KeybindSetting key = new KeybindSetting("Key", "", KeybindSetting.UNBOUND);
			key.fromJson(o.get("key"));
			m.setTriggerValue("key", Integer.toString(key.key()));
		}
		if (o.has("messages")) {
			for (JsonElement line : o.getAsJsonArray("messages")) {
				MacroStep step = new MacroStep(MacroStep.Type.CHAT);
				step.set("text", line.getAsString());
				m.steps.add(step);
			}
		}
		m.retriggerStops = false;
		return m;
	}

	/** Writes macros.json now (also called on shutdown). */
	public void saveNow() {
		write(tree());
	}

	/** The tree is built on the client thread (it reads the live macros); the write happens on the IO thread. */
	private void saveAsync() {
		JsonObject root = tree();
		Async.io(() -> write(root));
	}

	private JsonObject tree() {
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT);
		JsonArray arr = new JsonArray();
		for (Macro macro : macros) arr.add(macro.toJson());
		root.add("macros", arr);
		return root;
	}

	private void write(JsonObject root) {
		try {
			JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("Could not save macros: {}", e.toString());
		}
	}
}
