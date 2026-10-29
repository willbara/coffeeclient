package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.CycleButton;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.proxy.Proxy;
import dev.coffeeclient.proxy.ProxyManager;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Proxy manager, opened from Coffee's title screen: saved SOCKS5, SOCKS4 and HTTP proxies on the left,
 * the selected one (or a new one) on the right. While a proxy is in use, joining servers and the
 * server list's pings go through it.
 */
public class ProxiesScreen extends CoffeeScreen {
	private @Nullable Proxy selected;
	private int seenRevision = -1;
	private ScrollList list;
	private Panel editor;
	private int listX, listY, listW, listH;
	// The form, applied on Save.
	private String name = "", host = "", port = "", username = "", password = "";
	private Proxy.Type type = Proxy.Type.SOCKS5;
	private String status = "";
	private boolean statusError;

	public ProxiesScreen(@Nullable Screen parent) {
		super("Proxies", parent);
	}

	private static ProxyManager proxies() {
		return CoffeeClient.proxies();
	}

	@Override
	protected void build() {
		int[] frame = centred43(680);
		int innerX = frame[0] + 4, innerW = frame[2] - 8;
		int top = addHeader(innerX, frame[1] + 4, innerW, "Proxies");
		root.add(new Label(this::connectionLine, Label.Style.DIM)).setBounds(innerX, top, innerW - 76, 12);
		root.add(new Button("New", () -> select(null))).setBounds(innerX + innerW - 72, top - 2, 72, 16);
		top += 16;

		listX = innerX;
		listY = top;
		listW = Math.max(150, (innerW - 6) * 2 / 5);
		listH = frame[1] + frame[3] - 26 - listY;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(listX + 1, listY + 1, listW - 2, listH - 2);

		editor = root.add(new Panel("Proxy").subtitle(() -> selected == null ? "new" : selected == proxies().active() ? "in use" : ""));
		editor.setBounds(innerX + listW + 6, listY, innerW - listW - 6, listH);

		int bottom = frame[1] + frame[3] - 20;
		root.add(new Label(() -> status, Label.Style.TEXT).color(ui -> statusError ? ui.theme.error : ui.theme.accent))
			.setBounds(innerX, bottom + 2, innerW - 136, 12);
		root.add(new Button("Connect directly", () -> {
			proxies().setActive(null);
			setStatus("Connecting directly", false);
		})).setBounds(innerX + innerW - 132, bottom, 132, 16);
		seenRevision = -1;
		buildEditor();
	}

	private String connectionLine() {
		Proxy active = proxies().active();
		return active == null ? "Connections: direct" : "Connections go through " + active.displayName() + " (" + active.type.label + " " + active.address() + ")";
	}

	private void select(@Nullable Proxy proxy) {
		selected = proxy;
		if (proxy == null) {
			name = host = port = username = password = "";
			type = Proxy.Type.SOCKS5;
		} else {
			name = proxy.name;
			host = proxy.host;
			port = String.valueOf(proxy.port);
			username = proxy.username;
			password = proxy.password;
			type = proxy.type;
		}
		buildEditor();
	}

	private void rebuildList() {
		list.clear();
		for (Proxy proxy : proxies().all()) list.add(new Row(proxy));
		if (selected != null && !proxies().all().contains(selected)) select(null);
	}

	private void buildEditor() {
		editor.clear();
		int x = editor.contentX(), w = editor.contentWidth(), y = editor.contentY() + 2;
		int labelW = 58, fieldW = w - labelW;
		y = field("Name", new TextField().text(name).placeholder("Optional").maxLength(32).onChange(s -> name = s), x, y, labelW, fieldW);
		editor.add(new Label("Type", Label.Style.DIM)).setBounds(x, y + 1, labelW, 12);
		editor.add(new CycleButton(() -> type.label, step -> type = Proxy.Type.values()[Math.floorMod(type.ordinal() + step, Proxy.Type.values().length)]))
			.setBounds(x + labelW, y, fieldW, 14);
		y += 18;
		y = field("Host", new TextField().text(host).placeholder("Address").maxLength(253).onChange(this::onHost), x, y, labelW, fieldW);
		y = field("Port", new TextField().text(port).placeholder("1080").maxLength(5).filter(s -> s.matches("\\d*")).onChange(s -> port = s), x, y, labelW, fieldW);
		y = field("Username", new TextField().text(username).placeholder("Optional").maxLength(255).onChange(s -> username = s), x, y, labelW, fieldW);
		y = field("Password", new TextField().text(password).placeholder("Optional").maxLength(255).masked().onChange(s -> password = s), x, y, labelW, fieldW);
		y += 4;

		int half = (w - 4) / 2;
		Proxy proxy = selected;
		editor.add(new Button(proxy == null ? "Add" : "Save", this::save)).setBounds(x, y, half, 16);
		editor.add(new Button("Test", this::test)).setBounds(x + half + 4, y, w - half - 4, 16);
		y += 20;
		if (proxy != null) {
			editor.add(new Button(() -> proxy == proxies().active() ? "Stop using" : "Use", () -> {
				boolean using = proxy == proxies().active();
				proxies().setActive(using ? null : proxy);
				setStatus(using ? "Connecting directly" : "Using " + proxy.displayName(), false);
			})).setBounds(x, y, half, 16);
			editor.add(new Button("Delete", () -> {
				proxies().remove(proxy);
				select(null);
			}).danger()).setBounds(x + half + 4, y, w - half - 4, 16);
			y += 22;
			editor.add(new Label(() -> testResult(proxy), Label.Style.DIM)).setBounds(x, y, w, 12);
		}
	}

	private int field(String label, TextField field, int x, int y, int labelW, int fieldW) {
		editor.add(new Label(label, Label.Style.DIM)).setBounds(x, y + 1, labelW, 12);
		editor.add(field).setBounds(x + labelW, y, fieldW, 14);
		return y + 18;
	}

	/** "host:port" pasted into Host fills Port too (only on paste, so typing is not interrupted). */
	private void onHost(String text) {
		boolean pasted = text.length() > host.length() + 1;
		int colon = text.lastIndexOf(':');
		if (pasted && colon > 0 && text.indexOf(':') == colon && text.substring(colon + 1).matches("\\d{1,5}")) {
			host = text.substring(0, colon);
			port = text.substring(colon + 1);
			buildEditor();
		} else {
			host = text;
		}
	}

	private static String testResult(Proxy proxy) {
		if (proxy.latency > 0) return "Last test: working, " + proxy.latency + " ms";
		if (proxy.latency < 0) return "Last test: failed (" + proxy.lastError + ")";
		return "Not tested yet";
	}

	/** The form as a proxy, or null (with a status message) when it is incomplete. */
	private @Nullable Proxy fromForm() {
		String cleanHost = host.trim();
		int portNumber = port.isEmpty() ? -1 : Integer.parseInt(port);
		if (cleanHost.isEmpty() || cleanHost.contains(" ")) {
			setStatus("Enter the proxy's host", true);
			return null;
		}
		if (portNumber < 1 || portNumber > 65535) {
			setStatus("Port must be 1-65535", true);
			return null;
		}
		return new Proxy(name.trim(), type, cleanHost, portNumber, username.trim(), type == Proxy.Type.SOCKS4 ? "" : password);
	}

	private void save() {
		Proxy form = fromForm();
		if (form == null) return;
		Proxy proxy = selected;
		if (proxy == null) {
			select(proxies().add(form));
			setStatus("Added " + form.displayName(), false);
			return;
		}
		proxy.name = form.name;
		proxy.type = form.type;
		proxy.host = form.host;
		proxy.port = form.port;
		proxy.username = form.username;
		proxy.password = form.password;
		proxy.latency = 0;
		proxies().edited();
		setStatus("Saved " + proxy.displayName(), false);
	}

	private void test() {
		Proxy proxy = selected != null ? selected : fromForm();
		if (proxy == null) return;
		setStatus("Testing " + proxy.displayName() + "...", false);
		proxies().test(proxy).whenComplete((ms, error) -> minecraft.execute(() -> {
			if (error != null) setStatus("Test failed: " + proxy.lastError, true);
			else setStatus(proxy.displayName() + " works (" + ms + " ms)", false);
		}));
	}

	private void setStatus(String text, boolean error) {
		status = text;
		statusError = error;
	}

	@Override
	protected void renderContent(Ui ui) {
		if (seenRevision != proxies().revision()) {
			seenRevision = proxies().revision();
			rebuildList();
		}
		Draw.panel(ui.g, ui.theme, listX, listY, listW, listH);
		if (proxies().all().isEmpty()) Draw.text(ui.g, "No saved proxies yet", listX + 8, listY + 8, ui.theme.dimText);
		super.renderContent(ui);
	}

	/** One proxy: name, type and address, and its last test. */
	private final class Row extends Widget {
		private final Proxy proxy;

		Row(Proxy proxy) {
			this.proxy = proxy;
			this.height = 22;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean sel = proxy == selected;
			boolean inUse = proxy == proxies().active();
			Draw.listHighlight(ui.g, t, x, y, width, height, sel, hover, 0x18);
			Draw.text(ui.g, Draw.trim(proxy.displayName(), width - 12), x + 6, y + 2, sel ? t.strongText() : t.text);
			String line = proxy.type.label + " " + proxy.address() + (inUse ? " - in use" : "");
			Draw.text(ui.g, Draw.trim(line, width - 40), x + 6, y + 12, inUse ? t.accent : t.dimText);
			if (proxy.latency != 0) {
				String ms = proxy.latency > 0 ? proxy.latency + "ms" : "fail";
				Draw.textRight(ui.g, ms, x + width - 4, y + 12, proxy.latency > 0 ? t.dimText : t.error);
			}
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			select(proxy);
			return true;
		}
	}
}
