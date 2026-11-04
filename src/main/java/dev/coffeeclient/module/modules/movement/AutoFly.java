package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.combat.AutoLog;
import dev.coffeeclient.module.modules.exploit.NewChunks;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BlockPosSetting;
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
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Holds forward and steers along a pattern from where it was switched on: a round or square spiral
 * (inward or outward), an endless orbit, lawnmower lanes over a rectangle, a straight line to a
 * point, or a trail of chunks someone loaded before you (from NewChunks). Only the yaw is steered, so
 * pair it with the flight you want (elytra, creative flight, ElytraFly Hold) and keep your own pitch.
 * When the pattern is finished or the trail is lost for good it stops, disconnects or keeps heading.
 */
public final class AutoFly extends Module {
	public enum Pattern {
		Spiral, Square, Lawnmower, Orbit, Goto, Trail
	}

	public enum Spin {
		In, Out
	}

	public enum Turn {
		Clockwise, Counterclockwise
	}

	public enum Axis {
		X, Z
	}

	public enum Finish {
		Stop, Disconnect, Keep_heading
	}

	/** Which detections a trail is made of. */
	public enum Follow {
		/** Any chunk loaded before: the usual trail through new or unvisited land. */
		Old(EnumSet.of(NewChunks.Kind.OLD, NewChunks.Kind.OLD_VERSION)),
		/** Only old-version terrain someone has loaded since the server updated. */
		Old_version(EnumSet.of(NewChunks.Kind.OLD_VERSION)),
		/** Chunks being generated right now, by a player ahead of you. */
		New(EnumSet.of(NewChunks.Kind.NEW));

		final Set<NewChunks.Kind> kinds;

		Follow(Set<NewChunks.Kind> kinds) {
			this.kinds = kinds;
		}
	}

	public final BooleanSetting resume = group(new BooleanSetting("Resume path", "Switching AutoFly off pauses the path; switching it on again carries on along the same one (land, repair, come back)", true), "Path");
	public final ActionSetting newPath = group(new ActionSetting("Start a new path", "Forget the paused path: the next time AutoFly turns on it starts from where you are", this::forgetPath), "Path");
	public final EnumSetting<Finish> onFinish = group(new EnumSetting<>("On finish", "When the pattern ends or the trail is lost for good: stop, disconnect, or keep flying straight on until you switch AutoFly off", Finish.Stop), "Path");
	public final EnumSetting<Pattern> pattern = group(new EnumSetting<>("Pattern", "Spiral: a round spiral from here. Square: a square spiral of straight lanes. Lawnmower: back-and-forth lanes over a rectangle. Orbit: circle forever. Goto: straight to a point. Trail: follow a line of chunks someone loaded before you", Pattern.Spiral), "Pattern");
	public final EnumSetting<Spin> spin = group(new EnumSetting<>("Spiral", "In: start on the outside and wind toward the middle. Out: start in the middle and unwind outward", Spin.In), "Pattern");
	public final EnumSetting<Turn> turn = group(new EnumSetting<>("Direction", "Which way the pattern turns from where you started", Turn.Clockwise), "Pattern");
	public final BlockPosSetting farCorner = group(new BlockPosSetting("Far corner", "Lawnmower: the corner of the rectangle opposite where you switch it on (Y is ignored)", 0, 0, 0, -30_000_000, 30_000_000), "Pattern");
	public final EnumSetting<Axis> lanesAlong = group(new EnumSetting<>("Lanes along", "Lawnmower: the axis the long lanes run along", Axis.X), "Pattern");
	public final BlockPosSetting destination = group(new BlockPosSetting("Destination", "Goto: where to fly (Y is ignored)", 0, 0, 0, -30_000_000, 30_000_000), "Pattern");
	public final NumberSetting radius = group(new NumberSetting("Radius", "Spiral: the outer radius (where it starts going in, or ends going out). Square: half the outer side length (blocks)", 200, 32, 30000, 16), "Size");
	public final NumberSetting centerRadius = group(new NumberSetting("Center radius", "How close to the middle it gets (in) or where it starts (out); for Square, half the innermost lap. Keep it above your turning circle", 20, 6, 200, 2), "Size");
	public final NumberSetting spacing = group(new NumberSetting("Spacing", "Blocks between laps or lanes (smaller = denser)", 32, 4, 1000, 4), "Size");
	public final EnumSetting<Follow> follow = group(new EnumSetting<>("Follow", "Old: any chunk loaded before (the usual trail). Old version: only old-version terrain someone has revisited. New: chunks a player ahead is generating right now", Follow.Old), "Trail");
	public final NumberSetting lockOn = group(new NumberSetting("Lock on after", "Detections inside the window before it starts steering; until then it holds your heading and never gives up (leave it searching). 1 = steer and circle from the start", 1, 1, 50, 1), "Trail");
	public final EnumSetting<FlightPattern.Trail.Split> split = group(new EnumSetting<>("Split preference", "Where the trail forks, favour the branch on this side", FlightPattern.Trail.Split.Straight), "Trail");
	public final NumberSetting trailWindow = group(new NumberSetting("Trail window", "Seconds of detections to follow", 30, 5, 300, 5), "Trail");
	public final NumberSetting trailDeviation = group(new NumberSetting("Max deviation", "Ignore detections more than this many degrees off the trail", 60, 10, 180, 5), "Trail");
	public final NumberSetting trailLost = group(new NumberSetting("Circle after", "Seconds without a detection before circling to find the trail again", 10, 1, 120, 1), "Trail");
	public final NumberSetting trailGiveUp = group(new NumberSetting("Give up after", "Seconds without a detection before the trail counts as lost (then On finish applies)", 120, 10, 900, 10), "Trail");
	public final NumberSetting turnSpeed = group(new NumberSetting("Turn speed", "Most degrees turned per tick", 12, 1, 45, 1), "Flying");
	public final BooleanSetting holdForward = group(new BooleanSetting("Hold forward", "Hold the forward key while the pattern runs", true), "Flying");
	public final BooleanSetting pauseInGuis = group(new BooleanSetting("Pause in GUIs", "Let go of forward while a screen is open", true), "Flying");

	/** The path being flown, kept while the module is off so it can be resumed. */
	private FlightPattern path;
	private String pathKey = "";
	private Object pathConnection;
	private double lastX, lastZ;
	private boolean holding;
	/** Finished with Keep heading: hold forward on the last yaw, steer nothing. */
	private boolean coasting;
	/** Ground speed in blocks per tick, smoothed. */
	private double speedAvg;
	private double remaining = Double.NaN;

	public AutoFly() {
		super("AutoFly", Category.MOVEMENT, "Flies a spiral, lawnmower lanes, an orbit, a straight line or a trail of old chunks, steering for you.");
		spin.visibleWhen(() -> pattern.get() == Pattern.Spiral || pattern.get() == Pattern.Square);
		centerRadius.visibleWhen(() -> pattern.get() == Pattern.Spiral || pattern.get() == Pattern.Square);
		turn.visibleWhen(() -> pattern.get() != Pattern.Lawnmower && pattern.get() != Pattern.Goto);
		radius.visibleWhen(() -> pattern.get() == Pattern.Spiral || pattern.get() == Pattern.Square || pattern.get() == Pattern.Orbit);
		spacing.visibleWhen(() -> pattern.get() == Pattern.Spiral || pattern.get() == Pattern.Square || pattern.get() == Pattern.Lawnmower);
		for (Setting<?> s : List.of(farCorner, lanesAlong)) s.visibleWhen(() -> pattern.get() == Pattern.Lawnmower);
		destination.visibleWhen(() -> pattern.get() == Pattern.Goto);
		for (Setting<?> s : List.of(follow, lockOn, split, trailWindow, trailDeviation, trailLost, trailGiveUp)) s.visibleWhen(() -> pattern.get() == Pattern.Trail);
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
			centerRadius.display(), farCorner.display(), lanesAlong.display(), destination.display(), follow.display(),
			lockOn.display(), split.display(), player.level().dimension().identifier().toString());
	}

	private void forgetPath() {
		path = null;
		if (isEnabled()) start();
	}

	@Override
	protected void onEnable() {
		coasting = false;
		speedAvg = 0;
		remaining = Double.NaN;
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
		double x = player.getX(), z = player.getZ();
		path = switch (pattern.get()) {
			case Spiral -> new FlightPattern.Spiral(x, z, player.getYRot(), cw, spin.get() == Spin.Out, radius.get(), spacing.get(), centerRadius.get());
			case Square -> new FlightPattern.Square(x, z, player.getYRot(), cw, spin.get() == Spin.Out, radius.get() * 2, spacing.get(), centerRadius.get() * 2);
			case Lawnmower -> new FlightPattern.Lawnmower(x, z, farCorner.x() + 0.5, farCorner.z() + 0.5, spacing.get(), lanesAlong.get() == Axis.X);
			case Orbit -> new FlightPattern.Orbit(x, z, player.getYRot(), cw, radius.get());
			case Goto -> new FlightPattern.Goto(x, z, destination.x() + 0.5, destination.z() + 0.5);
			case Trail -> new FlightPattern.Trail(player.getYRot(), trailDeviation.get(), trailLost.intValue() * 20, trailGiveUp.intValue() * 20,
				cw ? 3 : -3, lockOn.intValue(), split.get());
		};
		if (pattern.get() == Pattern.Trail && !CoffeeClient.modules().get(NewChunks.class).isEnabled()) info("Trail follows NewChunks detections: turn NewChunks on.");
	}

	@Override
	protected void onDisable() {
		coasting = false;
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
		if (!Player.canUpdate() || path == null && !coasting) return;
		LocalPlayer player = mc().player;
		boolean paused = pauseInGuis.get() && mc().gui.screen() != null;
		double speed = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
		lastX = player.getX();
		lastZ = player.getZ();
		speedAvg += (speed - speedAvg) * 0.05;

		if (coasting) {
			hold(paused);
			return;
		}

		if (path instanceof FlightPattern.Trail trail) trail.points(CoffeeClient.modules().get(NewChunks.class).recentChunks(follow.get().kinds, trailWindow.intValue() * 1000L));
		float want = path.yaw(player.getX(), player.getZ(), speed);
		if (path.done()) {
			finish(path instanceof FlightPattern.Trail ? "Lost the trail." : "Pattern finished.");
			return;
		}
		remaining = path.remaining(player.getX(), player.getZ());
		hold(paused);
		if (paused) return;
		float delta = Mth.wrapDegrees(want - player.getYRot());
		float step = turnSpeed.floatValue();
		player.setYRot(player.getYRot() + Mth.clamp(delta, -step, step));
	}

	private void hold(boolean paused) {
		if (holdForward.get() && !paused) {
			holding = true;
			mc().options.keyUp.setDown(true);
		} else {
			release();
		}
	}

	private void finish(String why) {
		path = null;
		remaining = Double.NaN;
		switch (onFinish.get()) {
			case Stop -> {
				info(why);
				setEnabled(false);
			}
			case Disconnect -> {
				info(why + " Disconnecting.");
				setEnabled(false);
				AutoLog.disconnect(Component.literal("[AutoFly] " + why));
			}
			case Keep_heading -> {
				info(why + " Keeping this heading.");
				coasting = true;
			}
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public String infoString() {
		if (coasting) return "Heading";
		if (pattern.get() == Pattern.Trail && path instanceof FlightPattern.Trail trail && !trail.locked()) return "Trail · searching";
		if (path == null || Double.isNaN(remaining)) return pattern.display();
		String left = remaining >= 1000 ? String.format(Locale.ROOT, "%.1fk", remaining / 1000) : String.valueOf(Math.round(remaining));
		double perSecond = speedAvg * 20;
		if (perSecond < 1) return pattern.display() + " · " + left;
		long s = Math.round(remaining / perSecond);
		String eta = s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
		return pattern.display() + " · " + left + " · " + eta;
	}
}
