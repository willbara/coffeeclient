package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Warns when the server lags (low TPS) or stops responding (no time updates), and can draw an FPS,
 * TPS and ping graph on the HUD. While TPS stays low it can box the
 * server region you stand in (Folia-style 32x32 chunks), the likely home of whatever lags it, and it
 * can watch your worst 1% of frames, which catches hitching an average hides.
 */
public final class TickMonitor extends Module {
	public final NumberSetting warnBelow = setting(new NumberSetting("Warn below TPS", "Toast when TPS drops under this", 15, 5, 19.5, 0.5));
	public final NumberSetting freeze = setting(new NumberSetting("Not responding after (s)", "Seconds without a server update before warning", 2.5, 1.5, 15, 0.5));
	public final BooleanSetting toasts = setting(new BooleanSetting("Toasts", "Toast on lag and recovery", true));
	public final BooleanSetting alwaysShow = setting(new BooleanSetting("Always show", "Show the panel even when the server is fine", false));
	public final BooleanSetting showMspt = setting(new BooleanSetting("MSPT", "Milliseconds per tick (singleplayer only)", true));

	public final BooleanSetting regionBox = group(new BooleanSetting("Lag region", "Box the region you stand in once TPS has stayed low a while", false), "Lag region");
	public final NumberSetting regionAfter = group(new NumberSetting("Box after (s)", "Seconds of low TPS before the box shows", 10, 1, 120, 1), "Lag region");
	public final NumberSetting regionChunks = group(new NumberSetting("Region size", "Region width in chunks", 32, 1, 64, 1), "Lag region");
	public final ColorSetting regionColor = group(new ColorSetting("Region color", "Box colour", 0xFFFF5555), "Lag region");
	public final BooleanSetting worstFrames = group(new BooleanSetting("Worst frames", "Show and warn on your 1% low FPS", false), "Frames");
	public final NumberSetting worstBelow = group(new NumberSetting("Warn below", "Warn when the 1% low falls under this share of your average FPS", 50, 10, 95, 5).format(v -> (int) v + "%"), "Frames");

	public final BooleanSetting graph = group(new BooleanSetting("Graph", "A HUD graph of the last 48 seconds", false), "Graph");
	public final BooleanSetting graphFps = group(new BooleanSetting("FPS", "Frames per second (top = 120)", true), "Graph");
	public final ColorSetting fpsColor = group(new ColorSetting("FPS color", "FPS line colour", 0xFF5CD65C), "Graph");
	public final BooleanSetting graphTps = group(new BooleanSetting("TPS", "Server ticks per second (top = 20)", true), "Graph");
	public final ColorSetting tpsColor = group(new ColorSetting("TPS color", "TPS line colour", 0xFF4FD9C4), "Graph");
	public final BooleanSetting graphPing = group(new BooleanSetting("Ping", "Your latency (top = 500 ms)", true), "Graph");
	public final ColorSetting pingColor = group(new ColorSetting("Ping color", "Ping line colour", 0xFFE0A050), "Graph");
	public final NumberSetting graphWidth = group(new NumberSetting("Width", "Graph width", 160, 80, 400, 10), "Graph");
	public final NumberSetting graphHeight = group(new NumberSetting("Height", "Graph height", 50, 24, 160, 2), "Graph");

	/** One sample every 4 ticks (200 ms), 240 kept: the last 48 seconds. */
	private static final int SAMPLES = 240;
	private final float[] fps = new float[SAMPLES], tps = new float[SAMPLES], ping = new float[SAMPLES];
	private int head, filled, sampleTicks;

	private boolean lagging;
	private long lastToast;
	private long lowSince;
	/** Frame times in ms (a ring of the last {@link #FRAMES}), for the 1% low. */
	private static final int FRAMES = 1000;
	private final float[] frameMs = new float[FRAMES];
	private int frameHead, frameCount;
	private long lastFrame;
	private boolean hitching;

	public TickMonitor() {
		super("TickMonitor", Category.WORLD, "Estimates server TPS and warns on lag.");
		for (Setting<?> s : List.of(graphFps, fpsColor, graphTps, tpsColor, graphPing, pingColor, graphWidth, graphHeight)) s.visibleWhen(graph::get);
		for (Setting<?> s : List.of(regionAfter, regionChunks, regionColor)) s.visibleWhen(regionBox::get);
		worstBelow.visibleWhen(worstFrames::get);
	}

	@Override
	protected void onDisable() {
		head = filled = 0;
	}

	private double sinceUpdate() {
		long last = HudStats.lastServerUpdate();
		return last == 0 ? 0 : (System.currentTimeMillis() - last) / 1000.0;
	}

	@Override
	public void onTick() {
		if (mc().player == null) return;
		double tps = HudStats.tps();
		boolean bad = sinceUpdate() > freeze.get() || (!Double.isNaN(tps) && tps < warnBelow.get());
		long now = System.currentTimeMillis();
		if (bad && !lagging && toasts.get() && now - lastToast > 10_000) {
			notify(sinceUpdate() > freeze.get() ? "Server not responding" : String.format(Locale.ROOT, "Server lagging (%.1f TPS)", tps));
			lastToast = now;
		} else if (!bad && lagging && toasts.get()) {
			notify("Server recovered");
		}
		lagging = bad;
		boolean lowTps = !Double.isNaN(tps) && tps < warnBelow.get();
		if (!lowTps) lowSince = 0;
		else if (lowSince == 0) lowSince = now;
		sampleTicks++;
		if (worstFrames.get() && sampleTicks % 20 == 0) checkFrames(now);
		if (sampleTicks % 4 == 0) sample(tps);
	}

	/** Average and 1% low FPS over the frame ring, or null before there are enough frames. */
	private float[] frameStats() {
		if (frameCount < 100) return null;
		float[] sorted = Arrays.copyOf(frameMs, frameCount);
		Arrays.sort(sorted);
		double sum = 0;
		for (float f : sorted) sum += f;
		float worst = sorted[(int) (frameCount * 0.99)];
		return new float[]{(float) (1000 / (sum / frameCount)), 1000 / worst};
	}

	private void checkFrames(long now) {
		float[] stats = frameStats();
		if (stats == null) return;
		boolean bad = stats[1] < stats[0] * worstBelow.get() / 100;
		if (bad && !hitching && toasts.get() && now - lastToast > 10_000) {
			notify(String.format(Locale.ROOT, "Hitching: 1%% low %.0f FPS (average %.0f)", stats[1], stats[0]));
			lastToast = now;
		}
		hitching = bad;
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		long now = System.nanoTime();
		if (worstFrames.get() && lastFrame != 0) {
			frameMs[frameHead] = (now - lastFrame) / 1e6f;
			frameHead = (frameHead + 1) % FRAMES;
			frameCount = Math.min(frameCount + 1, FRAMES);
		}
		lastFrame = now;
		if (!regionBox.get() || lowSince == 0 || System.currentTimeMillis() - lowSince < regionAfter.get() * 1000 || mc().player == null) return;
		int size = regionChunks.intValue() * 16;
		int rx = Math.floorDiv(mc().player.getBlockX(), size) * size, rz = Math.floorDiv(mc().player.getBlockZ(), size) * size;
		float x0 = (float) (rx - cam.x), x1 = x0 + size, z0 = (float) (rz - cam.z), z1 = z0 + size;
		float y = (float) (mc().player.getY() - cam.y);
		int argb = 0xFF000000 | regionColor.argb();
		VertexConsumer vc = OverlayRenderer.buffer(WorldOverlays.outlineType(true));
		for (float dy : new float[]{-8, 0, 8}) {
			WorldOverlays.line(vc, x0, y + dy, z0, x1, y + dy, z0, 1, 0, 0, argb, 2);
			WorldOverlays.line(vc, x1, y + dy, z0, x1, y + dy, z1, 0, 0, 1, argb, 2);
			WorldOverlays.line(vc, x1, y + dy, z1, x0, y + dy, z1, -1, 0, 0, argb, 2);
			WorldOverlays.line(vc, x0, y + dy, z1, x0, y + dy, z0, 0, 0, -1, argb, 2);
		}
	}

	private void sample(double tpsNow) {
		var mc = mc();
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
		fps[head] = mc.getFps();
		tps[head] = (float) tpsNow;
		ping[head] = info == null || mc.getSingleplayerServer() != null ? Float.NaN : info.getLatency();
		head = (head + 1) % SAMPLES;
		filled = Math.min(filled + 1, SAMPLES);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "tick_monitor", "Tick monitor", () -> {
			List<String[]> rows = new ArrayList<>();
			double tps = HudStats.tps();
			rows.add(ModuleHudElement.row("TPS", Double.isNaN(tps) ? "..." : String.format(Locale.ROOT, "%.1f", tps)));
			double since = sinceUpdate();
			if (since > freeze.get()) rows.add(ModuleHudElement.row("No response", String.format(Locale.ROOT, "%.1fs", since)));
			var server = mc().getSingleplayerServer();
			if (showMspt.get() && server != null) rows.add(ModuleHudElement.row("MSPT", String.format(Locale.ROOT, "%.1f", server.getAverageTickTimeNanos() / 1e6)));
			float[] frames = worstFrames.get() ? frameStats() : null;
			if (frames != null) rows.add(ModuleHudElement.row("1% low", String.format(Locale.ROOT, "%.0f FPS", frames[1])));
			return rows;
		}, (w, h) -> new int[] {w / 2 - 40, 20}).when(() -> lagging || hitching || alwaysShow.get()).fixed(), new Graph());
	}

	private final class Graph extends ModuleCustomElement {
		private static final int LEGEND = 11;

		Graph() {
			super(TickMonitor.this, "performance_graph", "Performance graph");
		}

		@Override
		public boolean shouldRender() {
			return graph.get();
		}

		@Override
		public boolean autoStacks() {
			return false;
		}

		@Override
		public int width() {
			return graphWidth.intValue();
		}

		@Override
		public int height() {
			return graphHeight.intValue() + LEGEND;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w - width() - 4, h - height() - 40};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			int w = width(), h = graphHeight.intValue();
			Draw.box(ui.g, x, y, w, height(), ui.theme.panel, ui.theme.border);
			int top = y + LEGEND;
			Draw.rect(ui.g, x + 1, top + h / 2, w - 2, 1, 0x30FFFFFF);
			int legendX = x + 3;
			int last = (head - 1 + SAMPLES) % SAMPLES;
			if (graphFps.get()) legendX = series(ui, x, top, w, h, fps, 120, fpsColor.argb(), "FPS " + value(fps[last], "%.0f"), legendX, y);
			if (graphTps.get()) legendX = series(ui, x, top, w, h, tps, 20, tpsColor.argb(), "TPS " + value(tps[last], "%.1f"), legendX, y);
			if (graphPing.get()) series(ui, x, top, w, h, ping, 500, pingColor.argb(), "Ping " + value(ping[last], "%.0f"), legendX, y);
		}

		private String value(float v, String format) {
			return filled == 0 || Float.isNaN(v) ? "-" : String.format(Locale.ROOT, format, v);
		}

		/** One line, oldest sample on the left; a NaN sample breaks it. Returns where the next legend goes. */
		private int series(Ui ui, int x, int top, int w, int h, float[] data, float max, int color, String label, int legendX, int legendY) {
			int argb = 0xFF000000 | color;
			float step = (w - 2) / (float) (SAMPLES - 1);
			float px = Float.NaN, py = Float.NaN;
			for (int i = 0; i < filled; i++) {
				float v = data[(head - filled + i + SAMPLES) % SAMPLES];
				float sx = x + 1 + (SAMPLES - filled + i) * step;
				if (Float.isNaN(v)) {
					px = Float.NaN;
					continue;
				}
				float sy = top + h - 1 - Math.min(1f, v / max) * (h - 2);
				if (!Float.isNaN(px)) Draw.line(ui.g, px, py, sx, sy, 1, argb);
				px = sx;
				py = sy;
			}
			Draw.text(ui.g, label, legendX, legendY + 2, argb);
			return legendX + ui.font.width(label) + 8;
		}
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
