package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.MiningTracker;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows blocks other players are breaking: a box that shrinks as the break goes on, with the
 * breaker's name and progress. Recent breaks leave a fading trail, and chat warns when someone
 * breaks the same block again straight away (rebreak) or a block breaks after they moved on to
 * another (double mining).
 */
public final class BreakESP extends Module {
	public final ColorSetting color = group(new ColorSetting("Color", "Box colour", 0xFFFF5555), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each box is drawn", WorldOverlays.Shape.Both), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 70, 0, 255, 5), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.5, 0.5, 5, 0.5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", true), "Render");
	public final BooleanSetting labels = group(new BooleanSetting("Labels", "Name and progress above each block", true), "Render");
	public final NumberSetting scale = group(new NumberSetting("Label scale", "Label size", 1, 0.5, 2, 0.05), "Render");
	public final BooleanSetting trail = group(new BooleanSetting("Trail", "Mark blocks others broke recently", true), "Trail");
	public final NumberSetting trailSeconds = group(new NumberSetting("Trail time", "Seconds a broken block stays marked", 10, 1, 120, 1), "Trail");
	public final ColorSetting trailColor = group(new ColorSetting("Trail color", "Colour of broken blocks", 0xFFFFAA00), "Trail");
	public final BooleanSetting alerts = group(new BooleanSetting("Rebreak alerts", "Say in chat when someone rebreaks or double-mines", false), "Alerts");

	private record Mark(BlockPos pos, long at, String name) {
	}

	private final MiningTracker tracker = new MiningTracker();
	private final List<Mark> broken = new ArrayList<>();
	private final Map<Integer, Long> alerted = new HashMap<>();

	public BreakESP() {
		super("BreakESP", List.of("BreakIndicators", "MineESP"), Category.RENDER, "Shows blocks other players are breaking.");
		for (Setting<?> s : List.of(trailSeconds, trailColor)) s.visibleWhen(trail::get);
		scale.visibleWhen(labels::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketApply.Post.class, e -> {
			if (e.packet() instanceof ClientboundBlockDestructionPacket p) tracker.progress(p.getId(), p.getPos(), p.getProgress(), System.currentTimeMillis());
		});
		listen(Events.BlockUpdate.class, e -> {
			if (!e.newState().isAir()) return;
			MiningTracker.Broken b = tracker.blockGone(e.pos(), System.currentTimeMillis());
			if (b != null) onBroken(b);
		});
		listen(Events.WorldChange.class, e -> onDisable());
	}

	@Override
	protected void onDisable() {
		tracker.clear();
		broken.clear();
		alerted.clear();
	}

	private void onBroken(MiningTracker.Broken b) {
		String name = name(b.breaker());
		boolean again = broken.stream().anyMatch(m -> m.pos().equals(b.pos()) && b.at() - m.at() < 5000 && m.name().equals(name));
		if (trail.get()) broken.add(new Mark(b.pos(), b.at(), name));
		if (!alerts.get() || alerted.getOrDefault(b.breaker(), 0L) > b.at() - 10_000) return;
		if (again) info(name + " is rebreaking at " + b.pos().toShortString());
		else if (!b.current()) info(name + " is double-mining near " + b.pos().toShortString());
		else return;
		alerted.put(b.breaker(), b.at());
	}

	private String name(int id) {
		Entity e = mc().level == null ? null : mc().level.getEntity(id);
		return e == null ? "#" + id : e.getName().getString();
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		tracker.prune(now);
		broken.removeIf(m -> now - m.at() > trailSeconds.get() * 1000);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		List<MiningTracker.Entry> active = tracker.active();
		List<AABB> boxes = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (MiningTracker.Entry e : active) {
			double half = 0.5 * (1 - e.progress() / 10.0);
			Vec3 c = Vec3.atCenterOf(e.pos());
			boxes.add(new AABB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half));
			colors.add(color.argb() & 0xFFFFFF);
		}
		if (trail.get()) {
			for (Mark m : broken) {
				boxes.add(new AABB(m.pos()));
				colors.add(trailColor.argb() & 0xFFFFFF);
			}
		}
		if (boxes.isEmpty()) return;
		EspRender.boxes(boxes, colors.stream().mapToInt(Integer::intValue).toArray(), shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cam);
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (!labels.get()) return;
		for (MiningTracker.Entry e : tracker.active()) {
			Vec3 c = Vec3.atCenterOf(e.pos());
			float[] p = projector.project(c.x, c.y + 0.7, c.z);
			if (!projector.onScreen(p, 30)) continue;
			String text = name(e.breaker()) + " " + (e.progress() + 1) * 10 + "%";
			Nametag.draw(ui, p[0], p[1], List.of(new Nametag.Line(text, 0xFF000000 | color.argb())), scale.floatValue(), true, ui.theme.panel, -1, 0, null);
		}
	}

	@Override
	public String infoString() {
		int n = tracker.active().size();
		return n == 0 ? null : String.valueOf(n);
	}
}
