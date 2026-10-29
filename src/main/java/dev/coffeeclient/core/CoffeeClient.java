package dev.coffeeclient.core;

import dev.coffeeclient.config.ClientOptions;
import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.friend.FriendManager;
import dev.coffeeclient.hud.HudManager;
import dev.coffeeclient.macro.MacroManager;
import dev.coffeeclient.profile.ProfileManager;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.ErrorGuard;
import dev.coffeeclient.util.LogTail;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.MixinEnvironment;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. Creates the subsystems, loads config, and bridges Fabric events onto the {@link EventBus}. */
public final class CoffeeClient implements ClientModInitializer {
	public static final String MOD_ID = "coffeeclient";
	public static final Logger LOG = LoggerFactory.getLogger("Coffee Client");

	private static EventBus events;
	private static Theme theme;
	private static ClientOptions options;
	private static ConfigManager config;
	private static ModuleManager modules;
	private static HudManager hud;
	private static FriendManager friends;
	private static MacroManager macros;
	private static dev.coffeeclient.account.AccountManager accounts;
	private static dev.coffeeclient.proxy.ProxyManager proxies;
	private static ProfileManager profiles;
	private static Keybinds keybinds;

	@Override
	public void onInitializeClient() {
		// Dev runs apply every mixin up front, so a broken injection fails at launch instead of on first use.
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) MixinEnvironment.getCurrentEnvironment().audit();
		LogTail.install();
		events = new EventBus();
		theme = new Theme();
		options = new ClientOptions();
		config = new ConfigManager();
		modules = new ModuleManager();
		hud = new HudManager();
		friends = new FriendManager(config.directory().resolve("friends.json"));
		macros = new MacroManager(config.directory().resolve("macros.json"));
		accounts = new dev.coffeeclient.account.AccountManager(config.directory().resolve("accounts.json"));
		proxies = new dev.coffeeclient.proxy.ProxyManager(config.directory().resolve("proxies.json"));
		profiles = new ProfileManager(config.directory().resolve("profiles.json"));

		modules.init(events);
		hud.init(events);
		dev.coffeeclient.util.Rotations.init(events);
		dev.coffeeclient.util.CombatTick.init(events);
		dev.coffeeclient.util.BlockActions.init(events);
		dev.coffeeclient.util.BlockSearch.init(events);
		dev.coffeeclient.util.Setback.init(events);
		dev.coffeeclient.util.FlightRecorder.init(events);
		dev.coffeeclient.util.Latency.init(events);
		dev.coffeeclient.util.PacketHold.init(events);
		dev.coffeeclient.util.OutboundHold.init(events);
		dev.coffeeclient.util.MotionTracker.init(events);
		dev.coffeeclient.util.HurtTracker.init(events);
		dev.coffeeclient.util.CrystalTracker.init(events);
		config.load();
		friends.load();
		friends.init(events);
		dev.coffeeclient.module.modules.misc.Friends.attach(friends);
		macros.load();
		macros.init(events);
		ErrorGuard.run("load:accounts", accounts::load);
		ErrorGuard.run("load:proxies", proxies::load);
		profiles.load();
		events.subscribe(Events.KeyPress.class, event -> {
			if (!event.isCancelled()) macros.onKey(event);
		});
		events.subscribe(Events.MouseClick.class, event -> {
			if (!event.isCancelled()) macros.onMouseClick(event);
		});

		LogTail.setCapacity(options.logLinesKept.intValue());
		options.logLinesKept.onChange(v -> LogTail.setCapacity(v.intValue()));
		keybinds = new Keybinds();
		registerFabricEvents();
		if (Boolean.getBoolean("coffeeclient.devTour")) startDevTool("DevTour");
		if (Boolean.getBoolean("coffeeclient.bench")) startDevTool("DevBench");
		LOG.info("Coffee Client initialised: {} modules", modules.all().size());
		dev.coffeeclient.util.Scarecrow.watch(); // keeps intruders away (do not remove)
	}

	/** Dev-only tools (screenshot tour, profiling bench); excluded from release jars, so only under runClient. */
	private static void startDevTool(String name) {
		try {
			Class.forName("dev.coffeeclient.dev." + name).getMethod("install").invoke(null);
		} catch (ReflectiveOperationException e) {
			LOG.warn("{} requested but not available in this build", name);
		}
	}

	private static void registerFabricEvents() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ErrorGuard.run("tick:keybinds", () -> keybinds.tick(mc));
			events.post(new Events.Tick(mc));
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (graphics, delta) ->
			ErrorGuard.run("hud", () -> {
				hud.render(graphics, delta);
				events.post(new Events.HudRender(graphics, delta));
			}));
		// Events.AttackEntity comes from MultiPlayerGameModeMixin, so attacks made by modules count too.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, face) -> {
			if (level.isClientSide()) events.post(new Events.AttackBlock(pos, face));
			return InteractionResult.PASS;
		});
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> events.post(new Events.ChunkLoad(level, chunk)));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> events.post(new Events.ChunkUnload(level, chunk)));
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> events.post(new Events.BlockBroken(pos, state)));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> events.post(new Events.ChatReceived(message, false)));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) events.post(new Events.ChatReceived(message, true));
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ErrorGuard.run("join", () -> {
			dev.coffeeclient.module.modules.misc.AutoReconnect.onJoin();
			// The profile first, so the blocklist still has the last word on what may run here.
			if (mc.getCurrentServer() != null) profiles.onJoin(mc.getCurrentServer().ip);
			dev.coffeeclient.module.modules.client.ModuleBlocklist.onJoin();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			// Queued background saves first, so an older snapshot cannot land after the newest save below.
			Async.flush(5000);
			ErrorGuard.run("shutdown:config", config::saveNow);
			ErrorGuard.run("shutdown:friends", friends::saveNow);
			ErrorGuard.run("shutdown:macros", macros::saveNow);
		});
	}

	public static EventBus events() {
		return events;
	}

	public static Theme theme() {
		return theme;
	}

	public static ClientOptions options() {
		return options;
	}

	public static ConfigManager config() {
		return config;
	}

	public static ModuleManager modules() {
		return modules;
	}

	public static HudManager hud() {
		return hud;
	}

	public static FriendManager friends() {
		return friends;
	}

	public static MacroManager macros() {
		return macros;
	}

	public static dev.coffeeclient.account.AccountManager accounts() {
		return accounts;
	}

	public static dev.coffeeclient.proxy.ProxyManager proxies() {
		return proxies;
	}

	public static ProfileManager profiles() {
		return profiles;
	}

	public static Keybinds keybinds() {
		return keybinds;
	}
}
