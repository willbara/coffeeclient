package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.List;

/**
 * Client-side look of the world: sky theme (vanilla/custom/Nether/End/void), fixed time of day and
 * moon phase, forced weather everywhere (with weather-change notices), performance toggles (fog,
 * clouds, rain particles), and hiding server clutter such as holograms, the scoreboard and boss bars.
 * Nothing here is sent to the server. Configured in its own window.
 */
public final class Ambience extends Module {
	public enum Sky { VANILLA, CUSTOM, NETHER, END, VOID }
	public enum Weather { VANILLA, CLEAR, RAIN, SNOW, THUNDERSTORM }

	// sky
	public final EnumSetting<Sky> sky = group(new EnumSetting<>("Sky theme", "Vanilla, your own colours, or another dimension's look", Sky.VANILLA), "Sky");
	public final ColorSetting skyColor = group(new ColorSetting("Sky color", "Custom sky colour", 0xFF78A7FF), "Sky");
	public final ColorSetting fogColor = group(new ColorSetting("Fog color", "Custom horizon/fog colour", 0xFFC0D8FF), "Sky");
	public final BooleanSetting sunsetGlow = group(new BooleanSetting("Sunset glow", "Orange horizon tint at sunrise/sunset (custom theme)", false), "Sky");
	public final BooleanSetting customClouds = group(new BooleanSetting("Custom cloud color", "Tint the clouds", false), "Sky");
	public final ColorSetting cloudColor = group(new ColorSetting("Cloud color", "Cloud tint", 0xFFFFE0C0), "Sky");
	public final NumberSetting cloudHeight = group(new NumberSetting("Cloud height", "Cloud layer Y (0 = vanilla)", 0, 0, 320, 8)
		.format(v -> v <= 0 ? "Vanilla" : Integer.toString((int) Math.round(v))), "Sky");

	// time
	public final BooleanSetting fixedTime = group(new BooleanSetting("Fixed time", "Freeze the visible time of day", false), "Time");
	public final NumberSetting time = group(new NumberSetting("Time of day", "Clock time to show", 6000, 0, 23500, 250).format(Ambience::clock), "Time");
	public final NumberSetting moonPhase = group(new NumberSetting("Moon phase", "Show this moon (0 = full, 4 = new)", -1, -1, 7, 1)
		.format(v -> v < 0 ? "Vanilla" : MoonPhase.values()[(int) Math.round(v)].getSerializedName().replace('_', ' ')), "Time");

	// weather
	public final EnumSetting<Weather> weather = group(new EnumSetting<>("Weather", "Show this weather everywhere (client-side only)", Weather.VANILLA), "Weather");
	public final NumberSetting rainStrength = group(new NumberSetting("Rain strength", "How heavy precipitation looks", 1.0, 0, 1, 0.05), "Weather");
	public final BooleanSetting noRainParticles = group(new BooleanSetting("No rain splashes", "Skip rain splash and drip particles", false), "Weather");
	public final BooleanSetting announceWeather = group(new BooleanSetting("Announce weather", "Say in chat when the server's rain or thunder starts or stops", false), "Weather");

	// clutter
	public final BooleanSetting hideScoreboard = group(new BooleanSetting("Scoreboard", "Hide the scoreboard sidebar", false), "Clutter");
	public final BooleanSetting hideBossBars = group(new BooleanSetting("Boss bars", "Hide boss bars", false), "Clutter");
	public final BooleanSetting hideHolograms = group(new BooleanSetting("Holograms", "Hide floating text (text displays and invisible named armour stands)", false), "Clutter");
	public final BooleanSetting hideTextDisplays = group(new BooleanSetting("Text displays", "Hide all text display entities", false), "Clutter");
	public final BooleanSetting hideBlockDisplays = group(new BooleanSetting("Block displays", "Hide block display entities", false), "Clutter");
	public final BooleanSetting hideItemDisplays = group(new BooleanSetting("Item displays", "Hide item display entities", false), "Clutter");
	public final BooleanSetting hideMarkerStands = group(new BooleanSetting("Marker armor stands", "Hide marker armour stands", false), "Clutter");
	public final BooleanSetting hideNamedStands = group(new BooleanSetting("Named armor stands", "Hide armour stands with a name", false), "Clutter");
	public final BooleanSetting hideItemFrames = group(new BooleanSetting("Item frames", "Hide item frames", false), "Clutter");
	public final BooleanSetting hidePaintings = group(new BooleanSetting("Paintings", "Hide paintings", false), "Clutter");
	public final BooleanSetting hideXpOrbs = group(new BooleanSetting("XP orbs", "Hide experience orbs", false), "Clutter");
	public final EntityTypeListSetting hiddenTypes = group(new EntityTypeListSetting("Hidden entities", "Entity types never drawn", List.of()), "Clutter");
	public final BooleanSetting hideNpcLabels = group(new BooleanSetting("NPC labels", "Hide name tags like \"Click to play\", \"Shop\" or \"Daily reward\"", false), "Clutter");

	/** Words in server NPC names (list). */
	private static final String[] NPC_WORDS = {"npc", "click", "shop", "leaderboard", "vote", "discord", "store", "daily", "reward", "playtime", "top "};
	/** Last thunder level the server sent, to report a storm once when it crosses the line. */
	private float lastThunder;

	// performance
	public final BooleanSetting noFog = group(new BooleanSetting("No fog", "Remove distance fog (see further, cheaper)", false), "Performance");
	public final BooleanSetting hideClouds = group(new BooleanSetting("Hide clouds", "Skip cloud rendering", false), "Performance");
	public final BooleanSetting simpleSky = group(new BooleanSetting("Flat sky", "No sun, moon, stars or sky disc (cheaper)", false), "Performance");

	public Ambience() {
		super("Ambience", Category.RENDER, "Sky theme, time and weather overrides (opens its own window).");
		skyColor.visibleWhen(() -> sky.get() == Sky.CUSTOM);
		fogColor.visibleWhen(() -> sky.get() == Sky.CUSTOM);
		sunsetGlow.visibleWhen(() -> sky.get() == Sky.CUSTOM);
		cloudColor.visibleWhen(customClouds::get);
		time.visibleWhen(fixedTime::get);
		rainStrength.visibleWhen(() -> weather.get() != Weather.CLEAR);
	}

	private static String clock(double ticks) {
		int total = (int) Math.round(ticks);
		int hours = (total / 1000 + 6) % 24;
		int minutes = (int) ((total % 1000) * 60 / 1000.0);
		return String.format("%02d:%02d", hours, minutes);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		// Network thread: hop over before chatting.
		listen(Events.PacketReceive.class, e -> {
			if (!announceWeather.get() || !(e.packet instanceof ClientboundGameEventPacket packet)) return;
			var type = packet.getEvent();
			String text = null;
			if (type == ClientboundGameEventPacket.START_RAINING) text = "It started raining.";
			else if (type == ClientboundGameEventPacket.STOP_RAINING) text = "The rain stopped.";
			else if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
				float level = packet.getParam();
				if (level > 0.2f && lastThunder <= 0.2f) text = "A thunderstorm started.";
				else if (level <= 0.2f && lastThunder > 0.2f) text = "The thunderstorm ended.";
				lastThunder = level;
			}
			if (text != null) {
				String line = text;
				mc().execute(() -> info(line));
			}
		});
	}

	/** True when Ambience hides this entity entirely. Called from the entity render mixin. */
	public static boolean hidesEntity(Entity e) {
		if (!active()) return false;
		Ambience a = self();
		if (a.hiddenTypes.has(e)) return true;
		if (e instanceof Display.TextDisplay) return a.hideTextDisplays.get() || a.hideHolograms.get();
		if (e instanceof Display.BlockDisplay) return a.hideBlockDisplays.get();
		if (e instanceof Display.ItemDisplay) return a.hideItemDisplays.get();
		if (e instanceof ArmorStand stand) {
			boolean named = stand.hasCustomName();
			return stand.isMarker() && a.hideMarkerStands.get() || named && a.hideNamedStands.get()
				|| a.hideHolograms.get() && named && (stand.isInvisible() || stand.isMarker());
		}
		if (e instanceof ItemFrame) return a.hideItemFrames.get();
		if (e instanceof Painting) return a.hidePaintings.get();
		if (e instanceof ExperienceOrb) return a.hideXpOrbs.get();
		return false;
	}

	/** True when the entity's name tag looks like a server NPC label to hide. */
	public static boolean hidesNameTag(Entity e) {
		if (!active() || !self().hideNpcLabels.get() || !e.hasCustomName()) return false;
		String name = e.getCustomName().getString().toLowerCase(java.util.Locale.ROOT);
		for (String word : NPC_WORDS) if (name.contains(word)) return true;
		return false;
	}

	public static boolean hidesScoreboard() {
		return active() && self().hideScoreboard.get();
	}

	public static boolean hidesBossBars() {
		return active() && self().hideBossBars.get();
	}

	/** The moon phase to show, or {@code vanilla}. Called from the environment attribute mixin. */
	public static MoonPhase moonPhase(MoonPhase vanilla) {
		if (!active() || self().moonPhase.get() < 0) return vanilla;
		return MoonPhase.values()[self().moonPhase.intValue()];
	}

	private static Ambience self() {
		return CoffeeClient.modules().get(Ambience.class);
	}

	private static boolean active() {
		return ModuleManager.on(Ambience.class) && Minecraft.getInstance().isSameThread();
	}

	// ---- hooks (called from mixins; return the vanilla value when off) ----

	public static float rain(float vanilla) {
		if (!active()) return vanilla;
		Ambience a = self();
		return switch (a.weather.get()) {
			case VANILLA -> vanilla * a.rainStrength.floatValue();
			case CLEAR -> 0f;
			default -> a.rainStrength.floatValue();
		};
	}

	public static float thunder(float vanilla) {
		if (!active()) return vanilla;
		return switch (self().weather.get()) {
			case VANILLA -> vanilla;
			case THUNDERSTORM -> 1f;
			default -> 0f;
		};
	}

	public static Biome.Precipitation precipitation(Biome.Precipitation vanilla) {
		if (!active()) return vanilla;
		return switch (self().weather.get()) {
			case RAIN, THUNDERSTORM -> Biome.Precipitation.RAIN;
			case SNOW -> Biome.Precipitation.SNOW;
			case CLEAR -> Biome.Precipitation.NONE;
			default -> vanilla;
		};
	}

	public static boolean cloudsHidden() {
		return active() && self().hideClouds.get();
	}

	public static DimensionType.Skybox skybox(DimensionType.Skybox vanilla) {
		if (!active()) return vanilla;
		Ambience a = self();
		if (a.simpleSky.get()) return DimensionType.Skybox.NONE;
		return switch (a.sky.get()) {
			case END -> DimensionType.Skybox.END;
			case NETHER, VOID -> DimensionType.Skybox.NONE;
			default -> vanilla;
		};
	}

	/**
	 * Environment attribute overrides (sky/fog/cloud colours, fog distance, cloud height).
	 *
	 * <p>Each attribute has its own value type, and the game casts the probe's result to it, so the
	 * replacement must be built in the matching space: sky and fog colours are opaque {@code Integer}
	 * RGB, sunrise/sunset and cloud colours are {@code Integer} ARGB, the distances and cloud height
	 * are {@code Float}.
	 */
	@SuppressWarnings("unchecked")
	public static <V> V attribute(EnvironmentAttribute<V> attribute, V vanilla) {
		if (!active()) return vanilla;
		Ambience a = self();
		Object out = vanilla;
		if (attribute == EnvironmentAttributes.SKY_COLOR) {
			out = switch (a.sky.get()) {
				case CUSTOM -> rgb(a.skyColor.argb() & 0xFFFFFF);
				case NETHER -> rgb(0x2A0606);
				case END -> rgb(0x100A18);
				case VOID -> rgb(0x000000);
				default -> vanilla;
			};
		} else if (attribute == EnvironmentAttributes.FOG_COLOR) {
			out = switch (a.sky.get()) {
				case CUSTOM -> rgb(a.fogColor.argb() & 0xFFFFFF);
				case NETHER -> rgb(0x330808);
				case END -> rgb(0x181318);
				case VOID -> rgb(0x000000);
				default -> vanilla;
			};
		} else if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR && hidesSunset(a)) {
			// otherwise the fog blends toward this orange whenever you face the sun at dawn/dusk
			out = 0;
		} else if (attribute == EnvironmentAttributes.CLOUD_COLOR && a.customClouds.get()) {
			out = a.cloudColor.argb();
		} else if (attribute == EnvironmentAttributes.CLOUD_HEIGHT && a.cloudHeight.get() > 0) {
			out = a.cloudHeight.floatValue();
		} else if (a.noFog.get() && (attribute == EnvironmentAttributes.FOG_START_DISTANCE || attribute == EnvironmentAttributes.FOG_END_DISTANCE)) {
			out = attribute == EnvironmentAttributes.FOG_START_DISTANCE ? 100_000f : 100_001f;
		}
		return (V) out;
	}

	/** An RGB colour attribute value (vanilla stores them opaque). */
	private static Integer rgb(int rgb) {
		return 0xFF000000 | rgb;
	}

	private static boolean hidesSunset(Ambience a) {
		if (a.simpleSky.get()) return true;
		return switch (a.sky.get()) {
			case VANILLA -> false;
			case CUSTOM -> !a.sunsetGlow.get();
			default -> true;
		};
	}

	/** Fixed time of day: keep the day count, replace the time within the day. */
	public static long clock(long vanilla) {
		if (!active() || !self().fixedTime.get()) return vanilla;
		return Math.floorDiv(vanilla, 24000L) * 24000L + self().time.intValue();
	}

	public static boolean blocksParticle(ParticleOptions options) {
		if (!active() || !self().noRainParticles.get()) return false;
		var type = options.getType();
		return type == ParticleTypes.RAIN || type == ParticleTypes.SPLASH || type == ParticleTypes.DRIPPING_WATER;
	}
}
