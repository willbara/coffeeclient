package dev.coffeeclient.gui.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Dropdown;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.TextArea;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.profile.Profile;
import dev.coffeeclient.profile.ProfileManager;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Open;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Profiles: named configuration snapshots in a centred 4:3 window. Create a profile from the current
 * config, manage the selected one, and share profiles as real config files in the profile's
 * {@code config/coffeeclient/profiles} folder (export/import).
 */
public class ProfilesScreen extends CoffeeScreen {
	private @Nullable Profile selected;
	private String newName = "";
	private int seenRevision = -1;
	private ScrollList list;
	private String pickedFile;
	private List<String> files = List.of();
	private String folder = "";
	private Panel editor;
	private int frameX, frameY, frameW, frameH, listX, listY, listW, listH;

	public ProfilesScreen(@Nullable Screen parent) {
		super("Profiles", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().profiles;
	}

	private ProfileManager profiles() {
		return CoffeeClient.profiles();
	}

	@Override
	protected void build() {
		int[] frame = centred43(680);
		frameX = frame[0];
		frameY = frame[1];
		frameW = frame[2];
		frameH = frame[3];
		int innerX = frameX + 4, innerW = frameW - 8;
		int top = addHeader(innerX, frameY + 4, innerW, "Profiles");

		// create: type a name (or leave blank) and hit Create
		int createW = 72;
		root.add(new TextField().placeholder("New profile name (optional)").maxLength(32)
			.onChange(s -> newName = s)
			.onSubmit(s -> create())).setBounds(innerX, top, innerW - createW - 4, 14);
		root.add(new Button("Create", this::create)).setBounds(innerX + innerW - createW, top, createW, 14);

		int filesLabelY = frameY + frameH - 34;
		int importY = frameY + frameH - 18;
		listY = top + 18;
		listH = filesLabelY - listY - 4;

		listW = Math.max(150, (innerW - 6) * 2 / 5);
		int editorW = innerW - listW - 6;
		listX = innerX;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(listX + 1, listY + 1, listW - 2, listH - 2);

		editor = root.add(new Panel("Profile").subtitle(() -> selected == null ? "" : selected == profiles().byName(profiles().active()) ? "active" : ""));
		editor.setBounds(innerX + listW + 6, listY, editorW, listH);
		seenRevision = -1;
		buildEditor();

		// share: Export writes a real config file into the profile's config folder; Import reads one back
		int openW = 76;
		folder = folderLabel();
		root.add(new Label(() -> "Profile files — " + folder + "  (" + files.size() + ")", Label.Style.DIM))
			.setBounds(innerX, filesLabelY, innerW - openW - 4, 12);
		root.add(new Button("Open folder", () -> Open.path(profiles().exportDirectory())))
			.setBounds(innerX + innerW - openW, filesLabelY - 2, openW, 16);
		root.add(new Dropdown<>(this::filesNow, () -> pickedFile, name -> name, name -> pickedFile = name))
			.setBounds(innerX, importY, innerW - 76, 14);
		root.add(new Button("Import", this::importFile)).setBounds(innerX + innerW - 72, importY, 72, 14);
		refreshFiles();
	}

	/** Refresh on open so a file dropped into the folder while the screen is up shows up. */
	private List<String> filesNow() {
		refreshFiles();
		return files;
	}

	private void create() {
		select(profiles().capture(newName));
		newName = "";
		rebuildWidgets();
	}

	private void exportFile() {
		if (selected == null) return;
		try {
			Path out = profiles().export(selected);
			pickedFile = out.getFileName().toString();
			refreshFiles();
			CoffeeClient.hud().toast("Exported " + selected.name() + " → " + pickedFile, theme().accent);
		} catch (IOException e) {
			CoffeeClient.LOG.error("Could not export profile {}", selected.name(), e);
			CoffeeClient.hud().toast("Could not export " + selected.name(), theme().error);
		}
	}

	private void importFile() {
		if (pickedFile == null) {
			CoffeeClient.hud().toast(files.isEmpty() ? "No profile files — export one first" : "Pick a profile file to import", theme().error);
			return;
		}
		Profile profile = profiles().importFile(profiles().exportDirectory().resolve(pickedFile));
		if (profile == null) {
			refreshFiles();
			CoffeeClient.hud().toast("Could not read " + pickedFile, theme().error);
			return;
		}
		select(profile);
		CoffeeClient.hud().toast("Imported " + profile.name(), theme().accent);
	}

	/** Reload the exported-file list shown in the picker. */
	private void refreshFiles() {
		files = profiles().exportedFiles().stream().map(p -> p.getFileName().toString()).toList();
		if (pickedFile == null || !files.contains(pickedFile)) pickedFile = files.isEmpty() ? null : files.getFirst();
	}

	/** The export folder, shown relative to the game directory when it sits inside it. */
	private String folderLabel() {
		Path dir = profiles().exportDirectory().toAbsolutePath().normalize();
		try {
			Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
			if (dir.startsWith(game)) return game.relativize(dir).toString().replace('\\', '/');
		} catch (RuntimeException ignored) {
			// fall through to the absolute path
		}
		return dir.toString().replace('\\', '/');
	}

	private void select(@Nullable Profile profile) {
		selected = profile;
		buildEditor();
	}

	private void rebuildList() {
		list.clear();
		for (Profile profile : profiles().all()) list.add(new Row(profile));
		if (selected != null && !profiles().all().contains(selected)) select(null);
	}

	private void buildEditor() {
		editor.clear();
		int x = editor.contentX(), w = editor.contentWidth();
		int y = editor.contentY() + 2;
		Profile profile = selected;
		if (profile == null) {
			editor.add(new Label("Select a profile to manage it.", Label.Style.DIM)).setBounds(x, y, w, 12);
			editor.add(new Label("Export writes a profile file you can share.", Label.Style.DIM)).setBounds(x, y + 12, w, 12);
			return;
		}
		int labelW = 50;
		editor.add(new Label("Name", Label.Style.DIM)).setBounds(x, y + 1, labelW, 12);
		editor.add(new TextField().text(profile.name()).maxLength(32).onChange(s -> {
			if (!s.isBlank()) profiles().rename(profile, s);
		})).setBounds(x + labelW, y, w - labelW, 14);
		y += 22;

		int third = (w - 8) / 3;
		editor.add(new Button("Load", () -> profiles().apply(profile))).setBounds(x, y, third, 16);
		editor.add(new Button("Overwrite", () -> select(profiles().capture(profile.name())))).setBounds(x + third + 4, y, third, 16);
		editor.add(new Button("Delete", () -> {
			profiles().remove(profile);
			select(null);
		}).danger()).setBounds(x + 2 * (third + 4), y, w - 2 * (third + 4), 16);
		y += 22;

		editor.add(new Button("Export to file", this::exportFile)).setBounds(x, y, w, 16);
		y += 22;

		editor.add(new Label(() -> "Active: " + (profiles().active() == null ? "none" : profiles().active()), Label.Style.DIM)).setBounds(x, y, w, 12);
		y += 18;

		editor.add(new TextField().placeholder("Load on join for servers (example.com, other.net)").text(profile.servers()).maxLength(200)
			.onChange(s -> profiles().setServers(profile, s))).setBounds(x, y, w, 14);
		y += 20;

		editor.add(new Label("Modules on", Label.Style.DIM)).setBounds(x, y, w, 12);
		y += 14;
		int[] on = enabledPerCategory(profile);
		Category[] categories = Category.values();
		int cols = 3, cellW = w / cols;
		for (int i = 0; i < categories.length; i++) {
			editor.add(new Label(categories[i].displayName() + "  " + on[i], Label.Style.TEXT))
				.setBounds(x + (i % cols) * cellW, y + (i / cols) * 12, cellW, 12);
		}
		y += 3 * 12 + 6;

		editor.add(new Label("Notes", Label.Style.DIM)).setBounds(x, y, w, 12);
		y += 14;
		int bottom = editor.y + editor.height - Panel.PAD;
		editor.add(new TextArea().placeholder("Notes for this profile").text(profile.notes())
			.onBlur(s -> profiles().setNotes(profile, s))).setBounds(x, y, w, Math.max(40, bottom - y));
	}

	/** Modules switched on in a profile's saved config, counted per category (in {@link Category} order). */
	private static int[] enabledPerCategory(Profile profile) {
		int[] on = new int[Category.values().length];
		JsonElement modules = profile.config().get("modules");
		if (modules == null || !modules.isJsonObject()) return on;
		for (Module m : CoffeeClient.modules().all()) {
			JsonObject mj = savedModule(modules.getAsJsonObject(), m);
			if (mj != null && mj.has("enabled") && mj.get("enabled").getAsBoolean()) on[m.category().ordinal()]++;
		}
		return on;
	}

	/** A module's saved entry, by its name or, for a renamed module, an old alias (as the config loader does). */
	private static @Nullable JsonObject savedModule(JsonObject modules, Module m) {
		JsonElement mj = modules.get(m.name());
		for (int i = 0; mj == null && i < m.aliases().size(); i++) mj = modules.get(m.aliases().get(i));
		return mj != null && mj.isJsonObject() ? mj.getAsJsonObject() : null;
	}

	@Override
	protected void renderContent(Ui ui) {
		if (seenRevision != profiles().revision()) {
			seenRevision = profiles().revision();
			rebuildList();
		}
		Draw.panel(ui.g, ui.theme, listX, listY, listW, listH);
		super.renderContent(ui);
	}

	/** One profile row: name and active marker. */
	private final class Row extends Widget {
		private final Profile profile;

		Row(Profile profile) {
			this.profile = profile;
			this.height = 22;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean sel = profile == selected;
			boolean isActive = profile == profiles().byName(profiles().active());
			Draw.listHighlight(ui.g, t, x, y, width, height, sel, hover, 0x18);
			Draw.text(ui.g, Draw.trim(profile.name(), width - 12), x + 6, y + 2, sel ? t.strongText() : t.text);
			Draw.text(ui.g, isActive ? "active" : "saved", x + 6, y + 12, isActive ? t.accent : t.dimText);
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			select(profile);
			return true;
		}
	}
}
