package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.ServerboundMovePlayerPacketAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/** Reduces hunger consumption by spoofing movement packets. */
public final class AntiHunger extends Module {
	public final BooleanSetting sprint = setting(new BooleanSetting("Sprint", "Spoofs sprinting packets", true));
	public final BooleanSetting onGround = setting(new BooleanSetting("On ground", "Spoofs the onGround flag", true));

	private boolean lastOnGround;
	private boolean ignorePacket;

	public AntiHunger() {
		super("AntiHunger", Category.PLAYER, "Reduces (does NOT remove) hunger consumption.");
		listen(Events.SendMovementPackets.Pre.class, e -> preMovementPacket());
		// Last in the send chain, so packets Blink holds are only touched when they are finally sent.
		listen(Events.PacketSend.class, EventBus.LOW - 10, e -> {
			if (!e.isCancelled() && modifyPacket(e.packet)) e.cancel();
		});
	}

	/** Spoofs sprinting and the on-ground flag. */
	@Override
	public boolean affectsMovement() {
		return true;
	}

	@Override
	public void onEnable() {
		if (mc().player != null) lastOnGround = mc().player.onGround();
		ignorePacket = false;
	}

	/** Right before the movement packets are built. */
	private void preMovementPacket() {
		AntiHunger hunger = this;

		var player = Minecraft.getInstance().player;
		if (player == null) return;

		if (player.onGround() && !hunger.lastOnGround && hunger.onGround.get()) {
			// Lets the landing packet through so the server still registers fall damage.
			hunger.ignorePacket = true;
		}
		hunger.lastOnGround = player.onGround();
	}

	/** Returns true when the packet should be dropped. */
	private boolean modifyPacket(Packet<?> packet) {
		AntiHunger hunger = this;

		Minecraft mc = Minecraft.getInstance();
		var player = mc.player;
		if (player == null) return false;

		if (hunger.ignorePacket && packet instanceof ServerboundMovePlayerPacket) {
			hunger.ignorePacket = false;
			return false;
		}

		if (player.isPassenger() || player.isInWater() || player.isUnderWater()) return false;

		if (packet instanceof ServerboundPlayerCommandPacket command
			&& hunger.sprint.get()
			&& command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
			return true;
		}

		if (packet instanceof ServerboundMovePlayerPacket move
			&& hunger.onGround.get()
			&& player.onGround()
			&& player.fallDistance <= 0.0
			&& mc.gameMode != null
			&& !mc.gameMode.isDestroying()) {
			((ServerboundMovePlayerPacketAccessor) move).coffeeclient$setOnGround(false);
		}
		return false;
	}
}
