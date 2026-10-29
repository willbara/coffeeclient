package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Places a block in your own feet cell, so you stand inside it and crystals at your feet do little.
 * One go per switch-on: four position packets fake a jump, the block goes into the cell you left, and
 * a far jump up makes the server reject the move and put you back where the tick started, now inside
 * the block. Everything goes out in one packet batch.
 */
public final class Burrow extends Module {
	/** A vanilla jump's heights over its first ticks. */
	private static final double[] JUMP = {0.42, 0.7532, 1.0013, 1.1661};

	public final BlockListSetting blocks = setting(new BlockListSetting("Blocks", "Blocks to burrow into; the first one found in your hotbar is used",
		List.of("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest")));
	public final NumberSetting rubberband = setting(new NumberSetting("Rubberband height", "How far the last packet jumps; the server must refuse it (more than 10 blocks)", 12, -30, 30, 1));
	public final BooleanSetting onlyInHole = setting(new BooleanSetting("Only in hole", "Only burrow while walled in on every side", false));
	public final BooleanSetting rotate = setting(new BooleanSetting("Rotate", "Send the look at the block with the last jump packet", true));
	public final BooleanSetting swing = setting(new BooleanSetting("Swing", "Swing your hand when placing", true));

	public Burrow() {
		super("Burrow", List.of("SelfFill", "Self Fill"), Category.COMBAT, "Places a block inside your feet cell.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player != null && mc().level != null) {
			String problem = burrow(player);
			if (problem != null) info(problem);
		}
		setEnabled(false);
	}

	/** Does the burrow; returns why it couldn't, or null. */
	private String burrow(LocalPlayer player) {
		if (!player.onGround()) return "Stand on the ground first";
		var level = mc().level;
		BlockPos feet = player.blockPosition();
		var columns = Footprint.columns(player.getBoundingBox(), Footprint.blocks(level));
		if (onlyInHole.get() && !Footprint.walled(columns, Footprint.blocks(level), false)) return "Not in a hole";
		for (int dy = 2; dy <= 3; dy++) {
			if (!level.getBlockState(feet.above(dy)).getCollisionShape(level, feet.above(dy)).isEmpty()) return "No room to jump";
		}
		Inv.FindItemResult item = Inv.findInHotbar(s -> s.getItem() instanceof BlockItem b && blocks.has(b.getBlock()));
		if (!item.found()) return "No burrow block in your hotbar";
		Block block = ((BlockItem) player.getInventory().getItem(item.slot()).getItem()).getBlock();
		if (!BlockActions.canPlace(feet, block, false)) return "Already burrowed";
		BlockActions.PlaceParams params = BlockActions.placeParams(feet);
		if (params == null || params.requiresSneaking()) return "Nothing to place against";

		double x = player.getX(), y = player.getY(), z = player.getZ();
		boolean wall = player.horizontalCollision;
		Rotations.Rotation look = Rotations.needed(params.hitVec());
		InteractionHand hand = item.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		PacketBatch.run(() -> {
			for (int i = 0; i < JUMP.length; i++) {
				boolean last = i == JUMP.length - 1;
				player.connection.send(last && rotate.get()
					? new ServerboundMovePlayerPacket.PosRot(x, y + JUMP[i], z, look.yaw(), look.pitch(), false, wall)
					: new ServerboundMovePlayerPacket.Pos(x, y + JUMP[i], z, false, wall));
			}
			if (hand == InteractionHand.MAIN_HAND) Inv.swap(item.slot(), true);
			// You still stand in the cell client-side, so the local place fails and doesn't swing; the server's goes through.
			if (!BlockActions.interact(params.hitResult(), hand, swing.get()).consumesAction() && swing.get()) {
				player.connection.send(new ServerboundSwingPacket(hand));
			}
			if (hand == InteractionHand.MAIN_HAND) Inv.swapBack();
			player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + rubberband.get(), z, false, wall));
		});
		return null;
	}
}
