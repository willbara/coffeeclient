package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.friend.Friend;
import dev.coffeeclient.friend.FriendManager;
import dev.coffeeclient.friend.FriendTrait;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.ColorSwatch;
import dev.coffeeclient.gui.widget.CycleButton;
import dev.coffeeclient.gui.widget.Dropdown;
import dev.coffeeclient.gui.widget.FormRow;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.MenuList;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.SettingRow;
import dev.coffeeclient.gui.widget.Slider;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Toggle;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Skins;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Friends: the list on the left (add by name, as a temporary friend, or from the players online);
 * on the right either the selected friend (note, colour, how long they stay a friend, and overrides
 * of the friend rules) or, with nobody selected, the rules for all friends.
 */
public class FriendsScreen extends CoffeeScreen {
	/** How long a friendship lasts, as offered in the window. */
	private record Duration(String label, long millis) {
		static final long PERMANENT = 0, SESSION = -1;

		long expiresAt() {
			if (millis == PERMANENT) return Friend.PERMANENT;
			if (millis == SESSION) return Friend.SESSION;
			return System.currentTimeMillis() + millis;
		}
	}

	private static final List<Duration> DURATIONS = List.of(
		new Duration("Permanent", Duration.PERMANENT),
		new Duration("This session", Duration.SESSION),
		new Duration("10 minutes", 10 * 60_000L),
		new Duration("1 hour", 60 * 60_000L),
		new Duration("1 day", 24 * 60 * 60_000L),
		new Duration("1 week", 7 * 24 * 60 * 60_000L));

	private static final int[] PALETTE = {-1, 0x5CD65C, 0x55FFFF, 0x5599FF, 0xB86FD9, 0xFF77CC, 0xFF5555, 0xFFA040, 0xFFFF55, 0xFFFFFF};

	private String newName = "";
	private Button tempButton, onlineButton;
	private @Nullable Friend selected;
	private int seenRevision = -1;
	private ScrollList list;
	private Panel editor;
	private ScrollList form;
	private int listX, listY, listW, listH;

	public FriendsScreen(@Nullable Screen parent) {
		super("Friends", parent);
	}

	/** Opens with {@code friend} selected. */
	public FriendsScreen(@Nullable Screen parent, @Nullable Friend friend) {
		this(parent);
		this.selected = friend;
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().friends;
	}

	private static FriendManager friends() {
		return CoffeeClient.friends();
	}

	@Override
	protected void build() {
		int[] frame = centred43(680);
		int x0 = frame[0] + 4, y0 = frame[1], contentW = frame[2] - 8;
		int top = addHeader(x0, y0 + 4, contentW, "Friends");

		listW = Math.max(170, (contentW - 6) * 2 / 5);
		int editorW = contentW - listW - 6;
		int addW = 34, tempW = 50;
		root.add(new TextField().placeholder("Player name").maxLength(32)
			.onChange(s -> newName = s)
			.onSubmit(s -> add(Friend.PERMANENT))).setBounds(x0, top, listW - addW - tempW - 8, 14);
		root.add(new Button("Add", () -> add(Friend.PERMANENT))).setBounds(x0 + listW - addW - tempW - 4, top, addW, 14)
			.tooltip("Add as a permanent friend");
		tempButton = root.add(new Button("Temp", this::openTempMenu));
		tempButton.setBounds(x0 + listW - tempW, top, tempW, 14).tooltip("Add as a temporary friend: this session or for a while");
		onlineButton = root.add(new Button("Add a player online...", this::openOnlineMenu));
		onlineButton.setBounds(x0, top + 18, listW, 14);

		listX = x0;
		listY = top + 36;
		listH = y0 + frame[3] - listY - 4;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(listX + 1, listY + 1, listW - 2, listH - 2);

		editor = root.add(new Panel("").subtitle(() -> selected == null ? "rules for all friends" : expiryText(selected)));
		editor.setBounds(x0 + listW + 6, top, editorW, y0 + frame[3] - top - 4);
		seenRevision = -1;
		buildEditor();
	}

	private void add(long expiresAt) {
		if (newName.isBlank()) return;
		select(friends().add(newName, null, expiresAt));
		newName = "";
		rebuildWidgets();
	}

	private void openTempMenu() {
		if (newName.isBlank()) return;
		MenuList menu = new MenuList().header("Friend for...");
		for (Duration d : DURATIONS.subList(1, DURATIONS.size())) menu.add(d.label(), () -> add(d.expiresAt()));
		menu.openAt(tempButton.x, tempButton.y + tempButton.height + 1, UiState.screenWidth(), UiState.screenHeight(), 90);
	}

	/** Players on the tab list who are not friends yet. */
	private void openOnlineMenu() {
		MenuList menu = new MenuList().header("Online players");
		var connection = minecraft.getConnection();
		List<PlayerInfo> online = connection == null ? List.of() : new ArrayList<>(connection.getOnlinePlayers());
		online.sort(Comparator.comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
		int added = 0;
		for (PlayerInfo info : online) {
			String name = info.getProfile().name();
			if (minecraft.player != null && name.equals(minecraft.player.getGameProfile().name()) || friends().isFriend(name)) continue;
			menu.add(name, () -> select(friends().add(name, info.getProfile().id(), Friend.PERMANENT)));
			if (++added >= 40) break;
		}
		if (added == 0) menu.add("Nobody else online", () -> { });
		menu.openAt(onlineButton.x, onlineButton.y + onlineButton.height + 1, UiState.screenWidth(), UiState.screenHeight(), onlineButton.width);
	}

	private void select(@Nullable Friend friend) {
		selected = friend;
		buildEditor();
	}

	private void rebuildList() {
		list.clear();
		List<Friend> all = new ArrayList<>(friends().all());
		// Permanent friends first, then by name.
		all.sort(Comparator.comparing(Friend::temporary).thenComparing(Friend::name, String.CASE_INSENSITIVE_ORDER));
		for (Friend f : all) list.add(new Row(f));
		if (all.isEmpty()) {
			list.add(new Label("No friends yet.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 14);
			list.add(new Label("Add a name above, or a player online.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 12);
		}
		if (selected != null && !all.contains(selected)) select(null);
	}

	private void buildEditor() {
		editor.clear();
		form = editor.add(new ScrollList().padding(1));
		form.setBounds(editor.contentX() - 2, editor.contentY(), editor.contentWidth() + 4, editor.contentHeight() - 20);
		int x = editor.contentX(), w = editor.contentWidth();
		int bottom = editor.contentY() + editor.contentHeight() - 16;
		int half = (w - 4) / 2;
		Friend f = selected;
		if (f == null) {
			buildRules();
			editor.add(new Button("Remove temp", () -> {
				friends().clearTemporary();
				select(null);
			}).tooltip("Remove every temporary friend")).setBounds(x, bottom, half, 16);
			editor.add(new Button("Remove all", () -> {
				friends().clear();
				select(null);
			}).danger()).setBounds(x + w - half, bottom, half, 16);
		} else {
			buildFriend(f);
			editor.add(new Button("All rules", () -> select(null)).tooltip("Back to the rules for all friends")).setBounds(x, bottom, half, 16);
			editor.add(new Button("Remove", () -> {
				friends().remove(f);
				select(null);
			}).danger()).setBounds(x + w - half, bottom, half, 16);
		}
	}

	/** Nobody selected: the defaults for all friends. */
	private void buildRules() {
		form.add(new Label("What being a friend means", Label.Style.TITLE).indent(4)).setBounds(0, 0, 0, 14);
		form.add(new Label("(each friend can override these)", Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 12);
		Friends module = CoffeeClient.modules().get(Friends.class);
		for (FriendTrait trait : FriendTrait.values()) {
			var setting = module.rule(trait);
			SettingRow row = new SettingRow(trait.label(), new Toggle(setting::get, () -> setting.set(!setting.get())), 14);
			row.tooltip(trait.description());
			form.add(row);
		}
		form.add(new Label("Adding friends", Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 14);
		var click = module.middleClick;
		SettingRow clickRow = new SettingRow(click.name(), new Toggle(click::get, () -> click.set(!click.get())), 14);
		clickRow.tooltip(click.description());
		form.add(clickRow);
		SettingRow rangeRow = new SettingRow(module.middleClickRange.name(), new Slider(module.middleClickRange), 100);
		rangeRow.tooltip(module.middleClickRange.description());
		form.add(rangeRow);
	}

	/** One friend: note, colour, duration and rule overrides. */
	private void buildFriend(Friend f) {
		int labelW = 54;
		form.add(new Label(f.name(), Label.Style.TITLE).indent(4)).setBounds(0, 0, 0, 14);
		form.add(new FormRow(18, (r, x, y, w) -> {
			r.place(new Label("Note", Label.Style.DIM), x, y + 3, labelW, 12);
			r.place(new TextField().text(f.note()).maxLength(64).placeholder("Who is this?")
				.onChange(s -> friends().setNote(f, s)), x + labelW, y, w - labelW, 14);
		}));
		form.add(new FormRow(18, (r, x, y, w) -> {
			r.place(new Label("Colour", Label.Style.DIM), x, y + 3, labelW, 12);
			int size = Math.max(6, Math.min(12, (w - labelW) / PALETTE.length - 3));
			int sx = x + labelW;
			for (int rgb : PALETTE) {
				int shown = rgb < 0 ? 0x808080 : rgb;
				ColorSwatch swatch = new ColorSwatch(() -> 0xFF000000 | shown, () -> friends().setColor(f, rgb)).selected(() -> f.color() == rgb);
				swatch.tooltip(rgb < 0 ? "Each module's own friend colour" : "Use this colour for " + f.name());
				r.place(swatch, sx, y + 1, size, size);
				sx += size + 3;
			}
		}));
		form.add(new FormRow(18, (r, x, y, w) -> {
			r.place(new Label("Friend for", Label.Style.DIM), x, y + 3, labelW, 12);
			r.place(new Dropdown<>(() -> DURATIONS, () -> currentDuration(f), Duration::label,
				d -> friends().setExpiry(f, d.expiresAt())), x + labelW, y, w - labelW, 14);
		}));
		form.add(new Label("Rules for " + f.name(), Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 14);
		for (FriendTrait trait : FriendTrait.values()) {
			CycleButton cycle = new CycleButton(() -> overrideText(f, trait), dir -> cycleOverride(f, trait, dir));
			SettingRow row = new SettingRow(trait.label(), cycle, Math.min(110, Math.max(70, (editor.contentWidth()) / 2)));
			row.tooltip(trait.description());
			form.add(row);
		}
	}

	private static Duration currentDuration(Friend f) {
		if (f.expiresAt() == Friend.PERMANENT) return DURATIONS.get(0);
		if (f.expiresAt() == Friend.SESSION) return DURATIONS.get(1);
		return new Duration(expiryText(f), f.expiresAt());
	}

	private String overrideText(Friend f, FriendTrait trait) {
		Boolean o = f.override(trait);
		if (o == null) return "Default (" + (friends().effective(f, trait) ? "yes" : "no") + ")";
		return o ? "Always" : "Never";
	}

	/** Default -> Always -> Never -> Default (or backwards). */
	private void cycleOverride(Friend f, FriendTrait trait, int dir) {
		Boolean[] order = {null, true, false};
		Boolean o = f.override(trait);
		int i = o == null ? 0 : o ? 1 : 2;
		friends().setOverride(f, trait, order[Math.floorMod(i + dir, 3)]);
	}

	private static String expiryText(Friend f) {
		if (f.expiresAt() == Friend.PERMANENT) return "permanent";
		if (f.expiresAt() == Friend.SESSION) return "this session";
		long left = Math.max(0, f.expiresAt() - System.currentTimeMillis()) / 1000;
		if (left < 3600) return (left / 60 + 1) + " min left";
		if (left < 86400) return String.format(Locale.ROOT, "%d h %d min left", left / 3600, left % 3600 / 60);
		return (left / 86400) + " d " + (left % 86400 / 3600) + " h left";
	}

	@Override
	protected void renderContent(Ui ui) {
		if (seenRevision != friends().revision()) {
			seenRevision = friends().revision();
			rebuildList();
		}
		Draw.panel(ui.g, ui.theme, listX, listY, listW, listH);
		super.renderContent(ui);
	}

	/** One friend: head, name, note or time left, colour dot, remove cross. */
	private final class Row extends Widget {
		private final Friend friend;
		/** The friend's head item, or null on the title screen, where no item can exist yet. */
		private final ItemStack head;
		private final ResolvableProfile profile;

		Row(Friend friend) {
			this.friend = friend;
			ItemStack item = null;
			try {
				item = Skins.head(friend.name());
			} catch (RuntimeException e) {
				// Item components are bound when a world loads; draw the friend's face until then.
			}
			this.head = item;
			this.profile = ResolvableProfile.createUnresolved(friend.name().trim());
			this.height = 22;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean sel = friend == selected;
			Draw.listHighlight(ui.g, t, x, y, width, height, sel, hover, 0x14);
			if (head != null) ui.g.item(head, x + 4, y + 3);
			else PlayerFaceExtractor.extractRenderState(ui.g, profile, x + 4, y + 3, 16);
			int nameColor = friend.color() >= 0 ? 0xFF000000 | friend.color() : sel ? t.strongText() : t.text;
			Draw.text(ui.g, Draw.trim(friend.name(), width - 44), x + 24, y + 2, nameColor);
			String sub = friend.temporary() ? expiryText(friend) : friend.note().isEmpty() ? "" : friend.note();
			if (friend.temporary() && !friend.note().isEmpty()) sub += " · " + friend.note();
			Draw.text(ui.g, Draw.trim(sub, width - 44), x + 24, y + 12, friend.temporary() ? t.warn : t.dimText);
			boolean overDelete = isHovered(ui) && ui.mouseX >= x + width - 18;
			Draw.cross(ui.g, x + width - 12, y + 8, 7, overDelete ? t.error : t.dimText);
			if (overDelete) ui.tooltip("Remove " + friend.name());
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (mx >= x + width - 18) {
				friends().remove(friend);
				if (friend == selected) select(null);
			} else {
				select(friend);
			}
			return true;
		}
	}
}
