package dev.coffeeclient.core;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Event types dispatched through {@link EventBus}. */
public final class Events {
	private Events() {
	}

	/** End of every client tick. */
	public record Tick(Minecraft client) {
	}

	/** Once per frame while the HUD is drawn. */
	public record HudRender(GuiGraphicsExtractor graphics, DeltaTracker delta) {
	}

	/** The local player started attacking an entity (client side). */
	public record AttackEntity(net.minecraft.world.entity.Entity target) {
	}

	/** The local player started breaking a block. */
	public record AttackBlock(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face) {
	}

	/** A block the local player broke (client prediction). */
	public record BlockBroken(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
	}

	/** A chat or system message was shown. */
	public record ChatReceived(net.minecraft.network.chat.Component message, boolean system) {
	}

	/** A packet is going out to the server: posted after every {@link PacketSend} handler rewrote it and none cancelled it. */
	public record PacketOut(net.minecraft.network.protocol.Packet<?> packet) {
	}

	/** An entity used a totem of undying. */
	public record TotemPop(net.minecraft.world.entity.Entity entity) {
	}

	/** An entity joined the client world (spawned or came into view). Client thread. */
	public record EntityAdded(net.minecraft.world.entity.Entity entity) {
	}

	/** The server says a living entity (player or mob) just died. */
	public record EntityDeath(net.minecraft.world.entity.LivingEntity entity) {
	}

	/** The server moved the local player (teleport / correction) by {@code distance} blocks, {@code from} one spot {@code to} another. */
	public record ServerTeleport(double distance, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
	}

	/**
	 * Raw keyboard input from the window, before vanilla handles it.
	 *
	 * @param action GLFW action: 0 release, 1 press, 2 repeat
	 */
	public static final class KeyPress extends Cancellable {
		private final int key;
		private final int scancode;
		private final int action;
		private final int modifiers;
		private final boolean screenOpen;

		public KeyPress(int key, int scancode, int action, int modifiers, boolean screenOpen) {
			this.key = key;
			this.scancode = scancode;
			this.action = action;
			this.modifiers = modifiers;
			this.screenOpen = screenOpen;
		}

		public int key() { return key; }
		public int scancode() { return scancode; }
		public int action() { return action; }
		public int modifiers() { return modifiers; }
		public boolean screenOpen() { return screenOpen; }
	}

	// ---------------------------------------------------------------------------------------------
	// Cancellable events. Handlers may call {@link Cancellable#cancel()} and the firing mixin honours it.
	// ---------------------------------------------------------------------------------------------

	/** Base for events whose default action can be suppressed. */
	public static class Cancellable {
		private boolean cancelled;

		public void cancel() {
			cancelled = true;
		}

		public boolean isCancelled() {
			return cancelled;
		}
	}

	/** An entity is about to drop an item stack. */
	public static class DropItems extends Cancellable {
		public final net.minecraft.world.item.ItemStack stack;

		public DropItems(net.minecraft.world.item.ItemStack stack) {
			this.stack = stack;
		}
	}

	/** A screen is about to be shown. */
	public static class OpenScreen extends Cancellable {
		public net.minecraft.client.gui.screens.Screen screen;

		public OpenScreen(net.minecraft.client.gui.screens.Screen screen) {
			this.screen = screen;
		}
	}

	/** The client left the world / disconnected. */
	public static class GameLeft {
	}

	/** A mouse button was pressed in-game (no screen open). */
	public static class MouseClick extends Cancellable {
		public final int button;
		public final int action;
		public final int modifiers;

		public MouseClick(int button, int action, int modifiers) {
			this.button = button;
			this.action = action;
			this.modifiers = modifiers;
		}
	}

	/** The mouse wheel was scrolled in-game. */
	public static class MouseScroll extends Cancellable {
		public final double amount;

		public MouseScroll(double amount) {
			this.amount = amount;
		}
	}

	/**
	 * A packet is about to be sent. Handlers may replace {@code packet} or cancel to drop it. By
	 * convention rewriters (NoFall, Jesus, BuildHeight) run at {@link EventBus#HIGH} or above, droppers
	 * (Blink, Freecam) at {@link EventBus#NORMAL}, and observers at {@link EventBus#LOWEST}. May be posted
	 * off the client thread.
	 */
	public static class PacketSend extends Cancellable {
		public net.minecraft.network.protocol.Packet<?> packet;
		/** The connection it goes out on (also set during login and configuration, before a player exists). */
		public final net.minecraft.network.Connection connection;

		public PacketSend(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.Connection connection) {
			this.packet = packet;
			this.connection = connection;
		}
	}

	/**
	 * A packet arrived from the server; {@code packet} may be replaced or the event cancelled to drop it.
	 * Posted on the network thread: handlers must be thread-safe. Observers that only want packets that
	 * survive should subscribe at {@link EventBus#LOWEST} and skip cancelled events.
	 */
	public static class PacketReceive extends Cancellable {
		public net.minecraft.network.protocol.Packet<?> packet;
		/** The connection it came in on, for replying (also set during login and configuration). */
		public final net.minecraft.network.Connection connection;

		public PacketReceive(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.Connection connection) {
			this.packet = packet;
			this.connection = connection;
		}
	}

	/**
	 * A packet crossed the wire: {@code outbound} when it was encoded to leave (client to server), otherwise
	 * decoded after arriving (server to client). {@code bytes} is the encoded payload (packet id and body,
	 * without the length prefix or compression). Only the client's own play traffic is reported. Posted
	 * on the network thread, so handlers must be thread-safe.
	 */
	public record PacketWire(boolean outbound, net.minecraft.network.protocol.Packet<?> packet, int bytes) {
	}

	/**
	 * A server packet is taking effect on the client thread, one event per packet (packets inside a
	 * bundle get their own). Unlike {@link PacketReceive} this runs in game order, with the world in the
	 * state the packet sees, so a handler can answer in the same frame (AutoTotem refills on the pop
	 * packet itself). Cancelling {@link Pre} skips vanilla's handling.
	 */
	public static final class PacketApply {
		private PacketApply() {
		}

		public static final class Pre extends Cancellable {
			public final net.minecraft.network.protocol.Packet<?> packet;

			public Pre(net.minecraft.network.protocol.Packet<?> packet) {
				this.packet = packet;
			}
		}

		public record Post(net.minecraft.network.protocol.Packet<?> packet) {
		}
	}

	/** The client joined a world or moved to a new one (login, dimension change, respawn into a new level). Client thread. */
	public record WorldChange(net.minecraft.client.multiplayer.ClientLevel level) {
	}

	// ---------------------------------------------------------------------------------------------
	// World and player hooks. Client thread only.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Brackets {@code LocalPlayer#sendPosition}, which builds this tick's movement packet from the
	 * player's position and rotation. Rotations swaps the server-side look in at {@link Pre} and back at
	 * {@link Post}. Not fired while riding (vanilla sends the look separately then).
	 */
	public static final class SendMovementPackets {
		private SendMovementPackets() {
		}

		public static final class Pre {
			public static final Pre INSTANCE = new Pre();

			private Pre() {
			}
		}

		public static final class Post {
			public static final Post INSTANCE = new Post();

			private Post() {
			}
		}
	}

	/** The local player is about to move by {@code movement}; handlers may replace it. */
	public static final class PlayerMove {
		public final net.minecraft.world.entity.MoverType type;
		public net.minecraft.world.phys.Vec3 movement;

		public PlayerMove(net.minecraft.world.entity.MoverType type, net.minecraft.world.phys.Vec3 movement) {
			this.type = type;
			this.movement = movement;
		}
	}

	/**
	 * A block changed in a client chunk: server updates, local prediction and rollbacks alike. Not fired
	 * for whole chunks arriving (see {@link ChunkLoad}).
	 */
	public record BlockUpdate(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
							  net.minecraft.world.level.block.state.BlockState newState) {
	}

	/** A chunk's data arrived and the chunk is now in the client level (also when it is re-sent). */
	public record ChunkLoad(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
	}

	/** A chunk is leaving the client level. */
	public record ChunkUnload(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
	}

	/** The local player is about to use an item on a block (right click). Cancel to skip the interaction. */
	public static final class InteractBlock extends Cancellable {
		public final net.minecraft.world.InteractionHand hand;
		public final net.minecraft.world.phys.BlockHitResult hit;

		public InteractBlock(net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
			this.hand = hand;
			this.hit = hit;
		}
	}

	/** Whether the local player can stand on {@code fluid}; handlers may change {@code canWalk}. */
	public static final class CanWalkOnFluid {
		public final net.minecraft.world.level.material.FluidState fluid;
		public boolean canWalk;

		public CanWalkOnFluid(net.minecraft.world.level.material.FluidState fluid, boolean canWalk) {
			this.fluid = fluid;
			this.canWalk = canWalk;
		}
	}

	/** Whether the local player is kept from walking off ledges (as when sneaking); handlers may set {@code clip}. */
	public static final class ClipAtLedge {
		public boolean clip;
	}
}
