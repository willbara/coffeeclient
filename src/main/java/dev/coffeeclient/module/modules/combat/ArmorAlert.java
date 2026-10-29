package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Alarm;
import dev.coffeeclient.util.AlertOutput;
import dev.coffeeclient.util.Jingle;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/** Warns when a worn armor piece (or elytra) drops below a durability threshold. */
public final class ArmorAlert extends Module {
	public final NumberSetting threshold = setting(new NumberSetting("Threshold %", "Warn at or below this durability", 15, 1, 50, 1));
	public final BooleanSetting sound = setting(new BooleanSetting("Sound", "Play a warning sound", true));
	public final IdListSetting alarmSound = setting(Alarm.soundSetting());
	public final BooleanSetting useJingle = setting(Alarm.useJingleSetting());
	public final EnumSetting<Jingle> jingle = setting(Alarm.jingleSetting());
	public final NumberSetting soundCount = setting(new NumberSetting("Sound count", "How many times the sound plays, half a second apart", 1, 1, 10, 1));
	public final NumberSetting volume = setting(new NumberSetting("Volume", "How loud each copy is (above 100% is boosted)", 0.5, 0.05, 2, 0.05).format(v -> Math.round(v * 100) + "%"));
	public final NumberSetting repeat = setting(new NumberSetting("Repeat every (s)", "Remind again after this long (0 = once)", 0, 0, 50, 1));
	public final EnumSetting<AlertOutput> output = setting(new EnumSetting<>("Output", "Where to show the warning", AlertOutput.TOAST));

	private final Map<EquipmentSlot, Long> alerted = new EnumMap<>(EquipmentSlot.class);
	private int ticks;
	private final Alarm alarm = new Alarm(alarmSound, useJingle, jingle, soundCount, volume);

	public ArmorAlert() {
		super("ArmorAlert", Category.COMBAT, "Warns when armor durability gets low.");
		useJingle.visibleWhen(sound::get);
		jingle.visibleWhen(() -> sound.get() && useJingle.get());
		alarmSound.visibleWhen(() -> sound.get() && !useJingle.get());
		soundCount.visibleWhen(sound::get);
		volume.visibleWhen(sound::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return sound.get() && !useJingle.get() ? alarmSound : null;
	}

	@Override
	protected void onDisable() {
		alarm.reset();
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null) return;
		alarm.tick();
		if (++ticks % 10 != 0) return;
		long now = System.currentTimeMillis();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty() || !stack.isDamageableItem()) {
				alerted.remove(slot);
				continue;
			}
			int left = stack.getMaxDamage() - stack.getDamageValue();
			double pct = left * 100.0 / stack.getMaxDamage();
			if (pct > threshold.get()) {
				alerted.remove(slot);
				continue;
			}
			Long last = alerted.get(slot);
			boolean due = last == null || (repeat.get() > 0 && now - last > repeat.get() * 1000);
			if (!due) continue;
			alerted.put(slot, now);
			String text = stack.getHoverName().getString() + " at " + Math.round(pct) + "% (" + left + " left)";
			if (output.get() != AlertOutput.CHAT) notify(text);
			if (output.get() != AlertOutput.TOAST) chat(Component.literal(text));
			if (sound.get()) alarm.trigger();
		}
	}
}
