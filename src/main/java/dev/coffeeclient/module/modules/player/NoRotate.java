package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;

/**
 * Blocks rotations sent by the server. The behaviour lives in
 * {@code ClientPacketListenerMixin#handleMovePlayer}.
 */
public final class NoRotate extends Module {
	public NoRotate() {
		super("NoRotate", Category.PLAYER, "Attempts to block rotations sent from the server.");
	}
}
