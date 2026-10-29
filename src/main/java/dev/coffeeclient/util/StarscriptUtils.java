package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.module.Module;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.meteordev.starscript.Script;
import org.meteordev.starscript.Section;
import org.meteordev.starscript.StandardLib;
import org.meteordev.starscript.compiler.Compiler;
import org.meteordev.starscript.compiler.Parser;
import org.meteordev.starscript.utils.StarscriptError;
import org.meteordev.starscript.value.Value;
import org.meteordev.starscript.value.ValueMap;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starscript placeholders for the {@link dev.coffeeclient.hud.elements.TextElement Text HUD element}.
 *
 * <p>Templates are written like {@code "X: {player.pos.x} Y: {player.pos.y}"}; anything inside braces is
 * evaluated by Starscript. Values are read live from the client, so they are always up to date and safe
 * to call when no world is loaded (they fall back to sensible defaults).
 */
public final class StarscriptUtils {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final org.meteordev.starscript.Starscript ss = new org.meteordev.starscript.Starscript();
	/** {@link #format}'s compiled templates by source text, or the parse error to show instead. */
	private static final Map<String, Object> FORMATS = new ConcurrentHashMap<>();
	private static final int FORMATS_KEPT = 256;
	private static boolean initialised;

	private StarscriptUtils() {
	}

	private static void init() {
		if (initialised) return;
		initialised = true;
		StandardLib.init(ss);

		ss.set("mc_version", SharedConstants.getCurrentVersion().name());
		ss.set("fps", () -> Value.number(mc.getFps()));
		ss.set("ping", StarscriptUtils::ping);
		ss.set("time", () -> Value.string(LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))));
		ss.set("memory", () -> Value.number(GameStats.usedMemoryMb()));

		ss.set("coffee", new ValueMap()
			.set("name", "Coffee Client")
			.set("version", version())
			.set("modules", () -> Value.number(CoffeeClient.modules().all().size()))
			.set("active_modules", () -> Value.number(CoffeeClient.modules().all().stream().filter(Module::isEnabled).count()))
			.set("is_module_active", StarscriptUtils::isModuleActive)
			.set("get_module_info", StarscriptUtils::getModuleInfo)
		);

		ss.set("player", new ValueMap()
			.set("_toString", () -> Value.string(mc.getUser().getName()))
			.set("health", () -> Value.number(player() != null ? player().getHealth() : 0))
			.set("absorption", () -> Value.number(player() != null ? player().getAbsorptionAmount() : 0))
			.set("hunger", () -> Value.number(player() != null ? player().getFoodData().getFoodLevel() : 0))
			.set("saturation", () -> Value.number(player() != null ? player().getFoodData().getSaturationLevel() : 0))
			.set("speed", () -> Value.number(HudStats.speed()))
			.set("dimension", () -> Value.string(dimension()))
			.set("gamemode", StarscriptUtils::gamemode)
			.set("biome", StarscriptUtils::biome)
			.set("pos", new ValueMap()
				.set("_toString", () -> Value.string(posString(false)))
				.set("x", () -> Value.number(player() != null ? player().getX() : 0))
				.set("y", () -> Value.number(player() != null ? player().getY() : 0))
				.set("z", () -> Value.number(player() != null ? player().getZ() : 0))
			)
			.set("opposite_dim_pos", new ValueMap()
				.set("_toString", () -> Value.string(posString(true)))
				.set("x", () -> opposite(0))
				.set("y", () -> Value.number(player() != null ? player().getY() : 0))
				.set("z", () -> opposite(2))
			)
			.set("yaw", () -> yaw(false))
			.set("pitch", () -> pitch(false))
			.set("facing", () -> Value.string(facing(false)))
			.set("hand", () -> player() != null ? wrap(player().getMainHandItem()) : Value.null_())
			.set("offhand", () -> player() != null ? wrap(player().getOffhandItem()) : Value.null_())
			.set("xp", new ValueMap()
				.set("level", () -> Value.number(player() != null ? player().experienceLevel : 0))
				.set("progress", () -> Value.number(player() != null ? player().experienceProgress : 0))
				.set("total", () -> Value.number(player() != null ? player().totalExperience : 0))
			)
			.set("has_potion_effect", StarscriptUtils::hasPotionEffect)
		);

		ss.set("camera", new ValueMap()
			.set("pos", new ValueMap()
				.set("_toString", () -> Value.string(cameraPosString()))
				.set("x", () -> Value.number(mc.gameRenderer.mainCamera().position().x))
				.set("y", () -> Value.number(mc.gameRenderer.mainCamera().position().y))
				.set("z", () -> Value.number(mc.gameRenderer.mainCamera().position().z))
			)
			.set("yaw", () -> yaw(true))
			.set("pitch", () -> pitch(true))
			.set("facing", () -> Value.string(facing(true)))
		);

		ss.set("crosshair_target", new ValueMap()
			.set("type", StarscriptUtils::crosshairType)
			.set("value", StarscriptUtils::crosshairValue)
		);

		ss.set("server", new ValueMap()
			.set("tps", () -> {
				double tps = HudStats.tps();
				return Value.number(Double.isNaN(tps) ? 0 : tps);
			})
			.set("time", StarscriptUtils::worldTime)
			.set("weather", StarscriptUtils::weather)
			.set("player_count", () -> Value.number(mc.getConnection() != null ? mc.getConnection().getOnlinePlayers().size() : 0))
			.set("difficulty", () -> Value.string(mc.level != null ? mc.level.getDifficulty().getSerializedName() : ""))
		);
	}

	// ---- Public API ----

	/** Compile a template, or {@code null} if it does not parse. */
	public static Script compile(String source) {
		init();
		Parser.Result result = Parser.parse(source);
		if (result.hasErrors()) {
			CoffeeClient.LOG.warn("Starscript error in '{}': {}", source, result.errors.getFirst());
			return null;
		}
		return Compiler.compile(result);
	}

	/** Run a compiled template, returning its text, or an error message if it throws. */
	public static String run(Script script) {
		init();
		if (script == null) return null;
		try {
			return ss.run(script).toString();
		} catch (StarscriptError error) {
			return error.getMessage();
		}
	}

	/** Compile and run in one go; returns the error message when the template is invalid. */
	public static String format(String source) {
		init();
		// HUD text re-runs the same templates several times a second: parse and compile each once.
		Object compiled = FORMATS.get(source);
		if (compiled == null) {
			if (FORMATS.size() >= FORMATS_KEPT) FORMATS.clear();
			Parser.Result result = Parser.parse(source);
			compiled = result.hasErrors() ? result.errors.getFirst().toString() : Compiler.compile(result);
			FORMATS.put(source, compiled);
		}
		if (compiled instanceof String error) return error;
		try {
			return ss.run((Script) compiled).toString();
		} catch (StarscriptError error) {
			return error.getMessage();
		}
	}

	// ---- Functions ----

	private static Value ping() {
		if (mc.getConnection() == null || mc.player == null) return Value.number(0);
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return Value.number(info != null ? info.getLatency() : 0);
	}

	private static Value isModuleActive(org.meteordev.starscript.Starscript ss, int argCount) {
		if (argCount != 1) ss.error("coffee.is_module_active() requires 1 argument, got %d.", argCount);
		return Value.bool(CoffeeClient.modules().byName(ss.popString("First argument to coffee.is_module_active() needs to be a string.")).map(Module::isEnabled).orElse(false));
	}

	private static Value getModuleInfo(org.meteordev.starscript.Starscript ss, int argCount) {
		if (argCount != 1) ss.error("coffee.get_module_info() requires 1 argument, got %d.", argCount);
		// Coffee modules do not expose a live info string yet.
		return Value.string("");
	}

	private static Value hasPotionEffect(org.meteordev.starscript.Starscript ss, int argCount) {
		if (argCount < 1) ss.error("player.has_potion_effect() requires 1 argument, got %d.", argCount);
		if (player() == null) return Value.bool(false);
		Identifier id = Identifier.tryParse(ss.popString("First argument to player.has_potion_effect() needs to be a string."));
		if (id == null) return Value.bool(false);
		MobEffectInstance effect = BuiltInRegistries.MOB_EFFECT.get(id)
			.map(holder -> player().getEffect(holder))
			.orElse(null);
		return Value.bool(effect != null);
	}

	// ---- Values ----

	private static LocalPlayer player() {
		return mc.player;
	}

	private static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(CoffeeClient.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");
	}

	private static String dimension() {
		if (mc.level == null) return "";
		return switch (mc.level.dimension().identifier().getPath()) {
			case "overworld" -> "Overworld";
			case "the_nether" -> "Nether";
			case "the_end" -> "End";
			default -> GameStats.dimensionShort(mc.level);
		};
	}

	private static Value gamemode() {
		if (mc.gameMode == null) return Value.null_();
		GameType mode = mc.gameMode.getPlayerMode();
		if (mode == null) return Value.null_();
		String name = mode.getName();
		return Value.string(name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1));
	}

	private static Value biome() {
		if (player() == null || mc.level == null) return Value.string("");
		return Value.string(GameStats.biomeName(mc.level, player().blockPosition()));
	}

	private static Value opposite(int axis) {
		double v = player() == null ? 0 : (axis == 0 ? player().getX() : player().getZ());
		if (mc.level == null) return Value.number(v);
		String dim = mc.level.dimension().identifier().getPath();
		if (dim.equals("overworld")) v /= 8;
		else if (dim.equals("the_nether")) v *= 8;
		return Value.number(v);
	}

	private static Value yaw(boolean camera) {
		float yaw = camera ? mc.gameRenderer.mainCamera().yRot() : (player() != null ? player().getYRot() : 0);
		yaw %= 360;
		if (yaw < 0) yaw += 360;
		if (yaw > 180) yaw -= 360;
		return Value.number(yaw);
	}

	private static Value pitch(boolean camera) {
		float pitch = camera ? mc.gameRenderer.mainCamera().xRot() : (player() != null ? player().getXRot() : 0);
		pitch %= 360;
		if (pitch < 0) pitch += 360;
		if (pitch > 180) pitch -= 360;
		return Value.number(pitch);
	}

	private static String facing(boolean camera) {
		float yaw = camera ? mc.gameRenderer.mainCamera().yRot() : (player() != null ? player().getYRot() : 0);
		yaw = ((yaw % 360) + 360) % 360;
		String[] dirs = {"South", "West", "North", "East"};
		return dirs[Math.round(yaw / 90f) % 4];
	}

	private static String posString(boolean opposite) {
		if (player() == null) return "X: 0 Y: 0 Z: 0";
		double x = player().getX(), y = player().getY(), z = player().getZ();
		if (opposite && mc.level != null) {
			String dim = mc.level.dimension().identifier().getPath();
			if (dim.equals("overworld")) {
				x /= 8;
				z /= 8;
			} else if (dim.equals("the_nether")) {
				x *= 8;
				z *= 8;
			}
		}
		return posString(x, y, z);
	}

	private static String cameraPosString() {
		Vec3 pos = mc.gameRenderer.mainCamera().position();
		return posString(pos.x, pos.y, pos.z);
	}

	private static String posString(double x, double y, double z) {
		return String.format(Locale.ROOT, "X: %s Y: %s Z: %s", Coords.format(x), Coords.format(y), Coords.format(z));
	}

	private static Value crosshairType() {
		if (mc.hitResult == null) return Value.string("miss");
		return Value.string(switch (mc.hitResult.getType()) {
			case MISS -> "miss";
			case BLOCK -> "block";
			case ENTITY -> "entity";
		});
	}

	private static Value crosshairValue() {
		if (mc.level == null || mc.hitResult == null) return Value.null_();
		if (mc.hitResult.getType() == HitResult.Type.MISS) return Value.string("");
		if (mc.hitResult instanceof BlockHitResult hit) return wrap(hit.getBlockPos(), mc.level.getBlockState(hit.getBlockPos()));
		return wrap(((EntityHitResult) mc.hitResult).getEntity());
	}

	private static Value worldTime() {
		if (mc.level == null) return Value.string("");
		long time = mc.level.getLevelData().getGameTime();
		long day = time / 24000L;
		long hours = (time % 24000L) / 1000L;
		long minutes = (long) ((time % 1000L) * 0.06);
		return Value.string(String.format(Locale.ROOT, "Day %d %02d:%02d", day, (hours + 6) % 24, minutes));
	}

	private static Value weather() {
		if (mc.level == null) return Value.string("");
		return Value.string(mc.level.isThundering() ? "Thunder" : mc.level.isRaining() ? "Rain" : "Clear");
	}

	// ---- Wrapping ----

	private static Value wrap(ItemStack stack) {
		String name = stack.isEmpty() ? "" : stack.getHoverName().getString();
		int durability = 0;
		if (!stack.isEmpty() && stack.isDamageableItem()) durability = stack.getMaxDamage() - stack.getDamageValue();
		return Value.map(new ValueMap()
			.set("_toString", Value.string(stack.getCount() <= 1 ? name : String.format(Locale.ROOT, "%s %dx", name, stack.getCount())))
			.set("name", Value.string(name))
			.set("id", Value.string(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
			.set("count", Value.number(stack.getCount()))
			.set("durability", Value.number(durability))
			.set("max_durability", Value.number(stack.getMaxDamage()))
		);
	}

	private static Value wrap(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		return Value.map(new ValueMap()
			.set("_toString", Value.string(state.getBlock().getName().getString()))
			.set("id", Value.string(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))
			.set("pos", Value.map(new ValueMap()
				.set("_toString", Value.string(posString(pos.getX(), pos.getY(), pos.getZ())))
				.set("x", Value.number(pos.getX()))
				.set("y", Value.number(pos.getY()))
				.set("z", Value.number(pos.getZ()))
			))
		);
	}

	private static Value wrap(Entity entity) {
		return Value.map(new ValueMap()
			.set("_toString", Value.string(entity.getName().getString()))
			.set("id", Value.string(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))
			.set("health", Value.number(entity instanceof LivingEntity living ? living.getHealth() : 0))
			.set("absorption", Value.number(entity instanceof LivingEntity living ? living.getAbsorptionAmount() : 0))
			.set("pos", Value.map(new ValueMap()
				.set("_toString", Value.string(posString(entity.getX(), entity.getY(), entity.getZ())))
				.set("x", Value.number(entity.getX()))
				.set("y", Value.number(entity.getY()))
				.set("z", Value.number(entity.getZ()))
			))
		);
	}
}
