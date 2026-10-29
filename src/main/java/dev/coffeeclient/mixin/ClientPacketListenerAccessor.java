package dev.coffeeclient.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.LocalChatSession;
import net.minecraft.network.chat.SignedMessageChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** NoChatReports: drop the chat-signing session of a live connection. FakePlayer: tab-list entries for bots. */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("chatSession")
	void coffeeclient$setChatSession(LocalChatSession session);

	@Accessor("signedMessageEncoder")
	void coffeeclient$setSignedMessageEncoder(SignedMessageChain.Encoder encoder);

	@Accessor("playerInfoMap")
	Map<UUID, PlayerInfo> coffeeclient$playerInfoMap();

	@Accessor("listedPlayers")
	Set<PlayerInfo> coffeeclient$listedPlayers();
}
