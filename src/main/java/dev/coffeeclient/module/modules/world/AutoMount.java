package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

/** Automatically mounts entities. */
public final class AutoMount extends Module {
	public final BooleanSetting checkSaddle = setting(new BooleanSetting("Check saddle", "Checks if the entity contains a saddle before mounting", false));
	public final NumberSetting range = setting(new NumberSetting("Range", "Max distance", 4, 1, 8, 0.5));
	public final EntityTypeListSetting entities = setting(new EntityTypeListSetting("Entities", "Rideable entities", List.of()));

	public AutoMount() {
		super("AutoMount", Category.WORLD, "Automatically mounts entities.");
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
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isPassenger() || player.isShiftKeyDown()) return;
		if (player.getMainHandItem().getItem() instanceof SpawnEggItem) return;

		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!entities.has(entity)) continue;
			if (player.distanceTo(entity) > range.get()) continue;
			if ((entity instanceof Pig || entity instanceof SkeletonHorse || entity instanceof Strider || entity instanceof ZombieHorse) && !(entity instanceof Mob mob && mob.isSaddled()))
				continue;
			if (!(entity instanceof Llama) && entity instanceof Mob mob && checkSaddle.get() && !mob.isSaddled()) continue;

			EntityHitResult hit = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
			mc().gameMode.interact(player, entity, hit, InteractionHand.MAIN_HAND);
			return;
		}
	}

}
