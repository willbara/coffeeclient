package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.Setting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Blocks chosen status effects client side, and removes the ones that only spoil your view. Ported
 * from NoStatusEffects (formerly its name here); the view options merge AntiBlind,
 * AntiWobble, AntiFov and NoHurtcam.
 *
 * <p>The HUD, World and Entities groups switch off other things drawn on screen or in the world
 * (crosshair, titles, beacon beams, banners, sign text, break cracks, the world border, map
 * markers, falling blocks, fireworks, dying mobs, armour, cave culling). Hiding whole entity types
 * is Ambience's Hidden entities list.
 *
 * <p>The view options leave the effect itself alone (Blindness still stops sprinting unless Sprint ignores it), unlike the
 * blocked list, which hides the effect from the client entirely.
 */
public final class NoEffect extends Module {
	public enum Fov implements Labeled {
		VANILLA("Vanilla"),
		/** AntiFov: Speed and Slowness stop zooming the view; sprinting and flying still do. */
		NO_EFFECTS("No effects"),
		/** No zoom from movement at all; bows and spyglasses still zoom. */
		STEADY("Steady");

		private final String label;

		Fov(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final IdListSetting blockedEffects = setting(new IdListSetting("Blocked effects", "Effects to block",
		List.of("minecraft:levitation", "minecraft:jump_boost", "minecraft:slow_falling", "minecraft:dolphins_grace"),
		NoEffect::allEffects, false).labels(NoEffect::effectName));

	public final BooleanSetting blindness = group(new BooleanSetting("Blindness", "No blindness fog", true), "View");
	public final BooleanSetting darkness = group(new BooleanSetting("Darkness", "No darkness fog or pulsing", true), "View");
	public final BooleanSetting nausea = group(new BooleanSetting("Nausea", "No nausea or portal wobble", true), "View");
	public final BooleanSetting hurtCam = group(new BooleanSetting("Hurt cam", "No camera shake when you take damage", false), "View");
	public final EnumSetting<Fov> fov = group(new EnumSetting<>("FOV change", "What may zoom your view in or out", Fov.VANILLA), "View");
	public final BooleanSetting fireOverlay = group(new BooleanSetting("Fire", "No fire drawn over the screen while you burn", false), "Overlays");
	public final BooleanSetting waterOverlay = group(new BooleanSetting("Water", "No blue tint when your head is underwater", false), "Overlays");
	public final BooleanSetting wallOverlay = group(new BooleanSetting("In wall", "No block texture drawn over the screen when you are inside a block", false), "Overlays");
	public final BooleanSetting pumpkin = group(new BooleanSetting("Pumpkin", "No carved pumpkin overlay", false), "Overlays");
	public final BooleanSetting vignette = group(new BooleanSetting("Vignette", "No dark edges around the screen", false), "Overlays");
	public final BooleanSetting powderSnow = group(new BooleanSetting("Powder snow", "No frozen outline around the screen while you freeze", false), "Overlays");
	public final BooleanSetting guiDim = group(new BooleanSetting("GUI dimming", "No dark gradient behind inventories and other screens opened in a world", false), "Overlays");
	public final BooleanSetting totem = group(new BooleanSetting("Totem animation", "No totem (or other item) popping up on screen", false), "Overlays");

	public final BooleanSetting crosshair = group(new BooleanSetting("Crosshair", "Hide the crosshair", false), "HUD");
	public final BooleanSetting title = group(new BooleanSetting("Titles", "Hide titles and subtitles servers show", false), "HUD");
	public final BooleanSetting heldItemName = group(new BooleanSetting("Held item name", "No item name above the hotbar when you switch", false), "HUD");
	public final BooleanSetting potionIcons = group(new BooleanSetting("Potion icons", "No effect icons in the corner", false), "HUD");

	public final BooleanSetting beaconBeams = group(new BooleanSetting("Beacon beams", "Hide beacon beams", false), "World");
	public final BooleanSetting banners = group(new BooleanSetting("Banners", "Hide banners", false), "World");
	public final BooleanSetting signText = group(new BooleanSetting("Sign text", "Hide the writing on signs", false), "World");
	public final BooleanSetting enchantBook = group(new BooleanSetting("Enchanting book", "No floating book on enchanting tables", false), "World");
	public final BooleanSetting breakOverlay = group(new BooleanSetting("Break cracks", "No cracks drawn on blocks being broken", false), "World");
	public final BooleanSetting worldBorder = group(new BooleanSetting("World border", "Hide the world border wall", false), "World");
	public final BooleanSetting mapMarkers = group(new BooleanSetting("Map markers", "No player and banner markers on maps", false), "World");
	public final BooleanSetting itemParticles = group(new BooleanSetting("Eating particles", "No crumbs when eating or bits when an item breaks", false), "World");
	public final BooleanSetting caveCulling = group(new BooleanSetting("Cave culling", "Draw caves hidden behind walls (more to render, fewer gaps)", false), "World");

	public final BooleanSetting fallingBlocks = group(new BooleanSetting("Falling blocks", "Hide falling sand, gravel and anvils", false), "Entities");
	public final BooleanSetting fireworks = group(new BooleanSetting("Fireworks", "Hide firework rockets", false), "Entities");
	public final BooleanSetting deadEntities = group(new BooleanSetting("Dead entities", "No death animation: dying mobs vanish at once", false), "Entities");
	public final BooleanSetting armour = group(new BooleanSetting("Armour", "Hide worn armour on players and mobs", false), "Entities");

	public NoEffect() {
		super("NoEffect", List.of("NoStatusEffects", "NoRender", "AntiBlind"), Category.RENDER,
			"Blocks chosen status effects and the ones that spoil your view.");
		caveCulling.onChange(v -> reloadChunks());
	}

	@Override
	protected void onEnable() {
		if (caveCulling.get()) reloadChunks();
	}

	@Override
	protected void onDisable() {
		if (caveCulling.get()) reloadChunks();
	}

	/** Cave culling is baked into each chunk section when it builds, so rebuild them all. */
	private static void reloadChunks() {
		var mc = mc();
		if (mc.level != null) mc.execute(mc.levelExtractor::allChanged);
	}

	private static NoEffect on() {
		return ModuleManager.ifOn(NoEffect.class);
	}

	/** A HUD part or world feature switched off: {@code pick} reads its toggle. */
	public static boolean hides(java.util.function.Function<NoEffect, BooleanSetting> pick) {
		NoEffect m = on();
		return m != null && pick.apply(m).get();
	}

	/** Block entities drawn only for their extras: beacon beams, banners, the enchanting book. */
	public static boolean hidesBlockEntity(net.minecraft.world.level.block.state.BlockState state) {
		NoEffect m = on();
		if (m == null || state == null) return false;
		var block = state.getBlock();
		return m.beaconBeams.get() && block instanceof net.minecraft.world.level.block.BeaconBlock
			|| m.banners.get() && block instanceof net.minecraft.world.level.block.AbstractBannerBlock
			|| m.enchantBook.get() && block instanceof net.minecraft.world.level.block.EnchantingTableBlock;
	}

	public static boolean hidesEntity(net.minecraft.world.entity.Entity e) {
		NoEffect m = on();
		if (m == null) return false;
		return m.fallingBlocks.get() && e instanceof net.minecraft.world.entity.item.FallingBlockEntity
			|| m.fireworks.get() && e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity
			|| m.deadEntities.get() && e instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying() && e != mc().player;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return blockedEffects;
	}

	public boolean blocked(String id) {
		return isEnabled() && blockedEffects.contains(id);
	}

	/** Whether the view effect of {@code effect} (fog, wobble, pulsing) should be hidden for the local player. */
	public static boolean hidesView(Holder<MobEffect> effect) {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		if (m == null) return false;
		MobEffect value = effect.value();
		if (value == MobEffects.BLINDNESS.value()) return m.blindness.get();
		if (value == MobEffects.DARKNESS.value()) return m.darkness.get();
		if (value == MobEffects.NAUSEA.value()) return m.nausea.get();
		return false;
	}

	public static boolean hidesHurtCam() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.hurtCam.get();
	}

	public static boolean hidesFireOverlay() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.fireOverlay.get();
	}

	public static boolean hidesWaterOverlay() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.waterOverlay.get();
	}

	public static boolean hidesWallOverlay() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.wallOverlay.get();
	}

	public static boolean hidesPumpkinOverlay() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.pumpkin.get();
	}

	public static boolean hidesPowderSnow() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.powderSnow.get();
	}

	public static boolean hidesGuiDim() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.guiDim.get();
	}

	public static boolean hidesVignette() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m != null && m.vignette.get();
	}

	/** The FOV mode in effect ({@link Fov#VANILLA} while off). */
	public static Fov fovMode() {
		NoEffect m = ModuleManager.ifOn(NoEffect.class);
		return m == null ? Fov.VANILLA : m.fov.get();
	}

	/**
	 * Movement speed with the modifiers that active effects added left out, for the FOV calculation.
	 * Same order as {@code AttributeInstance.calculateValue}. From AntiFov.
	 */
	public static double speedWithoutEffects(Player player, double original) {
		AttributeInstance instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (instance == null) return original;
		Set<Identifier> fromEffects = new HashSet<>();
		for (MobEffectInstance effect : player.getActiveEffects()) {
			effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
				if (attribute.value() == Attributes.MOVEMENT_SPEED.value()) fromEffects.add(modifier.id());
			});
		}
		if (fromEffects.isEmpty()) return original;

		double base = instance.getBaseValue();
		double multipliedBase = 0;
		double multipliedTotal = 1;
		for (AttributeModifier modifier : instance.getModifiers()) {
			if (fromEffects.contains(modifier.id())) continue;
			switch (modifier.operation()) {
				case ADD_VALUE -> base += modifier.amount();
				case ADD_MULTIPLIED_BASE -> multipliedBase += modifier.amount();
				case ADD_MULTIPLIED_TOTAL -> multipliedTotal *= 1 + modifier.amount();
			}
		}
		return Math.max(0, (base + base * multipliedBase) * multipliedTotal);
	}

	private static List<String> allEffects() {
		return BuiltInRegistries.MOB_EFFECT.keySet().stream().map(Identifier::toString).sorted().toList();
	}

	private static String effectName(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return id;
		MobEffect effect = BuiltInRegistries.MOB_EFFECT.getOptional(key).orElse(null);
		return effect == null ? id : effect.getDisplayName().getString();
	}
}
