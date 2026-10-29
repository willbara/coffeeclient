package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.player.ChestSwap;
import dev.coffeeclient.module.modules.player.PacketMine;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CrystalTracker;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.HurtTracker;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import dev.coffeeclient.util.WorldProjector;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;

/**
 * Places end crystals near targets and blows them up. Two modes. Smart rates every spot by the damage
 * it deals each target against what it costs you, after AutoCrystal: it works from your real
 * ping ({@link Latency}), holds a crystal while the target is still immune to a hit that strong
 * ({@link HurtTracker}), aims where targets will be when the packets land ({@link MotionTracker}), fills
 * a gap in a target's surround the moment it opens, breaks enemy crystals near you while your own
 * immunity soaks them up, and can hit the next crystal's entity id before it exists. It reacts to
 * blasts and new crystals as the server reports them instead of waiting for the next tick. Face
 * placing, support blocks, yaw steps and the pause rules come from CrystalAura.
 *
 * <p>Simple is CrystalAura: break every crystal in range, place on the free base nearest each
 * target. also adds taking crystals from the inventory and the visible-sides check. Simple also
 * follows Min damage, Face place, Preferred distance, Hurt sync and Support; the settings it ignores
 * are hidden in it.
 */
public final class CrystalAura extends Module {
	public enum Mode implements Labeled {
		SMART("Smart"), SIMPLE("Simple");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum AutoSwitch implements Labeled {
		NONE("None"), NORMAL("Normal"), SILENT("Silent"), SWAP("Inv swap");

		private final String label;

		AutoSwitch(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum AutoChestplate implements Labeled {
		NONE("None"), STRICT("Strict"), SMART("Smart");

		private final String label;

		AutoChestplate(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Timing implements Labeled {
		NORMAL("Normal"), SEQUENTIAL("Sequential");

		private final String label;

		Timing(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum FriendProtect implements Labeled {
		OFF("Off"), NO_KILL("No kill"), STRICT("Strict");

		private final String label;

		FriendProtect(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Support { DISABLED, ACCURATE, FAST }

	public enum Phase {
		NONE, PLACE, BREAK, BOTH;

		boolean matches(Phase phase) {
			return this == BOTH || this == phase;
		}
	}

	public enum YawSteps { BREAK, ALL }

	public enum Swing {
		BOTH, PACKET, CLIENT, NONE;

		boolean client() {
			return this == BOTH || this == CLIENT;
		}

		boolean packet() {
			return this == BOTH || this == PACKET;
		}
	}

	// General
	public final EnumSetting<Mode> mode = group(new EnumSetting<>("Mode", "Smart: rates spots by damage, ping-aware. Simple: nearest spot that deals Min damage, breaks everything", Mode.SMART), "General");
	public final NumberSetting targetRange = group(new NumberSetting("Target range", "How far targets can be", 10, 1, 16, 0.5), "General");
	public final BooleanSetting predictMovement = group(new BooleanSetting("Predict movement", "Rate damage where targets will be when your packets reach the server", true), "General");
	public final BooleanSetting asyncCalc = group(new BooleanSetting("Async calc", "Rate spots on a background thread and place the result next tick; less lag in busy fights, one tick slower", false), "General");
	public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entities to target", List.of("minecraft:player")), "General");
	public final BooleanSetting ignoreNakeds = group(new BooleanSetting("Ignore nakeds", "Skip players with no armour or items", false), "General");
	public final BooleanSetting rotate = group(new BooleanSetting("Rotate", "Turn (server side) towards crystals and spots", false), "General");
	public final EnumSetting<YawSteps> yawStepMode = group(new EnumSetting<>("Yaw steps mode", "Limit turning speed for breaking only, or for everything", YawSteps.BREAK), "General");
	public final NumberSetting yawSteps = group(new NumberSetting("Yaw steps", "Most degrees turned per tick", 180, 1, 180, 1), "General");
	public final EnumSetting<AutoSwitch> autoSwitch = group(new EnumSetting<>("Auto switch",
		"Normal stays on crystals. Silent switches back in the same packet batch. Inv swap swaps them in from anywhere in the inventory and back, and keeps eating going",
		AutoSwitch.NORMAL), "General");
	public final BooleanSetting fromInventory = group(new BooleanSetting("From inventory", "Pull crystals into the hotbar when none are there ", false), "General");
	public final NumberSetting switchDelay = group(new NumberSetting("Switch delay", "Ticks to wait after changing hotbar slot", 0, 0, 10, 1), "General");
	public final BooleanSetting noGapSwitch = group(new BooleanSetting("No gap switch", "Don't switch away from golden apples", true), "General");
	public final BooleanSetting noBowSwitch = group(new BooleanSetting("No bow switch", "Don't switch away from a bow", true), "General");
	public final BooleanSetting antiWeakness = group(new BooleanSetting("Anti weakness", "With Weakness, use a sword or axe to break crystals", true), "General");
	public final EnumSetting<AutoChestplate> autoChestplate = group(new EnumSetting<>("Auto Chestplate",
		"Strict: wear your best chestplate when enabled while wearing an elytra. Smart: also put the elytra back when disabled",
		AutoChestplate.NONE), "General");

	// Damage
	public final NumberSetting minDamage = group(new NumberSetting("Min damage", "Least damage a crystal must deal to a target", 6, 0, 36, 0.5), "Damage");
	public final NumberSetting maxDamage = group(new NumberSetting("Max self damage", "Most damage a crystal may deal to you", 6, 0, 36, 0.5), "Damage");
	public final NumberSetting maxSelfShare = group(new NumberSetting("Max self share", "Most of your current health (percent) one crystal may take", 100, 1, 100, 1), "Damage");
	public final BooleanSetting minimizeSelf = group(new BooleanSetting("Minimize self damage", "Among spots close in damage, take the one that hurts you least", false), "Damage");
	public final NumberSetting maxReduction = group(new NumberSetting("Max damage reduction", "Most target damage given up to take a safer spot", 2, 0, 10, 0.5), "Damage");
	public final BooleanSetting antiSuicide = group(new BooleanSetting("Anti suicide", "Never use a crystal that would kill or pop you", true), "Damage");
	public final EnumSetting<FriendProtect> friendProtection = group(new EnumSetting<>("Friend protection",
		"No kill: never kill or pop a friend. Strict: also never hurt one more than your own self damage limit", FriendProtect.STRICT), "Damage");
	public final NumberSetting lethalMultiplier = group(new NumberSetting("Lethal multiplier", "Allow a crystal under min damage when this many would kill (0 is off)", 0, 0, 5, 1), "Damage");
	public final BooleanSetting totemAware = group(new BooleanSetting("Totem aware", "Value a kill less on a target holding a totem (it only pops), and face place for a moment after a pop", true), "Damage");

	// Place
	public final BooleanSetting doPlace = group(new BooleanSetting("Place", "Place crystals", true), "Place");
	public final NumberSetting placeDelay = group(new NumberSetting("Place delay", "Ticks between placements", 0, 0, 20, 1), "Place");
	public final NumberSetting placesPerTick = group(new NumberSetting("Places per tick", "Most crystals placed in one tick; two lets a crystal broken mid tick be replaced at once", 2, 1, 4, 1), "Place");
	public final NumberSetting placeRange = group(new NumberSetting("Place range", "How far you place", 4.5, 0, 6, 0.1), "Place");
	public final NumberSetting placeWallsRange = group(new NumberSetting("Place walls range", "How far you place through walls", 4.5, 0, 6, 0.1), "Place");
	public final EnumSetting<Timing> timing = group(new EnumSetting<>("Timing", "Normal places whenever a spot is ready. Sequential waits until your last crystal is gone", Timing.NORMAL), "Place");
	public final BooleanSetting avoidOwnSurround = group(new BooleanSetting("Avoid own surround", "Never put a crystal in a cell of your own surround", true), "Place");
	public final NumberSetting crystalReserve = group(new NumberSetting("Crystal reserve", "With this many crystals or fewer left, only place crystals that kill", 0, 0, 256, 1), "Place");
	public final NumberSetting preferredDistance = group(new NumberSetting("Preferred distance", "Place about this far from you when a spot there works, otherwise wherever one does (0 is off)", 0, 0, 6, 0.5), "Place");
	public final BooleanSetting placement112 = group(new BooleanSetting("1.12 placement", "Need two air blocks above the base", false), "Place");
	public final BooleanSetting visibleSides = group(new BooleanSetting("Visible sides only", "Only click base faces you can see (line-of-sight check)", false), "Place");
	public final BooleanSetting surroundGaps = group(new BooleanSetting("Surround gaps", "Put a crystal in a gap of a target's surround the moment it opens, and prefer crystals that keep it open", true), "Place");
	public final EnumSetting<Support> support = group(new EnumSetting<>("Support",
		"Place obsidian first when no base works (from the inventory too with Inv swap or From inventory). Fast puts the crystal on it in the same batch; Accurate waits Support delay ticks for the server", Support.DISABLED), "Place");
	public final NumberSetting supportDelay = group(new NumberSetting("Support delay", "Ticks after a support block (Accurate)", 1, 0, 20, 1), "Place");

	// Face place
	public final BooleanSetting facePlace = group(new BooleanSetting("Face place", "Lower the damage bar to finish weak or bare targets", true), "Face place");
	public final NumberSetting facePlaceHealth = group(new NumberSetting("Face place health", "At or below this health", 8, 1, 36, 0.5), "Face place");
	public final NumberSetting facePlaceDurability = group(new NumberSetting("Face place durability", "When an armour piece is at or below this percent", 2, 1, 100, 1), "Face place");
	public final BooleanSetting facePlaceMissingArmor = group(new BooleanSetting("Missing armor", "When an armour slot is empty", false), "Face place");
	public final KeybindSetting forceFacePlace = group(new KeybindSetting("Force face place", "Hold to face place anyway", KeybindSetting.UNBOUND), "Face place");

	// Break
	public final BooleanSetting doBreak = group(new BooleanSetting("Break", "Break crystals", true), "Break");
	public final NumberSetting breakDelay = group(new NumberSetting("Break delay", "Ticks between breaks", 0, 0, 20, 1), "Break");
	public final NumberSetting breaksPerTick = group(new NumberSetting("Breaks per tick", "Most crystals broken in one tick", 2, 1, 4, 1), "Break");
	public final BooleanSetting hurtSync = group(new BooleanSetting("Hurt sync", "Hold a ready crystal until the target's hit immunity ends, so none is spent on a hit that deals nothing", true), "Break");
	public final NumberSetting breakRange = group(new NumberSetting("Break range", "How far you break", 4.5, 0, 6, 0.1), "Break");
	public final NumberSetting breakWallsRange = group(new NumberSetting("Break walls range", "How far you break through walls", 4.5, 0, 6, 0.1), "Break");
	public final BooleanSetting multiPoint = group(new BooleanSetting("Multi point", "Count a crystal as seen when any point of its box is in sight, not just its centre", true), "Break");
	public final BooleanSetting onlyOwn = group(new BooleanSetting("Only own", "Only break crystals you placed", false), "Break");
	public final NumberSetting breakAttempts = group(new NumberSetting("Break attempts", "Hits per crystal before giving up", 2, 1, 5, 1), "Break");
	public final NumberSetting ticksExisted = group(new NumberSetting("Ticks existed", "Crystal age before breaking", 0, 0, 20, 1), "Break");
	public final NumberSetting attackFrequency = group(new NumberSetting("Attack frequency", "Most breaks per second", 25, 1, 30, 1), "Break");
	public final BooleanSetting fastBreak = group(new BooleanSetting("Fast break", "Act the moment the server reports a new crystal, a blast or a cleared spot, not on the next tick", true), "Break");
	public final BooleanSetting defend = group(new BooleanSetting("Defend", "Break an enemy crystal near you while your own hit immunity makes its blast almost harmless, so it cannot hit you later", true), "Break");
	public final BooleanSetting idPredict = group(new BooleanSetting("ID predict", "Right after placing, hit the entity id the new crystal will most likely get, so it breaks in the same server tick at any ping", false), "Break");
	public final NumberSetting idPredictCount = group(new NumberSetting("ID predict count", "How many upcoming entity ids to hit", 1, 1, 3, 1), "Break");
	public final BooleanSetting kickGuard = group(new BooleanSetting("Kick guard", "Skip predicting when the id could belong to a dropped item, arrow or XP orb (servers that kick for hitting those)", false), "Break");

	// Pause
	public final EnumSetting<Phase> pauseOnUse = group(new EnumSetting<>("Pause on use", "While eating or using an item", Phase.PLACE), "Pause");
	public final EnumSetting<Phase> pauseOnMine = group(new EnumSetting<>("Pause on mine", "While mining, or while PacketMine breaks a block", Phase.NONE), "Pause");
	public final BooleanSetting pauseOnLag = group(new BooleanSetting("Pause on lag", "While the server is not responding", true), "Pause");
	public final BooleanSetting pauseInPortal = group(new BooleanSetting("Pause in portal", "While standing in a portal", true), "Pause");
	public final BooleanSetting pauseOnKillAura = group(new BooleanSetting("Pause with KillAura", "While KillAura is fighting", false), "Pause");
	public final NumberSetting pauseHealth = group(new NumberSetting("Pause health", "At or below this health", 5, 0, 36, 0.5), "Pause");

	// Render
	public final EnumSetting<Swing> swing = group(new EnumSetting<>("Swing", "Swing your hand (Client), tell the server (Packet), both or neither", Swing.BOTH), "Render");
	public final BooleanSetting render = group(new BooleanSetting("Render", "Box where a crystal was placed", true), "Render");
	public final BooleanSetting renderBreak = group(new BooleanSetting("Render break", "Box where a crystal was broken", false), "Render");
	public final NumberSetting renderTime = group(new NumberSetting("Render time", "Ticks a box stays", 10, 1, 40, 1), "Render");
	public final ColorSetting color = group(new ColorSetting("Color", "Box colour", 0xFFFF4060), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "Box style", WorldOverlays.Shape.Both), "Render");
	public final BooleanSetting showDamage = group(new BooleanSetting("Damage", "Show the damage over the box", true), "Render");
	public final BooleanSetting showStatus = group(new BooleanSetting("Show status", "Show in the HUD list why the last crystal was or was not placed", true), "Render");

	/** Face-place damage bar. */
	private static final float FACE_PLACE_DAMAGE = 1.5f;
	/** Score bonuses: a kill beats any amount of damage; the current focus and surround gaps tip close calls. */
	private static final float LETHAL_BONUS = 100, FOCUS_BONUS = 1, GAP_BONUS = 2;
	/** Totem aware: a kill on a target holding a totem only pops it, so it is worth this share of a kill. */
	private static final float TOTEM_FACTOR = 0.3f;
	/** Totem aware: face place this long after a target pops, while its absorption hides how weak it is. */
	private static final long COMBO_MS = 1500;
	/** A base that took this many crystals in a row with none appearing is skipped for a while. */
	private static final int STUCK_PLACES = 3;
	private static final long STUCK_MS = 3000;
	/** Ticks another target must stay best before the focus switches to it. */
	private static final int FOCUS_TICKS = 20;
	/** Scores this close count as a tie, broken by self damage (Minimize self damage widens it). */
	private static final float TIE = 0.5f;
	/** Preferred distance: a crystal this close to the preferred distance counts as at it. */
	private static final double PREFERRED_BAND = 0.75;
	/** Support: no support block while one of your crystals is on its way this close to the target. */
	private static final double SUPPORT_WAIT = 3;
	/** Hurt sync (Simple): hold a crystal while immunity would take more than this share of its hit. */
	private static final float SIMPLE_HOLD_SHARE = 0.5f;
	private static final int MAX_PREDICT_TICKS = 6;
	/** Defend: an enemy crystal worth breaking threatens this much, and may cost this much through immunity. */
	private static final float DEFENSE_THREAT = 4, DEFENSE_FREE = 0.5f;
	/** The server lets you hit entities this far away. */
	private static final double SERVER_BREAK_RANGE = 6;
	private static final double PREDICT_CLEAR_RANGE = 8;
	/** Kick guard: weak blocks this close to a predicted blast could drop items with the predicted id. */
	private static final int DROP_RADIUS = 11;
	private static final double EXPLOSION_NOTICE = 20;
	private static final int GAP_PLACES = 1;
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private record Target(LivingEntity entity, AABB box, Vec3 feet, float health, float minDamage, LongSet gaps, float lethalBonus) {
	}

	/** A rated crystal: best target, damage to it (after immunity when timed), raw damage, cost to you and score. */
	private record Choice(Target target, float damage, float raw, float self, float score) {
		boolean beats(Choice other, float tie) {
			if (other == null) return true;
			if (Math.abs(score - other.score) > tie) return score > other.score;
			return self < other.self;
		}
	}

	private record Spot(BlockPos base, Choice choice, boolean needsSupport) {
	}

	private final Map<Integer, Integer> attempts = new HashMap<>();
	private int breakTimer, placeTimer, ticksPassed, attacks;
	private volatile int switchTimer;
	private boolean didRotate;
	/** Set when Auto Chestplate put a chestplate on, so Smart can put the elytra back. */
	private boolean swappedChestplate;
	private Item mainItem, offItem;
	private BlockPos placeRender, breakRender;
	private int placeRenderTimer, breakRenderTimer;
	private double renderDamage;

	// Smart mode state
	private int placeBudget, breakBudget, gapBudget;
	private boolean running;
	private LivingEntity lastTarget, focus;
	private int challengeTicks;
	/** When a crystal held back by hurt sync becomes worth breaking (wall clock), 0 for none. */
	private long releaseAt;
	private final Long2FloatOpenHashMap selfCache = new Long2FloatOpenHashMap();
	private final Long2ObjectOpenHashMap<Int2FloatOpenHashMap> rawCache = new Long2ObjectOpenHashMap<>();
	private final IntOpenHashSet predictedIds = new IntOpenHashSet();
	/** When each entity last popped a totem. */
	private final Int2LongOpenHashMap pops = new Int2LongOpenHashMap();
	/** Crystals placed on each base with none appearing, and bases skipped until a time. */
	private final Long2IntOpenHashMap misses = new Long2IntOpenHashMap();
	private final Long2LongOpenHashMap stuckUntil = new Long2LongOpenHashMap();
	/** Avoid own surround: your surround cells this pass. */
	private LongSet ownRing = LongSets.EMPTY_SET;
	private long predictedBase, predictedAt;
	private LivingEntity predictedTarget;
	private String status;
	/** Crystal reserve reached: only kills are placed. */
	private boolean reserveOnly;
	/** Async calc: the spot rating running on the calc thread. */
	private CompletableFuture<Spot> pending;
	/** Async calc: the pass {@link #pending} runs, and the last one that finished (its tallies give the status). */
	private Pass asyncRunning, asyncDone;
	/** Simple: spots the last placement pass could rate at all (free, in range); none means "no spot". */
	private int rated;
	private long lastDropRisk;
	private final List<Vec3> ownBreaks = new ArrayList<>();
	private final List<Long> ownBreakTimes = new ArrayList<>();
	private List<Target> targets = List.of();
	/** A crystal or drop near you was in the removal packet being applied. */
	private boolean crystalGoneNear;
	/** Crystals placed this tick, and the bases whose crystal was hit this tick (extra places only refill those). */
	private int placedThisTick;
	private final LongOpenHashSet brokenThisTick = new LongOpenHashSet();
	/** The last support block and when it was placed (one at a time). */
	private BlockPos supportBase;
	private long supportAt;
	/**
	 * Rotate: this tick's look is a hit on the crystal standing on {@code lookBase}; a crystal put back on
	 * that base goes in {@code lookFollowUp} and is placed in the same look, right after the hit.
	 */
	private Runnable[] lookFollowUp;
	private BlockPos lookBase;
	/** Hurt sync: a pass waiting for the moment a held crystal is worth breaking, and that moment. */
	private ScheduledFuture<?> releaseTask;
	private long scheduledRelease;

	public CrystalAura() {
		super("CrystalAura", List.of("Crystal Aura", "AutoCrystal", "CA"), Category.COMBAT, "Places and blows up end crystals near targets.");
		// Hidden in Simple means Simple ignores it.
		for (Setting<?> s : List.of(predictMovement, onlyOwn, breakAttempts, fastBreak, defend, idPredict,
			surroundGaps, placesPerTick, breaksPerTick, friendProtection, asyncCalc, minimizeSelf, crystalReserve,
			lethalMultiplier, totemAware, timing, avoidOwnSurround))
			s.visibleWhen(() -> mode.get() == Mode.SMART);
		for (Setting<?> s : List.of(idPredictCount, kickGuard)) s.visibleWhen(() -> mode.get() == Mode.SMART && idPredict.get());
		for (Setting<?> s : List.of(yawStepMode, yawSteps)) s.visibleWhen(rotate::get);
		for (Setting<?> s : List.of(noGapSwitch, noBowSwitch, fromInventory)) s.visibleWhen(() -> autoSwitch.get() != AutoSwitch.NONE);
		maxReduction.visibleWhen(() -> mode.get() == Mode.SMART && minimizeSelf.get());
		supportDelay.visibleWhen(() -> support.get() == Support.ACCURATE);
		for (Setting<?> s : List.of(facePlaceHealth, facePlaceDurability, facePlaceMissingArmor, forceFacePlace))
			s.visibleWhen(facePlace::get);
		for (Setting<?> s : List.of(renderTime, color, shape)) s.visibleWhen(() -> render.get() || renderBreak.get());
		showDamage.visibleWhen(render::get);

		listen(Events.PacketSend.class, e -> {
			if (!e.isCancelled() && e.packet instanceof ServerboundSetCarriedItemPacket) switchTimer = switchDelay.intValue();
		});
		listen(Events.EntityAdded.class, e -> onEntityAdded(e.entity()));
		listen(Events.TotemPop.class, e -> pops.put(e.entity().getId(), System.currentTimeMillis()));
		listen(Events.PacketApply.Pre.class, e -> {
			if (e.packet instanceof ClientboundRemoveEntitiesPacket packet) onRemoveEntities(packet);
		});
		listen(Events.PacketApply.Post.class, e -> onPacketApplied(e.packet()));
		listen(Events.GameLeft.class, e -> clearState());
		listen(Events.WorldChange.class, e -> clearState());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	@Override
	protected void onEnable() {
		breakTimer = placeTimer = ticksPassed = attacks = 0;
		placeRenderTimer = breakRenderTimer = 0;
		placeBudget = placesPerTick.intValue();
		breakBudget = breaksPerTick.intValue();
		gapBudget = GAP_PLACES;
		clearState();
		if (autoChestplate.get() != AutoChestplate.NONE) equipChestplate();
	}

	@Override
	protected void onDisable() {
		if (autoChestplate.get() == AutoChestplate.SMART) equipElytra();
		swappedChestplate = false;
		if (predictedAt != 0) endPrediction();
		clearState();
		CombatTick.release(this);
	}

	/** Auto Chestplate: swap the worn elytra for the best inventory chestplate. */
	private void equipChestplate() {
		LocalPlayer player = mc().player;
		// Inv's slot ids are only valid while the player's own inventory menu is open.
		if (player == null || player.containerMenu != player.inventoryMenu) return;
		if (!player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;
		int best = ChestSwap.bestChestplate(player.getInventory().getNonEquipmentItems());
		if (best < 0) return;
		Inv.move(Inv.inventoryId(best), Inv.armorId(EquipmentSlot.CHEST));
		swappedChestplate = true;
	}

	/** Smart: put the first elytra back after {@link #equipChestplate} ran. */
	private void equipElytra() {
		LocalPlayer player = mc().player;
		if (!swappedChestplate || player == null || player.containerMenu != player.inventoryMenu) return;
		if (player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;
		var slots = player.getInventory().getNonEquipmentItems();
		for (int i = 0; i < slots.size(); i++) {
			if (!slots.get(i).has(DataComponents.GLIDER)) continue;
			Inv.move(Inv.inventoryId(i), Inv.armorId(EquipmentSlot.CHEST));
			return;
		}
	}

	private void clearState() {
		attempts.clear();
		targets = List.of();
		lastTarget = focus = null;
		challengeTicks = 0;
		releaseAt = 0;
		predictedAt = 0;
		predictedTarget = null;
		pending = null;
		asyncRunning = asyncDone = null;
		pops.clear();
		misses.clear();
		stuckUntil.clear();
		status = null;
		ownBreaks.clear();
		ownBreakTimes.clear();
		supportBase = null;
		supportAt = 0;
		lookFollowUp = null;
		lookBase = null;
		if (releaseTask != null) releaseTask.cancel(false);
		releaseTask = null;
		scheduledRelease = 0;
	}

	/** The player or mob CrystalAura is fighting, or null. */
	public LivingEntity target() {
		return isEnabled() ? focus : null;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) return;
		Latency.probe();
		didRotate = false;
		if (++ticksPassed >= 20) {
			ticksPassed = 0;
			attacks = 0;
		}
		if (breakTimer > 0) breakTimer--;
		if (placeTimer > 0) placeTimer--;
		if (switchTimer > 0) switchTimer--;
		if (placeRenderTimer > 0) placeRenderTimer--;
		if (breakRenderTimer > 0) breakRenderTimer--;
		placeBudget = placesPerTick.intValue();
		breakBudget = breaksPerTick.intValue();
		gapBudget = GAP_PLACES;
		placedThisTick = 0;
		brokenThisTick.clear();
		lookFollowUp = null;
		lookBase = null;
		clearCache();
		if (predictedAt != 0 && System.currentTimeMillis() - predictedAt > Latency.timeout()) endPrediction();
		attempts.keySet().removeIf(id -> mc.level.getEntity(id) == null);

		if (mode.get() == Mode.SMART) {
			pass();
			updateFocus();
		} else {
			simpleTick();
		}
	}

	// ---- events (Smart) ----

	private void onEntityAdded(Entity entity) {
		if (mc().player == null || mode.get() != Mode.SMART) return;
		if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
			// A drop landed on a spot we are placing on: that crystal will not come.
			if (near(entity.position()) && CrystalTracker.cancelPlacing(entity.getBoundingBox()) && fastBreak.get()) pass();
			return;
		}
		if (!(entity instanceof EndCrystal crystal)) return;
		misses.remove(crystal.blockPosition().below().asLong());
		if (predictedAt != 0) {
			if (System.currentTimeMillis() - predictedAt > Latency.timeout()) endPrediction();
			else if (crystal.blockPosition().below().asLong() == predictedBase) {
				if (predictedIds.contains(crystal.getId())) {
					// The predicted hit already broke it on the server.
					CrystalTracker.kill(crystal, Latency.timeout());
					if (predictedTarget != null) HurtTracker.confirmSpeculative(predictedTarget);
					predictedAt = 0;
				} else {
					endPrediction();
				}
			}
		}
		if (fastBreak.get() && near(crystal.position())) pass();
	}

	private void onRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		for (int id : packet.getEntityIds()) {
			Entity entity = mc.level.getEntity(id);
			if ((entity instanceof EndCrystal || entity instanceof ItemEntity || entity instanceof ExperienceOrb) && near(entity.position())) {
				crystalGoneNear = true;
				return;
			}
		}
	}

	/** Blasts, removed crystals and cleared blocks near you can open a spot: act on them at once. */
	private void onPacketApplied(net.minecraft.network.protocol.Packet<?> packet) {
		Minecraft mc = mc();
		if (mc.player == null) return;
		if (packet instanceof ClientboundExplodePacket explosion && !HurtTracker.isWind(explosion)
			&& explosion.center().distanceToSqr(mc.player.position()) <= EXPLOSION_NOTICE * EXPLOSION_NOTICE && !isOwnBreak(explosion.center())) {
			lastDropRisk = System.currentTimeMillis();
		}
		if (mode.get() != Mode.SMART) return;
		// Any change near you spoils the cached damage; only a cleared block (or blast, or removed crystal) can open a spot.
		boolean changed = false, opened = false;
		switch (packet) {
			case ClientboundExplodePacket explosion -> changed = opened = near(explosion.center());
			case ClientboundRemoveEntitiesPacket ignored -> opened = crystalGoneNear;
			case ClientboundBlockUpdatePacket update -> {
				changed = near(Vec3.atCenterOf(update.getPos()));
				opened = changed && update.getBlockState().canBeReplaced();
			}
			case ClientboundSectionBlocksUpdatePacket update -> {
				boolean[] found = {false, false};
				update.runUpdates((pos, state) -> {
					if (!near(Vec3.atCenterOf(pos))) return;
					found[0] = true;
					found[1] |= state.canBeReplaced();
				});
				changed = found[0];
				opened = found[1];
			}
			default -> {
			}
		}
		crystalGoneNear = false;
		if (changed) clearCache();
		if (opened && fastBreak.get()) pass();
	}

	private boolean near(Vec3 pos) {
		double range = placeRange.get() + Damage.CRYSTAL_RANGE;
		return mc().player.getEyePosition().distanceToSqr(pos) <= range * range;
	}

	// ---- Smart ----

	private void pass() {
		// With rotations, actions wait for the movement packet: once a tick is enough.
		if (running || !canAct() || rotate.get() && didRotate) return;
		running = true;
		try {
			run();
		} finally {
			running = false;
		}
	}

	private void run() {
		if (releaseAt != 0 && System.currentTimeMillis() >= releaseAt) releaseAt = 0;
		targets = findTargets();
		if (targets.isEmpty()) {
			lastTarget = null;
			status = null;
			return;
		}
		float health = Damage.health(mc().player);
		boolean holding = false;
		if (doBreak.get() && breakTimer <= 0 && switchTimer <= 0 && !paused(Phase.BREAK)) holding = breakBudget > 0 && breakCrystals(health);
		// Rotate: one look per tick, and a break (or a yaw step towards one) already took it. Only a
		// crystal back on the broken base can share a hit's look.
		if (rotate.get() && didRotate && lookFollowUp == null) return;
		if (!doPlace.get() || placeTimer > 0 || paused(Phase.PLACE)) {
			if (mc().player.isUsingItem() && pauseOnUse.get().matches(Phase.PLACE)) status = "eating";
			return;
		}
		if (!crystalsAvailable()) {
			status = "no crystals";
			return;
		}
		if (switchBlocked()) return;
		if (timing.get() == Timing.SEQUENTIAL && (CrystalTracker.hasPlacing() || ownCrystalUp())) {
			status = "sequential";
			return;
		}
		if (surroundGaps.get() && gapBudget > 0) fillGaps(health);
		if (placeBudget <= 0) return;
		if (holding) {
			status = "hurt sync";
			return;
		}

		ownRing = avoidOwnSurround.get() ? ring(mc().player.getBoundingBox()) : LongSets.EMPTY_SET;
		reserveOnly = crystalReserve.intValue() > 0 && mc().player.getInventory().countItem(Items.END_CRYSTAL) <= crystalReserve.intValue();
		Pass pass = asyncCalc.get() ? null : pass(false);
		Spot spot = pass == null ? asyncSpot(health) : bestSpot(health, pass);
		if (spot != null) {
			// A second place in one tick only refills a base whose crystal was just hit, never a worse spot.
			if (placedThisTick == 0 || brokenThisTick.contains(spot.base().asLong())) placeSpot(spot);
			return;
		}
		Pass tally = pass != null ? pass : asyncDone;
		if (tally == null || tally.rated == 0) status = "no spot";
		else if (tally.rejectedSelf) status = "self damage";
		else if (tally.rejectedFriend) status = "friend";
		else if (tally.rejectedReserve) status = "reserve";
		else status = "low damage";
	}

	private void updateFocus() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		double range2 = targetRange.get() * targetRange.get();
		if (focus != null && (!focus.isAlive() || focus.isRemoved() || focus.distanceToSqr(player) > range2)) focus = null;
		if (lastTarget != null && (!lastTarget.isAlive() || lastTarget.distanceToSqr(player) > range2)) lastTarget = null;
		if (focus == null) {
			focus = lastTarget;
			challengeTicks = 0;
		} else if (lastTarget != null && lastTarget != focus) {
			if (++challengeTicks >= FOCUS_TICKS) {
				focus = lastTarget;
				challengeTicks = 0;
			}
		} else {
			challengeTicks = 0;
		}
	}

	private List<Target> findTargets() {
		List<Target> found = new ArrayList<>();
		LocalPlayer self = mc().player;
		double range2 = targetRange.get() * targetRange.get();
		int ticks = predictTicks();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!valid(entity, self, range2)) continue;
			LivingEntity living = (LivingEntity) entity;
			AABB box = MotionTracker.predict(living, ticks);
			Vec3 feet = MotionTracker.feet(box);
			float health = Damage.health(living);
			LongSet gaps = living instanceof Player ? ring(box) : LongSets.EMPTY_SET;
			float lethal = totemAware.get() && holdsTotem(living) ? LETHAL_BONUS * TOTEM_FACTOR : LETHAL_BONUS;
			found.add(new Target(living, box, feet, health, minimumDamage(living, health), gaps, lethal));
		}
		return found;
	}

	/** The cells around the blocks {@code box} stands in: a surround, where one is built. */
	private LongSet ring(AABB box) {
		LongSet cells = new LongOpenHashSet();
		for (BlockPos cell : Footprint.ring(Footprint.columns(box, Footprint.blocks(mc().level)))) cells.add(cell.asLong());
		return cells;
	}

	private static boolean holdsTotem(LivingEntity entity) {
		return entity.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || entity.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
	}

	/** Sequential: one of your crystals is still up and breakable. */
	private boolean ownCrystalUp() {
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof EndCrystal crystal && CrystalTracker.isOwn(crystal) && breakable(crystal)) return true;
		}
		return false;
	}

	/** Enough damage to use on {@code target}: the bar, a kill, or (Lethal multiplier) a kill in that many crystals. */
	private boolean enough(Target target, float damage) {
		int lethal = lethalMultiplier.intValue();
		return damage >= target.minDamage || damage >= target.health || lethal > 0 && damage * lethal >= target.health;
	}

	/** Most damage one crystal may deal to you: Max self damage, and Max self share of your health. */
	private float selfLimit() {
		return Math.min(maxDamage.floatValue(), Damage.health(mc().player) * maxSelfShare.floatValue() / 100);
	}

	private boolean valid(Entity entity, LocalPlayer self, double range2) {
		if (!(entity instanceof LivingEntity living) || living == self || !living.isAlive() || living.isDeadOrDying()) return false;
		if (living instanceof Player player) {
			if (Targets.isProtectedFriend(player) || Targets.gameMode(player) == GameType.CREATIVE || Targets.gameMode(player) == GameType.SPECTATOR) return false;
			if (ignoreNakeds.get() && naked(player)) return false;
		}
		return entities.has(living) && living.distanceToSqr(self) <= range2;
	}

	private int predictTicks() {
		return mode.get() == Mode.SMART && predictMovement.get() ? Math.min(Mth.ceil(Latency.rtt() / 50.0), MAX_PREDICT_TICKS) : 0;
	}

	private float minimumDamage(LivingEntity target, float health) {
		boolean combo = mode.get() == Mode.SMART && totemAware.get() && System.currentTimeMillis() - pops.getOrDefault(target.getId(), 0L) < COMBO_MS;
		return combo || facePlace(target, health) ? Math.min(minDamage.floatValue(), FACE_PLACE_DAMAGE) : minDamage.floatValue();
	}

	private boolean facePlace(LivingEntity target, float health) {
		if (!facePlace.get()) return false;
		if (forceFacePlace.isBound() && KeyNames.isHeld(forceFacePlace.key())) return true;
		if (health <= facePlaceHealth.get()) return true;
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = target.getItemBySlot(slot);
			if (stack.isEmpty()) {
				if (facePlaceMissingArmor.get()) return true;
			} else if (stack.isDamageableItem()
				&& (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage() * 100 <= facePlaceDurability.get()) {
				return true;
			}
		}
		return false;
	}

	// ---- Smart: break ----

	/** Breaks the best crystals; true when a ready crystal is being held for hurt sync and none was broken. */
	private boolean breakCrystals(float health) {
		long arrival = System.currentTimeMillis() + Latency.rtt() / 2;
		long release = 0;
		boolean held = false, broke = false;
		while (breakBudget > 0 && attacks < attackFrequency.intValue()) {
			EndCrystal best = null;
			Choice bestChoice = null;
			for (Entity entity : mc().level.entitiesForRendering()) {
				if (!(entity instanceof EndCrystal crystal) || !breakable(crystal)) continue;
				Choice choice = evaluate(crystal.position(), health, hurtSync.get() ? arrival : 0, mc().level);
				if (choice == null) {
					if (hurtSync.get()) {
						Choice ready = evaluate(crystal.position(), health, 0, mc().level);
						if (ready != null) {
							held = true;
							long time = HurtTracker.releaseTime(ready.target.entity);
							if (time > 0 && (release == 0 || time < release)) release = time;
						}
					}
					continue;
				}
				if (choice.beats(bestChoice, tie())) {
					best = crystal;
					bestChoice = choice;
				}
			}
			if (best == null || !breakCrystal(best)) break;
			HurtTracker.expect(bestChoice.target.entity, bestChoice.raw);
			lastTarget = bestChoice.target.entity;
			broke = true;
			if (rotate.get()) break;
		}
		releaseAt = release;
		if (release > 0 && !rotate.get()) scheduleRelease(release);
		if (defend.get() && breakBudget > 0 && !(rotate.get() && didRotate) && defend(health)) broke = true;
		return held && !broke;
	}

	/**
	 * Hurt sync: run a pass the moment a held crystal becomes worth breaking, not at the next tick (up to
	 * 50 ms later each time). Without Rotate only: a rotated hit waits for the movement packet anyway.
	 */
	private void scheduleRelease(long at) {
		if (at == scheduledRelease) return;
		if (releaseTask != null) releaseTask.cancel(false);
		scheduledRelease = at;
		Minecraft mc = mc();
		releaseTask = Async.ioLater(() -> mc.execute(() -> {
			if (scheduledRelease != at) return;
			scheduledRelease = 0;
			releaseTask = null;
			if (isEnabled() && mode.get() == Mode.SMART && !rotate.get() && mc.player != null) pass();
		}), Math.max(0, at - System.currentTimeMillis()));
	}

	/** A live crystal in break range that this module may hit (Only own and Break attempts are Smart's). */
	private boolean breakable(EndCrystal crystal) {
		if (!CrystalTracker.isAlive(crystal)) return false;
		boolean smart = mode.get() == Mode.SMART;
		if (smart && onlyOwn.get() && !CrystalTracker.isOwn(crystal)) return false;
		if (smart && attempts.getOrDefault(crystal.getId(), 0) >= breakAttempts.intValue()) return false;
		if (crystal.tickCount < ticksExisted.intValue()) return false;
		return !outOfRange(crystal);
	}

	/** Breaks an enemy crystal near you while your immunity takes almost all of its blast. */
	private boolean defend(float health) {
		long arrival = System.currentTimeMillis() + Latency.rtt() / 2;
		boolean broke = false;
		LocalPlayer player = mc().player;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (breakBudget <= 0) return broke;
			if (!(entity instanceof EndCrystal crystal) || !CrystalTracker.isAlive(crystal)) continue;
			if (outOfRange(crystal)) continue;
			Vec3 center = crystal.position();
			float raw = Damage.crystalRaw(player, player.position(), player.getBoundingBox(), center, mc().level);
			float full = Damage.mitigate(player, raw);
			if (full < DEFENSE_THREAT || full >= health) continue;
			if (HurtTracker.effectiveOnSelf(raw, arrival) > DEFENSE_FREE || hurtsFriend(center, mc().level)) continue;
			// Leave crystals that are worth something against a target, or whose blast would spoil one.
			if (evaluate(center, health, 0, mc().level) != null || spoilsReady(center, health)) continue;
			if (!breakCrystal(crystal)) return broke;
			for (Target target : targets) {
				float hit = Damage.crystalRaw(target.entity, target.feet, target.box, center, mc().level);
				if (hit > 0) HurtTracker.expect(target.entity, hit);
			}
			broke = true;
			if (rotate.get()) return true;
		}
		return broke;
	}

	private boolean spoilsReady(Vec3 center, float health) {
		double range = Damage.CRYSTAL_RANGE * Damage.CRYSTAL_RANGE;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EndCrystal other) || !CrystalTracker.isAlive(other) || other.position().equals(center)) continue;
			if (other.position().distanceToSqr(center) > range) continue;
			if (evaluate(other.position(), health, 0, mc().level) != null) return true;
		}
		return CrystalTracker.placingNear(center, Damage.CRYSTAL_RANGE);
	}

	/** Hits {@code crystal} (with Weakness through a weapon); false when it could not be hit now. */
	private boolean breakCrystal(EndCrystal crystal) {
		if (antiWeakness.get() && weakened() && !breaksWithWeakness(mc().player.getMainHandItem())) {
			boolean anywhere = autoSwitch.get() == AutoSwitch.SWAP;
			Inv.FindItemResult weapon = anywhere ? Inv.find(this::breaksWithWeakness, 0, 35) : Inv.findInHotbar(this::breaksWithWeakness);
			if (!weapon.found()) {
				status = "weakness";
				return false;
			}
			if (autoSwitch.get() == AutoSwitch.SILENT || anywhere) {
				return hit(crystal, () -> Inv.withItem(weapon.slot(), !anywhere, () -> attack(crystal)));
			}
			Inv.swap(weapon.slot(), false);
			switchTimer = 1;
			return false;
		} else if (weakened() && !breaksWithWeakness(mc().player.getMainHandItem())) {
			status = "weakness";
			return false;
		}
		return hit(crystal, () -> attack(crystal));
	}

	/**
	 * Runs {@code attack} now or (Rotate) when the look is sent, and books the hit. False, with nothing
	 * booked, when the look could not be had this tick: a hit booked but never sent would mark a live
	 * crystal as dying.
	 */
	private boolean hit(EndCrystal crystal, Runnable attack) {
		if (rotate.get()) {
			Rotations.Rotation look = Rotations.needed(crystal.position());
			Runnable[] then = {null};
			Runnable send = () -> {
				attack.run();
				if (then[0] != null) then[0].run();
			};
			if (!yawStep(look) || !Rotations.rotate(look, Rotations.HIGH, send)) return false;
			didRotate = true;
			lookFollowUp = then;
			lookBase = crystal.blockPosition().below();
		} else {
			attack.run();
		}
		breakTimer = breakDelay.intValue();
		breakBudget--;
		attempts.merge(crystal.getId(), 1, Integer::sum);
		CrystalTracker.explodeAt(crystal.position());
		breakRender = crystal.blockPosition().below();
		breakRenderTimer = renderTime.intValue();
		brokenThisTick.add(breakRender.asLong());
		return true;
	}

	private void attack(Entity crystal) {
		LocalPlayer player = mc().player;
		if (player == null) return;
		PacketBatch.run(() -> {
			player.connection.send(new ServerboundAttackPacket(crystal.getId()));
			swing(player.getOffhandItem().is(Items.END_CRYSTAL) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		});
		attacks++;
		noteOwnBreak(crystal.position());
		if (kickGuard.get() && dropRisk(crystal.position())) lastDropRisk = System.currentTimeMillis();
	}

	private boolean weakened() {
		MobEffectInstance weakness = mc().player.getEffect(MobEffects.WEAKNESS);
		MobEffectInstance strength = mc().player.getEffect(MobEffects.STRENGTH);
		return weakness != null && (strength == null || strength.getAmplifier() <= weakness.getAmplifier());
	}

	/** Swords, axes and maces deal enough damage through Weakness to pop a crystal. */
	private boolean breaksWithWeakness(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.getItem() instanceof MaceItem;
	}

	// ---- Smart: place ----

	/** The base in reach whose crystal scores best; a real base beats any support spot. */
	private Spot bestSpot(float health, Pass pass) {
		int r = (int) Math.ceil(Math.max(placeRange.get(), placeWallsRange.get()));
		BlockPos center = BlockPos.containing(mc().player.getEyePosition());
		Spot best = null;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			Spot spot = rateSpot(pos, health, pass.allowSupport && (best == null || best.needsSupport()), pass);
			if (spot != null && spotBeats(spot, best)) best = spot;
		}
		return best;
	}

	/**
	 * A real base beats a support spot, a kill beats the rest, then (Preferred distance) a spot at that
	 * distance beats one off it. Then the higher score wins; close calls go to the spot nearest the
	 * target (Minimize self damage: the one that hurts you least).
	 */
	private boolean spotBeats(Spot spot, Spot best) {
		if (best == null) return true;
		if (spot.needsSupport() != best.needsSupport()) return !spot.needsSupport();
		Choice a = spot.choice(), b = best.choice();
		boolean kill = a.damage >= a.target.health, bestKill = b.damage >= b.target.health;
		if (kill != bestKill) return kill;
		if (preferredDistance.get() > 0) {
			boolean preferred = atPreferred(CrystalTracker.center(spot.base())), bestPreferred = atPreferred(CrystalTracker.center(best.base()));
			if (preferred != bestPreferred) return preferred;
		}
		if (Math.abs(a.score - b.score) > tie()) return a.score > b.score;
		if (minimizeSelf.get() && a.self != b.self) return a.self < b.self;
		return CrystalTracker.center(spot.base()).distanceToSqr(a.target.feet) < CrystalTracker.center(best.base()).distanceToSqr(b.target.feet);
	}

	/** Preferred distance: a crystal at {@code crystal} is about that far from your eyes. */
	private boolean atPreferred(Vec3 crystal) {
		return Math.abs(Math.sqrt(mc().player.getEyePosition().distanceToSqr(crystal)) - preferredDistance.get()) <= PREFERRED_BAND;
	}

	/** A crystal on {@code pos} (or on support obsidian there, when allowed), or null when it cannot go there or rates too low. */
	private Spot rateSpot(BlockPos pos, float health, boolean allowSupport, Pass pass) {
		BlockState state = mc().level.getBlockState(pos);
		boolean hasBase = isBase(state);
		if (!hasBase && (!allowSupport || !state.canBeReplaced())) return null;
		// A support block needs ground to stand on; without it nearly every air block would be rated.
		if (!hasBase && !mc().level.getBlockState(pos.below()).isFaceSturdy(mc().level, pos.below(), Direction.UP)) return null;
		if (!roomAbove(pos) || CrystalTracker.placingOverlaps(pos) || blocked(pos)) return null;
		if (pass.ring.contains(pos.above().asLong()) || System.currentTimeMillis() < pass.stuck.getOrDefault(pos.asLong(), 0L)) return null;
		Vec3 crystal = CrystalTracker.center(pos);
		if (outOfRange(crystal, pos.above(), true)) return null;
		if (!hasBase && overlapsTarget(pos)) return null;
		BlockGetter view = hasBase ? mc().level : Damage.View.obsidian(mc().level, pos);
		pass.rated++;
		Choice choice = evaluate(crystal, health, 0, view, pass);
		if (choice == null) return null;
		if (pass.reserveOnly && choice.damage < choice.target.health) {
			pass.rejectedReserve = true;
			return null;
		}
		return new Spot(pos.immutable(), choice, !hasBase);
	}

	/**
	 * One placement pass: its inputs, and what it turned down (for the status). The calc thread's pass
	 * gets copies, so it never walks a collection the client thread is changing.
	 */
	private static final class Pass {
		final LongSet ring;
		final Long2LongMap stuck;
		final List<? extends Player> players;
		final boolean allowSupport, reserveOnly;
		int rated;
		boolean rejectedSelf, rejectedFriend, rejectedReserve;

		Pass(LongSet ring, Long2LongMap stuck, List<? extends Player> players, boolean allowSupport, boolean reserveOnly) {
			this.ring = ring;
			this.stuck = stuck;
			this.players = players;
			this.allowSupport = allowSupport;
			this.reserveOnly = reserveOnly;
		}
	}

	/** This tick's pass inputs; {@code copied} for the calc thread. Client thread. */
	private Pass pass(boolean copied) {
		return new Pass(ownRing, copied ? new Long2LongOpenHashMap(stuckUntil) : stuckUntil,
			copied ? List.copyOf(mc().level.players()) : mc().level.players(), supportAllowed(), reserveOnly);
	}

	/** Async calc: the spot the calc thread found last time, checked again now, and the next rating started. */
	private Spot asyncSpot(float health) {
		Spot spot = null;
		if (pending != null && pending.isDone()) {
			Spot found = pending.getNow(null);
			pending = null;
			// Completing the future publishes the calc thread's tallies; the status reads them from here.
			asyncDone = asyncRunning;
			if (found != null) {
				Pass check = pass(false);
				spot = rateSpot(found.base(), health, found.needsSupport() && check.allowSupport, check);
			}
		}
		if (pending == null) {
			Pass job = pass(true);
			asyncRunning = job;
			pending = Async.calc(() -> bestSpot(health, job));
		}
		return spot;
	}

	private boolean supportAllowed() {
		return support.get() != Support.DISABLED && findObsidian().found();
	}

	/** Obsidian in the hotbar, else (Inv swap or From inventory) in the main inventory. */
	private Inv.FindItemResult findObsidian() {
		Predicate<ItemStack> obsidian = stack -> stack.is(Items.OBSIDIAN);
		Inv.FindItemResult hotbar = Inv.findInHotbar(obsidian);
		return hotbar.found() || !fromInventoryAllowed() ? hotbar : Inv.find(obsidian, 9, 35);
	}

	/** Places a support block on {@code base}; obsidian from the main inventory is swapped in and back. */
	private boolean placeSupport(BlockPos base, Inv.FindItemResult obsidian) {
		boolean clientSwing = swing.get().client();
		if (!obsidian.isMain()) return BlockActions.place(base, obsidian, rotate.get(), Rotations.HIGH, clientSwing, true, false);
		BlockActions.PlaceParams params = BlockActions.placeParams(base);
		double reach = mc().player.blockInteractionRange();
		if (params == null || params.requiresSneaking() || params.distanceSq() > reach * reach || !BlockActions.canPlace(base, Blocks.OBSIDIAN, true)) return false;
		BlockHitResult hit = params.hitResult();
		Runnable place = () -> Inv.withItem(obsidian.slot(), false, () -> BlockActions.interact(hit, InteractionHand.MAIN_HAND, clientSwing));
		if (rotate.get()) return Rotations.rotate(hit.getLocation(), Rotations.HIGH, place);
		place.run();
		return true;
	}

	private float tie() {
		return minimizeSelf.get() ? Math.max(maxReduction.floatValue(), TIE) : TIE;
	}

	private boolean overlapsTarget(BlockPos base) {
		for (Target target : targets) if (target.box.intersects(new AABB(base))) return true;
		return false;
	}

	private void placeSpot(Spot spot) {
		// Rotate, with this tick's look already spent on a hit: only the broken base can still take a crystal.
		boolean chained = rotate.get() && lookFollowUp != null;
		if (chained && (spot.needsSupport() || !spot.base().equals(lookBase))) {
			status = "rotating";
			return;
		}
		if (spot.needsSupport()) {
			// One support block at a time, and none while one of your crystals is already on its way to this target.
			if (supportBase != null && System.currentTimeMillis() - supportAt < Latency.timeout() && !isBase(mc().level.getBlockState(supportBase))) return;
			if (CrystalTracker.placingNear(spot.choice().target.feet, SUPPORT_WAIT)) return;
			if (!placeSupport(spot.base(), findObsidian())) return;
			supportBase = spot.base();
			supportAt = System.currentTimeMillis();
			status = "support";
			if (rotate.get() || support.get() == Support.ACCURATE || !isBase(mc().level.getBlockState(spot.base()))) {
				didRotate |= rotate.get();
				placeTimer += supportDelay.intValue();
				return;
			}
			// Fast: the client already shows the obsidian, so the crystal goes on it in the same batch.
		}
		BlockHitResult hit = chained ? topFace(spot.base()) : placeInfo(spot.base());
		if (hit == null) {
			status = "no face";
			return;
		}
		Choice choice = spot.choice();
		Runnable place = () -> PacketBatch.run(() -> {
			if (placeCrystal(hit) && idPredict.get() && mode.get() == Mode.SMART) predictAttack(spot.base(), choice);
		});
		if (chained) {
			// The hit and the click share one look: the crystal stood on the very point the click lands on.
			lookFollowUp[0] = place;
			lookFollowUp = null;
		} else if (rotate.get()) {
			Rotations.Rotation look = Rotations.needed(hit.getLocation());
			// Nothing is booked unless the look is taken: a booked place that never happens hides its base.
			if (yawStepMode.get() == YawSteps.ALL && !yawStep(look) || !Rotations.rotate(look, Rotations.HIGH, place)) {
				status = "rotating";
				return;
			}
			didRotate = true;
		} else {
			boolean[] placed = {false};
			PacketBatch.run(() -> {
				placed[0] = placeCrystal(hit);
				if (placed[0] && idPredict.get() && mode.get() == Mode.SMART) predictAttack(spot.base(), choice);
			});
			if (!placed[0]) {
				status = "switch";
				return;
			}
		}
		placeTimer += placeDelay.intValue();
		placeBudget--;
		placedThisTick++;
		status = String.format(java.util.Locale.ROOT, "%.1f dmg", choice.damage);
		lastTarget = choice.target.entity;
		CrystalTracker.placed(spot.base());
		long key = spot.base().asLong();
		if (misses.addTo(key, 1) + 1 >= STUCK_PLACES) {
			misses.remove(key);
			stuckUntil.put(key, System.currentTimeMillis() + STUCK_MS);
		}
		placeRender = spot.base();
		placeRenderTimer = renderTime.intValue();
		renderDamage = choice.damage;
		CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 8);
	}

	/** Puts a crystal into a gap of a target's surround the moment it opens. */
	private void fillGaps(float health) {
		for (Target target : targets) {
			for (long key : target.gaps) {
				if (gapBudget <= 0 || rotate.get() && didRotate) return;
				BlockPos gap = BlockPos.of(key);
				BlockPos base = gap.below();
				if (!canFillGap(gap, target, health)) continue;
				BlockHitResult hit = placeInfo(base);
				if (hit == null) continue;
				if (rotate.get()) {
					if (!Rotations.rotate(Rotations.needed(hit.getLocation()), Rotations.HIGH, () -> placeCrystal(hit))) return;
					didRotate = true;
				} else if (!placeCrystal(hit)) {
					return;
				}
				gapBudget--;
				CrystalTracker.placed(base);
				status = "gap";
				lastTarget = target.entity;
				placeRender = base;
				placeRenderTimer = renderTime.intValue();
			}
		}
	}

	private boolean canFillGap(BlockPos gap, Target target, float health) {
		BlockPos base = gap.below();
		if (!mc().level.getBlockState(gap).isAir() || !isBase(mc().level.getBlockState(base))) return false;
		if (CrystalTracker.isPlacing(base) || blocked(base) || !roomAbove(base)) return false;
		Vec3 center = CrystalTracker.center(base);
		if (outOfRange(center, gap, true) || !selfSafe(center, health, 0, mc().level) || hurtsFriend(center, mc().level)) return false;
		float raw = Damage.crystalRaw(target.entity, target.feet, target.box, center, mc().level);
		return Damage.mitigate(target.entity, raw) >= FACE_PLACE_DAMAGE;
	}

	/** Places a crystal on the clicked base, switching to crystals as set; false when none could be used. */
	private boolean placeCrystal(BlockHitResult hit) {
		LocalPlayer player = mc().player;
		if (player == null) return false;
		Predicate<ItemStack> want = stack -> stack.is(Items.END_CRYSTAL);
		if (player.getOffhandItem().is(Items.END_CRYSTAL)) {
			PacketBatch.run(() -> click(InteractionHand.OFF_HAND, hit));
			return true;
		}
		if (player.getMainHandItem().is(Items.END_CRYSTAL)) {
			PacketBatch.run(() -> click(InteractionHand.MAIN_HAND, hit));
			return true;
		}
		switch (autoSwitch.get()) {
			case NONE -> {
				return false;
			}
			case NORMAL -> {
				Inv.FindItemResult hotbar = Inv.find(want, 0, 8);
				if (hotbar.found()) Inv.swap(hotbar.slot(), false);
				else if (!fromInventory.get() || !Inv.select(want)) return false;
				PacketBatch.run(() -> click(InteractionHand.MAIN_HAND, hit));
				return true;
			}
			case SILENT -> {
				Inv.FindItemResult item = Inv.find(want, 0, 8);
				if (!item.found()) {
					if (!fromInventory.get()) return false;
					item = Inv.find(want, 9, 35);
					return item.found() && Inv.withItem(item.slot(), false, () -> click(InteractionHand.MAIN_HAND, hit));
				}
				return Inv.withItem(item.slot(), true, () -> click(InteractionHand.MAIN_HAND, hit));
			}
			default -> {
				Inv.FindItemResult item = Inv.find(want, 0, 35);
				return item.found() && Inv.withItem(item.slot(), false, () -> click(InteractionHand.MAIN_HAND, hit));
			}
		}
	}

	/** Click without sneaking, like BlockActions.interact, but with this module's own swing. */
	private void click(InteractionHand hand, BlockHitResult hit) {
		LocalPlayer player = mc().player;
		boolean sneaking = player.isShiftKeyDown();
		player.setShiftKeyDown(false);
		try {
			mc().gameMode.useItemOn(player, hand, hit);
		} finally {
			player.setShiftKeyDown(sneaking);
		}
		swing(hand);
	}

	/** Hits the entity ids the new crystal will most likely get, so it breaks in the same server tick. */
	private void predictAttack(BlockPos base, Choice choice) {
		LocalPlayer player = mc().player;
		if (weakened() && !breaksWithWeakness(player.getMainHandItem()) || !predictSafe()) return;
		int highest = CrystalTracker.highestId();
		if (highest <= 0) return;
		if (hurtSync.get()) {
			float effective = HurtTracker.effective(choice.target.entity, choice.raw, System.currentTimeMillis() + Latency.rtt() / 2);
			if (!enough(choice.target, effective)) return;
		}
		Vec3 center = CrystalTracker.center(base);
		if (kickGuard.get() && dropRisk(center)) return;
		if (predictedAt != 0) endPrediction();
		predictedIds.clear();
		for (int i = 1; i <= idPredictCount.intValue(); i++) {
			int id = highest + i;
			if (mc().level.getEntity(id) != null) continue;
			player.connection.send(new ServerboundAttackPacket(id));
			predictedIds.add(id);
		}
		if (predictedIds.isEmpty()) return;
		noteOwnBreak(center);
		predictedAt = System.currentTimeMillis();
		predictedBase = base.asLong();
		predictedTarget = choice.target.entity;
		HurtTracker.expectSpeculative(choice.target.entity, choice.raw);
	}

	/**
	 * A blind hit is only safe when whatever crystal could have that id cannot hurt you or a friend
	 * too much: no friend in blast reach and no base in server reach that would.
	 */
	private boolean predictSafe() {
		LocalPlayer player = mc().player;
		if (kickGuard.get()) {
			if (System.currentTimeMillis() - lastDropRisk < Latency.rtt() + 2L * Latency.jitter() + 50) return false;
			AABB area = player.getBoundingBox().inflate(PREDICT_CLEAR_RANGE);
			if (!mc().level.getEntities(player, area, e -> e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof AbstractArrow).isEmpty()) return false;
		}
		double friendRange = Damage.CRYSTAL_RANGE * Damage.CRYSTAL_RANGE;
		for (Player other : mc().level.players()) {
			if (other != player && Targets.isProtectedFriend(other) && other.distanceToSqr(player) <= friendRange) return false;
		}
		float health = Damage.health(player);
		Vec3 eyes = player.getEyePosition();
		BlockPos center = BlockPos.containing(eyes);
		int radius = Mth.ceil(SERVER_BREAK_RANGE) + 1;
		for (BlockPos base : BlockPos.betweenClosed(center.offset(-radius, -radius - Footprint.MAX_DROP, -radius), center.offset(radius, radius, radius))) {
			if (!isBase(mc().level.getBlockState(base)) || !mc().level.getBlockState(base.above()).isAir()) continue;
			AABB box = CrystalTracker.crystalBox(base).inflate(0.5, 0, 0.5);
			if (box.distanceToSqr(eyes) > SERVER_BREAK_RANGE * SERVER_BREAK_RANGE || box.intersects(player.getBoundingBox())) continue;
			float self = selfDamage(CrystalTracker.center(base), mc().level);
			if (self >= health || self > selfLimit()) return false;
		}
		return true;
	}

	/** Weak blocks or fragile entities in reach of a blast at {@code crystal}, whose drops could take a predicted id. */
	private boolean dropRisk(Vec3 crystal) {
		double range = Damage.CRYSTAL_RANGE * Damage.CRYSTAL_RANGE;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity == mc().player || entity.distanceToSqr(crystal) > range) continue;
			if (entity instanceof ArmorStand stand && stand.isMarker()) continue;
			if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity || entity instanceof ArmorStand
				|| entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity) return true;
			if (entity instanceof LivingEntity living && living.isAlive() && Damage.upperBound(living, living.position(), crystal) >= Damage.health(living)) return true;
		}
		BlockPos center = BlockPos.containing(crystal);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -DROP_RADIUS; dx <= DROP_RADIUS; dx++) {
			for (int dy = -DROP_RADIUS; dy <= DROP_RADIUS; dy++) {
				for (int dz = -DROP_RADIUS; dz <= DROP_RADIUS; dz++) {
					if (dx * dx + dy * dy + dz * dz > DROP_RADIUS * DROP_RADIUS) continue;
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = mc().level.getBlockState(pos);
					if (!state.isAir() && state.getFluidState().isEmpty() && state.getBlock().getExplosionResistance() < 600) return true;
				}
			}
		}
		return false;
	}

	private void noteOwnBreak(Vec3 crystal) {
		long now = System.currentTimeMillis();
		for (int i = ownBreakTimes.size() - 1; i >= 0; i--) {
			if (now - ownBreakTimes.get(i) > Latency.timeout()) {
				ownBreakTimes.remove(i);
				ownBreaks.remove(i);
			}
		}
		ownBreaks.add(crystal);
		ownBreakTimes.add(now);
	}

	private boolean isOwnBreak(Vec3 center) {
		long now = System.currentTimeMillis();
		for (int i = 0; i < ownBreaks.size(); i++) {
			if (now - ownBreakTimes.get(i) <= Latency.timeout() && ownBreaks.get(i).distanceToSqr(center) < 0.01) return true;
		}
		return false;
	}

	private void endPrediction() {
		if (predictedTarget != null) HurtTracker.forgetSpeculative(predictedTarget);
		predictedAt = 0;
	}

	// ---- Smart: rating ----

	/**
	 * Rates a crystal at {@code crystal}: the target it hurts most (with bonuses for kills, the focus and
	 * surround gaps), or null when no target takes enough or it costs you or a friend too much. With
	 * {@code arrival} the damage is what is left after each target's hit immunity at that time.
	 */
	private Choice evaluate(Vec3 crystal, float health, long arrival, BlockGetter view) {
		return evaluate(crystal, health, arrival, view, null);
	}

	/** @param pass the placement pass to tally rejections in (and whose players to protect), or null */
	private Choice evaluate(Vec3 crystal, float health, long arrival, BlockGetter view, Pass pass) {
		boolean cached = view == mc().level && mc().isSameThread();
		long key = BlockPos.containing(crystal).asLong();

		boolean reachable = false;
		for (Target target : targets) {
			float bound = Damage.upperBound(target.entity, target.feet, crystal);
			if (enough(target, bound)) {
				reachable = true;
				break;
			}
		}
		if (!reachable) return null;

		float self = selfDamage(crystal, view);
		if (antiSuicide.get() && self >= Damage.health(mc().player)) {
			if (pass != null) pass.rejectedSelf = true;
			return null;
		}
		if (arrival != 0) self = HurtTracker.effectiveOnSelf(selfRaw(crystal, view), arrival);
		if (self > selfLimit()) {
			if (pass != null) pass.rejectedSelf = true;
			return null;
		}
		if (hurtsFriend(crystal, view, pass == null ? mc().level.players() : pass.players)) {
			if (pass != null) pass.rejectedFriend = true;
			return null;
		}

		Choice best = null;
		for (Target target : targets) {
			float bound = Damage.upperBound(target.entity, target.feet, crystal);
			if (!enough(target, bound)) continue;
			boolean gap = surroundGaps.get() && target.gaps.contains(key);
			float bonus = FOCUS_BONUS + (bound >= target.health ? target.lethalBonus : 0) + (gap ? GAP_BONUS : 0);
			if (best != null && bound + bonus < best.score) continue;

			float raw;
			Int2FloatOpenHashMap raws = cached ? rawCache.computeIfAbsent(key, k -> new Int2FloatOpenHashMap()) : null;
			if (raws != null && raws.containsKey(target.entity.getId())) {
				raw = raws.get(target.entity.getId());
			} else {
				raw = Damage.crystalRaw(target.entity, target.feet, target.box, crystal, view);
				if (raws != null) raws.put(target.entity.getId(), raw);
			}
			float damage = arrival != 0 ? HurtTracker.effective(target.entity, raw, arrival) : Damage.mitigate(target.entity, raw);
			if (!enough(target, damage)) continue;

			float score = damage + (target.entity == focus ? FOCUS_BONUS : 0);
			if (damage >= target.health) score += target.lethalBonus;
			if (gap) score += GAP_BONUS;
			Choice choice = new Choice(target, damage, raw, self, score);
			if (choice.beats(best, tie())) best = choice;
		}
		return best;
	}

	private boolean selfSafe(Vec3 crystal, float health, long arrival, BlockGetter view) {
		float full = selfDamage(crystal, view);
		if (antiSuicide.get() && full >= health) return false;
		if (arrival == 0) return full <= selfLimit();
		return HurtTracker.effectiveOnSelf(selfRaw(crystal, view), arrival) <= selfLimit();
	}

	private float selfDamage(Vec3 crystal, BlockGetter view) {
		return Damage.mitigate(mc().player, selfRaw(crystal, view));
	}

	private float selfRaw(Vec3 crystal, BlockGetter view) {
		boolean cached = view == mc().level && mc().isSameThread();
		long key = BlockPos.containing(crystal).asLong();
		if (cached && selfCache.containsKey(key)) return selfCache.get(key);
		LocalPlayer player = mc().player;
		float raw = Damage.crystalRaw(player, player.position(), player.getBoundingBox(), crystal, view);
		if (cached) selfCache.put(key, raw);
		return raw;
	}

	private boolean hurtsFriend(Vec3 crystal, BlockGetter view) {
		return hurtsFriend(crystal, view, mc().level.players());
	}

	private boolean hurtsFriend(Vec3 crystal, BlockGetter view, List<? extends Player> players) {
		FriendProtect protect = friendProtection.get();
		if (protect == FriendProtect.OFF) return false;
		double range = Damage.CRYSTAL_RANGE * Damage.CRYSTAL_RANGE;
		for (Player player : players) {
			if (player == mc().player || !Targets.isProtectedFriend(player) || player.distanceToSqr(crystal) > range) continue;
			float damage = Damage.mitigate(player, Damage.crystalRaw(player, player.position(), player.getBoundingBox(), crystal, view));
			if (damage >= Damage.health(player) || protect == FriendProtect.STRICT && damage > maxDamage.get()) return true;
		}
		return false;
	}

	private void clearCache() {
		selfCache.clear();
		rawCache.clear();
	}

	// ---- Simple ----

	private void simpleTick() {
		targets = findTargets();
		if (targets.isEmpty()) {
			status = null;
			return;
		}
		float health = Damage.health(mc().player);
		boolean holding = false;
		if (doBreak.get() && breakTimer <= 0 && switchTimer <= 0 && attacks < attackFrequency.intValue() && !paused(Phase.BREAK)) {
			// : furthest crystal first.
			long arrival = hurtSync.get() ? System.currentTimeMillis() + Latency.rtt() / 2 : 0;
			EndCrystal best = null;
			double bestDistance = -1;
			for (Entity entity : mc().level.entitiesForRendering()) {
				if (!(entity instanceof EndCrystal crystal) || !breakable(crystal) || !selfSafe(crystal.position(), health, 0, mc().level)) continue;
				if (arrival != 0 && heldByImmunity(crystal.position(), arrival)) {
					holding = true;
					continue;
				}
				double distance = mc().player.distanceToSqr(crystal);
				if (distance > bestDistance) {
					bestDistance = distance;
					best = crystal;
				}
			}
			if (best != null && breakCrystal(best)) {
				holding = false;
				for (Target target : targets) {
					float raw = Damage.crystalRaw(target.entity, target.feet, target.box, best.position(), mc().level);
					if (raw > 0) HurtTracker.expect(target.entity, raw);
				}
			}
		}
		// Rotate: a hit took this tick's look; only a crystal back on its base can share it.
		if (didRotate && lookFollowUp == null || !doPlace.get() || placeTimer > 0 || paused(Phase.PLACE)) return;
		if (!crystalsAvailable()) {
			status = "no crystals";
			return;
		}
		if (switchBlocked()) return;
		if (holding) {
			status = "hurt sync";
			return;
		}
		Spot spot = simpleSpot(health);
		if (spot != null) placeSpot(spot);
		else status = rated == 0 ? "no spot" : "low damage";
	}

	/** Hurt sync (Simple): the crystal reaches a target, and every target it reaches is still immune to most of the hit. */
	private boolean heldByImmunity(Vec3 crystal, long arrival) {
		boolean reaches = false;
		for (Target target : targets) {
			float raw = Damage.crystalRaw(target.entity, target.feet, target.box, crystal, mc().level);
			float full = Damage.mitigate(target.entity, raw);
			if (full <= 0) continue;
			if (HurtTracker.effective(target.entity, raw, arrival) >= full * SIMPLE_HOLD_SHARE) return false;
			reaches = true;
		}
		return reaches;
	}

	/**
	 * Simple: for each target (nearest first), the free base nearest its eyes that deals Min damage (Preferred
	 * distance: one at that distance first). With Support, and no base that does, the nearest support spot
	 * that would.
	 */
	private Spot simpleSpot(float health) {
		LocalPlayer player = mc().player;
		var level = mc().level;
		boolean allowSupport = supportAllowed();
		rated = 0;
		List<Target> order = new ArrayList<>(targets);
		order.sort(Comparator.comparingDouble((Target t) -> t.entity.distanceToSqr(player)));
		for (Target target : order) {
			Vec3 eyes = target.entity.getEyePosition();
			List<BlockPos> bases = new ArrayList<>(), supports = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(target.entity.blockPosition().offset(-2, -3, -2), target.entity.blockPosition().offset(2, 1, 2))) {
				BlockState state = level.getBlockState(pos);
				boolean hasBase = isBase(state);
				if (!hasBase && (!allowSupport || !state.canBeReplaced() || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP))) continue;
				if (!roomAbove(pos) || target.entity.getBoundingBox().intersects(new AABB(pos.above()))) continue;
				if (!hasBase && overlapsTarget(pos)) continue;
				Vec3 crystal = CrystalTracker.center(pos);
				BlockGetter view = hasBase ? level : Damage.View.obsidian(level, pos);
				if (outOfRange(crystal, pos.above(), true) || !selfSafe(crystal, health, 0, view) || blocked(pos)) continue;
				(hasBase ? bases : supports).add(pos.immutable());
			}
			rated += bases.size() + supports.size();
			Comparator<BlockPos> rank = Comparator.comparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos.above())));
			if (preferredDistance.get() > 0) rank = Comparator.comparing((BlockPos pos) -> !atPreferred(CrystalTracker.center(pos))).thenComparing(rank);
			Spot spot = simpleFirst(target, bases, rank, false);
			if (spot == null) spot = simpleFirst(target, supports, rank, true);
			if (spot != null) return spot;
		}
		return null;
	}

	/** The first of {@code bases} in {@code rank} order whose crystal deals {@code target} Min damage (or kills it). */
	private Spot simpleFirst(Target target, List<BlockPos> bases, Comparator<BlockPos> rank, boolean needsSupport) {
		bases.sort(rank);
		for (BlockPos base : bases) {
			BlockGetter view = needsSupport ? Damage.View.obsidian(mc().level, base) : mc().level;
			float raw = Damage.crystalRaw(target.entity, target.feet, target.box, CrystalTracker.center(base), view);
			float damage = Damage.mitigate(target.entity, raw);
			if (damage >= target.minDamage || damage >= target.health) return new Spot(base, new Choice(target, damage, raw, 0, 0), needsSupport);
		}
		return null;
	}

	// ---- shared checks ----

	private static boolean isBase(BlockState state) {
		return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
	}

	private boolean canAct() {
		LocalPlayer player = mc().player;
		return player != null && mc().level != null && mc().gameMode != null && player.isAlive() && !player.isSpectator();
	}

	private boolean crystalsAvailable() {
		if (Inv.findInHotbar(stack -> stack.is(Items.END_CRYSTAL)).found()) return true;
		return fromInventoryAllowed() && Inv.find(stack -> stack.is(Items.END_CRYSTAL), 9, 35).found();
	}

	/** Items may come from the main inventory: Inv swap, or From inventory with switching on. */
	private boolean fromInventoryAllowed() {
		return autoSwitch.get() == AutoSwitch.SWAP || fromInventory.get() && autoSwitch.get() != AutoSwitch.NONE;
	}

	/** Normal switching must not take golden apples or a bow out of your hand. */
	private boolean switchBlocked() {
		LocalPlayer player = mc().player;
		mainItem = player.getMainHandItem().getItem();
		offItem = player.getOffhandItem().getItem();
		if (mainItem == Items.END_CRYSTAL || offItem == Items.END_CRYSTAL) return false;
		if (autoSwitch.get() == AutoSwitch.NONE) return true;
		if (noGapSwitch.get() && autoSwitch.get() == AutoSwitch.NORMAL && (gapple(mainItem) || gapple(offItem))) return true;
		return noBowSwitch.get() && (mainItem == Items.BOW || offItem == Items.BOW);
	}

	private static boolean gapple(Item item) {
		return item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE;
	}

	private static boolean naked(Player player) {
		for (EquipmentSlot slot : EquipmentSlot.values()) if (!player.getItemBySlot(slot).isEmpty()) return false;
		return true;
	}

	private boolean roomAbove(BlockPos base) {
		if (!mc().level.getBlockState(base.above()).isAir()) return false;
		return !placement112.get() || mc().level.getBlockState(base.above(2)).isAir();
	}

	/** A crystal on {@code base} would collide with an entity (crystals and drops already doomed don't count). */
	private boolean blocked(BlockPos base) {
		// Vanilla checks the two blocks above the base in every version, 1.12 included.
		AABB box = CrystalTracker.crystalBox(base);
		return !mc().level.getEntities((Entity) null, box, e -> !e.isSpectator()
			&& (!(e instanceof EndCrystal || e instanceof ItemEntity || e instanceof ExperienceOrb) || CrystalTracker.isAlive(e))).isEmpty();
	}

	/** The face of {@code base} to click: one you can see, else (unless Visible sides only) the top or bottom. */
	private BlockHitResult placeInfo(BlockPos base) {
		LocalPlayer player = mc().player;
		Vec3 eyes = player.getEyePosition();
		for (Direction side : Direction.values()) {
			Vec3 face = Vec3.atCenterOf(base).add(side.getUnitVec3i().getX() * 0.5, side.getUnitVec3i().getY() * 0.5, side.getUnitVec3i().getZ() * 0.5);
			BlockHitResult hit = mc().level.clip(new ClipContext(eyes, face, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(base)) return hit;
		}
		if (visibleSides.get()) return null;
		Direction side = base.getY() > eyes.y ? Direction.DOWN : Direction.UP;
		return new BlockHitResult(Vec3.atCenterOf(base).add(0, side == Direction.UP ? 0.5 : -0.5, 0), side, base, false);
	}

	/**
	 * The centre of {@code base}'s top face, where a crystal on it stands, so a click there shares the
	 * look of a hit on that crystal. Null when the face is out of sight from below, or (Visible sides only)
	 * hidden behind a block.
	 */
	private BlockHitResult topFace(BlockPos base) {
		LocalPlayer player = mc().player;
		Vec3 eyes = player.getEyePosition();
		Vec3 top = CrystalTracker.center(base);
		if (eyes.y <= top.y) return null;
		if (visibleSides.get()) {
			BlockHitResult seen = mc().level.clip(new ClipContext(eyes, top.subtract(0, 0.05, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(base) || seen.getDirection() != Direction.UP) return null;
		}
		return new BlockHitResult(top, Direction.UP, base, false);
	}

	private void swing(InteractionHand hand) {
		LocalPlayer player = mc().player;
		Swing mode = swing.get();
		// LocalPlayer.swing(hand) animates and sends the packet; the two-argument form only animates.
		if (mode.client() && mode.packet()) player.swing(hand);
		else if (mode.client()) player.swing(hand, false);
		else if (mode.packet()) player.connection.send(new ServerboundSwingPacket(hand));
	}

	/** yaw steps: true when the server look is within the step, otherwise turns one step now. */
	private boolean yawStep(Rotations.Rotation look) {
		float server = Rotations.serverYaw();
		float diff = Math.abs(Mth.wrapDegrees(look.yaw() - server));
		if (diff <= yawSteps.floatValue()) return true;
		float step = Rotations.limitAngleChange(server, look.yaw(), yawSteps.floatValue());
		didRotate = true;
		Rotations.rotate(step, look.pitch(), Rotations.LOW, null);
		return false;
	}

	private boolean outOfRange(Vec3 point, BlockPos expected, boolean placing) {
		LocalPlayer player = mc().player;
		Vec3 eyes = player.getEyePosition();
		BlockHitResult hit = mc().level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean throughWall = hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(expected);
		double range = throughWall ? (placing ? placeWallsRange : breakWallsRange).get() : (placing ? placeRange : breakRange).get();
		return eyes.distanceToSqr(point) > range * range;
	}

	/** A crystal is in break range when its centre is, or (Multi point) any corner of its box is in sight and range. */
	private boolean outOfRange(EndCrystal crystal) {
		if (!outOfRange(crystal.position(), crystal.blockPosition(), false)) return false;
		if (!multiPoint.get()) return true;
		LocalPlayer player = mc().player;
		Vec3 eyes = player.getEyePosition();
		double range2 = breakRange.get() * breakRange.get();
		AABB box = crystal.getBoundingBox().deflate(0.05);
		for (int i = 0; i < 8; i++) {
			Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
			if (eyes.distanceToSqr(corner) > range2) continue;
			if (mc().level.clip(new ClipContext(eyes, corner, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS) return false;
		}
		return true;
	}

	private boolean paused(Phase phase) {
		Minecraft mc = mc();
		if (pauseInPortal.get() && mc.level.getBlockStatesIfLoaded(mc.player.getBoundingBox())
			.anyMatch(s -> s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY))) {
			status = "portal";
			return true;
		}
		if ((mc.player.isUsingItem() || mc.options.keyUse.isDown()) && pauseOnUse.get().matches(phase)) return true;
		if (pauseOnLag.get() && CombatTick.lagging()) return true;
		if (pauseOnKillAura.get() && CombatTick.busy(CombatTick.Kind.MELEE, this)) return true;
		if (pauseOnMine.get().matches(phase) && (mc.gameMode.isDestroying() || PacketMine.mining())) {
			status = "mining";
			return true;
		}
		return Damage.health(mc.player) <= pauseHealth.get();
	}

	// ---- render ----

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		List<AABB> boxes = new ArrayList<>(2);
		if (render.get() && placeRenderTimer > 0 && placeRender != null) boxes.add(new AABB(placeRender));
		if (renderBreak.get() && breakRenderTimer > 0 && breakRender != null) boxes.add(new AABB(breakRender));
		if (boxes.isEmpty()) return;
		int[] colors = new int[boxes.size()];
		java.util.Arrays.fill(colors, color.argb() & 0xFFFFFF);
		EspRender.boxes(boxes, colors, shape.get(), false, 1.5f, (color.argb() >>> 24) / 3, cameraPos);
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (!render.get() || !showDamage.get() || placeRenderTimer <= 0 || placeRender == null) return;
		float[] pos = projector.project(placeRender.getX() + 0.5, placeRender.getY() + 0.5, placeRender.getZ() + 0.5);
		if (pos == null) return;
		String text = String.format(java.util.Locale.ROOT, "%.1f", renderDamage);
		Draw.textCentered(ui.g, text, (int) pos[0], (int) pos[1] - 4, 0xFFFFFFFF);
	}

	@Override
	public String infoString() {
		if (mode.get() == Mode.SIMPLE) return !showStatus.get() || status == null ? "Simple" : "Simple " + status;
		String name = focus == null ? null : focus.getName().getString();
		if (!showStatus.get() || status == null) return name;
		return name == null ? status : name + " " + status;
	}
}
