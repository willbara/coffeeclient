package dev.coffeeclient.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Jesus tweak the position/ground flag of outgoing movement packets. */
@Mixin(ServerboundMovePlayerPacket.class)
public interface ServerboundMovePlayerPacketAccessor {
	@Mutable
	@Accessor("y")
	void coffeeclient$setY(double y);

	@Mutable
	@Accessor("onGround")
	void coffeeclient$setOnGround(boolean onGround);
}
