package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import dev.coffeeclient.module.modules.movement.elytrafly.FireworkUse;
import dev.coffeeclient.module.modules.movement.elytrafly.GlideTricks;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Boost;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Bounce;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Constant;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Control;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Hold;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Packet;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Pitch40;
import dev.coffeeclient.module.modules.movement.elytrafly.modes.Vanilla;
import dev.coffeeclient.module.modules.player.ChestSwap;
import dev.coffeeclient.module.modules.render.Freecam;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gives you full control over elytra flight with seven modes: Vanilla, Packet, Pitch40, Bounce,
 * Control (Vanilla with a fall that follows your pitch), Constant (accelerates to an exact speed)
 * Boost (steady thrust along your view) and Hold (pitch steered to hold an altitude or speed).
 * Recovery picks the glide back up after water or lava, holds still after a lag-back and swims
 * you up out of lava. Durability, Packets and Elytra walk hold the packet-level options (see
 * {@link GlideTricks}).
 *
 * <p>{@code Rotation} module is not ported, so Bounce uses an internal yaw lock instead.
 */
public final class ElytraFly extends Module {
	/** What happens to your velocity when the module is switched off mid-flight. */
	public enum DisableMomentum {
		Stop, Keep_Speed, Vanilla
	}

	public enum ChestSwapMode {
		Always,
		Never,
		Wait_For_Ground
	}

	public enum YawLock {
		None,
		Smart,
		Simple
	}

	public enum HoldTarget {
		Altitude, Speed
	}

	// General

	public final EnumSetting<ElytraFlightModes> flightMode = group(new EnumSetting<>("Mode", "The mode of flying", ElytraFlightModes.Vanilla), "General");

	public final BooleanSetting autoTakeOff = group(new BooleanSetting("Auto take-off", "Automatically takes off when you hold jump without needing to double jump", false)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "General");

	public final NumberSetting fallMultiplier = group(new NumberSetting("Fall multiplier", "Controls how fast you go down naturally", 0.01, 0, 1, 0.01)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "General");

	public final NumberSetting horizontalSpeed = group(new NumberSetting("Horizontal speed", "How fast you go forward and backward", 1, 0, 10, 0.1)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "General");

	public final NumberSetting verticalSpeed = group(new NumberSetting("Vertical speed", "How fast you go up and down", 1, 0, 10, 0.1)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "General");

	public final BooleanSetting acceleration = group(new BooleanSetting("Acceleration", "Gradually accelerates your horizontal speed", false)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "General");

	public final NumberSetting accelerationStep = group(new NumberSetting("Acceleration step", "How quickly acceleration builds up", 1, 0.1, 5, 0.1)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && acceleration.get() && !glidePhysicsMode()), "General");

	public final NumberSetting accelerationMin = group(new NumberSetting("Acceleration start", "The speed acceleration starts at", 0, 0, 1, 0.1)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && acceleration.get() && !glidePhysicsMode()), "General");

	public final BooleanSetting stopInWater = group(new BooleanSetting("Stop in water", "Stops flying in water", true)
		.visibleWhen(() -> !glidePhysicsMode()), "General");

	public final BooleanSetting dontGoIntoUnloadedChunks = group(new BooleanSetting("No unloaded chunks", "Stops you from going into unloaded chunks", true), "General");

	public final BooleanSetting autoHover = group(new BooleanSetting("Auto hover", "Automatically hover 0.3 blocks off the ground when holding shift", false)
		.visibleWhen(() -> !glidePhysicsMode()), "General");

	public final BooleanSetting noCrash = group(new BooleanSetting("No crash", "Stops you from flying into walls", false)
		.visibleWhen(() -> !glidePhysicsMode()), "General");

	public final NumberSetting crashLookAhead = group(new NumberSetting("Crash look ahead", "Distance to look ahead when flying", 5, 1, 15, 1)
		.visibleWhen(() -> noCrash.get() && !glidePhysicsMode()), "General");

	public final EnumSetting<DisableMomentum> onDisable = group(new EnumSetting<>("On disable",
		"Stop: kill momentum. Keep speed: continue at the speed you were flying. Vanilla: keep the elytra's own (often much higher) velocity",
		DisableMomentum.Stop).visibleWhen(() -> !glidePhysicsMode()), "General");
	public final BooleanSetting instaDrop = group(new BooleanSetting("Insta drop", "Makes you drop out of flight instantly", false)
		.visibleWhen(() -> !glidePhysicsMode()), "General");

	public final NumberSetting pitch40lowerBounds = group(new NumberSetting("Pitch40 lower bounds", "The bottom height boundary for Pitch40", 180, -128, 360, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40), "General");

	public final NumberSetting pitch40upperBounds = group(new NumberSetting("Pitch40 upper bounds", "The upper height boundary for Pitch40", 220, -128, 360, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40), "General");

	public final NumberSetting pitch40rotationSpeedUp = group(new NumberSetting("Pitch40 rotate speed up", "Rotation speed upwards (degrees per tick)", 5.45, 1, 20, 0.05)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40), "General");

	public final NumberSetting pitch40rotationSpeedDown = group(new NumberSetting("Pitch40 rotate speed down", "Rotation speed downwards (degrees per tick)", 0.90, 0.5, 2, 0.05)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40), "General");

	public final BooleanSetting autoJump = group(new BooleanSetting("Auto jump", "Automatically jumps for you", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final EnumSetting<YawLock> yawLockMode = group(new EnumSetting<>("Yaw lock", "Whether to lock your yaw angle", YawLock.Smart)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final NumberSetting yaw = group(new NumberSetting("Yaw", "The yaw angle to look at when using simple yaw lock in bounce mode", 0, 0, 360, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yawLockMode.get() == YawLock.Simple), "General");

	public final BooleanSetting lockPitch = group(new BooleanSetting("Pitch lock", "Whether to lock your pitch angle", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final NumberSetting pitch = group(new NumberSetting("Pitch", "The pitch angle to look at in bounce mode", 85, 0, 90, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && lockPitch.get()), "General");

	public final BooleanSetting restart = group(new BooleanSetting("Restart", "Restarts flying with the elytra when rubberbanding", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final NumberSetting restartDelay = group(new NumberSetting("Restart delay", "Ticks to wait before restarting the elytra after rubberbanding", 7, 0, 20, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && restart.get()), "General");

	public final BooleanSetting sprint = group(new BooleanSetting("Sprint constantly", "Sprint all the time; if off, only sprint while on the ground", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final BooleanSetting manualTakeoff = group(new BooleanSetting("Manual takeoff", "Does not automatically take off", false)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final BooleanSetting smartPitch = group(new BooleanSetting("Smart pitch", "Look straight down while rising, nearly level while falling (the fastest bounce)", false)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && lockPitch.get()), "General");

	public final NumberSetting setbackPause = group(new NumberSetting("Setback pause", "Ticks to stop flying after the server sets you back", 5, 0, 100, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final BooleanSetting minimizePackets = group(new BooleanSetting("Minimize packets", "Send the start-fly packet only when a take-off can work", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && !manualTakeoff.get()), "General");

	public final BooleanSetting laneKeeping = group(new BooleanSetting("Lane keeping", "Sneak-strafe back onto the locked line when you drift off it", false)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yawLockMode.get() != YawLock.None), "General");

	public final NumberSetting laneWidth = group(new NumberSetting("Lane width", "How far off the line you may drift", 0.1, 0.01, 2, 0.01)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yawLockMode.get() != YawLock.None && laneKeeping.get()), "General");

	public final BooleanSetting yMotion = group(new BooleanSetting("Cancel Y motion", "Cancel the hop off the ground while bouncing fast, to keep speed", false)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce), "General");

	public final BooleanSetting yMotionDiagonal = group(new BooleanSetting("Only diagonal", "Cancel Y motion only while flying off the main axes", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yMotion.get()), "General");

	public final NumberSetting yMotionStart = group(new NumberSetting("Y motion start", "Speed (blocks per second) before Y motion is cancelled", 30, 0, 60, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yMotion.get()), "General");

	public final NumberSetting yMotionLimit = group(new NumberSetting("Y motion limit", "Stop cancelling above this speed (blocks per second)", 120, 10, 400, 5)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Bounce && yMotion.get()), "General");

	public final NumberSetting controlFall = group(new NumberSetting("Control fall", "Fall speed when looking straight down; less as you look level", 0.05, 0, 1, 0.01)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Control), "General");

	public final NumberSetting constSpeed = group(new NumberSetting("Constant speed", "Speed held, in blocks per tick", 1, 0.1, 5, 0.05)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Constant), "General");

	public final NumberSetting constAcceleration = group(new NumberSetting("Constant acceleration", "How fast you reach it", 1, 0.1, 5, 0.1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Constant), "General");

	public final BooleanSetting constStop = group(new BooleanSetting("Constant stop", "Stop when you let go of forward and back", true)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Constant), "General");

	public final NumberSetting boostThrust = group(new NumberSetting("Boost thrust", "Push added along your view each tick", 0.02, 0.005, 0.1, 0.005)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Boost), "General");

	public final NumberSetting boostMax = group(new NumberSetting("Boost top speed", "Thrust stops above this, in blocks per tick", 1.6, 0.5, 4, 0.05)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Boost), "General");

	public final EnumSetting<HoldTarget> holdTarget = group(new EnumSetting<>("Hold", "Hold an altitude or a speed", HoldTarget.Altitude)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Hold), "General");

	public final NumberSetting holdAltitude = group(new NumberSetting("Target altitude", "Y level to hold", 320, -64, 1500, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Hold && holdTarget.get() == HoldTarget.Altitude), "General");

	public final NumberSetting holdSpeed = group(new NumberSetting("Target speed", "Speed to hold, in blocks per second", 20, 1, 60, 0.5)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Hold && holdTarget.get() == HoldTarget.Speed), "General");

	public final NumberSetting holdMaxPitch = group(new NumberSetting("Max pitch", "Steepest the controller may look up or down", 45, 5, 90, 1)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Hold), "General");

	public final BooleanSetting pitch40AutoBounds = group(new BooleanSetting("Pitch40 auto bounds", "Start the 40-block window at your height and move it up with each climb", false)
		.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40), "General");

	// Recovery

	public final BooleanSetting retakeoff = group(new BooleanSetting("Re-takeoff after fluid", "Start gliding again after flying out of water or lava", true), "Recovery");

	public final NumberSetting lagBackHold = group(new NumberSetting("Lag-back hold", "Ticks to hold still after a setback while gliding (0 = off)", 0, 0, 40, 1), "Recovery");

	public final BooleanSetting lavaEscape = group(new BooleanSetting("Lava escape", "Rise straight up out of lava while wearing an elytra", false), "Recovery");

	// Inventory

	public final BooleanSetting replace = group(new BooleanSetting("Elytra replace", "Replaces a broken elytra with a new one", false), "Inventory");

	public final NumberSetting replaceDurability = group(new NumberSetting("Replace durability", "The durability threshold your elytra is replaced at", 2, 1, 500, 1)
		.visibleWhen(replace::get), "Inventory");

	public final EnumSetting<ChestSwapMode> chestSwap = group(new EnumSetting<>("Chest swap", "Enables ChestSwap when toggling this module", ChestSwapMode.Never), "Inventory");

	// Autopilot

	public final BooleanSetting autoPilot = group(new BooleanSetting("Auto pilot", "Moves forward while elytra flying", false)
		.visibleWhen(() -> flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "Autopilot");

	public final NumberSetting autoPilotMinimumHeight = group(new NumberSetting("Minimum height", "The minimum height for autopilot", 120, -128, 260, 1)
		.visibleWhen(() -> autoPilot.get() && flightMode.get() != ElytraFlightModes.Pitch40 && !glidePhysicsMode()), "Autopilot");

	// Fireworks

	public final BooleanSetting useFireworks = group(new BooleanSetting("Use fireworks", "Fires firework rockets for you while gliding", false), "Fireworks");

	public final EnumSetting<FireworkUse.Trigger> fireworkTrigger = group(new EnumSetting<>("Fire when",
		"Timer: every Firework delay. Speed: when you fall under Min speed. Either: whichever comes first. Other modes set their own speed, so they always use Timer", FireworkUse.Trigger.Timer)
		.visibleWhen(() -> useFireworks.get() && keepsGlidePhysics()), "Fireworks");

	public final NumberSetting autoPilotFireworkDelay = group(new NumberSetting("Firework delay", "Seconds between fireworks", 8, 1, 20, 1)
		.visibleWhen(() -> useFireworks.get() && !(keepsGlidePhysics() && fireworkTrigger.get() == FireworkUse.Trigger.Speed)), "Fireworks");

	public final NumberSetting fireworkMinSpeed = group(new NumberSetting("Min speed", "Fires a rocket when you fly slower than this, in blocks per second", 25, 1, 100, 1)
		.visibleWhen(() -> useFireworks.get() && keepsGlidePhysics() && fireworkTrigger.get() != FireworkUse.Trigger.Timer), "Fireworks");

	public final NumberSetting fireworkMinGap = group(new NumberSetting("Min gap", "The fewest ticks between two rockets, whatever the trigger", 10, 0, 100, 1)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final BooleanSetting fireworkWaitForRocket = group(new BooleanSetting("Wait for rocket", "Does not fire while one of your rockets is still flying", true)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final BooleanSetting fireworkPauseUsing = group(new BooleanSetting("Pause while using item", "Does not fire while you eat, drink or draw a bow", true)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final EnumSetting<FireworkUse.UseMode> fireworkUseMode = group(new EnumSetting<>("Use mode",
		"Normal: shows the slot swap and swings your hand. Silent: swaps and uses in one packet flush, no swing", FireworkUse.UseMode.Normal)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final BooleanSetting fireworkSearchInventory = group(new BooleanSetting("Search inventory", "Also takes rockets from your main inventory when none are in the hotbar or offhand", false)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final BooleanSetting fireBelow = group(new BooleanSetting("Fire below height", "Also fire a rocket whenever you drop under a Y level", false)
		.visibleWhen(useFireworks::get), "Fireworks");

	public final NumberSetting fireBelowY = group(new NumberSetting("Fire below Y", "The Y level", 200, -64, 1500, 1)
		.visibleWhen(() -> useFireworks.get() && fireBelow.get()), "Fireworks");

	public final KeybindSetting fireworkKey = group(new KeybindSetting("Firework key", "Fires one rocket while gliding", KeybindSetting.UNBOUND), "Fireworks");

	public final BooleanSetting groundTakeoff = group(new BooleanSetting("Firework take-off", "On the ground, the firework key jumps, opens the elytra and fires", true)
		.visibleWhen(fireworkKey::isBound), "Fireworks");

	public final BooleanSetting autoReplenish = group(new BooleanSetting("Replenish fireworks", "Moves fireworks into a selected hotbar slot", false), "Fireworks");

	public final NumberSetting replenishSlot = group(new NumberSetting("Replenish slot", "The hotbar slot fireworks are moved to", 9, 1, 9, 1)
		.visibleWhen(autoReplenish::get), "Fireworks");

	// Rocket boost: only modes that keep vanilla glide physics (Pitch40, Bounce) feel it; the others set their own velocity.

	public final BooleanSetting rocketBoost = group(new BooleanSetting("Custom boost", "Scales the top speed your rockets give", false)
		.visibleWhen(this::keepsGlidePhysics), "Rocket boost");

	public final NumberSetting boostHorizontal = group(new NumberSetting("Boost horizontal", "Multiplier on the sideways rocket speed (1 is vanilla)", 1, 0, 5, 0.05)
		.visibleWhen(rocketBoostVisible()), "Rocket boost");

	public final NumberSetting boostVertical = group(new NumberSetting("Boost vertical", "Multiplier on the up and down rocket speed (1 is vanilla)", 1, 0, 5, 0.05)
		.visibleWhen(rocketBoostVisible()), "Rocket boost");

	public final NumberSetting boostMaxSpeed = group(new NumberSetting("Max speed", "Rockets never push you past this, in blocks per second (0 is no limit)", 0, 0, 200, 1)
		.visibleWhen(rocketBoostVisible()), "Rocket boost");

	public final BooleanSetting recast = group(new BooleanSetting("Durability saver",
		"Keep restarting the glide so the server's wear counter never reaches a durability hit", false), "Durability");
	public final NumberSetting recastEvery = group(new NumberSetting("Recast every",
		"Ticks between restarts: 1 is every tick, up to 19 still beats the 20-tick wear", 1, 1, 19, 1), "Durability");
	public final BooleanSetting recastStill = group(new BooleanSetting("Only while still", "Only restart while no movement key is held", false), "Durability");
	public final BooleanSetting fakeFly = group(new BooleanSetting("Chestplate fake-fly",
		"Wear a chestplate; an elytra from your inventory is swapped in just for each glide start", false), "Durability");

	public final BooleanSetting headingLock = group(new BooleanSetting("Heading lock",
		"Glide along your take-off heading while you look around; the server sees that heading too", false), "Packets");
	public final NumberSetting packetGap = group(new NumberSetting("Packet gap",
		"While gliding with no keys held, send a move packet only every this many ticks (0 = off)", 0, 0, 100, 1), "Packets");
	public final BooleanSetting yawFlipFlop = group(new BooleanSetting("Yaw flip-flop", "Nudge the sent yaw by a hair on every other packet while gliding", false), "Packets");

	public final BooleanSetting walk = group(new BooleanSetting("Elytra walk",
		"Skate along the ground in the gliding pose: move packets say you're in the air and the glide is lit on the server", false), "Elytra walk");
	public final NumberSetting walkSpeed = group(new NumberSetting("Walk speed", "Ground speed while elytra walking, in blocks per tick", 0.5, 0, 3, 0.05), "Elytra walk");

	public final BooleanSetting hud = group(new BooleanSetting("Elytra HUD", "A panel with your flight numbers", false), "HUD");
	public final BooleanSetting hudAlways = group(new BooleanSetting("Always show", "Show the panel when not gliding too", false), "HUD");
	public final BooleanSetting hudYaw = group(new BooleanSetting("Yaw", "Show yaw", true), "HUD");
	public final BooleanSetting hudPitch = group(new BooleanSetting("Pitch", "Show pitch", true), "HUD");
	public final BooleanSetting hudAltitude = group(new BooleanSetting("Altitude", "Show your Y", true), "HUD");
	public final BooleanSetting hudSpeed = group(new BooleanSetting("Speed", "Show speed in blocks per second", true), "HUD");
	public final BooleanSetting hudHeading = group(new BooleanSetting("Heading", "Show the compass direction", true), "HUD");
	public final BooleanSetting hudDurability = group(new BooleanSetting("Durability", "Show the elytra's uses left", true), "HUD");

	private static final String[] HEADINGS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	public ElytraFlightMode currentMode = new Vanilla(this);
	public final FireworkUse fireworks = new FireworkUse(this);
	public final GlideTricks tricks = new GlideTricks(this);

	private boolean instaDropActive;
	/** The movement this module last applied; vanilla glide physics keeps its own, faster velocity underneath. */
	private Vec3 lastApplied;
	private boolean waitGroundSwap;
	/** Recovery state: ticks left to hold still, and the re-takeoff window after a fluid. */
	private int holdTicks;
	private boolean fluidFlight, wasGliding;
	private int takeoffTries, airTicks;
	/** Ticks left in a firework take-off (jump, open, fire), or 0. */
	private int takeoffTicks;

	public ElytraFly() {
		super("ElytraFly", Category.MOVEMENT, "Gives you more control over your elytra.");
		flightMode.onChange(this::onModeChanged);
		for (Setting<?> s : List.of(pitch40lowerBounds, pitch40upperBounds))
			s.visibleWhen(() -> flightMode.get() == ElytraFlightModes.Pitch40 && !pitch40AutoBounds.get());
		for (Setting<?> s : List.of(hudAlways, hudYaw, hudPitch, hudAltitude, hudSpeed, hudHeading, hudDurability)) s.visibleWhen(hud::get);
		for (Setting<?> s : List.of(recastEvery, recastStill)) s.visibleWhen(recast::get);
		walkSpeed.visibleWhen(walk::get);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "elytra_info", "Elytra info", this::hudRows, (w, h) -> new int[] {w / 2 + 100, h / 2 - 30})
			.when(() -> hud.get() && mc().player != null && (hudAlways.get() || mc().player.isFallFlying())));
	}

	private List<String[]> hudRows() {
		List<String[]> rows = new ArrayList<>();
		LocalPlayer p = mc().player;
		if (p == null) return rows;
		float yaw = Mth.wrapDegrees(p.getYRot());
		if (hudYaw.get()) rows.add(ModuleHudElement.row("Yaw", String.format(Locale.ROOT, "%.1f", yaw)));
		if (hudPitch.get()) rows.add(ModuleHudElement.row("Pitch", String.format(Locale.ROOT, "%.1f", p.getXRot())));
		if (hudAltitude.get()) rows.add(ModuleHudElement.row("Altitude", Coords.format(p.getY(), 1)));
		if (hudSpeed.get()) rows.add(ModuleHudElement.row("Speed", String.format(Locale.ROOT, "%.1f b/s", HudStats.speed())));
		if (hudHeading.get()) rows.add(ModuleHudElement.row("Heading", HEADINGS[Math.floorMod(Math.round(yaw / 45f), 8)]));
		if (hudDurability.get()) {
			var chest = p.getItemBySlot(EquipmentSlot.CHEST);
			if (chest.has(DataComponents.GLIDER) && chest.isDamageableItem()) {
				int left = chest.getMaxDamage() - chest.getDamageValue();
				rows.add(ModuleHudElement.row("Elytra", left + " (" + Math.round(100f * left / chest.getMaxDamage()) + "%)"));
			}
		}
		return rows;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	private java.util.function.BooleanSupplier rocketBoostVisible() {
		return () -> rocketBoost.get() && keepsGlidePhysics();
	}

	/** Pitch40, Bounce, Constant and Boost leave vanilla glide physics alone, so rockets push you there; the other modes set their own velocity. */
	public boolean keepsGlidePhysics() {
		return flightMode.get() == ElytraFlightModes.Pitch40 || glidePhysicsMode();
	}

	/** Modes that steer through {@link ElytraFlightMode#adjustMove} on top of vanilla glide physics, not the speed settings. */
	public boolean glidePhysicsMode() {
		ElytraFlightModes m = flightMode.get();
		return m == ElytraFlightModes.Bounce || m == ElytraFlightModes.Constant || m == ElytraFlightModes.Boost || m == ElytraFlightModes.Hold;
	}

	@Override
	protected void init(EventBus bus) {
		// Packet/Bounce modes react to packets we send and to server position corrections.
		listen(Events.PacketOut.class, e -> currentMode.onPacketSend(e.packet()));
		listen(Events.ServerTeleport.class, e -> {
			currentMode.zeroAcceleration();
			currentMode.onPositionCorrection();
			if (mc().player != null && mc().player.isFallFlying()) holdTicks = lagBackHold.intValue();
		});
		listen(Events.PlayerMove.class, EventBus.LOW, e -> e.movement = tricks.walkMove(e.type, modifyMove(e.movement)));
		listen(Events.PacketSend.class, e -> {
			if (!(e.packet instanceof ServerboundMovePlayerPacket m)) return;
			ServerboundMovePlayerPacket out = tricks.onMovePacket(m);
			if (out == null) e.cancel();
			else e.packet = out;
		});
		listen(Events.PacketApply.Post.class, e -> {
			if (e.packet() instanceof ClientboundSetEntityDataPacket data) tricks.onEntityData(data);
		});
		listen(Events.KeyPress.class, e -> {
			if (!e.screenOpen() && e.action() == 1) fireworkKey(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action == 1) fireworkKey(KeyNames.mouseKey(e.button));
		});
	}

	@Override
	protected void onEnable() {
		currentMode.onActivate();
		fireworks.reset();
		tricks.reset();

		LocalPlayer p = mc().player;
		if ((chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.Wait_For_Ground)
			&& p != null && !p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && isEnabled()) {
			ChestSwap cs = CoffeeClient.modules().get(ChestSwap.class);
			if (cs != null) cs.swap();
		}
	}

	@Override
	protected void onDisable() {
		if (autoPilot.get()) mc().options.keyUp.setDown(false);

		LocalPlayer p = mc().player;
		if (p != null) {
			if (chestSwap.get() == ChestSwapMode.Always && p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
				ChestSwap cs = CoffeeClient.modules().get(ChestSwap.class);
				if (cs != null) cs.swap();
			} else if (chestSwap.get() == ChestSwapMode.Wait_For_Ground) {
				waitGroundSwap = true;
			}

			if (p.isFallFlying() && instaDrop.get()) instaDropActive = true;
			if (p.isFallFlying() && !glidePhysicsMode()) {
				switch (onDisable.get()) {
					case Stop -> p.setDeltaMovement(Vec3.ZERO);
					case Keep_Speed -> { if (lastApplied != null) p.setDeltaMovement(lastApplied); }
					case Vanilla -> { }
				}
			}
			lastApplied = null;
		}

		currentMode.onDeactivate();
	}

	@Override
	public void onTick() {
		if (mc().player == null) return;
		currentMode.onTick();
		tricks.tick(mc().player);
		tickRetakeoff(mc().player);
		tickFireworkTakeoff(mc().player);
		fireworks.tick();
		tickInstaDrop();
		tickGroundSwap();
	}

	/** Called from the entity move hook: steers the player's velocity while gliding. */
	private Vec3 modifyMove(Vec3 movement) {
		LocalPlayer p = mc().player;
		if (p == null || mc().level == null) return movement;

		if (!p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER) && !tricks.fakeGliding(p)) {
			if (currentMode.lastForwardPressed && !glidePhysicsMode()) {
				mc().options.keyUp.setDown(false);
				currentMode.lastForwardPressed = false;
			}
			return movement;
		}

		currentMode.onPreTick();
		currentMode.autoTakeoff();
		Vec3 vanilla = movement;

		if (lavaEscape.get() && p.isInLava()) return new Vec3(movement.x, 0.4, movement.z);
		if (holdTicks > 0 && p.isFallFlying()) {
			holdTicks--;
			p.setDeltaMovement(Vec3.ZERO);
			return Vec3.ZERO;
		}

		if (p.isFallFlying()) {
			if (!glidePhysicsMode()) {
				currentMode.velX = 0;
				currentMode.velY = movement.y;
				currentMode.velZ = 0;
				currentMode.forward = Vec3.directionFromRotation(0, p.getYRot()).scale(0.1);
				currentMode.right = Vec3.directionFromRotation(0, p.getYRot() + 90).scale(0.1);

				if (p.isInWater() && stopInWater.get()) {
					if (mc().getConnection() != null)
						mc().getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
					return movement;
				}

				currentMode.handleFallMultiplier();
				currentMode.handleAutopilot();
				currentMode.handleAcceleration();
				currentMode.handleHorizontalSpeed(movement);
				currentMode.handleVerticalSpeed(movement);
			}

			int chunkX = (int) ((p.getX() + currentMode.velX) / 16);
			int chunkZ = (int) ((p.getZ() + currentMode.velZ) / 16);
			if (dontGoIntoUnloadedChunks.get()) {
				if (mc().level.getChunkSource().getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) {
					if (!glidePhysicsMode())
						movement = new Vec3(currentMode.velX, currentMode.velY, currentMode.velZ);
				} else {
					currentMode.zeroAcceleration();
					movement = new Vec3(0, currentMode.velY, 0);
				}
			} else if (!glidePhysicsMode()) {
				movement = new Vec3(currentMode.velX, currentMode.velY, currentMode.velZ);
			}

			if (!glidePhysicsMode()) currentMode.onMove();
			movement = currentMode.adjustMove(vanilla, movement);

			if (noCrash.get() && !glidePhysicsMode()) {
				Vec3 velocity = p.getDeltaMovement();
				if (velocity.lengthSqr() > 1.0E-7) {
					Vec3 lookAhead = p.position().add(velocity.normalize().scale(crashLookAhead.get()));
					ClipContext clip = new ClipContext(p.position(), new Vec3(lookAhead.x, p.getY(), lookAhead.z),
						ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p);
					BlockHitResult hit = mc().level.clip(clip);
					if (hit != null && hit.getType() == HitResult.Type.BLOCK)
						movement = new Vec3(0, currentMode.velY, 0);
				}
			}

			if (autoHover.get() && mc().options.keyShift.isDown() && !freecamActive() && !glidePhysicsMode()) {
				BlockPos below = p.blockPosition().below();
				boolean underCollidable = collidable(below);
				boolean under2Collidable = collidable(below.below());

				if (!underCollidable && under2Collidable) {
					movement = new Vec3(movement.x, -0.1f, movement.z);
					p.setXRot(Mth.clamp(p.getXRot(), -50f, 20f));
				}

				if (underCollidable) {
					movement = new Vec3(movement.x, -0.03f, movement.z);
					p.setXRot(Mth.clamp(p.getXRot(), -50f, 20f));
					if (p.getY() <= below.getY() + 1.34f) {
						movement = new Vec3(movement.x, 0, movement.z);
						p.setShiftKeyDown(false);
					}
				}
			}
			if (!glidePhysicsMode()) lastApplied = movement;
		} else if (currentMode.lastForwardPressed && !glidePhysicsMode()) {
			mc().options.keyUp.setDown(false);
			currentMode.lastForwardPressed = false;
		}

		return movement;
	}

	private void fireworkKey(int key) {
		if (!fireworkKey.isBound() || key != fireworkKey.key() || mc().player == null) return;
		if (mc().player.isFallFlying()) fireworks.fireNow();
		else if (groundTakeoff.get() && mc().player.onGround()) takeoffTicks = 20;
	}

	private void tickFireworkTakeoff(LocalPlayer p) {
		if (takeoffTicks <= 0) return;
		takeoffTicks--;
		if (p.isFallFlying()) {
			fireworks.fireNow();
			takeoffTicks = 0;
		} else if (p.onGround()) {
			p.jumpFromGround();
		} else if (p.getDeltaMovement().y < 0.2) {
			sendCommand();
		}
	}

	/** Picks the glide back up: up to 5 start-fly tries in the 40 ticks after leaving a fluid mid-flight. */
	private void tickRetakeoff(LocalPlayer p) {
		if (!retakeoff.get()) return;
		if (p.isInWater() || p.isInLava()) {
			if (wasGliding || fluidFlight) {
				fluidFlight = true;
				takeoffTries = 5;
			}
			airTicks = 0;
			return;
		}
		if (p.onGround()) {
			fluidFlight = wasGliding = false;
			return;
		}
		wasGliding = p.isFallFlying();
		if (!fluidFlight) return;
		if (++airTicks > 40 || takeoffTries <= 0) {
			fluidFlight = false;
			return;
		}
		if (wasGliding || !p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER) || p.isPassenger()) return;
		takeoffTries--;
		sendCommand();
	}

	private boolean collidable(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		return !state.getCollisionShape(mc().level, pos).isEmpty() || !state.getFluidState().isEmpty();
	}

	private boolean freecamActive() {
		Freecam freecam = CoffeeClient.modules().get(Freecam.class);
		return freecam != null && freecam.isEnabled();
	}

	private void tickInstaDrop() {
		if (!instaDropActive) return;
		LocalPlayer p = mc().player;
		if (p != null && p.isFallFlying()) {
			p.setDeltaMovement(0, 0, 0);
			if (p.connection != null) p.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, p.horizontalCollision));
		} else {
			instaDropActive = false;
		}
	}

	private void tickGroundSwap() {
		if (!waitGroundSwap) return;
		LocalPlayer p = mc().player;
		if (p == null) return;
		if (p.onGround()) {
			if (p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
				ChestSwap cs = CoffeeClient.modules().get(ChestSwap.class);
				if (cs != null) cs.swap();
			}
			waitGroundSwap = false;
		}
	}

	private void onModeChanged(ElytraFlightModes mode) {
		currentMode = switch (mode) {
			case Vanilla -> new Vanilla(this);
			case Packet -> new Packet(this);
			case Pitch40 -> {
				// Pitch40 is an autopilot of its own.
				autoPilot.set(false);
				yield new Pitch40(this);
			}
			case Bounce -> new Bounce(this);
			case Control -> new Control(this);
			case Constant -> new Constant(this);
			case Boost -> new Boost(this);
			case Hold -> new Hold(this);
		};
	}

	/** LivingEntityMixin: the yaw the local player's glide physics use, or null for their own. */
	public static Float glideHeading() {
		ElytraFly m = ModuleManager.ifOn(ElytraFly.class);
		return m == null || mc().player == null ? null : m.tricks.heading(mc().player);
	}

	/** SoundBlocker: fake-fly swaps armour on every glide start, so its equip sounds are muted. */
	public static boolean mutesSound(Identifier id) {
		ElytraFly m = ModuleManager.ifOn(ElytraFly.class);
		return m != null && m.fakeFly.get() && (id.getPath().startsWith("item.armor.equip") || id.getPath().equals("item.elytra.flying"));
	}

	/** Re-sends START_FALL_FLYING for the local player (used by the flight modes). */
	public static void sendCommand() {
		LocalPlayer p = mc().player;
		if (p == null || mc().getConnection() == null) return;
		mc().getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
	}

	/** Used by the modes for user-facing warnings. */
	public void error(String text) {
		info(text);
	}

	@Override
	public String infoString() {
		return flightMode.display();
	}
}
