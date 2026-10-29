package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MoveSim;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Prevents you from falling into the void, and optionally into lava with no floor under it. Ported
 * from AntiVoid; the floor height can be set per dimension, and the module can switch itself on when
 * you take void damage. Predict acts up to 10 ticks early, when the simulated fall ends in the
 * void. Besides Flight and Jump it can throw a pearl back at the last place you stood safely (Use
 * item) or put you back there (Last safe).
 */
public final class AntiVoid extends Module {
	public enum Mode {
		Flight, Jump, UseItem, LastSafe
	}

	/** How far below the floor the void still counts. */
	private static final double VOID_DEPTH = 15;

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "How to save you from the void", Mode.Jump));
	public final BooleanSetting predict = setting(new BooleanSetting("Predict", "Act as soon as a simulated fall of the next 10 ticks ends in the void", true));
	public final BooleanSetting perDimension = group(new BooleanSetting("Per-dimension floor", "Set the floor height for each dimension instead of using the world bottom", false), "Floor");
	public final NumberSetting overworldY = group(new NumberSetting("Overworld floor Y", "Act at or below this height in the Overworld", -64, -128, 320, 1), "Floor");
	public final NumberSetting netherY = group(new NumberSetting("Nether floor Y", "Act at or below this height in the Nether", 0, -64, 256, 1), "Floor");
	public final NumberSetting endY = group(new NumberSetting("End floor Y", "Act at or below this height in the End", 0, -64, 256, 1), "Floor");
	public final BooleanSetting lavaFloor = group(new BooleanSetting("Lava false floor", "Flight mode only: also act when you fall toward lava; the floor is the lava surface under you, found as you go", false), "Lava");
	public final NumberSetting lavaMargin = group(new NumberSetting("Lava margin", "Blocks above the lava surface at which to act", 3, 1, 10, 1), "Lava");
	public final BooleanSetting autoEnable = group(new BooleanSetting("Auto enable on void damage", "Switch on by itself when you take void damage, and off again once you are safe", false), "Auto");

	private Vec3 lastSafe;
	private int pearlWait;
	private boolean wasFlightEnabled;
	private boolean hasRun;
	private boolean autoEnabled;

	public AntiVoid() {
		super("AntiVoid", Category.MOVEMENT, "Attempts to prevent you from falling into the void.");
		mode.onChange(value -> onEnable());
		for (Setting<?> s : List.of(overworldY, netherY, endY)) s.visibleWhen(perDimension::get);
		lavaFloor.visibleWhen(() -> mode.get() == Mode.Flight);
		lavaMargin.visibleWhen(() -> mode.get() == Mode.Flight && lavaFloor.get());
	}

	@Override
	protected void init(EventBus bus) {
		// Always on: this is what turns the module on.
		bus.subscribe(Events.Tick.class, e -> {
			LocalPlayer p = e.client().player;
			if (p == null || isEnabled() || !autoEnable.get()) return;
			DamageSource last = p.getLastDamageSource();
			if (last != null && last.is(DamageTypes.FELL_OUT_OF_WORLD) && p.hurtTime > 0) {
				autoEnabled = true;
				setEnabled(true);
			}
		});
	}

	@Override
	public void onEnable() {
		pearlWait = 0;
		if (mode.get() == Mode.Flight) wasFlightEnabled = CoffeeClient.modules().get(Flight.class).isEnabled();
	}

	@Override
	public void onDisable() {
		autoEnabled = false;
		if (!wasFlightEnabled && mode.get() == Mode.Flight && Player.canUpdate()) {
			CoffeeClient.modules().get(Flight.class).setEnabled(false);
		}
	}

	/** The height at or below which the void takes over in this dimension. */
	private double floorY() {
		Level level = mc().level;
		if (perDimension.get()) {
			if (level.dimension() == Level.OVERWORLD) return overworldY.get();
			if (level.dimension() == Level.NETHER) return netherY.get();
			if (level.dimension() == Level.END) return endY.get();
		}
		return level.getMinY();
	}

	/** Falling toward lava whose surface is within the margin below you, with nothing solid in between. */
	private boolean aboveLava(LocalPlayer p) {
		if (p.onGround() || p.getDeltaMovement().y > -0.1) return false;
		Vec3 from = p.position();
		BlockHitResult hit = mc().level.clip(new ClipContext(from, from.add(0, -(lavaMargin.get() + 2), 0),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, p));
		return hit.getType() != HitResult.Type.MISS && mc().level.getFluidState(hit.getBlockPos()).is(FluidTags.LAVA)
			&& from.y - hit.getLocation().y <= lavaMargin.get();
	}

	/** Within 10 ticks the fall ends below the floor (or in the void) instead of on something. */
	private boolean fallingIntoVoid(LocalPlayer p, double floor) {
		if (!predict.get() || p.onGround() || p.getDeltaMovement().y >= 0) return false;
		MoveSim.Result r = MoveSim.run(p, p.getBoundingBox(), p.getDeltaMovement(), false, 10);
		return r.stop() == MoveSim.Stop.VOID || r.stop() == MoveSim.Stop.NONE && r.box().minY <= floor;
	}

	@Override
	public void onTick() {
		if (!Player.canUpdate()) return;
		LocalPlayer p = mc().player;
		if (pearlWait > 0) pearlWait--;

		double floor = floorY();
		if (p.onGround() && p.getY() > floor + 1) lastSafe = p.position();
		// Once void damage switched us on we are already far below the usual window, so anything under the floor counts.
		boolean inVoid = p.getY() <= floor && (autoEnabled || p.getY() >= floor - VOID_DEPTH) || fallingIntoVoid(p, floor);
		if (!inVoid && !(lavaFloor.get() && mode.get() == Mode.Flight && aboveLava(p))) {
			if (hasRun && mode.get() == Mode.Flight) {
				CoffeeClient.modules().get(Flight.class).setEnabled(false);
				hasRun = false;
			}
			if (autoEnabled && p.onGround() && p.getY() > floor + VOID_DEPTH) setEnabled(false);
			return;
		}

		switch (mode.get()) {
			case Flight -> {
				CoffeeClient.modules().get(Flight.class).setEnabled(true);
				hasRun = true;
			}
			case Jump -> p.jumpFromGround();
			case UseItem -> throwPearl(p);
			case LastSafe -> {
				if (lastSafe == null) return;
				p.setPos(lastSafe);
				p.setDeltaMovement(Vec3.ZERO);
				p.resetFallDistance();
			}
		}
	}

	/** One pearl at the last safe spot (straight up when there is none), then a pause while it flies. */
	private void throwPearl(LocalPlayer p) {
		if (pearlWait > 0) return;
		Inv.FindItemResult pearl = Inv.findInHotbar(s -> s.is(Items.ENDER_PEARL));
		if (!pearl.found()) return;
		pearlWait = 40;
		Runnable use = () -> Inv.use(pearl);
		// shortcut: aims straight at the spot, no arc solved; upgrade if far throws fall short
		if (lastSafe != null) Rotations.rotate(lastSafe.add(0, 1, 0), Rotations.HIGH, use);
		else Rotations.rotate(p.getYRot(), -90, Rotations.HIGH, use);
	}
}
