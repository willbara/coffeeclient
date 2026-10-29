package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Eases your own view (not a server-side look) towards a target while you attack: the turn per tick
 * is sized by a smoother, can be limited to yaw or pitch, and aims where the target will be. The
 * camera blends each tick's turn over the frames, so it looks like a steady hand.
 */
public final class AimAssist extends Module {
	/** Closer than this, the view is already on target and your mouse stays in charge. */
	private static final float DEAD_ZONE = 1;

	public final NumberSetting range = group(new NumberSetting("Range", "Farthest target", 4.5, 1, 8, 0.1), "General");
	public final NumberSetting fov = group(new NumberSetting("FOV", "Only targets within this many degrees of your view", 60, 10, 360, 5), "General");
	public final EnumSetting<Rotations.Smoother> smoother = group(new EnumSetting<>("Smoother", "How each tick's turn is sized", Rotations.Smoother.Sigmoid), "General");
	public final NumberSetting speed = group(new NumberSetting("Speed", "Most degrees turned per tick", 8, 1, 60, 0.5), "General");
	public final EnumSetting<Rotations.AimPoint> aimPoint = group(new EnumSetting<>("Aim point", "Where on the target to aim", Rotations.AimPoint.Torso), "General");
	public final BooleanSetting yaw = group(new BooleanSetting("Yaw", "Turn left and right", true), "General");
	public final BooleanSetting pitch = group(new BooleanSetting("Pitch", "Turn up and down", false), "General");
	public final NumberSetting predict = group(new NumberSetting("Predict", "Aim this many ticks ahead of the target", 1, 0, 5, 1), "General");
	public final BooleanSetting onlyOnClick = group(new BooleanSetting("Only on click", "Only while you hold attack", true), "General");
	public final BooleanSetting sticky = group(new BooleanSetting("Sticky", "Stay on the same target while it stays valid", true), "General");

	public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entities to aim at", List.of("minecraft:player")), "Targeting");
	public final BooleanSetting ignoreTeammates = group(new BooleanSetting("Ignore teammates", "Leave players on your team alone", false), "Targeting");
	public final BooleanSetting ignoreBots = group(new BooleanSetting("Ignore bots", "Leave players that are not in the tab list alone", false), "Targeting");
	public final BooleanSetting ignoreInvisible = group(new BooleanSetting("Ignore invisible", "Leave invisible entities alone", true), "Targeting");

	private Entity locked;
	private float lastStep;

	public AimAssist() {
		super("AimAssist", List.of("Aim Assist", "Aimbot"), Category.COMBAT, "Eases your view towards a target while you fight.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	@Override
	protected void onDisable() {
		locked = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gui.screen() != null || onlyOnClick.get() && !mc().options.keyAttack.isDown()) {
			lastStep = 0;
			return;
		}
		if (!sticky.get() || locked == null || !valid(locked)) locked = Targets.get(this::valid, Targets.SortPriority.ClosestAngle);
		if (locked == null) {
			lastStep = 0;
			return;
		}
		AABB box = MotionTracker.predict(locked, predict.intValue());
		Vec3 point = Rotations.aimPoint(locked, aimPoint.get(), range.get() + 1, range.get());
		Vec3 aim = (point != null ? point : locked.getBoundingBox().getCenter()).add(box.getCenter().subtract(locked.getBoundingBox().getCenter()));
		Rotations.Rotation want = Rotations.needed(aim);
		float dYaw = yaw.get() ? Mth.wrapDegrees(want.yaw() - player.getYRot()) : 0;
		float dPitch = pitch.get() ? want.pitch() - player.getXRot() : 0;
		float angle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
		if (angle <= DEAD_ZONE) {
			lastStep = 0;
			return;
		}
		float step = smoother.get().step(angle, speed.floatValue(), lastStep);
		lastStep = step;
		// Only the current rotation moves; the camera lerps from last tick's, so the turn is spread over the frames.
		player.setYRot(player.getYRot() + dYaw * step / angle);
		player.setXRot(Mth.clamp(player.getXRot() + dPitch * step / angle, -90, 90));
	}

	private boolean valid(Entity entity) {
		LocalPlayer player = mc().player;
		if (entity == player || !entity.isAlive() || !entities.has(entity) || !Targets.withinRange(entity, range.get()) || !Targets.inFov(entity, fov.get())) return false;
		if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
		if (entity instanceof Player p && !Targets.isAttackablePlayer(p)) return false;
		if (ignoreBots.get() && entity instanceof Player p && Targets.isBot(p)) return false;
		if (ignoreTeammates.get() && Targets.isTeammate(entity)) return false;
		return !Targets.excluded(entity, false, ignoreInvisible.get(), false);
	}

	@Override
	public String infoString() {
		return locked == null ? null : locked.getName().getString();
	}
}
