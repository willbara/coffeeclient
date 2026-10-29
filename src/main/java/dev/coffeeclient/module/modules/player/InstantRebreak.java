package dev.coffeeclient.module.modules.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.MultiPlayerGameModeAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Re-breaks the block you are standing on after it breaks. Render
 * outlines the block it keeps breaking, and a box that grows with your progress while you mine.
 */
public final class InstantRebreak extends Module {
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Ticks between break attempts", 0, 0, 20, 1));
	public final BooleanSetting pick = setting(new BooleanSetting("Only pick", "Only mine while holding a pickaxe", true));
	public final BooleanSetting render = setting(new BooleanSetting("Render", "Outline the rebreak block and show your mining progress", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Outline and progress colour", 0xFF55FFFF));

	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, Integer.MIN_VALUE, 0);
	private Direction direction;
	private int ticks;

	public InstantRebreak() {
		super("InstantRebreak", Category.PLAYER, "Instantly re-breaks blocks in the same position.");
		color.visibleWhen(render::get);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		if (!render.get() || mc().gameMode == null) return;
		int rgb = color.argb() & 0xFFFFFF;
		if (pos.getY() != Integer.MIN_VALUE) EspRender.boxes(List.of(new AABB(pos)), new int[]{rgb}, WorldOverlays.Shape.Outline, false, 1.5f, 0, cam);
		var game = (MultiPlayerGameModeAccessor) mc().gameMode;
		if (!mc().gameMode.isDestroying()) return;
		double half = 0.5 * Math.min(1, game.coffeeclient$destroyProgress());
		Vec3 c = Vec3.atCenterOf(game.coffeeclient$destroyBlockPos());
		EspRender.boxes(List.of(new AABB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half)), new int[]{rgb},
			WorldOverlays.Shape.Filled, false, 1, 90, cam);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.AttackBlock.class, event -> {
			direction = event.face();
			pos.set(event.pos());
		});
	}

	@Override
	public void onEnable() {
		ticks = 0;
		pos.set(0, Integer.MIN_VALUE, 0);
	}

	@Override
	public void onTick() {
		if (ticks < delay.get().intValue()) {
			ticks++;
			return;
		}
		ticks = 0;
		if (!shouldMine()) return;

		var level = mc().level;
		((MultiPlayerGameModeAccessor) mc().gameMode).coffeeclient$startPrediction(level, sequence ->
			new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos,
				direction == null ? Direction.UP : direction, sequence));

		if (mc().getConnection() != null) mc().getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}

	private boolean shouldMine() {
		var level = mc().level;
		var player = mc().player;
		if (level == null || player == null) return false;
		if (pos.getY() == Integer.MIN_VALUE || level.isOutsideBuildHeight(pos)) return false;
		if (level.getBlockState(pos).isAir()) return false;
		return !pick.get() || player.getMainHandItem().is(ItemTags.PICKAXES);
	}
}
