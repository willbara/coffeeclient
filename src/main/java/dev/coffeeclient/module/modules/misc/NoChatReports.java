package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.mixin.ClientPacketListenerAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/**
 * Sends chat unsigned, so your messages carry no signature that could back a chat report.
 * While on, the game is handed an empty profile key manager, so no
 * signing session is made on join, and a live session is dropped when the module is switched on.
 * Singleplayer is left alone.
 *
 * <p>Servers that enforce secure chat refuse unsigned messages; their refusal is explained in chat.
 */
public final class NoChatReports extends Module {
	public final BooleanSetting markReportable = setting(new BooleanSetting("Mark reportable",
		"Tag other players' signed messages, which could be used in a report", true));
	public final BooleanSetting hideToast = setting(new BooleanSetting("Hide chat toast",
		"Skip the \"chat messages can't be verified\" toast on servers without secure chat", true));
	public final BooleanSetting explainRefusal = setting(new BooleanSetting("Explain refusals",
		"Explain the message a secure-chat server sends when it refuses unsigned chat", true));

	public final BooleanSetting blockTelemetry = setting(new BooleanSetting("Block telemetry",
		"Stop the game from sending Mojang's usage telemetry events", true));

	private static final int REPORTABLE_COLOR = 0xE84F58;

	public NoChatReports() {
		super("NoChatReports", List.of("No Chat Reports", "NoChatSigning", "No Encryption", "NoTelemetry"), Category.MISC,
			"Sends chat without signatures, so it can't be used in chat reports.");
	}

	/** ClientTelemetryManagerMixin: whether telemetry events are dropped. */
	public static boolean blocksTelemetry() {
		NoChatReports m = ModuleManager.ifOn(NoChatReports.class);
		return m != null && m.blockTelemetry.get();
	}

	/** Off in singleplayer: the local server never reports, and signing there costs nothing. */
	private static boolean active() {
		NoChatReports m = ModuleManager.ifOn(NoChatReports.class);
		return m != null && !Minecraft.getInstance().isLocalServer();
	}

	@Override
	protected void onEnable() {
		ClientPacketListener connection = mc().getConnection();
		if (connection == null || mc().isLocalServer()) return;
		ClientPacketListenerAccessor access = (ClientPacketListenerAccessor) connection;
		access.coffeeclient$setChatSession(null);
		access.coffeeclient$setSignedMessageEncoder(SignedMessageChain.Encoder.UNSIGNED);
	}

	@Override
	protected void onDisable() {
		// Sign again from now on (vanilla only signs on online-mode servers).
		ClientPacketListener connection = mc().getConnection();
		if (connection != null && connection.onlineMode() && !mc().isLocalServer()) connection.prepareKeyPair();
	}

	/** MinecraftMixin: hand out no keys while active, so nothing gets signed. */
	public static ProfileKeyPairManager keyManager(ProfileKeyPairManager original) {
		return active() ? ProfileKeyPairManager.EMPTY_KEY_MANAGER : original;
	}

	/** ClientPacketListenerMixin, before the login handler: skip the insecure-chat toast. */
	public static boolean hideInsecureToast() {
		NoChatReports m = ModuleManager.ifOn(NoChatReports.class);
		return m != null && m.hideToast.get();
	}

	/** ChatComponentMixin: tag for a chat line; signed player messages get a "Reportable" marker. */
	public static GuiMessageTag tag(MessageSignature signature, GuiMessageTag tag) {
		if (tag != null || signature == null || !active()) return tag;
		if (!CoffeeClient.modules().get(NoChatReports.class).markReportable.get()) return tag;
		return new GuiMessageTag(REPORTABLE_COLOR, GuiMessageTag.Icon.CHAT_MODIFIED,
			Component.literal("Reportable: this message is signed and could be used in a chat report"), "Reportable");
	}

	/** ChatComponentMixin: replaces the server's "missing profile key" refusal with an explanation. */
	public static Component explain(Component message) {
		if (!(message.getContents() instanceof TranslatableContents tr) || !tr.getKey().equals("chat.disabled.missingProfileKey")) return message;
		if (!active() || !CoffeeClient.modules().get(NoChatReports.class).explainRefusal.get()) return message;
		return Component.literal("This server requires signed chat. Turn off NoChatReports to talk here.")
			.withStyle(s -> s.withColor(0xFF5555).withHoverEvent(new HoverEvent.ShowText(
				Component.literal("Original message: ").append(message))));
	}
}
