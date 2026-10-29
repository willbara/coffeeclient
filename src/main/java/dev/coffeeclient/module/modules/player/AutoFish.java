package dev.coffeeclient.module.modules.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.mixin.FishingHookAccessor;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Reels in when the bobber splashes, casts again, and can pick the best rod in your hotbar. */
public final class AutoFish extends Module {
	public final NumberSetting reelDelay = setting(new NumberSetting("Reel delay", "Ticks between the splash and reeling in", 2, 0, 20, 1));
	public final BooleanSetting recast = setting(new BooleanSetting("Cast again", "Throws the line again after reeling in", true));
	public final NumberSetting castDelay = setting(new NumberSetting("Cast delay", "Ticks to wait before casting again", 20, 5, 60, 1)
		.visibleWhen(recast::get));
	public final BooleanSetting selectRod = setting(new BooleanSetting("Select rod", "Switches to the most enchanted rod in your hotbar when you hold none", true));
	public final NumberSetting minDurability = setting(new NumberSetting("Min durability", "Rods with this many uses left or fewer are not picked", 5, 0, 50, 1)
		.visibleWhen(selectRod::get));

	public final BooleanSetting openWaterCheck = setting(new BooleanSetting("Open-water check", "Outlines the area around the bobber (green in open water, red in shallow water) and warns once per cast when it is shallow, since only open water gives treasure", true));

	private FishingHook checked;
	private net.minecraft.core.BlockPos checkedPos;
	private boolean openWater;
	private int reelAt = -1, castAt = -1, tick;

	public AutoFish() {
		super("AutoFish", Category.PLAYER, "Reels in fish for you when the bobber splashes.");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketApply.Post.class, e -> {
			LocalPlayer p = mc().player;
			if (p == null || p.fishing == null || !(e.packet() instanceof ClientboundSoundPacket sound)) return;
			if (!sound.getSound().value().equals(SoundEvents.FISHING_BOBBER_SPLASH)) return;
			if (p.fishing.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) < 4) reelAt = tick + reelDelay.intValue();
		});
	}

	@Override
	protected void onEnable() {
		reelAt = castAt = -1;
		checked = null;
	}

	@Override
	public String infoString() {
		return checked == null || checked.isRemoved() ? null : openWater ? "open" : "shallow";
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (!openWaterCheck.get() || checked == null || checked.isRemoved()) return;
		AABB box = new AABB(-2, -1, -2, 3, 2, 3).move(checked.blockPosition());
		EspRender.boxes(List.of(box), new int[] {openWater ? 0x00FF00 : 0xFF0000}, WorldOverlays.Shape.Outline, true, 1.5f, 0, cameraPos);
	}

	private void checkOpenWater(LocalPlayer p) {
		FishingHook hook = p.fishing;
		if (!openWaterCheck.get() || hook == null || !hook.isInWater()) {
			if (hook == null || !openWaterCheck.get()) checked = null;
			return;
		}
		// The answer only changes when the bobber moves to another block.
		if (hook == checked && hook.blockPosition().equals(checkedPos)) return;
		checkedPos = hook.blockPosition();
		boolean open = ((FishingHookAccessor) hook).coffeeclient$calculateOpenWater(hook.blockPosition());
		if (hook != checked && !open) info("Shallow water: no treasure here, move to open water");
		checked = hook;
		openWater = open;
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null || mc().gameMode == null) return;
		tick++;
		checkOpenWater(p);
		// Eating (or any item in use) owns the held slot; reeling and casting resume afterwards.
		if (p.isUsingItem() && !p.getUseItem().is(Items.FISHING_ROD) || CombatTick.busy(CombatTick.Kind.USE, this)) return;
		InteractionHand hand = rodHand(p);
		if (hand == null) return;

		if (reelAt >= 0 && tick >= reelAt) {
			reelAt = -1;
			use(p, hand);
			castAt = recast.get() ? tick + castDelay.intValue() : -1;
		} else if (castAt >= 0 && tick >= castAt && p.fishing == null) {
			castAt = -1;
			use(p, hand);
		}
	}

	private void use(LocalPlayer p, InteractionHand hand) {
		mc().gameMode.useItem(p, hand);
		p.swing(hand, false);
	}

	/** The hand holding a rod; switches to the best hotbar rod first when Select rod is on. */
	private InteractionHand rodHand(LocalPlayer p) {
		if (p.getMainHandItem().is(Items.FISHING_ROD)) return InteractionHand.MAIN_HAND;
		if (p.getOffhandItem().is(Items.FISHING_ROD)) return InteractionHand.OFF_HAND;
		if (!selectRod.get()) return null;
		int best = -1, bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = p.getInventory().getItem(i);
			if (!stack.is(Items.FISHING_ROD) || stack.getMaxDamage() - stack.getDamageValue() <= minDurability.intValue()) continue;
			int score = enchantLevels(stack);
			if (score > bestScore) {
				best = i;
				bestScore = score;
			}
		}
		if (best < 0) return null;
		Inv.swap(best, false);
		return InteractionHand.MAIN_HAND;
	}

	private static int enchantLevels(ItemStack stack) {
		ItemEnchantments enchantments = stack.getEnchantments();
		int total = 0;
		for (Holder<Enchantment> enchantment : enchantments.keySet()) total += enchantments.getLevel(enchantment);
		return total;
	}
}
