package dev.coffeeclient.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** NoChatReports: mark the "chat messages can't be verified" toast as already seen. */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {
	@Accessor("seenInsecureChatWarning")
	void coffeeclient$setSeenInsecureChatWarning(boolean seen);
}
