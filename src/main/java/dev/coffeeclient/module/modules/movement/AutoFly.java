package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.exploit.NewChunks;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.FlightPattern;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Player;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Holds forward and steers along a pattern from where it was switched on: a spiral inward, a square
 * swept in lanes, an endless orbit, or a trail of new chunks (from NewChunks). Only the yaw is
 * steered, so pair it with the flight you want (elytra, creative flight, ElytraFly Hold) and keep
 * your own pitch. Switches itself off when the pattern is finished or the trail is lost for good.
 */
public final class AutoFly extends Module {
	public enum Pattern {
		Spiral, Square, Orbit, Trail
	}

	public enum Spin {
		In, Out
	}

	public enum Turn {
		Clockwise, Counterclockwise
	}

	public final BooleanSetting resume = group(new BooleanSetting("Resume path", "Switching AutoFly off pauses the path; switching it on again carries on along the same one (land, repair, come back)", true), "Path");
	public final ActionSetting newPath = group(new ActionSetting("Start a new path", "Forget the paused path: the next time AutoFly turns on it starts from where you are", this::forgetPath), "Path");
	public final EnumSetting<Pattern> pattern = group(new EnumSetting<>("Pattern", "Spiral: circle inward from here. Square: sweep a square in lanes. Orbit: circle forever. Trail: follow new chunks", Pattern.Spiral), "Pattern");
	public final EnumSetting<Spin> spin = group(new EnumSetting<>("Spiral", "In: start on the outside and wind toward the middle. Out: start in the middle and unwind outward", Spin.In), "Pattern");
	public final EnumSetting<Turn> turn = group(new EnumSetting<>("Direction", "Which way the pattern turns from where you started", Turn.Clockwise), "Pattern");
	public final NumberSetting radius = group(new NumberSetting("Radius", "Spiral: the outer radius (where it starts going in, or ends going out). Square: half the side length (blocks)", 200, 32, 30000, 16), "Size");
	public final NumberSetting centerRadius = group(new NumberSetting("Center radius", "Spiral: how close to the middle it gets (in) or where it starts (out). Keep it above your turning circle", 20, 6, 200, 2), "Size");
	public final NumberSetting spacing = group(new NumberSetting("Spacing", "Blocks between spiral laps or square lanes (smaller = denser)", 32, 4, 1000, 4), "Size");
	public final NumberSetting trailWindow = group(new NumberSetting("Trail window", "Seconds of new-chunk detections to follow", 30, 5, 300, 5), "Trail");
	public final NumberSetting trailDeviation = group(new NumberSetting("Max deviation", "Ignore detections more than this many degrees off the trail", 60, 10, 180, 5), "Trail");
	public final NumberSetting trailLost = group(new NumberSetting("Circle after", "Seconds without a detection before circling to find the trail again", 10, 1, 120, 1), "Trail");
	public final NumberSetting trailGiveUp = group(new NumberSetting("Give up after", "Seconds without a detection before stopping", 120, 10, 900, 10), "Trail");
	public final NumberSetting turnSpeed = group(new NumberSetting("Turn speed", "Most degrees turned per tick", 12, 1, 45, 1), "Flying");
	public final BooleanSetting holdForward = group(new BooleanSetting("Hold forward", "Hold the forward key while the pattern runs", true), "Flying");
	public final BooleanSetting pauseInGuis = group(new BooleanSetting("Pause in GUIs", "Let go of forward while a screen is open", true), "Flying");

	/** The path being flown, kept while the module is off so it can be resumed. */
	private FlightPattern path;
	private String pathKey = "";
	private Object pathConnection;
	private double lastX, lastZ;
	private boolean holding;

	public AutoFly() {
		super("AutoFly", Category.MOVEMENT, "Flies a spiral or a square from where you start, steering for you.");
		spin.visibleWhen(() -> pattern.get() == Pattern.Spiral);
		centerRadius.visibleWhen(() -> pattern.get() == Pattern.Spiral);
		radius.visibleWhen(() -> pattern.get() != Pattern.Trail);
		spacing.visibleWhen(() -> pattern.get() == Pattern.Spiral || pattern.get() == Pattern.Square);
		for (Setting<?> s : java.util.List.of(trailWindow, trailDeviation, trailLost, trailGiveUp)) s.visibleWhen(() -> pattern.get() == Pattern.Trail);
	}

	@Override
	protected void init(EventBus bus) {
		// A key let go between ticks is pressed again before the player reads its input.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (isEnabled() && holding) mc.options.keyUp.setDown(true);
		});
	}

	private String key(LocalPlayer player) {
		return String.join("|", pattern.display(), spin.display(), turn.display(), radius.display(), spacing.display(),
			centerRadius.display(), player.level().dimension().identifier().toString());
	}

	private void forgetPath() {
		path = null;
		if (isEnabled()) start();
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		if (resume.get() && path != null && key(player).equals(pathKey) && mc().getConnection() == pathConnection) {
			path.resync(player.getX(), player.getZ());
			info("Resuming the paused path.");
		} else {
			start();
		}
		lastX = player.getX();
		lastZ = player.getZ();
	}

	private void start() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		pathKey = key(player);
		pathConnection = mc().getConnection();
		boolean cw = turn.get() == Turn.Clockwise;
		path = switch (pattern.get()) {
			case Spiral -> new FlightPattern.Spiral(player.getX(), player.getZ(), player.getYRot(), cw, spin.get() == Spin.Out, radius.get(), spacing.get(), centerRadius.get());
			case Square -> new FlightPattern.Square(player.getX(), player.getZ(), player.getYRot(), cw, radius.get() * 2, spacing.get());
			case Orbit -> new FlightPattern.Orbit(player.getX(), player.getZ(), player.getYRot(), cw, radius.get());
			case Trail -> new FlightPattern.Trail(player.getYRot(), trailDeviation.get(), trailLost.intValue() * 20, trailGiveUp.intValue() * 20, turn.get() == Turn.Clockwise ? 3 : -3);
		};
		if (pattern.get() == Pattern.Trail && !CoffeeClient.modules().get(NewChunks.class).isEnabled()) info("Trail follows NewChunks detections: turn NewChunks on.");
	}

	@Override
	protected void onDisable() {
		release();
	}

	private void release() {
		if (!holding) return;
		holding = false;
		KeyMapping up = mc().options.keyUp;
		up.setDown(KeyNames.isPhysicallyDown(up));
	}

	@Override
	public void onTick() {
		if (!Player.canUpdate() || path == null) return;
		LocalPlayer player = mc().player;
		boolean paused = pauseInGuis.get() && mc().gui.screen() != null;
		double speed = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
		lastX = player.getX();
		lastZ = player.getZ();

		if (path instanceof FlightPattern.Trail trail) trail.points(CoffeeClient.modules().get(NewChunks.class).recentNewChunks(trailWindow.intValue() * 1000L));
		float want = path.yaw(player.getX(), player.getZ(), speed);
		if (path.done()) {
			info(path instanceof FlightPattern.Trail ? "Lost the trail." : "Pattern finished.");
			path = null;
			setEnabled(false);
			return;
		}
		if (holdForward.get() && !paused) {
			holding = true;
			mc().options.keyUp.setDown(true);
		} else {
			release();
		}
		if (paused) return;
		float delta = Mth.wrapDegrees(want - player.getYRot());
		float step = turnSpeed.floatValue();
		player.setYRot(player.getYRot() + Mth.clamp(delta, -step, step));
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public String infoString() {
		return pattern.display();
	}
}
