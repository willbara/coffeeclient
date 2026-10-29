package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Wind charge keys. The throw key jumps and throws one at your feet (a wind jump), and Chain throws
 * more each time you start to fall again. The pearl key throws an ender pearl and then a wind charge
 * along the same look, which catches the pearl and flings it farther. Items are swapped in for the
 * throw and straight back.
 */
public final class WindCharge extends Module {
	public final KeybindSetting throwKey = setting(new KeybindSetting("Throw key", "Jump and throw a wind charge at your feet", KeybindSetting.UNBOUND));
	public final NumberSetting chain = setting(new NumberSetting("Chain", "More wind charges thrown each time you start to fall again", 0, 0, 5, 1));
	public final KeybindSetting pearlKey = setting(new KeybindSetting("Pearl key", "Throw a pearl, then a wind charge after it to fling it farther", KeybindSetting.UNBOUND));
	public final NumberSetting pearlDelay = setting(new NumberSetting("Pearl delay", "Ticks between the pearl and the wind charge", 1, 0, 5, 1));

	private int chainLeft, pearlTimer;
	private float pearlYaw, pearlPitch;
	private boolean rising;

	public WindCharge() {
		super("WindCharge", List.of("Wind Charge", "WindChargeKey", "PearlBoost"), Category.COMBAT, "Wind jumps and pearl boosts on a key.");
		pearlDelay.visibleWhen(pearlKey::isBound);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.KeyPress.class, e -> {
			if (!e.screenOpen() && e.action() == 1) onKey(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action == 1) onKey(KeyNames.mouseKey(e.button));
		});
	}

	@Override
	protected void onDisable() {
		chainLeft = pearlTimer = 0;
	}

	private void onKey(int key) {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null) return;
		if (throwKey.isBound() && key == throwKey.key()) {
			if (player.onGround()) player.jumpFromGround();
			if (toss(Items.WIND_CHARGE, player.getYRot(), 90)) {
				chainLeft = chain.intValue();
				rising = true;
			}
		} else if (pearlKey.isBound() && key == pearlKey.key()) {
			pearlYaw = player.getYRot();
			pearlPitch = player.getXRot();
			if (toss(Items.ENDER_PEARL, pearlYaw, pearlPitch)) pearlTimer = pearlDelay.intValue() + 1;
		}
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		if (pearlTimer > 0 && --pearlTimer == 0) toss(Items.WIND_CHARGE, pearlYaw, pearlPitch);
		if (player.onGround()) chainLeft = 0;
		if (chainLeft <= 0) return;
		if (player.getDeltaMovement().y > 0) {
			rising = true;
		} else if (rising && toss(Items.WIND_CHARGE, player.getYRot(), 90)) {
			// At the top of each hop, the next charge keeps you up.
			rising = false;
			chainLeft--;
		}
	}

	/** Throws {@code item} along the look, swapped in silently; false when there is none or it is cooling down. */
	private boolean toss(Item item, float yaw, float pitch) {
		LocalPlayer player = mc().player;
		Inv.FindItemResult found = Inv.find(s -> s.is(item), 0, 35);
		if (!found.found() || player.getCooldowns().isOnCooldown(player.getInventory().getItem(found.slot()))) return false;
		Rotations.rotate(yaw, pitch, Rotations.HIGH, () -> Inv.withItem(found.slot(), found.slot() < 9, () -> {
			mc().gameMode.useItem(player, InteractionHand.MAIN_HAND);
			player.swing(InteractionHand.MAIN_HAND);
		}));
		return true;
	}
}
