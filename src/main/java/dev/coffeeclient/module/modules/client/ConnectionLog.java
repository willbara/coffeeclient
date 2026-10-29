package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A HUD overlay of connection events: joining and leaving a server, the disconnect reason, ping spikes
 * and the server moving you back (setbacks). Keeps the last few entries in memory only.
 */
public final class ConnectionLog extends Module {
	private enum Kind {
		INFO, GOOD, WARN, BAD
	}

	private record Entry(long at, Kind kind, String text) {
	}

	private static final int KEEP = 200;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

	public final NumberSetting lines = setting(new NumberSetting("Lines", "Entries shown on the overlay", 8, 1, 30, 1));
	public final NumberSetting width = setting(new NumberSetting("Width", "Overlay width", 220, 120, 400, 10));
	public final NumberSetting fadeAfter = setting(new NumberSetting("Fade after (s)", "Entries older than this are drawn dimmer (0 = never)", 20, 0, 300, 5));
	public final NumberSetting spikeMs = setting(new NumberSetting("Ping spike (ms)", "Log when ping jumps this far above its recent average", 250, 50, 2000, 10));
	public final BooleanSetting showTime = setting(new BooleanSetting("Show time", "Timestamp on each entry", true));
	public final BooleanSetting toChat = setting(new BooleanSetting("Also to chat", "Print each entry in chat too", false));

	private final ArrayDeque<Entry> entries = new ArrayDeque<>();
	private boolean joined;
	private volatile String disconnectReason;
	private double pingAverage = -1;
	private long lastSpike;
	private int tickCount;

	public ConnectionLog() {
		super("ConnectionLog", List.of("ConnectionLogOverlay"), Category.CLIENT, "Overlay of connection events: joins, leaves, disconnect reasons, ping spikes and setbacks.");
	}

	@Override
	protected void onDisable() {
		synchronized (entries) {
			entries.clear();
		}
		joined = false;
		pingAverage = -1;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.WorldChange.class, e -> {
			if (joined) return;
			joined = true;
			add(Kind.GOOD, "Joined " + serverName());
		});
		listen(Events.GameLeft.class, e -> {
			if (!joined) return;
			joined = false;
			add(Kind.INFO, "Left " + serverName());
		});
		// Network thread: keep the reason the server gave; the disconnect screen opens after it.
		listen(Events.PacketReceive.class, EventBus.LOWEST, e -> {
			if (!e.isCancelled() && e.packet instanceof ClientboundDisconnectPacket disconnect) disconnectReason = disconnect.reason().getString();
		});
		listen(Events.OpenScreen.class, e -> {
			if (!(e.screen instanceof DisconnectedScreen)) return;
			add(Kind.BAD, disconnectReason == null ? "Connection lost" : "Disconnected: " + disconnectReason);
			disconnectReason = null;
		});
		listen(Events.ServerTeleport.class, e -> {
			if (e.distance() > 1) add(Kind.WARN, String.format(Locale.ROOT, "Server moved you back %.1f blocks", e.distance()));
		});
	}

	@Override
	public void onTick() {
		if (++tickCount % 20 != 0) return;
		var mc = mc();
		if (mc.player == null || mc.getConnection() == null || mc.getSingleplayerServer() != null) return;
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		if (info == null) return;
		double ping = info.getLatency();
		if (pingAverage < 0) {
			pingAverage = ping;
			return;
		}
		long now = System.currentTimeMillis();
		if (ping - pingAverage >= spikeMs.get() && now - lastSpike > 5_000) {
			lastSpike = now;
			add(Kind.WARN, String.format(Locale.ROOT, "Ping spike: %.0f ms (usually %.0f)", ping, pingAverage));
		}
		pingAverage += (ping - pingAverage) * 0.1;
	}

	private static String serverName() {
		var server = mc().getCurrentServer();
		return server == null ? "singleplayer" : server.name;
	}

	private void add(Kind kind, String text) {
		Entry entry = new Entry(System.currentTimeMillis(), kind, text);
		synchronized (entries) {
			entries.addLast(entry);
			while (entries.size() > KEEP) entries.removeFirst();
		}
		if (toChat.get()) info(text);
	}

	@Override
	public String infoString() {
		synchronized (entries) {
			return entries.isEmpty() ? null : entries.size() + " events";
		}
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new Overlay());
	}

	private final class Overlay extends ModuleCustomElement {
		private static final int PAD = 4;
		private static final int ROW = 10;

		Overlay() {
			super(ConnectionLog.this, "connection_log", "Connection log");
		}

		/** The newest entries that fit, oldest first. */
		private List<Entry> visible() {
			synchronized (entries) {
				int skip = Math.max(0, entries.size() - lines.intValue());
				List<Entry> shown = new ArrayList<>(entries.size() - skip);
				for (Entry e : entries) {
					if (skip > 0) skip--;
					else shown.add(e);
				}
				return shown;
			}
		}

		@Override
		public int width() {
			return width.intValue();
		}

		@Override
		public int height() {
			return PAD * 2 + Math.max(1, lines.intValue()) * ROW;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {4, h / 3};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			Draw.box(ui.g, x, y, width(), height(), ui.theme.panel, ui.theme.border);
			List<Entry> shown = preview && visible().isEmpty()
				? List.of(new Entry(System.currentTimeMillis(), Kind.GOOD, "Joined example.server"), new Entry(System.currentTimeMillis(), Kind.BAD, "Disconnected: example"))
				: visible();
			long now = System.currentTimeMillis();
			int row = y + PAD;
			for (Entry e : shown) {
				boolean old = fadeAfter.get() > 0 && now - e.at() > fadeAfter.get() * 1000;
				int color = switch (e.kind()) {
					case GOOD -> 0xFF55FF55;
					case WARN -> ui.theme.warn;
					case BAD -> ui.theme.error;
					default -> ui.theme.text;
				};
				if (old) color = (color & 0x00FFFFFF) | 0x80000000;
				String text = (showTime.get() ? STAMP.format(LocalTime.ofInstant(Instant.ofEpochMilli(e.at()), ZoneId.systemDefault())) + "  " : "") + e.text();
				Draw.text(ui.g, Draw.trim(text, width() - PAD * 2), x + PAD, row, color);
				row += ROW;
			}
		}
	}
}
