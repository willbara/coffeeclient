package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Sounds;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Plays a sound of your choice (heard only by you) when you hit something. */
public final class HitSounds extends Module {
	public final IdListSetting sound = setting(new IdListSetting("Sound", "Sound played on each hit (click ▶ to preview)",
		List.of("minecraft:entity.experience_orb.pickup"), Sounds::allIds, true).labels(Sounds::label).preview(id -> {
		Sounds.play(id, 1f, 1f);
		return id;
	}));
	public final NumberSetting volume = setting(new NumberSetting("Volume", "Hit sound volume", 0.7, 0, 1, 0.05));
	public final NumberSetting pitch = setting(new NumberSetting("Pitch", "Base pitch", 1.0, 0.5, 2.0, 0.05));
	public final BooleanSetting randomPitch = setting(new BooleanSetting("Random pitch", "Vary the pitch slightly per hit", true));
	public final BooleanSetting livingOnly = setting(new BooleanSetting("Living only", "Ignore hits on item frames, boats, etc.", true));
	public final BooleanSetting playersOnly = setting(new BooleanSetting("Players only", "Only when hitting players", false));

	public HitSounds() {
		super("HitSounds", Category.COMBAT, "Plays a configurable sound when a hit lands.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return sound;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackEntity.class, e -> {
			if (livingOnly.get() && !(e.target() instanceof LivingEntity)) return;
			if (playersOnly.get() && !(e.target() instanceof Player)) return;
			float p = pitch.floatValue() * (randomPitch.get() ? 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f : 1f);
			Sounds.play(sound.first(), p, volume.floatValue());
		});
	}
}
