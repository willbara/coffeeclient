package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.AutoReconnect;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Disconnects you when you are in danger, with Antisocial
 * (leave when a real player comes within range) and AutoLeave (illegal-packet disconnect, or a command
 * instead of leaving).
 */
public final class AutoLog extends Module {
	public enum Leave implements Labeled {
		/** Close the connection like the vanilla disconnect button. */
		DISCONNECT("Disconnect"),
		/** Send a packet the server refuses, so it drops you itself, at once. */
		ILLEGAL("Illegal packet"),
		/** Run a command (/spawn, /hub) and stay connected. */
		COMMAND("Command");

		private final String label;

		Leave(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Illegal implements Labeled {
		/** A command with a section sign: vanilla and Paper kick for illegal characters. */
		CHAT("Illegal character"),
		/** A move packet with NaN coordinates: kicked for invalid movement. */
		MOVE("Invalid move");

		private final String label;

		Illegal(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final NumberSetting health = group(new NumberSetting("Health", "Disconnect at or below this health (0 = off)", 6, 0, 19, 1), "Triggers");
	public final NumberSetting totemPops = group(new NumberSetting("Totem pops", "Disconnect after this many totem pops (0 = off)", 0, 0, 27, 1), "Triggers");
	public final BooleanSetting predict = group(new BooleanSetting("Predict damage", "Count the worst crystal, creeper, bed or anchor blast near you and the fall you are in against Health", false), "Triggers");
	public final NumberSetting totemsLeft = group(new NumberSetting("Totems left", "Disconnect when you have this many totems or fewer, inventory included (0 = off)", 0, 0, 27, 1), "Triggers");
	public final BooleanSetting untrustedNear = group(new BooleanSetting("Untrusted player", "Disconnect when a player who is not a trusted friend comes near", false), "Triggers");
	public final NumberSetting playerRange = group(new NumberSetting("Player range", "How near that player must be (0 = anywhere loaded)", 0, 0, 128, 1)
		.format(v -> v == 0 ? "Any" : (int) v + " blocks"), "Triggers");
	public final BooleanSetting ignoreNpcs = group(new BooleanSetting("Ignore NPCs", "Only count players that are in the tab list", true), "Triggers");
	public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entity types that count towards the limit",
		List.of("minecraft:end_crystal")), "Entities");
	public final BooleanSetting totalCount = group(new BooleanSetting("Count together", "Add all listed types together (off: each type has its own limit)", true), "Entities");
	public final NumberSetting entityLimit = group(new NumberSetting("Entity limit", "Disconnect when this many listed entities are in range", 10, 1, 32, 1), "Entities");
	public final NumberSetting range = group(new NumberSetting("Range", "How close listed entities must be", 5, 1, 16, 1), "Entities");
	public final EnumSetting<Leave> leave = group(new EnumSetting<>("Leave by", "How AutoLog gets you out", Leave.DISCONNECT), "Leaving");
	public final EnumSetting<Illegal> illegal = group(new EnumSetting<>("Illegal packet", "Which packet the server refuses", Illegal.CHAT), "Leaving");
	public final StringSetting command = group(new StringSetting("Command", "Command to run, without the slash (AutoLog then turns off)", "spawn", 256), "Leaving");
	public final BooleanSetting toggleOff = group(new BooleanSetting("Toggle off", "Turn AutoLog off after it disconnects you", true), "General");
	public final BooleanSetting smartToggle = group(new BooleanSetting("Smart toggle", "After a low-health leave, turn back on once you have healed", false), "General");
	public final BooleanSetting stopReconnect = group(new BooleanSetting("Stop AutoReconnect", "Turn AutoReconnect off so you stay disconnected", true), "General");

	/** Written from the packet listener, read on the client thread. */
	private volatile int pops;
	/** Smart toggle: switched off by a low-health leave and waiting to heal. */
	private static boolean waitingToHeal;
	private final Map<EntityType<?>, Integer> counts = new HashMap<>();

	public AutoLog() {
		super("AutoLog", List.of("Auto Log", "Antisocial", "AutoLeave"), Category.COMBAT, "Disconnects you when your health, totems or surroundings put you in danger.");
		playerRange.visibleWhen(untrustedNear::get);
		ignoreNpcs.visibleWhen(untrustedNear::get);
		illegal.visibleWhen(() -> leave.get() == Leave.ILLEGAL);
		command.visibleWhen(() -> leave.get() == Leave.COMMAND);
		smartToggle.visibleWhen(() -> health.intValue() > 0);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		pops = 0;
		waitingToHeal = false;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.TotemPop.class, e -> {
			if (e.entity() == mc().player) pops++;
		});
		bus.subscribe(Events.GameLeft.class, e -> pops = 0);
		// Smart toggle runs while AutoLog is off, so it subscribes for good.
		bus.subscribe(Events.Tick.class, e -> {
			if (!waitingToHeal || isEnabled()) return;
			LocalPlayer player = mc().player;
			if (health.intValue() <= 0) waitingToHeal = false;
			else if (player != null && !player.isDeadOrDying() && player.getHealth() > health.intValue()) {
				waitingToHeal = false;
				info("Health is above " + health.intValue() + " again, turning back on.");
				setEnabled(true);
			}
		});
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return;
		if (player.getHealth() <= 0) return;

		if (health.intValue() > 0 && player.getHealth() <= health.intValue()) {
			trigger("Health was at or below " + health.intValue() + ".", smartToggle.get());
			return;
		}
		if (predict.get() && health.intValue() > 0) {
			// Absorption soaks a hit first, so it counts on the safe side of the prediction.
			float incoming = Math.max(Damage.entityBlast(player), Damage.blockBlast(player)) + Damage.fall(player);
			if (Damage.health(player) - incoming <= health.intValue()) {
				trigger(String.format(java.util.Locale.ROOT, "A hit of about %.1f could take health to %d or below.", incoming, health.intValue()), smartToggle.get());
				return;
			}
		}
		if (totemsLeft.intValue() > 0 && totems(player) <= totemsLeft.intValue()) {
			trigger("Down to " + totems(player) + " totems.", false);
			return;
		}
		if (totemPops.intValue() > 0 && pops >= totemPops.intValue()) {
			trigger("Popped " + pops + " totems.", false);
			return;
		}

		boolean checkEntities = !entities.get().isEmpty();
		if (!untrustedNear.get() && !checkEntities) return;
		double range2 = range.get() * range.get();
		double playerRange2 = playerRange.get() * playerRange.get();
		int total = 0;
		counts.clear();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (untrustedNear.get() && entity instanceof Player other && other != player && untrusted(other)
				&& (playerRange.get() == 0 || other.distanceToSqr(player) <= playerRange2)) {
				trigger("Untrusted player " + other.getName().getString() + " is nearby.", false);
				return;
			}
			if (checkEntities && entities.has(entity) && entity.distanceToSqr(player) <= range2) {
				total++;
				if (!totalCount.get() && counts.merge(entity.getType(), 1, Integer::sum) >= entityLimit.intValue()) {
					trigger(entityLimit.intValue() + " " + entity.getType().getDescription().getString() + " were within range.", false);
					return;
				}
			}
		}
		if (checkEntities && totalCount.get() && total >= entityLimit.intValue()) trigger(total + " listed entities were within range.", false);
	}

	private static int totems(LocalPlayer player) {
		int count = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			var stack = player.getInventory().getItem(i);
			if (stack.has(net.minecraft.core.component.DataComponents.DEATH_PROTECTION)) count += stack.getCount();
		}
		return count;
	}

	private boolean untrusted(Player other) {
		if (FakePlayerEntity.isFake(other)) return false;
		if (Friends.is(other.getName().getString(), dev.coffeeclient.friend.FriendTrait.TRUSTED)) return false;
		if (!ignoreNpcs.get()) return true;
		ClientPacketListener connection = mc().getConnection();
		return connection != null && connection.getPlayerInfo(other.getUUID()) != null;
	}

	/** Leave by the chosen method; {@code healWait} arms the smart toggle. */
	private void trigger(String reason, boolean healWait) {
		pops = 0;
		if (leave.get() == Leave.COMMAND) {
			LocalPlayer player = mc().player;
			if (player != null) player.connection.sendCommand(command.get().startsWith("/") ? command.get().substring(1) : command.get());
			info(reason + " Ran /" + command.get() + ".");
			// Always off, or the command would be sent every tick.
			setEnabled(false);
			if (healWait) waitingToHeal = true;
			return;
		}
		Component text = reasonText(reason);
		if (toggleOff.get() || healWait) setEnabled(false);
		if (healWait) waitingToHeal = true;
		if (leave.get() == Leave.ILLEGAL) illegalDisconnect(illegal.get(), text);
		else disconnect(text);
	}

	private Component reasonText(String reason) {
		MutableComponent text = Component.literal("[AutoLog] " + reason);
		AutoReconnect autoReconnect = CoffeeClient.modules().get(AutoReconnect.class);
		if (stopReconnect.get() && autoReconnect.isEnabled()) {
			autoReconnect.setEnabled(false);
			text.append(Component.literal("\n\nAutoReconnect was turned off.").withColor(0xAAAAAA));
		}
		return text;
	}

	/** Leave like the disconnect button, showing {@code reason} on the disconnect screen. */
	public static void disconnect(Component reason) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) player.connection.handleDisconnect(new ClientboundDisconnectPacket(reason));
	}

	/**
	 * Sends a packet the server refuses, so the server itself drops you at once instead of waiting
	 * out a combat-log timer. If no kick arrives within a second, disconnects normally.
	 */
	public static void illegalDisconnect(Illegal method, Component reason) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return;
		ClientPacketListener connection = player.connection;
		CoffeeClient.LOG.info("[AutoLog] Illegal disconnect ({}): {}", method, reason.getString());
		switch (method) {
			case CHAT -> connection.send(new ServerboundChatCommandPacket("§"));
			case MOVE -> connection.send(new ServerboundMovePlayerPacket.Pos(Double.NaN, Double.NaN, Double.NaN, false, false));
		}
		CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
			if (mc.getConnection() == connection && connection.getConnection().isConnected()) {
				connection.handleDisconnect(new ClientboundDisconnectPacket(reason));
			}
		}));
	}

	@Override
	public String infoString() {
		List<String> armed = new ArrayList<>();
		if (health.intValue() > 0) armed.add(health.intValue() + "hp");
		if (totemPops.intValue() > 0) armed.add(pops + "/" + totemPops.intValue() + " pops");
		if (untrustedNear.get()) armed.add("untrusted");
		if (totemsLeft.intValue() > 0) armed.add("<=" + totemsLeft.intValue() + " totems");
		return armed.isEmpty() ? null : String.join(" ", armed);
	}
}
