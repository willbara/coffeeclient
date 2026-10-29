package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Timer;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;

import java.util.List;

/**
 * Removes the movement penalties from using items, sneaking and various blocks.
 * The "honey" and "soul sand" options work by treating those blocks as stone. Using and
 * sneaking each get a forward and sideways speed share (1 = full speed). Grim keeps the server from
 * slowing you while eating by re-using the other hand (or flicking the slot) every tick; the Grim
 * anticheat mode instead takes the normal slowdown on every other tick.
 */
public final class NoSlow extends Module {
	public enum WebMode {
		Vanilla, Timer, None
	}

	public final BooleanSetting items = setting(new BooleanSetting("Items", "Using items no longer slows you", true));
	public final NumberSetting usingForward = setting(new NumberSetting("Using forward", "Forward speed share while using an item", 1, 0.2, 1, 0.05));
	public final NumberSetting usingSideways = setting(new NumberSetting("Using sideways", "Sideways speed share while using an item", 1, 0.2, 1, 0.05));
	public enum AcMode implements dev.coffeeclient.setting.Labeled {
		OFF("Off"), MATRIX("Matrix"), NCP_STRICT("NCP strict"), GRIM_2371("Grim");

		private final String label;

		AcMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<AcMode> acMode = setting(new EnumSetting<>("Anticheat mode", "Matrix: slow every other tick on the ground. NCP strict: an abort-break each tick of use. Grim: full speed every other tick, the normal slowdown in between (for testing)", AcMode.OFF));
	public final BooleanSetting grim = setting(new BooleanSetting("Grim", "Re-use the other hand each tick so Grim doesn't slow your item use", false));
	public final EnumSetting<WebMode> web = setting(new EnumSetting<>("Web", "How to handle cobwebs. Vanilla drops the slowdown and Timer speeds the game, both of which NCP and Grim replay and flag; None leaves cobwebs alone", WebMode.Vanilla));
	public final NumberSetting webTimer = setting(new NumberSetting("Web timer", "Timer value used in Timer web mode", 10, 1, 50, 0.5).visibleWhen(() -> web.get() == WebMode.Timer));
	public final BooleanSetting honeyBlock = setting(new BooleanSetting("Honey block", "Honey blocks no longer slow you", true));
	public final BooleanSetting soulSand = setting(new BooleanSetting("Soul sand", "Soul sand no longer slows you", true));
	public final BooleanSetting slimeBlock = setting(new BooleanSetting("Slime block", "Slime blocks no longer slow you", true));
	public final BooleanSetting berryBush = setting(new BooleanSetting("Berry bush", "Berry bushes no longer slow you", true));
	public final BooleanSetting airStrict = setting(new BooleanSetting("Air strict", "Bypasses anti-cheats that watch for item use in the air", false));
	public final BooleanSetting fluidDrag = setting(new BooleanSetting("Fluid drag", "Water and lava no longer slow you", false));
	public final BooleanSetting allowSwimming = setting(new BooleanSetting("Allow swimming", "Using an item no longer stops you sprint-swimming", false));
	public final BooleanSetting ignoreVines = setting(new BooleanSetting("Ignore vines", "Vines no longer slow your sideways movement", false));
	public final BooleanSetting sneaking = setting(new BooleanSetting("Sneaking", "Sneaking no longer slows you", false));
	public final NumberSetting sneakForward = setting(new NumberSetting("Sneak forward", "Forward speed share while sneaking", 1, 0.3, 1, 0.05));
	public final NumberSetting sneakSideways = setting(new NumberSetting("Sneak sideways", "Sideways speed share while sneaking", 1, 0.3, 1, 0.05));
	public final BooleanSetting hunger = setting(new BooleanSetting("Hunger", "Hunger no longer slows you", false));
	public final BooleanSetting slowness = setting(new BooleanSetting("Slowness", "The slowness effect is ignored", false));

	private boolean timerReset = true;

	public NoSlow() {
		super("NoSlow", Category.MOVEMENT, "Removes movement penalties from items and blocks.");
		for (Setting<?> s : List.of(grim, acMode)) s.visibleWhen(items::get);
		for (Setting<?> s : List.of(usingForward, usingSideways)) s.visibleWhen(() -> items.get() && acMode.get() != AcMode.GRIM_2371);
		for (Setting<?> s : List.of(sneakForward, sneakSideways)) s.visibleWhen(sneaking::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public void onEnable() {
		timerReset = true;
	}

	@Override
	public void onDisable() {
		Timer.release(this);
	}

	@Override
	public void onTick() {
		if (items.get() && grim.get()) grimUse();
		if (items.get() && acMode.get() != AcMode.OFF && acMode.get() != AcMode.GRIM_2371) anticheatUse();
		if (web.get() != WebMode.Timer) return;
		var player = mc().player;
		if (player == null || mc().level == null) return;

		if (mc().level.getBlockState(player.blockPosition()).getBlock() == Blocks.COBWEB && !player.onGround()) {
			timerReset = false;
			Timer.override(this, webTimer.get());
		} else if (!timerReset) {
			Timer.release(this);
			timerReset = true;
		}
	}

	private int acTicks;

	private void anticheatUse() {
		LocalPlayer p = mc().player;
		ClientPacketListener conn = mc().getConnection();
		if (p == null || conn == null || !p.isUsingItem()) return;
		if (acMode.get() == AcMode.MATRIX) {
			if (p.onGround() && acTicks++ % 2 == 0) {
				var v = p.getDeltaMovement();
				p.setDeltaMovement(v.x * 0.4, v.y, v.z * 0.4);
			}
		} else {
			conn.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
				net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, p.blockPosition(), net.minecraft.core.Direction.DOWN));
		}
	}

	private void grimUse() {
		LocalPlayer p = mc().player;
		ClientPacketListener net = mc().getConnection();
		if (p == null || net == null || !p.isUsingItem() || p.isPassenger()) return;
		if (p.getUsedItemHand() == InteractionHand.MAIN_HAND) {
			net.send(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 0, p.getYRot(), p.getXRot()));
		} else {
			int slot = p.getInventory().getSelectedSlot();
			net.send(new ServerboundSetCarriedItemPacket(slot % 8 + 1));
			net.send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	// ---- helpers used by the movement mixins ----

	/** LocalPlayerMixin: the per-state speed shares on top of the removed slowdowns. */
	public static Vec2 scaleInput(Vec2 input) {
		NoSlow n = instance();
		LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
		if (n == null || p == null) return input;
		if (n.sneaking.get() && p.isShiftKeyDown() && !p.isVisuallyCrawling()) return new Vec2(input.x * n.sneakSideways.floatValue(), input.y * n.sneakForward.floatValue());
		if (n.items.get() && p.isUsingItem() && !p.isPassenger() && n.acMode.get() != AcMode.GRIM_2371) return new Vec2(input.x * n.usingSideways.floatValue(), input.y * n.usingForward.floatValue());
		return input;
	}

	private static NoSlow instance() {
		return ModuleManager.on(NoSlow.class) ? CoffeeClient.modules().get(NoSlow.class) : null;
	}

	public static boolean items() {
		NoSlow n = instance();
		if (n == null || !n.items.get()) return false;
		// Grim only flags two unslowed ticks in a row, and also simulates the unslowed case, so every other tick goes free.
		LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
		return n.acMode.get() != AcMode.GRIM_2371 || p != null && p.tickCount % 2 == 0;
	}

	public static boolean cobweb() {
		NoSlow n = instance();
		return n != null && n.web.get() == WebMode.Vanilla;
	}

	public static boolean honeyBlock() {
		NoSlow n = instance();
		return n != null && n.honeyBlock.get();
	}

	public static boolean soulSand() {
		NoSlow n = instance();
		return n != null && n.soulSand.get();
	}

	public static boolean slimeBlock() {
		NoSlow n = instance();
		return n != null && n.slimeBlock.get();
	}

	public static boolean berryBush() {
		NoSlow n = instance();
		return n != null && n.berryBush.get();
	}

	public static boolean allowSwimming() {
		NoSlow n = instance();
		return n != null && n.allowSwimming.get();
	}

	public static boolean ignoreVines() {
		NoSlow n = instance();
		return n != null && n.ignoreVines.get();
	}

	public static boolean fluidDrag() {
		NoSlow n = instance();
		return n != null && n.fluidDrag.get();
	}

	public static boolean sneaking() {
		NoSlow n = instance();
		return n != null && n.sneaking.get();
	}

	public static boolean hunger() {
		NoSlow n = instance();
		return n != null && n.hunger.get();
	}

	public static boolean slowness() {
		NoSlow n = instance();
		return n != null && n.slowness.get();
	}

	public static boolean airStrict() {
		NoSlow n = instance();
		if (n == null || !n.airStrict.get()) return false;
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player != null && player.isUsingItem();
	}
}
