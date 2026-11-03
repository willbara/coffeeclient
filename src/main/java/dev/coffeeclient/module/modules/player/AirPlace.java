package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Lets you place blocks against air (where your crosshair points). */
public final class AirPlace extends Module {
	public final BooleanSetting render = group(new BooleanSetting("Render", "Render a marker where the block will be placed", true), "General");
	public final BooleanSetting customRange = group(new BooleanSetting("Custom range", "Use a custom placement range", false), "Range");
	public final NumberSetting range = group(new NumberSetting("Range", "Custom placement range", 5, 0, 6, 0.5), "Range");
	public final ColorSetting color = group(new ColorSetting("Color", "Marker colour", 0xFFFFFFFF), "Render");

	private HitResult hitResult;

	public AirPlace() {
		super("AirPlace", Category.PLAYER, "Places a block where your crosshair is pointing, even in air.");
	}

	@Override
	protected void onDisable() {
		hitResult = null;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.player == null || mc.level == null) return;
		if (!testInHands()) {
			hitResult = null;
			return;
		}
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
			hitResult = mc.hitResult;
			return;
		}

		double r = customRange.get() ? range.get() : mc.player.blockInteractionRange();
		HitResult hr = mc.getCameraEntity().pick(r, 0, false);
		// A missed ray is still a BlockHitResult (type MISS), so test the type before taking the block branch.
		if (hr instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
			hitResult = bhr;
		} else if (hr.getType() == HitResult.Type.MISS) {
			Vec3 end = hr.getLocation();
			BlockPos bp = BlockPos.containing(end);
			BlockHitResult air = new BlockHitResult(end, mc.player.getMotionDirection().getOpposite(), bp, false);
			hitResult = air;
			// Vanilla resets mc.hitResult every frame and handles the click before our tick, so place here.
			if (mc.options.keyUse.isDown()) {
				// Only the block already in hand, main hand first like vanilla; never swap to another slot.
				Inv.FindItemResult item = mc.player.getMainHandItem().getItem() instanceof BlockItem
					? new Inv.FindItemResult(mc.player.getInventory().getSelectedSlot(), mc.player.getMainHandItem().getCount())
					: mc.player.getOffhandItem().getItem() instanceof BlockItem
						? new Inv.FindItemResult(Inv.OFFHAND_INDEX, mc.player.getOffhandItem().getCount())
						: null;
				if (item != null) BlockActions.placeAt(air, item, false, 0, true, true);
			}
		}
	}

	private boolean testInHands() {
		Minecraft mc = mc();
		for (var hand : net.minecraft.world.InteractionHand.values()) {
			ItemStack stack = mc.player.getItemInHand(hand);
			Item item = stack.getItem();
			if (item instanceof BlockItem || item instanceof ArmorStandItem) return true;
		}
		return false;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (!render.get() || !(hitResult instanceof BlockHitResult bhr)) return;
		Minecraft mc = mc();
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return;
		BlockPos p = bhr.getBlockPos();
		BlockState state = mc.level.getBlockState(p);
		if (!state.canBeReplaced()) return;
		float[] pt = projector.project(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
		if (!projector.onScreen(pt, 4)) return;
		int c = Colors.withAlpha(color.argb(), 0x80);
		Draw.plus(ui.g, Math.round(pt[0]) - 4, Math.round(pt[1]) - 4, 9, c);
	}
}
