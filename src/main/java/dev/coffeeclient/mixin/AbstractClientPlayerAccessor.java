package dev.coffeeclient.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Supplies a skin for client-side fake players. */
@Mixin(AbstractClientPlayer.class)
public interface AbstractClientPlayerAccessor {
	@Accessor("playerInfo")
	void coffeeclient$setPlayerInfo(PlayerInfo info);
}
