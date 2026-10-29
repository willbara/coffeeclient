package dev.coffeeclient.module.modules.misc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Marks two blocks and shows the distance, the offsets and the box between them. Optionally draws a
 * box a set distance in front of your crosshair to judge reach and placement range.
 */
public final class Measure extends Module {
	public final KeybindSetting markKey = setting(new KeybindSetting("Mark key", "Marks the block you look at; a third mark replaces the oldest", KeybindSetting.UNBOUND));
	public final KeybindSetting clearKey = setting(new KeybindSetting("Clear key", "Removes both marks", KeybindSetting.UNBOUND));
	public final ActionSetting clear = setting(new ActionSetting("Clear marks", "Removes both marks", this::clearMarks));
	public final NumberSetting reach = setting(new NumberSetting("Mark reach", "How far away a block can be marked", 64, 4, 256, 1));
	public final BooleanSetting span = setting(new BooleanSetting("Box and line", "Draw the box that spans both marks and a line between them", true));
	public final BooleanSetting crosshairBox = setting(new BooleanSetting("Crosshair box", "A box in front of your crosshair: green in open air, yellow with a block above it, red inside a block", false));
	public final NumberSetting crosshairDistance = setting(new NumberSetting("Crosshair distance", "Blocks in front of your crosshair", 5, 1, 64, 1)
		.visibleWhen(crosshairBox::get));
	public final ColorSetting markColor = setting(new ColorSetting("Mark color", "Marked blocks", 0xFF4DD8FF));
	public final ColorSetting spanColor = setting(new ColorSetting("Span color", "Box and line between the marks", 0xFFFFFFFF));
	public final BooleanSetting seeThrough = setting(new BooleanSetting("See through walls", "Render through terrain", true));

	private final List<BlockPos> marks = new ArrayList<>(2);

	public Measure() {
		super("Measure", List.of("MeasurementESP", "Ruler"), Category.MISC, "Marks two blocks and shows the distance and box between them.");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.KeyPress.class, e -> {
			if (!e.screenOpen() && e.action() == 1) press(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action == 1) press(KeyNames.mouseKey(e.button));
		});
	}

	@Override
	protected void onDisable() {
		clearMarks();
	}

	private void press(int key) {
		if (mc().gui.screen() != null) return;
		if (clearKey.isBound() && key == clearKey.key()) clearMarks();
		else if (markKey.isBound() && key == markKey.key()) mark();
	}

	private void mark() {
		LocalPlayer p = mc().player;
		if (p == null) return;
		HitResult hit = p.pick(reach.get(), 1f, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
			info("No block in reach");
			return;
		}
		if (marks.size() == 2) marks.removeFirst();
		marks.add(block.getBlockPos().immutable());
	}

	private void clearMarks() {
		marks.clear();
	}

	/** The box covering both marked blocks, edges included. */
	private static AABB span(BlockPos a, BlockPos b) {
		return new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
			Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		List<AABB> boxes = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		int mark = markColor.argb() & 0xFFFFFF, join = spanColor.argb() & 0xFFFFFF;
		for (BlockPos pos : marks) {
			boxes.add(new AABB(pos));
			colors.add(mark);
		}
		if (span.get() && marks.size() == 2) {
			boxes.add(span(marks.get(0), marks.get(1)));
			colors.add(join);
		}
		if (crosshairBox.get() && mc().player != null && mc().level != null) {
			BlockPos pos = BlockPos.containing(mc().player.getEyePosition().add(mc().player.getLookAngle().scale(crosshairDistance.get())));
			boxes.add(new AABB(pos));
			colors.add(mc().level.getBlockState(pos).isAir() ? mc().level.getBlockState(pos.above()).isAir() ? 0x00FF00 : 0xFFFF00 : 0xFF0000);
		}
		EspRender.boxes(boxes, colors.stream().mapToInt(Integer::intValue).toArray(), WorldOverlays.Shape.Outline, seeThrough.get(), 1.5f, 0, cam);

		if (span.get() && marks.size() == 2) {
			Vec3 a = Vec3.atCenterOf(marks.get(0)).subtract(cam), b = Vec3.atCenterOf(marks.get(1)).subtract(cam);
			Vec3 n = b.subtract(a).normalize();
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough.get()));
			WorldOverlays.line(lines, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, (float) n.x, (float) n.y, (float) n.z, 0xFF000000 | join, 1.5f);
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (marks.size() != 2) return;
		BlockPos a = marks.get(0), b = marks.get(1);
		Vec3 mid = Vec3.atCenterOf(a).add(Vec3.atCenterOf(b)).scale(0.5);
		float[] point = projector.project(mid.x, mid.y + 0.6, mid.z);
		if (!projector.onScreen(point, 30)) return;
		AABB box = span(a, b);
		int color = 0xFF000000 | (spanColor.argb() & 0xFFFFFF);
		Nametag.draw(ui, point[0], point[1], List.of(
			new Nametag.Line(String.format(Locale.ROOT, "%.1f blocks", Vec3.atCenterOf(a).distanceTo(Vec3.atCenterOf(b))), color),
			new Nametag.Line(String.format(Locale.ROOT, "dx %s  dy %s  dz %s", Coords.format(b.getX() - a.getX()), Coords.format(b.getY() - a.getY()), Coords.format(b.getZ() - a.getZ())), color),
			new Nametag.Line(String.format(Locale.ROOT, "%.0f x %.0f x %.0f", box.getXsize(), box.getYsize(), box.getZsize()), color)),
			1f, true, ui.theme.panel, -1, 0, null);
	}

	@Override
	public String infoString() {
		return marks.size() == 2 ? String.format(Locale.ROOT, "%.1f", Vec3.atCenterOf(marks.get(0)).distanceTo(Vec3.atCenterOf(marks.get(1)))) : null;
	}
}
