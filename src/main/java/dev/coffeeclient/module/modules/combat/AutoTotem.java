package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;

/**
 * Keeps a totem of undying in the offhand, under any screen and at any ping, after
 * AutoTotem. The refill goes out the moment the pop packet is applied (not on the next tick): a swap
 * click when the open menu shows a totem, else the swap-hands key on a hotbar totem, which works with
 * any screen open. Each refill ends with a numbered no-op the server must answer after it, so the
 * module knows when the server's view of the offhand is final, and retries (or asks for a full
 * inventory resend) when the totem did not land. A second totem can wait in the main hand, which pops
 * first, so a second hit before the refill arrives still finds one.
 *
 * <p>Smart only fills the offhand while a hit could be lethal: low health, gliding,
 * falling, a mace smash coming, or a dangerous crystal, creeper, bed, anchor or TNT nearby. Strict
 * always keeps a totem there.
 */
public final class AutoTotem extends Module {
	public enum Mode { Smart, Strict }

	public enum MainHand { Off, Smart, Always }

	private static final int NONE = -1;
	private static final int MAX_ATTEMPTS = 3;
	/** Smart main hand keeps the totem this many ticks after the danger passes. */
	private static final int DANGER_HOLD = 40;
	private static final long VERIFY_MS = 1_000;
	/** Mace: an enemy holding a mace this close and falling this fast (blocks per tick) could smash you. */
	private static final double MACE_RANGE = 6, MACE_FALL_SPEED = 0.3;

	public final EnumSetting<Mode> mode = group(new EnumSetting<>("Mode", "Smart: only while a hit could be lethal. Strict: always", Mode.Smart), "General");
	public final NumberSetting health = group(new NumberSetting("Health", "Smart: equip at or below this health (also holds the main-hand totem)", 10, 1, 36, 1), "General");
	public final BooleanSetting closeContainers = group(new BooleanSetting("Close containers",
		"Close an open container when you pop: the server does not report the offhand while one is open, so the refill could not be confirmed", true), "General");
	public final NumberSetting humanDelay = group(new NumberSetting("Human delay", "Most random wait before a refill, in ms (0 is off); a quarter of it when your health is low", 0, 0, 500, 10), "General");
	public final BooleanSetting warn = group(new BooleanSetting("Warn", "Say in chat when you run out of totems", true), "General");
	public final BooleanSetting elytra = group(new BooleanSetting("Elytra", "Equip while elytra flying or riptiding", true), "Threats");
	public final BooleanSetting fall = group(new BooleanSetting("Fall", "Equip while falling", true), "Threats");
	public final BooleanSetting explosion = group(new BooleanSetting("Explosion", "Equip near TNT, or a crystal, lit creeper, bed or anchor that could kill you", true), "Threats");
	public final BooleanSetting mace = group(new BooleanSetting("Mace", "Equip while an enemy holding a mace falls towards you", true), "Threats");
	public final EnumSetting<MainHand> mainHand = group(new EnumSetting<>("Main hand",
		"Hold a second totem in the main hand. It pops first, so a second hit before the refill arrives still finds a totem. Smart: only in danger", MainHand.Off), "Main hand");
	public final NumberSetting combatRange = group(new NumberSetting("Combat range", "Smart: hold the main-hand totem while an enemy player is this close", 12, 0, 32, 1), "Main hand");
	public final NumberSetting resumeDelay = group(new NumberSetting("Resume delay", "Ticks to wait after you switch away, eat or hold food before taking the totem back", 20, 0, 100, 1), "Main hand");
	public final NumberSetting slot = group(new NumberSetting("Slot", "Hotbar slot a totem is moved into when the hotbar has none", 9, 1, 9, 1), "Main hand");
	public final BooleanSetting serverSide = group(new BooleanSetting("Server-side", "Hold the reserve totem only on the server: your hand keeps its item, and each hit, use or break takes it back for that moment", false), "Main hand");
	public final BooleanSetting restore = group(new BooleanSetting("Restore", "Switch back to the previous slot when the main-hand totem is no longer needed", true), "Main hand");

	private boolean pending, verifying, confirmed, warned;
	private long pendingUntil, nextVerify, humanUntil;
	/** Worst bed or anchor blast near you, rated once a tick. */
	private float blockBlast;
	private int barrier, pendingContainer, attempts, totems;
	private int dangerHold, resumeTicks;
	private int reserveSlot = NONE, previousSlot = NONE, expectedSlot = NONE;
	/** Server-side: the server was told the reserve totem's slot while your hand shows another. */
	private boolean serverHeld;

	public AutoTotem() {
		super("AutoTotem", java.util.List.of("Auto Totem", "AutoTotem+"), Category.COMBAT, "Automatically keeps a totem of undying in your offhand.");
		for (Setting<?> s : java.util.List.of(elytra, fall, explosion, mace)) s.visibleWhen(() -> mode.get() == Mode.Smart);
		combatRange.visibleWhen(() -> mainHand.get() == MainHand.Smart);
		for (Setting<?> s : java.util.List.of(resumeDelay, slot, restore, serverSide)) s.visibleWhen(() -> mainHand.get() != MainHand.Off);
		restore.visibleWhen(() -> mainHand.get() != MainHand.Off && !serverSide.get());

		// Highest: the refill should be the first thing that goes out after the pop.
		listen(Events.TotemPop.class, dev.coffeeclient.core.EventBus.HIGHEST, e -> {
			if (e.entity() == mc().player && canAct()) onPop();
		});
		listen(Events.PacketApply.Post.class, dev.coffeeclient.core.EventBus.HIGHEST, e -> {
			if (canAct()) onPacket(e.packet());
		});
		listen(Events.EntityAdded.class, e -> {
			if (e.entity() instanceof EndCrystal && canAct()) {
				refill(false);
				updateMainHand();
			}
		});
		listen(Events.GameLeft.class, e -> reset());
		listen(Events.WorldChange.class, e -> reset());
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		if (canAct()) release();
		reserveSlot = previousSlot = expectedSlot = NONE;
	}

	private void reset() {
		clearPending();
		attempts = 0;
		confirmed = warned = false;
		nextVerify = humanUntil = 0;
		blockBlast = 0;
		dangerHold = resumeTicks = 0;
		reserveSlot = previousSlot = expectedSlot = NONE;
	}

	/** Whether AutoTotem currently needs the offhand, so other offhand users should hold off. */
	public boolean wantsOffhand() {
		return isEnabled() && countTotems() > 0 && wanted();
	}

	@Override
	public void onTick() {
		// No ping probes here: every barrier is itself a round-trip sample for Latency.
		if (!canAct()) return;
		if (resumeTicks > 0) resumeTicks--;
		if (dangerHold > 0) dangerHold--;
		// The block scan is the costly part, so once a tick (and only when something reads it).
		blockBlast = explosion.get() || mainHand.get() != MainHand.Off ? Damage.blockBlast(mc().player) : 0;
		if (pending && System.currentTimeMillis() >= pendingUntil) retry();
		refill(false);
		LocalPlayer player = mc().player;
		if (!pending && !confirmed && isTotem(player.getOffhandItem()) && System.currentTimeMillis() >= nextVerify) verify();
		updateMainHand();
		totems = countTotems();
		if (pending) return;
		if (totems > 0) warned = false;
		else if (warn.get() && !warned && wanted()) {
			warned = true;
			info("Out of totems.");
		}
	}

	private void onPacket(Packet<?> packet) {
		switch (packet) {
			case ClientboundBlockChangedAckPacket ack when pending && ack.sequence() >= barrier -> onBarrier();
			case ClientboundContainerSetSlotPacket p when p.getContainerId() == 0 && p.getSlot() == InventoryMenu.SHIELD_SLOT -> onOffhandUpdate();
			case ClientboundSetPlayerInventoryPacket p when p.slot() == Inv.OFFHAND_INDEX -> onOffhandUpdate();
			case ClientboundContainerSetContentPacket p when p.containerId() == 0 -> onOffhandUpdate();
			default -> {
			}
		}
	}

	private void onPop() {
		LocalPlayer player = mc().player;
		// The server spends the main-hand totem first (see LivingEntity#checkTotemDeathProtection).
		int server = Inv.serverSlot();
		ItemStack main = server < 0 ? ItemStack.EMPTY : player.getInventory().getItem(server);
		boolean mainPopped = isTotem(main);
		if (mainPopped) main.shrink(1);
		else player.getOffhandItem().shrink(1);
		confirmed = false;
		if (closeContainers.get() && player.containerMenu != player.inventoryMenu) player.closeContainer();
		clearPending();
		attempts = 0;
		refill(!mainPopped);
		if (!pending) verify();
		if (mainPopped) updateMainHand();
	}

	/** Asks for a full resend and a barrier, to learn the real offhand. */
	private void verify() {
		if (!wanted()) return;
		Inv.resync();
		pending = verifying = true;
		pendingContainer = mc().player.containerMenu.containerId;
		pendingUntil = System.currentTimeMillis() + Latency.timeout();
		nextVerify = System.currentTimeMillis() + VERIFY_MS;
		barrier = Inv.barrier();
	}

	private void onOffhandUpdate() {
		if (pending) return;
		confirmed = isTotem(mc().player.getOffhandItem());
		refill(false);
	}

	/** Everything sent before the barrier has been answered: the offhand now shows the server's view. */
	private void onBarrier() {
		LocalPlayer player = mc().player;
		boolean sameMenu = player.containerMenu.containerId == pendingContainer && player.containerMenu == player.inventoryMenu;
		boolean holding = isTotem(player.getOffhandItem());
		if (verifying) {
			clearPending();
			if (holding) confirmed = sameMenu;
			else refill(false);
			return;
		}
		if (player.containerMenu.containerId != pendingContainer) {
			clearPending();
			refill(true);
			return;
		}
		clearPending();
		if (holding) {
			attempts = 0;
			confirmed = sameMenu;
			return;
		}
		if (++attempts <= MAX_ATTEMPTS) refill(true);
	}

	private void retry() {
		boolean wasVerifying = verifying;
		clearPending();
		if (wasVerifying) return;
		if (++attempts <= MAX_ATTEMPTS + 2) refill(true);
		else attempts = 0;
	}

	/** Puts a totem in the offhand when it needs one ({@code force}: even if the client shows one there). */
	private void refill(boolean force) {
		if (pending || !wanted()) return;
		LocalPlayer player = mc().player;
		if (!force && isTotem(player.getOffhandItem())) {
			humanUntil = 0;
			return;
		}
		int source = offhandSource();
		if (source == NONE || attempts == 0 && humanWait()) return;
		int container = player.containerMenu.containerId;
		Inv.OffhandSwap swap = Inv.swapWithOffhand(source);
		if (swap == Inv.OffhandSwap.NONE) return;
		// A click is answered with slot updates only if its prediction was wrong; ask for all of them.
		if (swap == Inv.OffhandSwap.CLICK) Inv.resync();
		pending = true;
		verifying = false;
		pendingContainer = container;
		pendingUntil = System.currentTimeMillis() + Latency.timeout();
		barrier = Inv.barrier();
	}

	/** Human delay: true while the random wait before this refill runs; the wait is shorter at low health. */
	private boolean humanWait() {
		int max = humanDelay.intValue();
		if (max <= 0) return false;
		long now = System.currentTimeMillis();
		if (humanUntil == 0) {
			int range = Damage.health(mc().player) <= health.get() ? max / 4 : max;
			humanUntil = now + 1 + (long) (Math.random() * range);
		}
		if (now < humanUntil) return true;
		humanUntil = 0;
		return false;
	}

	/** The totem to swap into the offhand: the main inventory first, then the hotbar, keeping the main-hand reserve. */
	private int offhandSource() {
		LocalPlayer player = mc().player;
		for (int i = 9; i < 36; i++) {
			if (isTotem(player.getInventory().getItem(i)) && Inv.menuSlot(player.containerMenu, i) != -1) return i;
		}
		int server = Inv.serverSlot();
		for (int i = 0; i < 9; i++) {
			if (i != server && i != reserveSlot && isTotem(player.getInventory().getItem(i))) return i;
		}
		return server >= 0 && isTotem(player.getInventory().getItem(server)) ? server : NONE;
	}

	// ---- main hand ----

	private void updateMainHand() {
		LocalPlayer player = mc().player;
		int selected = player.getInventory().getSelectedSlot();
		// You picked another slot yourself: give it back for a while.
		if (expectedSlot != NONE && selected != expectedSlot) {
			resumeTicks = resumeDelay.intValue();
			reserveSlot = previousSlot = NONE;
		}
		expectedSlot = NONE;
		boolean wanted = switch (mainHand.get()) {
			case Off -> false;
			case Smart -> {
				if (danger()) dangerHold = DANGER_HOLD;
				yield dangerHold > 0;
			}
			case Always -> true;
		};
		if (!wanted) {
			release();
			return;
		}
		ItemStack held = player.getInventory().getItem(selected);
		if (isTotem(held)) {
			reserveSlot = expectedSlot = selected;
			return;
		}
		boolean popped = selected == reserveSlot && held.isEmpty() && !eating();
		if (!popped && busy(held)) resumeTicks = Math.max(resumeTicks, resumeDelay.intValue());
		if (!popped && resumeTicks > 0 || !isTotem(player.getOffhandItem())) return;

		int hotbar = Inv.find(AutoTotem::isTotem, 0, 8).slot();
		if (hotbar == NONE) {
			int source = Inv.find(AutoTotem::isTotem, 9, 35).slot();
			if (source == NONE) return;
			hotbar = reserveHotbar();
			if (hotbar == NONE || !Inv.swapWithHotbar(source, hotbar)) return;
		}
		if (eating()) return;
		if (serverSide.get()) {
			holdOnServer(hotbar);
			reserveSlot = hotbar;
			return;
		}
		Inv.swap(hotbar, false);
		if (previousSlot == NONE) previousSlot = selected;
		reserveSlot = expectedSlot = hotbar;
	}

	/** Tells the server you hold {@code hotbar}; vanilla sends your real slot again before your next action. */
	private void holdOnServer(int hotbar) {
		serverHeld = true;
		if (Inv.serverSlot() == hotbar) return;
		mc().player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(hotbar));
		((dev.coffeeclient.mixin.MultiPlayerGameModeAccessor) mc().gameMode).coffeeclient$setCarriedIndex(hotbar);
	}

	/** MultiPlayerGameModeMixin: the server-side totem is up, so the tick must not resync the slot. */
	public static boolean holdingServerTotem() {
		AutoTotem m = dev.coffeeclient.module.ModuleManager.ifOn(AutoTotem.class);
		// Once an action has sent your real slot again, the tick syncs as usual until the totem is re-held.
		return m != null && m.serverHeld && m.reserveSlot != NONE && Inv.serverSlot() == m.reserveSlot;
	}

	private boolean eating() {
		LocalPlayer player = mc().player;
		return player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND;
	}

	private boolean busy(ItemStack held) {
		Minecraft mc = mc();
		if (mc.player.isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown() || mc.gameMode.isDestroying()) return true;
		return held.has(DataComponents.FOOD) || held.has(DataComponents.CONSUMABLE);
	}

	/** The hotbar slot for the reserve totem: the chosen slot unless it holds food, else an empty or food-free one. */
	private int reserveHotbar() {
		LocalPlayer player = mc().player;
		int preferred = slot.intValue() - 1;
		if (!food(player.getInventory().getItem(preferred))) return preferred;
		for (int i = 0; i < 9; i++) if (player.getInventory().getItem(i).isEmpty()) return i;
		for (int i = 0; i < 9; i++) if (!food(player.getInventory().getItem(i))) return i;
		return NONE;
	}

	private static boolean food(ItemStack stack) {
		return stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE);
	}

	private void release() {
		if (serverHeld) {
			serverHeld = false;
			Inv.syncSelectedSlot();
		}
		if (reserveSlot == NONE) return;
		LocalPlayer player = mc().player;
		if (restore.get() && previousSlot != NONE && player.getInventory().getSelectedSlot() == reserveSlot) {
			if (eating()) return;
			Inv.swap(previousSlot, false);
		}
		reserveSlot = previousSlot = NONE;
	}

	/** Main-hand danger: low health, an enemy player close by, or a crystal that could kill you. */
	private boolean danger() {
		LocalPlayer player = mc().player;
		float hp = Damage.health(player);
		if (hp <= health.get()) return true;
		double range = combatRange.get() * combatRange.get();
		for (Player other : mc().level.players()) {
			if (other != player && other.isAlive() && !Targets.isProtectedFriend(other) && !Damage.isInvulnerable(other)
				&& other.distanceToSqr(player) <= range) return true;
		}
		return worstBlast() >= hp;
	}

	// ---- offhand threats (Smart) ----

	/** Strict, or (Smart) a hit could be lethal right now. */
	private boolean wanted() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return false;
		if (mode.get() == Mode.Strict) return true;
		float hp = Damage.health(player);
		float blasts = explosion.get() ? worstBlast() : 0;
		if (hp - fallDamage() - blasts <= health.get()) return true;
		if (elytra.get() && (player.isFallFlying() && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA) || player.isAutoSpinAttack())) return true;
		if (fall.get() && fallDamage() > 0) return true;
		if (mace.get() && maceComing()) return true;
		return explosion.get() && nearTnt();
	}

	/** The most damage one blast near you would deal if it went off now: crystals, lit creepers, beds and anchors. */
	private float worstBlast() {
		return Math.max(blockBlast, Damage.entityBlast(mc().player));
	}

	/** An enemy holding a mace is falling from above you, close enough to smash. */
	private boolean maceComing() {
		LocalPlayer player = mc().player;
		for (Player other : mc().level.players()) {
			if (other == player || Targets.isProtectedFriend(other) || !(other.getMainHandItem().getItem() instanceof MaceItem)) continue;
			if (other.getY() > player.getY() && other.distanceToSqr(player) <= MACE_RANGE * MACE_RANGE
				&& MotionTracker.velocity(other).y < -MACE_FALL_SPEED) return true;
		}
		return false;
	}

	private float fallDamage() {
		return fall.get() ? Damage.fall(mc().player) : 0;
	}

	private boolean nearTnt() {
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof PrimedTnt && entity.distanceToSqr(mc().player) <= 64) return true;
		}
		return false;
	}

	// ---- helpers ----

	private int countTotems() {
		LocalPlayer player = mc().player;
		if (player == null) return 0;
		int count = isTotem(player.getOffhandItem()) ? player.getOffhandItem().getCount() : 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (isTotem(stack)) count += stack.getCount();
		}
		return count;
	}

	/** Any item that protects from death (totems, and custom items with the component). */
	private static boolean isTotem(ItemStack stack) {
		return stack.has(DataComponents.DEATH_PROTECTION);
	}

	private void clearPending() {
		pending = verifying = false;
		pendingUntil = 0;
	}

	private boolean canAct() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		return player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null
			&& player.isAlive() && !player.isSpectator() && !player.isCreative();
	}

	@Override
	public String infoString() {
		return String.valueOf(totems);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
