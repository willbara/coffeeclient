package dev.coffeeclient.gui.screen;

import dev.coffeeclient.account.Account;
import dev.coffeeclient.account.AccountManager;
import dev.coffeeclient.account.MicrosoftAuth;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Open;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Account switcher, opened from Coffee's title screen. Saved accounts on the left (double-click or Log
 * in to switch); on the right, adding Microsoft (device code: you sign in on Microsoft's page, no
 * password here), offline and access-token accounts. Switching happens only from the menus. Saved
 * logins are encrypted; access tokens last about a day.
 */
public class AccountsScreen extends CoffeeScreen {
	private @Nullable Account selected;
	private int seenRevision = -1;
	private ScrollList list;
	private Panel addPanel;
	private int listX, listY, listW, listH;
	private String offlineName = "", token = "";
	/** Last outcome shown to the user, and whether it was an error. */
	private String status = "";
	private boolean statusError;
	private boolean busy;
	/** The Microsoft login in progress, if any. */
	private MicrosoftAuth.@Nullable DeviceCode deviceCode;
	private @Nullable AtomicBoolean cancelLogin;

	public AccountsScreen(@Nullable Screen parent) {
		super("Accounts", parent);
	}

	private static AccountManager accounts() {
		return CoffeeClient.accounts();
	}

	@Override
	protected void build() {
		int[] frame = centred43(680);
		int innerX = frame[0] + 4, innerW = frame[2] - 8;
		int top = addHeader(innerX, frame[1] + 4, innerW, "Accounts");
		root.add(new Label(this::playingAs, Label.Style.DIM)).setBounds(innerX, top, innerW, 12);
		top += 16;

		int bottom = frame[1] + frame[3] - 20;
		listX = innerX;
		listY = top;
		listW = Math.max(150, (innerW - 6) * 2 / 5);
		listH = bottom - 4 - listY;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(listX + 1, listY + 1, listW - 2, listH - 2);

		// Actions on the selected account, under the list.
		int half = (listW - 4) / 2;
		root.add(new Button(this::loginLabel, () -> {
			if (selected != null) login(selected);
		})).setBounds(listX, bottom, half, 16);
		root.add(new Button("Remove", () -> {
			if (selected == null) return;
			accounts().remove(selected);
			select(null);
		}).danger()).setBounds(listX + half + 4, bottom, listW - half - 4, 16);

		int rightX = innerX + listW + 6, rightW = innerW - listW - 6;
		addPanel = root.add(new Panel("Add account"));
		addPanel.setBounds(rightX, listY, rightW, listH);
		root.add(new Button("Use launch account", this::restoreOriginal)).setBounds(rightX, bottom, rightW, 16);
		seenRevision = -1;
		buildAddPanel();
	}

	private String loginLabel() {
		if (busy) return "Logging in...";
		if (selected == null) return "Log in";
		return selected == accounts().current() ? "In use" : "Log in";
	}

	private String playingAs() {
		Account current = accounts().current();
		return "Playing as " + accounts().currentName() + (current == null ? " (launch account)" : " (" + current.type.label + ")");
	}

	private void select(@Nullable Account account) {
		selected = account;
	}

	private void rebuildList() {
		list.clear();
		accounts().all().stream()
			.sorted(Comparator.comparingLong(Account::lastUsed).reversed().thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER))
			.forEach(a -> list.add(new Row(a)));
		if (selected != null && !accounts().all().contains(selected)) select(null);
	}

	private void buildAddPanel() {
		addPanel.clear();
		int x = addPanel.contentX(), w = addPanel.contentWidth(), y = addPanel.contentY() + 2;
		int buttonW = 40;

		addPanel.add(new Label("Microsoft", Label.Style.ACCENT)).setBounds(x, y, w, 12);
		y += 14;
		if (deviceCode == null) {
			addPanel.add(new Button(() -> busy && cancelLogin != null ? "Asking Microsoft..." : "Sign in with Microsoft", this::startMicrosoft))
				.setBounds(x, y, w, 16);
			y += 22;
		} else {
			MicrosoftAuth.DeviceCode code = deviceCode;
			addPanel.add(new Label("Enter this code on the page:", Label.Style.DIM)).setBounds(x, y, w, 12);
			addPanel.add(new Label(code.userCode(), Label.Style.TITLE)).setBounds(x, y + 12, w, 12);
			y += 28;
			int third = (w - 8) / 3;
			addPanel.add(new Button("Open page", () -> Open.url(code.link()))).setBounds(x, y, third, 16);
			addPanel.add(new Button("Copy code", () -> {
				minecraft.keyboardHandler.setClipboard(code.userCode());
				setStatus("Code copied", false);
			})).setBounds(x + third + 4, y, third, 16);
			addPanel.add(new Button("Cancel", this::cancelMicrosoft).danger()).setBounds(x + 2 * (third + 4), y, w - 2 * (third + 4), 16);
			y += 20;
			addPanel.add(new Label(() -> "Waiting for sign-in (" + secondsLeft(code) + " s)", Label.Style.DIM)).setBounds(x, y, w, 12);
			y += 16;
		}

		addPanel.add(new Label("Offline", Label.Style.ACCENT)).setBounds(x, y, w, 12);
		y += 14;
		addPanel.add(new TextField().placeholder("Username").maxLength(16).filter(s -> s.matches("[A-Za-z0-9_]*"))
			.onChange(s -> offlineName = s).onSubmit(s -> addOffline())).setBounds(x, y, w - buttonW - 4, 14);
		addPanel.add(new Button("Add", this::addOffline)).setBounds(x + w - buttonW, y, buttonW, 14);
		y += 22;

		addPanel.add(new Label("Access token", Label.Style.ACCENT)).setBounds(x, y, w, 12);
		y += 14;
		addPanel.add(new TextField().placeholder("Paste token").maxLength(4096).masked()
			.onChange(s -> token = s).onSubmit(s -> addToken())).setBounds(x, y, w - buttonW - 4, 14);
		addPanel.add(new Button("Add", this::addToken)).setBounds(x + w - buttonW, y, buttonW, 14);
		int statusY = addPanel.y + addPanel.height - Panel.PAD - 12;
		addPanel.add(new Label(() -> status, Label.Style.TEXT).color(ui -> statusError ? ui.theme.error : ui.theme.accent))
			.setBounds(x, Math.max(y + 18, statusY), w, 12);
	}

	private static long secondsLeft(MicrosoftAuth.DeviceCode code) {
		return Math.max(0, (code.expiresAt() - System.currentTimeMillis()) / 1000);
	}

	// ---- actions ----

	private void login(Account account) {
		if (busy || account == accounts().current()) return;
		busy = true;
		setStatus("Logging in as " + account.name() + "...", false);
		accounts().login(account).whenComplete((v, error) -> minecraft.execute(() -> {
			busy = false;
			if (error != null) setStatus(message(error), true);
			else setStatus("Logged in as " + account.name(), false);
		}));
	}

	private void restoreOriginal() {
		if (busy || accounts().onOriginal()) return;
		accounts().restoreOriginal();
		setStatus("Back on " + accounts().currentName(), false);
	}

	private void addOffline() {
		Account account = accounts().addOffline(offlineName);
		if (account == null) {
			setStatus("Names are 1-16 letters, digits or _", true);
			return;
		}
		select(account);
		setStatus("Added " + account.name() + " (log in to use it)", false);
		rebuildWidgets();
	}

	private void addToken() {
		if (busy || token.isBlank()) return;
		busy = true;
		setStatus("Checking the token...", false);
		accounts().addToken(token).whenComplete((account, error) -> minecraft.execute(() -> {
			busy = false;
			if (error != null) {
				setStatus(message(error), true);
				return;
			}
			token = "";
			select(account);
			setStatus("Added " + account.name() + " (log in to use it)", false);
			rebuildWidgets();
		}));
	}

	private void startMicrosoft() {
		if (busy) return;
		busy = true;
		AtomicBoolean cancel = new AtomicBoolean();
		cancelLogin = cancel;
		setStatus("Asking Microsoft for a code...", false);
		CompletableFuture.supplyAsync(() -> {
			try {
				MicrosoftAuth.DeviceCode code = MicrosoftAuth.requestDeviceCode();
				minecraft.execute(() -> {
					if (cancel.get()) return;
					deviceCode = code;
					Open.url(code.link());
					setStatus("Sign in on the page that opened", false);
					buildAddPanel();
				});
				return MicrosoftAuth.awaitDeviceCode(code, cancel::get);
			} catch (MicrosoftAuth.AuthException e) {
				throw new IllegalStateException(e.getMessage());
			}
		}, Util.ioPool()).whenComplete((login, error) -> minecraft.execute(() -> {
			if (cancelLogin == cancel) {
				busy = false;
				deviceCode = null;
				cancelLogin = null;
			}
			if (cancel.get()) return;
			if (error != null) {
				setStatus(message(error), true);
			} else {
				Account account = accounts().addMicrosoft(login);
				select(account);
				setStatus("Logged in as " + account.name(), false);
			}
			buildAddPanel();
		}));
	}

	private void cancelMicrosoft() {
		if (cancelLogin != null) cancelLogin.set(true);
		cancelLogin = null;
		deviceCode = null;
		busy = false;
		setStatus("Login cancelled", false);
		buildAddPanel();
	}

	@Override
	public void removed() {
		if (cancelLogin != null) cancelLogin.set(true);
		super.removed();
	}

	private void setStatus(String text, boolean error) {
		status = text;
		statusError = error;
	}

	private static String message(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		String text = cause.getMessage();
		return text == null || text.isBlank() ? "Login failed" : text;
	}

	@Override
	protected void renderContent(Ui ui) {
		if (seenRevision != accounts().revision()) {
			seenRevision = accounts().revision();
			rebuildList();
		}
		Draw.panel(ui.g, ui.theme, listX, listY, listW, listH);
		if (accounts().all().isEmpty()) Draw.text(ui.g, "No saved accounts yet", listX + 8, listY + 8, ui.theme.dimText);
		super.renderContent(ui);
	}

	/** One account: name, type and whether it is the one in use. */
	private final class Row extends Widget {
		private final Account account;

		Row(Account account) {
			this.account = account;
			this.height = 22;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean sel = account == selected;
			boolean inUse = account == accounts().current();
			Draw.listHighlight(ui.g, t, x, y, width, height, sel, hover, 0x18);
			Draw.text(ui.g, Draw.trim(account.name(), width - 12), x + 6, y + 2, sel ? t.strongText() : t.text);
			Draw.text(ui.g, inUse ? account.type.label + " - in use" : account.type.label, x + 6, y + 12, inUse ? t.accent : t.dimText);
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			select(account);
			if (button == 0 && System.currentTimeMillis() - lastClick < 350 && lastClicked == account) login(account);
			lastClick = System.currentTimeMillis();
			lastClicked = account;
			return true;
		}
	}

	/** Double-click on a row logs in. */
	private long lastClick;
	private @Nullable Account lastClicked;
}
