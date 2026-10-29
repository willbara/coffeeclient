package dev.coffeeclient.module;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.modules.client.ConnectionLog;
import dev.coffeeclient.module.modules.client.DiscordPresence;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.module.modules.client.ModuleBlocklist;
import dev.coffeeclient.module.modules.client.Interface;
import dev.coffeeclient.module.modules.client.MainMenu;
import dev.coffeeclient.module.modules.client.Notifier;
import dev.coffeeclient.module.modules.client.Notifications;
import dev.coffeeclient.module.modules.client.PlayerProtect;
import dev.coffeeclient.module.modules.client.SkinChanger;
import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.module.modules.combat.*;
import dev.coffeeclient.module.modules.cosmetics.*;
import dev.coffeeclient.module.modules.exploit.*;
import dev.coffeeclient.module.modules.misc.*;
import dev.coffeeclient.module.modules.movement.*;
import dev.coffeeclient.module.modules.player.*;
import dev.coffeeclient.module.modules.render.*;
import dev.coffeeclient.module.modules.world.*;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.ErrorGuard;
import dev.coffeeclient.util.KeyNames;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns every module. To add one, write the class and add an instance to {@link #createModules()}. */
public final class ModuleManager {
	/** A module tick taking at least this long is logged (once every few seconds). */
	private static final long SLOW_TICK_MS = 20;
	private final List<Module> modules = createModules();
	private EventBus bus;
	private final Map<Class<?>, Module> byClass = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Long> slowWarned = new HashMap<>();

	public ModuleManager() {
		for (Module m : modules) byClass.put(m.getClass(), m);
	}

	/** The single registration list. Order within a category is the ClickGUI order (sorted by name). */
	private static List<Module> createModules() {
		return List.of(
			// Combat
			new HitSounds(), new ArmorAlert(), new DamageNumbers(), new KillTracker(), new AutoTotem(), new Offhand(), new AutoArmor(), new AutoLog(), new KillAura(), new AimAssist(), new Backtrack(), new FakeLag(), new TargetStrafe(), new AntiAim(), new Hitbox(), new TickBase(), new CrystalAura(), new Surround(), new HoleFill(), new AnchorAura(), new AutoTrap(), new AutoWeb(), new AutoCity(), new Burrow(), new CevBreaker(), new DTap(), new Criticals(), new SuperKnockback(), new AutoWeapon(), new AutoMace(), new WindCharge(), new BowSpam(), 
			// Misc
			new ChatTimestamps(), new ChatTweaks(), new DeathCoords(), new AfkTimer(), new SessionStats(), new ScreenshotTools(),
			new MessageLogger(), new ClipboardCoords(), new BookCopier(),
			new Spam(), new SoundBlocker(), new Friends(), new AutoDisable(), new AutoReconnect(), new MessageAura(),
			new Macros(), new NoChatReports(), new Panic(), new ChatEffects(),
			// Render
			new Brightness(), new SelectOutline(), new Measure(), new ChunkBorders(), new ItemESP(), new EntityESP(), new Nametags(), new Freecam(),
			new HandView(), new LightOverlay(), new Ambience(), new ItemPhysics(),
			new ParticleLimiter(),
			new VoidESP(), new StorageESP(), new HoleESP(),
			new BlockESP(), new BetterTab(), new CameraTweaks(), new BossStack(), new PlayerSpots(), new EntityOwner(), new BetterTooltips(), new ReactiveFx(), new ScreenFilter(), new Trajectories(), new XRay(), new KillEffects(),
			new SignPreview(), new Breadcrumbs(), new EntityCounter(), new TickMonitor(), new BreakESP(), new SlotHighlight(), new InvLeak(), new PearlInfo(),
			// Movement
			new SnapLook(), new MountStats(), new Flight(), new Step(),
			new Velocity(), new NoSlow(), new Sprint(), new Blink(), new Spider(), new HighJump(), new ReverseStep(),
			new TridentBoost(), new Jesus(), new SafeWalk(), new AntiVoid(), new AirJump(), new Parkour(),
			new AutoJump(), new ElytraFly(), new AutoFly(), new EntityFly(), new Speed(), new Scaffold(),
			new Timer(), new TickShift(), new SnapTap(), new AutoWalk(), new TravelGuard(),
			// World
			new BuildHeight(), new AutoSign(), new AutoMount(), new AutoShearer(), new EndermanLook(),
			new StashFinder(), new ChestSearch(), new StrongholdFinder(), new StasisFinder(), new Sonar(),
			// Player
			new HotbarLock(), new DurabilityAlert(), new ArmorStatus(), new InventoryPlus(), new QuickStack(), new Organizer(),
			new PickupNotifier(), new XpTracker(), new ItemCounter(), new NoFall(), new AutoTool(),
			new EffectTimers(), new Reach(), new NoEffect(), new AntiHunger(), new AutoRespawn(),
			new AntiAFK(), new ChestSwap(), new MiddleClickExtra(), new PacketMine(), new AutoReplenish(), new FastUse(), new NoRotate(), new InstantRebreak(),
			new AirPlace(), new BreakDelay(), new NoSlowBreak(), new Multitask(), new LiquidInteract(), new NoMiningTrace(),
			new PotionSaver(), new EXPThrower(), new GhostHand(), new FakePlayer(), new AutoMend(), new AutoPot(), new ClutchFall(),
			// Exploit (diagnostics only in this baseline)
			new PacketInspector(), new ChunkTracer(), new IllegalDisconnect(), new AntiPacketKick(), new PacketCanceller(), new PacketRate(), new Spoofs(),
			new RegistryDump(), new ProtocolInfo(), new DesyncDetector(), new GhostBlockCheck(),
			new NewChunks(),
			// Cosmetics (client-side only)
			new Cape(), new Halo(), new Wings(), new Hats(), new OrbitAura(), new Pet(), new Scarf(), new LilBuddy(),
			// Client
			new Interface(), new HudModule(), new Notifications(), new Notifier(), new MainMenu(), new ModuleBlocklist(), new AutoEat(), new AutoFish(), new AutoClicker(), new AutoFarm(), new ConnectionLog(),
			new DiscordPresence(), new PlayerProtect(), new SkinChanger(), new StreamerMode());
	}

	public void init(EventBus bus) {
		this.bus = bus;
		for (Module m : modules) {
			ErrorGuard.run("init:" + m.name(), () -> m.init(bus));
			m.syncListeners();
		}
		bus.subscribe(Events.Tick.class, e -> tick());
		bus.subscribe(Events.KeyPress.class, this::onKey);
		bus.subscribe(Events.MouseClick.class, this::onMouseClick);
	}

	private void tick() {
		boolean profiling = ModuleProfiler.active();
		for (Module m : modules) {
			if (!m.isEnabled()) continue;
			long start = System.nanoTime();
			try {
				m.onTick();
			} catch (Throwable t) {
				ErrorGuard.report(m.tickKey, t);
			}
			long nanos = System.nanoTime() - start;
			if (profiling) ModuleProfiler.record(m, ModuleProfiler.Phase.TICK, nanos);
			// Periodic work is the usual source of "pulsing" stutter, so name the culprit when one turns up.
			long ms = nanos / 1_000_000L;
			if (ms >= SLOW_TICK_MS && warnSlow(m)) {
				CoffeeClient.LOG.warn("[Perf] {} tick took {} ms", m.name(), ms);
			}
		}
		ModuleProfiler.roll(modules);
	}

	/** At most one slow-tick warning per module every few seconds, so a bad module cannot flood the log. */
	private boolean warnSlow(Module m) {
		long now = System.currentTimeMillis();
		Long last = slowWarned.get(m.name());
		if (last != null && now - last < 5000) return false;
		slowWarned.put(m.name(), now);
		return true;
	}

	private void onKey(Events.KeyPress e) {
		if (e.isCancelled() || e.screenOpen() || e.key() < 0) return;
		toggleBound(e.key(), e.action());
	}

	/** Mouse buttons work as keybinds too; the mouse mixin only posts these in-game, so no screen check. */
	private void onMouseClick(Events.MouseClick e) {
		if (e.isCancelled()) return;
		toggleBound(KeyNames.mouseKey(e.button), e.action);
	}

	/** Toggles every module bound to {@code key}, ignoring the action unless a module waits for the release. */
	private void toggleBound(int key, int action) {
		for (Module m : modules) {
			if (m.keybind().key() != key) continue;
			// A "press" (1) fires immediately; release-toggle modules wait for the release (0).
			if (m.toggleOnBindRelease()) {
				if (action == 0) m.toggle();
			} else if (action == 1) {
				m.toggle();
			}
		}
	}

	void onToggled(Module module) {
		if (bus != null) bus.post(new ModuleToggled(module, Module.silent));
	}

	/** Posted on the event bus whenever a module is switched on or off. */
	/** @param silent toggled by {@link Module#setEnabledSilently}: no toast or chat line */
	public record ModuleToggled(Module module, boolean silent) {
	}

	public List<Module> all() {
		return modules;
	}

	public List<Module> byCategory(Category category) {
		return modules.stream()
			.filter(m -> m.category() == category)
			.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
			.toList();
	}

	public <T extends Module> T get(Class<T> type) {
		Module cached = byClass.get(type);
		if (cached == null) {
			for (Module m : modules) if (type.isInstance(m)) cached = m;
			if (cached == null) throw new IllegalArgumentException("Module not registered: " + type.getName());
			byClass.put(type, cached);
		}
		return type.cast(cached);
	}

	/** Null-safe "is this module on" for hot paths (mixins), usable before init completes. */
	public static boolean on(Class<? extends Module> type) {
		ModuleManager mm = dev.coffeeclient.core.CoffeeClient.modules();
		return mm != null && mm.get(type).isEnabled();
	}

	/** The module if it is on, else null: one lookup for mixins that check and then use it. */
	public static <T extends Module> T ifOn(Class<T> type) {
		ModuleManager mm = dev.coffeeclient.core.CoffeeClient.modules();
		if (mm == null) return null;
		T module = mm.get(type);
		return module.isEnabled() ? module : null;
	}

	public Optional<Module> byName(String name) {
		return modules.stream().filter(m -> m.name().equalsIgnoreCase(name) || m.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(name))).findFirst();
	}

	/** Favourite modules first, then by name within each category. */
	public List<Module> sortedFor(Category category) {
		return modules.stream()
			.filter(m -> m.category() == category)
			.sorted((a, b) -> {
				if (a.favorite() != b.favorite()) return a.favorite() ? -1 : 1;
				return a.name().compareToIgnoreCase(b.name());
			})
			.toList();
	}
}
