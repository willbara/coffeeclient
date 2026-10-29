package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Hide your name and skin (client-side). Formerly NameProtect. */
public final class PlayerProtect extends Module {
	public enum SkinSource {
		Steve, Tab_player
	}

	/** Ticks between donor checks while wearing a tab-list skin. */
	private static final int DONOR_CHECK_TICKS = 20;

	public final BooleanSetting nameProtect = setting(new BooleanSetting("Name protect", "Hides your name client-side", true));
	public final StringSetting name = setting(new StringSetting("Name", "Name to be replaced with", "seasnail", 64));
	public final BooleanSetting randomizeChat = setting(new BooleanSetting("Randomize chat names", "Replace other players' names with random aliases", false));
	public final BooleanSetting skinProtect = setting(new BooleanSetting("Skin protect", "Replace your own skin client-side", true));
	public final EnumSetting<SkinSource> skinSource = setting(new EnumSetting<>("Skin",
		"Steve: the default skin. Tab player: a random tab-list player's skin, or Steve when nobody else's skin is available", SkinSource.Steve));

	private String username = "";

	/** Real player name -> stable random alias, kept for the session. */
	private final Map<String, String> aliases = new LinkedHashMap<>();

	/** The tab-list player whose skin we wear, and the connection it was picked on. */
	private UUID donor;
	private ClientPacketListener donorConnection;
	private int donorTimer;

	/** True while {@link #ownSkin} reads the donor's skin: our own entry would re-enter this method through the mixin. */
	private boolean borrowing;

	public PlayerProtect() {
		super("PlayerProtect", List.of("NameProtect", "Name Protect", "Player Protect"), Category.CLIENT, "Hide your name and skin.");
		skinSource.visibleWhen(skinProtect::get);
		skinSource.onChange(v -> clearDonor());
	}

	@Override
	protected void onEnable() {
		if (mc().getUser() != null) username = mc().getUser().getName();
	}

	@Override
	protected void onDisable() {
		clearDonor();
	}

	@Override
	public void onTick() {
		var connection = mc().getConnection();
		if (connection == null) {
			clearDonor();
			return;
		}
		if (skinProtect.get() && skinSource.get() == SkinSource.Tab_player) tickDonor(connection);
		if (!randomizeChat.get()) return;
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			String playerName = info.getProfile().name();
			if (playerName.isEmpty() || playerName.equals(username)) continue;
			aliases.computeIfAbsent(playerName, n -> randomAlias());
		}
	}

	public String replaceName(String string) {
		if (string == null || !isEnabled()) return string;
		String out = string;
		if (nameProtect.get() && !username.isEmpty()) out = out.replace(username, name.get());
		if (randomizeChat.get() && out.length() >= 3) {
			for (Map.Entry<String, String> e : aliases.entrySet()) out = out.replace(e.getKey(), e.getValue());
		}
		return out;
	}

	public String getName(String original) {
		if (!name.get().isEmpty() && isEnabled()) return name.get();
		return original;
	}

	/** Whether the skin shown for you is PlayerProtect's, not your real one. */
	public boolean swapsOwnSkin() {
		return isEnabled() && skinProtect.get();
	}

	/** The skin to show for yourself, or null to keep the real one. */
	public PlayerSkin ownSkin() {
		if (!isEnabled() || !skinProtect.get()) return null;
		if (skinSource.get() == SkinSource.Tab_player && donor != null && donorConnection != null && !borrowing) {
			PlayerInfo info = donorConnection.getPlayerInfo(donor);
			if (info != null) {
				borrowing = true;
				try {
					return info.getSkin();
				} finally {
					borrowing = false;
				}
			}
		}
		return DefaultPlayerSkin.getDefaultSkin();
	}

	/** Keeps the borrowed skin while its owner stays listed and loaded; otherwise picks another. */
	private void tickDonor(ClientPacketListener connection) {
		if (connection != donorConnection) clearDonor();
		if (donorTimer-- > 0) return;
		donorTimer = DONOR_CHECK_TICKS;
		if (donor != null) {
			PlayerInfo current = connection.getPlayerInfo(donor);
			if (current != null && connection.getListedOnlinePlayers().contains(current) && hasRealSkin(current)) return;
		}
		List<PlayerInfo> candidates = new ArrayList<>();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			if (!isSelf(info) && hasRealSkin(info)) candidates.add(info);
		}
		donorConnection = connection;
		donor = candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).getProfile().id();
	}

	/** Our own tab entry, matched by the live account name (the cached one is empty before login). */
	private boolean isSelf(PlayerInfo info) {
		var user = mc().getUser();
		return user != null && info.getProfile().name().equals(user.getName());
	}

	/** False while the skin is still downloading (or the player uses a default one), which shows as Steve. */
	private static boolean hasRealSkin(PlayerInfo info) {
		return !info.getSkin().equals(DefaultPlayerSkin.get(info.getProfile()));
	}

	private void clearDonor() {
		donor = null;
		donorConnection = null;
		donorTimer = 0;
	}

	/** A random, unrepeatable-looking alias such as {@code Qxlmvtrz}. */
	private static String randomAlias() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		StringBuilder sb = new StringBuilder(8);
		for (int i = 0; i < 8; i++) sb.append((char) ('a' + random.nextInt(26)));
		return Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
	}
}
