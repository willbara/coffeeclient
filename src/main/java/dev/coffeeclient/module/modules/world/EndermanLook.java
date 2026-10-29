package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;

/** Either looks at all Endermen or prevents you from looking at Endermen. */
public final class EndermanLook extends Module {
	public enum Mode { At, Away }

	public final EnumSetting<Mode> lookMode = setting(new EnumSetting<>("Look mode", "How this module behaves", Mode.Away));
	public final BooleanSetting stun = setting(new BooleanSetting("Stun hostiles", "Automatically stares at hostile endermen to stun them", true));

	public EndermanLook() {
		super("EndermanLook", Category.WORLD, "Either looks at all Endermen or prevents you from looking at Endermen.");
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) return;
		ItemStack head = mc().player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
		if (head.is(ItemTags.GAZE_DISGUISE_EQUIPMENT) || mc().player.getAbilities().instabuild) return;

		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EnderMan enderman) || !enderman.isAlive() || !mc().player.hasLineOfSight(enderman)) continue;

			switch (lookMode.get()) {
				case Away -> {
					if (enderman.isCreepy() && stun.get()) {
						// stare at it to stun
						setRotation(enderman);
					} else if (angleCheck(enderman)) {
						// look away (up)
						mc().player.setXRot(90);
					}
				}
				case At -> {
					if (!enderman.isCreepy()) setRotation(enderman);
				}
			}
		}
	}

	private void setRotation(EnderMan e) {
		double dx = e.getX() - mc().player.getX();
		double dy = e.getEyeY() - mc().player.getEyeY();
		double dz = e.getZ() - mc().player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
		mc().player.setYRot(yaw);
		mc().player.setXRot(pitch);
	}

	private boolean angleCheck(EnderMan entity) {
		net.minecraft.world.phys.Vec3 vec3d = mc().player.getViewVector(1.0F).normalize();
		net.minecraft.world.phys.Vec3 vec3d2 = new net.minecraft.world.phys.Vec3(entity.getX() - mc().player.getX(), entity.getEyeY() - mc().player.getEyeY(), entity.getZ() - mc().player.getZ());

		double d = vec3d2.length();
		vec3d2 = vec3d2.normalize();
		double e = vec3d.dot(vec3d2);

		return e > 1.0D - 0.025D / d;
	}
}
