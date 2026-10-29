package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fly freely while riding an entity (boat, horse, minecart, ...), and optionally steer mounts
 * that have no saddle, lock the mount to your view yaw, always jump horses at full power and ignore
 * the server's vehicle position corrections.
 */
public final class EntityFly extends Module {
	public final BooleanSetting fly = group(new BooleanSetting("Fly", "Fly your mount. Turn it off to use only the options under Control", true), "General");
	public final NumberSetting upSpeed = group(new NumberSetting("Up speed", "Vertical speed while ascending (hold jump)", 0.3, 0.05, 2, 0.05), "General");
	public final NumberSetting downSpeed = group(new NumberSetting("Down speed", "Vertical speed while descending (hold sneak)", 0.3, 0.05, 2, 0.05), "General");
	public final NumberSetting horizontalBoost = group(new NumberSetting("Horizontal boost", "Multiplier on your mount's existing horizontal speed", 1, 0.1, 5, 0.1), "General");
	public final BooleanSetting hoverWhenIdle = group(new BooleanSetting("Hover when idle", "Ignore gravity when neither jump nor sneak is held", true), "General");
	public final BooleanSetting livingOnly = group(new BooleanSetting("Living mounts only", "Only fly living entities (horses, pigs, ...)", false), "General");
	public final BooleanSetting resetFall = group(new BooleanSetting("Reset fall distance", "Prevent fall damage on your mount", true), "General");

	public final BooleanSetting ridesUnsaddled = group(new BooleanSetting("Ride without saddle", "Steer horses, pigs, striders and other mounts that have no saddle, as if they had one", false), "Control");
	public final BooleanSetting lockYaw = group(new BooleanSetting("Lock yaw", "Your mount always faces where you look", false), "Control");
	public final BooleanSetting maxJump = group(new BooleanSetting("Max jump", "Horse jumps always use full power", false), "Control");
	public final BooleanSetting cancelCorrections = group(new BooleanSetting("Cancel corrections", "Ignore the server moving your vehicle back", false), "Control");

	public EntityFly() {
		super("EntityFly", Category.MOVEMENT, "Fly freely while riding an entity.");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketReceive.class, e -> {
			if (cancelCorrections.get() && e.packet instanceof ClientboundMoveVehiclePacket && mc().player != null && mc().player.isPassenger()) e.cancel();
		});
	}

	@Override
	public boolean affectsMovement() {
		return true;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	/** MobMixin: whether {@code rider}'s mount counts as saddled. */
	public static boolean ridesUnsaddled(Entity rider) {
		EntityFly m = ModuleManager.ifOn(EntityFly.class);
		return m != null && m.ridesUnsaddled.get() && rider == net.minecraft.client.Minecraft.getInstance().player;
	}

	/** LocalPlayerMixin: whether a charged horse jump goes out at full power. */
	public static boolean maxJump() {
		EntityFly m = ModuleManager.ifOn(EntityFly.class);
		return m != null && m.maxJump.get();
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null) return;
		Entity vehicle = p.getVehicle();
		if (vehicle == null) return;
		if (lockYaw.get()) vehicle.setYRot(p.getYRot());
		if (!fly.get()) return;
		if (livingOnly.get() && !(vehicle instanceof LivingEntity)) return;

		Vec3 velocity = vehicle.getDeltaMovement();

		double y;
		if (mc().options.keyJump.isDown()) y = upSpeed.get();
		else if (mc().options.keyShift.isDown()) y = -downSpeed.get();
		else y = hoverWhenIdle.get() ? 0 : velocity.y;

		double boost = horizontalBoost.get();
		vehicle.setDeltaMovement(velocity.x * boost, y, velocity.z * boost);

		if (resetFall.get()) vehicle.resetFallDistance();
	}
}
