package dev.coffeeclient.gui.screen;

import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.ResetRow;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.SectionHeader;
import dev.coffeeclient.gui.widget.SettingControls;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.modules.exploit.PacketInspector;
import dev.coffeeclient.module.modules.exploit.PacketInspector.Entry;
import dev.coffeeclient.module.modules.exploit.PacketInspector.TopRow;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PacketInspector window: a live feed of packets (pausable), a graph with the top packets, and the
 * module's settings. Opened from the ClickGUI like the other config windows.
 */
public class PacketInspectorScreen extends CoffeeScreen {
	private enum Tab {
		LIVE("Live"), STATS("Stats"), SEND("Send"), SETTINGS("Settings");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	private static final int ROW = 10;

	private final PacketInspector module;
	private final List<Map.Entry<Widget, Setting<?>>> rows = new ArrayList<>();
	private final List<Widget> liveWidgets = new ArrayList<>();
	private final List<Widget> statsWidgets = new ArrayList<>();
	private final List<Widget> sendWidgets = new ArrayList<>();
	private String sendStatus = "";
	private Tab tab = Tab.LIVE;
	private boolean paused;
	private @Nullable List<Entry> frozen;
	private ScrollList settings;
	private int contentX, contentY, contentW, contentH;

	public PacketInspectorScreen(@Nullable Screen parent, PacketInspector module) {
		super(module.name(), parent);
		this.module = module;
	}

	@Override
	protected void build() {
		rows.clear();
		liveWidgets.clear();
		statsWidgets.clear();
		sendWidgets.clear();
		int[] frame = centred43(640);
		int x0 = frame[0] + 6, y0 = frame[1] + 4, w = frame[2] - 12;

		root.add(new Label(module.name(), Label.Style.TITLE)).setBounds(x0, y0, Draw.width(module.name()) + 2, 14);
		root.add(new Button("Back", this::onClose)).setBounds(x0 + w - 56, y0 - 1, 56, 16);
		int tabX = x0;
		for (Tab t : Tab.values()) {
			root.add(new Button(() -> tab == t ? "[" + t.label + "]" : t.label, () -> show(t))).setBounds(tabX, y0 + 18, 64, 16);
			tabX += 68;
		}

		contentX = x0;
		contentY = y0 + 40;
		contentW = w;
		contentH = frame[1] + frame[3] - contentY - 6;

		buildLive();
		buildStats();
		buildSend();
		buildSettings();
		show(tab);
	}

	private void buildLive() {
		Button pause = root.add(new Button(() -> paused ? "Resume" : "Pause", this::togglePause));
		pause.setBounds(contentX, contentY, 56, 16);
		Button clear = root.add(new Button("Clear", () -> {
			module.clear();
			frozen = paused ? List.of() : null;
		}));
		clear.setBounds(contentX + 60, contentY, 56, 16);
		Label note = root.add(new Label(() -> "Filter: " + module.filter.get().label() + (paused ? ". Paused, showing the packets from when you paused."
			: ". Newest at the bottom; scroll to look back."), Label.Style.DIM));
		note.setBounds(contentX + 124, contentY + 3, contentW - 124, 12);
		Feed feed = root.add(new Feed());
		feed.setBounds(contentX, contentY + 20, contentW, contentH - 20);
		liveWidgets.addAll(List.of(pause, clear, note, feed));
	}

	private void buildStats() {
		Graph graph = root.add(new Graph());
		graph.setBounds(contentX, contentY, contentW, Math.min(120, contentH / 2));
		Table table = root.add(new Table());
		table.setBounds(contentX, contentY + Math.min(120, contentH / 2) + 8, contentW, contentH - Math.min(120, contentH / 2) - 8);
		statsWidgets.addAll(List.of(graph, table));
	}

	private void buildSend() {
		TextField field = root.add(new TextField().placeholder("e.g. swing, pos 0 64 0 true, chat /help").maxLength(256));
		field.setBounds(contentX, contentY, contentW - 64, 16);
		Runnable go = () -> {
			String err = module.send(field.text());
			sendStatus = err == null ? "Sent. Replies for the next 3 s appear below." : err;
		};
		field.onSubmit(s -> go.run());
		Button button = root.add(new Button("Send", go));
		button.setBounds(contentX + contentW - 60, contentY, 60, 16);
		Label help = root.add(new Label(() -> sendStatus.isEmpty() ? PacketInspector.SEND_HELP : sendStatus, Label.Style.DIM));
		help.setBounds(contentX, contentY + 20, contentW, 12);
		Responses out = root.add(new Responses());
		out.setBounds(contentX, contentY + 36, contentW, contentH - 36);
		sendWidgets.addAll(List.of(field, button, help, out));
	}

	private void buildSettings() {
		settings = root.add(new ScrollList().padding(1));
		settings.setBounds(contentX, contentY, contentW, contentH);
		Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
		if (module.isToggleable() && !module.isAction()) groups.computeIfAbsent("General", g -> new ArrayList<>()).add(module.keybind());
		if (module.listable()) groups.computeIfAbsent("General", g -> new ArrayList<>()).add(module.shownInList());
		for (Setting<?> s : module.settings()) {
			groups.computeIfAbsent(s.group().isEmpty() ? "General" : s.group(), g -> new ArrayList<>()).add(s);
		}
		for (var group : groups.entrySet()) {
			settings.add(new SectionHeader(group.getKey()));
			for (Setting<?> s : group.getValue()) {
				Widget row = ResetRow.wrap(SettingControls.row(s), s);
				if (row == null) continue;
				settings.add(row);
				rows.add(Map.entry(row, s));
			}
		}
	}

	private void show(Tab next) {
		tab = next;
		for (Widget w : liveWidgets) w.visible = next == Tab.LIVE;
		for (Widget w : statsWidgets) w.visible = next == Tab.STATS;
		for (Widget w : sendWidgets) w.visible = next == Tab.SEND;
		settings.visible = next == Tab.SETTINGS;
	}

	private void togglePause() {
		paused = !paused;
		frozen = paused ? module.recent() : null;
	}

	@Override
	protected void renderContent(Ui ui) {
		for (var e : rows) e.getKey().visible = e.getValue().isVisible();
		// The settings list sits on the same panel as the feed and the stats boxes.
		if (tab == Tab.SETTINGS) Draw.panel(ui.g, ui.theme, contentX, contentY, contentW, contentH);
		super.renderContent(ui);
	}

	/** The packets shown in the feed: the live list, or the frozen one while paused. */
	private List<Entry> feedEntries() {
		return paused && frozen != null ? frozen : module.recent();
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	/** The packet list, newest at the bottom. Scrolling the wheel looks back in time. */
	private final class Feed extends Widget {
		private int back;

		@Override
		protected void draw(Ui ui) {
			Draw.panel(ui.g, ui.theme, x, y, width, height);
			List<Entry> all = feedEntries();
			int rows = Math.max(1, (height - 14) / ROW);
			back = Math.min(back, Math.max(0, all.size() - rows));
			int end = all.size() - back;
			int start = Math.max(0, end - rows);
			int sizeX = x + width - 6;
			Draw.text(ui.g, "time", x + 6, y + 4, ui.theme.dimText);
			Draw.text(ui.g, "dir", x + 96, y + 4, ui.theme.dimText);
			Draw.text(ui.g, "packet", x + 128, y + 4, ui.theme.dimText);
			Draw.textRight(ui.g, "bytes", sizeX, y + 4, ui.theme.dimText);
			int row = y + 14;
			if (all.isEmpty()) Draw.text(ui.g, "No packets yet. Traffic appears here as it moves.", x + 6, row, ui.theme.dimText);
			for (int i = start; i < end; i++) {
				Entry e = all.get(i);
				Draw.text(ui.g, STAMP.format(Instant.ofEpochMilli(e.at())), x + 6, row, ui.theme.dimText);
				Draw.text(ui.g, e.outbound() ? "OUT" : "IN", x + 96, row, e.outbound() ? 0xFFE0A050 : 0xFF5CD65C);
				Draw.text(ui.g, Draw.trim(shortId(e.id()), width - 128 - 60), x + 128, row, ui.theme.text);
				Draw.textRight(ui.g, e.bytes() + " B", sizeX, row, ui.theme.text);
				row += ROW;
			}
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			back = Math.max(0, back + (amount > 0 ? 3 : -3));
			return true;
		}
	}

	/** The sent packet and what the server answered with. */
	private final class Responses extends Widget {
		@Override
		protected void draw(Ui ui) {
			Draw.panel(ui.g, ui.theme, x, y, width, height);
			List<String> lines = module.probeLines();
			if (lines.isEmpty()) Draw.text(ui.g, "Nothing sent yet.", x + 6, y + 4, ui.theme.dimText);
			int row = y + 4;
			for (String l : lines) {
				if (row + ROW > y + height - 2) break;
				Draw.text(ui.g, Draw.trim(l, width - 12), x + 6, row, l.contains(" OUT ") ? 0xFFE0A050 : ui.theme.text);
				row += ROW;
			}
		}
	}

	/** Packets per second over the last 30 seconds, inbound and outbound. */
	private final class Graph extends Widget {
		@Override
		protected void draw(Ui ui) {
			Draw.panel(ui.g, ui.theme, x, y, width, height);
			float[][] r = module.rates();
			int n = r[0].length;
			float max = 20;
			for (int i = 0; i < n; i++) max = Math.max(max, Math.max(r[0][i], r[1][i]));
			int left = x + 6, top = y + 16, h = height - 26, w = width - 12;
			Draw.rect(ui.g, left, top + h / 2, w, 1, 0x30FFFFFF);
			String legend = String.format(Locale.ROOT, "in %.0f   out %.0f   max %.0f", n == 0 ? 0 : r[0][n - 1], n == 0 ? 0 : r[1][n - 1], max);
			Draw.text(ui.g, "Packets per second (30 s)", left, y + 4, ui.theme.heading);
			Draw.textRight(ui.g, legend, x + width - 6, y + 4, ui.theme.dimText);
			line(ui, r[0], n, max, left, top, w, h, 0xFF5CD65C);
			line(ui, r[1], n, max, left, top, w, h, 0xFFE0A050);
		}

		private void line(Ui ui, float[] data, int n, float max, int left, int top, int w, int h, int color) {
			float step = n > 1 ? w / (float) (n - 1) : 0;
			float px = Float.NaN, py = Float.NaN;
			for (int i = 0; i < n; i++) {
				float sx = left + i * step;
				float sy = top + h - 1 - Math.min(1f, data[i] / max) * (h - 2);
				if (!Float.isNaN(px)) Draw.line(ui.g, px, py, sx, sy, 1, 0xFF000000 | color);
				px = sx;
				py = sy;
			}
		}
	}

	/** The packets with the most traffic in the last five seconds. */
	private final class Table extends Widget {
		@Override
		protected void draw(Ui ui) {
			Draw.panel(ui.g, ui.theme, x, y, width, height);
			Draw.text(ui.g, "Top packets, last 5 s", x + 6, y + 4, ui.theme.heading);
			Draw.text(ui.g, "packet", x + 6, y + 18, ui.theme.dimText);
			Draw.textRight(ui.g, "per second", x + width - 110, y + 18, ui.theme.dimText);
			Draw.textRight(ui.g, "bytes per second", x + width - 6, y + 18, ui.theme.dimText);
			List<TopRow> rows = module.top();
			int row = y + 30;
			if (rows.isEmpty()) Draw.text(ui.g, "Nothing yet.", x + 6, row, ui.theme.dimText);
			for (TopRow t : rows) {
				if (row + ROW > y + height - 4) break;
				Draw.text(ui.g, Draw.trim(shortId(t.id()), width - 170), x + 6, row, ui.theme.text);
				Draw.textRight(ui.g, String.format(Locale.ROOT, "%.1f", t.count() / 5.0), x + width - 110, row, ui.theme.text);
				Draw.textRight(ui.g, String.valueOf(t.bytes() / 5), x + width - 6, row, ui.theme.text);
				row += ROW;
			}
		}
	}
}
