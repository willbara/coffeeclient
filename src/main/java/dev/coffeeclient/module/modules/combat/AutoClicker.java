package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.mixin.KeyMappingAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Clicker;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** Clicks the attack or use button on a timer, for AFK farms and grinders. */
public final class AutoClicker extends Module {
	public enum Button {
		Left, Right
	}

	public enum Timing {
		Interval, Human
	}

	public final EnumSetting<Button> button = setting(new EnumSetting<>("Button", "Left attacks and mines, right uses and places", Button.Left));
	public final EnumSetting<Timing> timing = setting(new EnumSetting<>("Timing", "Interval clicks every few ticks; Human clicks at a CPS with uneven, person-like gaps", Timing.Interval));
	public final NumberSetting interval = setting(new NumberSetting("Interval", "Ticks between clicks", 5, 1, 40, 1));
	public final NumberSetting jitter = setting(new NumberSetting("Jitter", "Up to this many extra ticks, picked at random each click", 0, 0, 10, 1));
	public final NumberSetting cps = setting(new NumberSetting("CPS", "Average clicks per second", 10, 1, 20, 0.5));
	public final NumberSetting spread = setting(new NumberSetting("Spread", "How uneven the gaps are (0 = a steady beat, 0.3 is about a person's)", 0.3, 0, 1, 0.05));
	public final NumberSetting perTick = setting(new NumberSetting("Max per tick", "Most clicks sent in one tick", 1, 1, 3, 1));
	public final NumberSetting missChance = setting(new NumberSetting("Miss chance", "Chance to skip a click", 0, 0, 0.5, 0.01));
	public final NumberSetting minCooldown = setting(new NumberSetting("Min cooldown", "Left click waits until the attack cooldown is at least this full (0 = never wait)", 0, 0, 1, 0.05));
	public final BooleanSetting onlyEntities = setting(new BooleanSetting("Only on entities", "Click only while the crosshair is on an entity", false));
	public final BooleanSetting hitSelect = setting(new BooleanSetting("Hit select", "Left click waits while the entity you look at is still immune from its last hit", false));
	public final BooleanSetting onlyHeld = setting(new BooleanSetting("Only while held", "Click only while you hold the same mouse button", false));
	public final NumberSetting maxClicks = setting(new NumberSetting("Max clicks", "Switches the module off after this many clicks (0 is no limit)", 0, 0, 10000, 10));
	public final BlockListSetting onlyAt = setting(new BlockListSetting("Only at blocks", "Click only while you look at one of these blocks (empty is anywhere)", List.of()));

	private final Clicker clicker = new Clicker(new Random());
	private int wait, clicks;

	public AutoClicker() {
		super("AutoClicker", Category.COMBAT, "Clicks for you at a steady rate.");
		interval.visibleWhen(() -> timing.get() == Timing.Interval);
		jitter.visibleWhen(() -> timing.get() == Timing.Interval);
		for (NumberSetting human : List.of(cps, spread, perTick, missChance)) human.visibleWhen(() -> timing.get() == Timing.Human);
		minCooldown.visibleWhen(() -> button.get() == Button.Left);
		hitSelect.visibleWhen(() -> button.get() == Button.Left);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public dev.coffeeclient.setting.IdListSetting embeddedList() {
		return onlyAt;
	}

	@Override
	protected void onEnable() {
		wait = clicks = 0;
		clicker.reset();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gui.screen() != null) return;
		KeyMapping key = button.get() == Button.Left ? mc().options.keyAttack : mc().options.keyUse;
		if (onlyHeld.get() && !key.isDown()) return;
		if (!onlyAt.get().isEmpty() && !(mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			&& onlyAt.has(mc().level.getBlockState(hit.getBlockPos()).getBlock()))) return;
		if (button.get() == Button.Left && mc().player.getAttackStrengthScale(0.5f) < minCooldown.get()) return;
		EntityHitResult looked = mc().hitResult instanceof EntityHitResult e && e.getType() == HitResult.Type.ENTITY ? e : null;
		if (onlyEntities.get() && looked == null) return;
		// Hurt time counts down from 10; past half of it the hit immunity is over.
		if (hitSelect.get() && button.get() == Button.Left && looked != null && looked.getEntity() instanceof LivingEntity living && living.hurtTime > 5) return;

		int due;
		if (timing.get() == Timing.Human) {
			due = clicker.due(System.currentTimeMillis(), cps.get(), spread.get(), perTick.intValue());
		} else {
			if (--wait > 0) return;
			wait = interval.intValue() + (jitter.intValue() > 0 ? ThreadLocalRandom.current().nextInt(jitter.intValue() + 1) : 0);
			due = 1;
		}
		for (int i = 0; i < due; i++) {
			if (timing.get() == Timing.Human && clicker.miss(missChance.get())) continue;
			KeyMappingAccessor queue = (KeyMappingAccessor) key;
			queue.coffeeclient$setClickCount(queue.coffeeclient$clickCount() + 1);
			if (maxClicks.intValue() > 0 && ++this.clicks >= maxClicks.intValue()) {
				info("Reached " + maxClicks.intValue() + " clicks");
				setEnabled(false);
				return;
			}
		}
	}
}
