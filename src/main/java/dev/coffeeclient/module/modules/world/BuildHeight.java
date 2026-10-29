package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.BlockHitResultAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

/** Lets you interact with blocks at the build limit. */
public final class BuildHeight extends Module {
	public BuildHeight() {
		super("BuildHeight", Category.WORLD, "Allows you to interact with objects at the build limit.");
		listen(Events.PacketSend.class, EventBus.HIGH, e -> {
			if (!e.isCancelled()) modifyPacket(e.packet);
		});
	}

	private static void modifyPacket(Packet<?> packet) {
		if (!(packet instanceof ServerboundUseItemOnPacket use)) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		if (use.getHitResult().getLocation().y >= mc.level.getHeight() && use.getHitResult().getDirection() == Direction.UP) {
			((BlockHitResultAccessor) use.getHitResult()).coffeeclient$setDirection(Direction.DOWN);
		}
	}
}
