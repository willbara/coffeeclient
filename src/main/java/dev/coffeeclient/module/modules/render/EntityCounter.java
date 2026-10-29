package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Sounds;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loaded entities by type (top N), plus a floating count over each chunk with a threshold, the busiest
 * chunk highlighted and an alert when a crowded chunk is near. The chunk labels follow
 * EntityCount; good for finding farms, trading halls and lag machines.
 */
public final class EntityCounter extends Module {
	public enum Kind {
		ALL("All"), VILLAGERS("Villagers"), MOBS("Hostile mobs"), ANIMALS("Animals"), FARM("Farm animals"), CUSTOM("Custom");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public final NumberSetting topN = group(new NumberSetting("Top N", "How many types to list", 5, 1, 20, 1), "General");
	public final NumberSetting range = group(new NumberSetting("Range", "Only within this many blocks (0 = all loaded)", 0, 0, 256, 16), "General");
	public final BooleanSetting items = group(new BooleanSetting("Include items", "Count dropped items", true), "General");
	public final BooleanSetting players = group(new BooleanSetting("Include players", "Count other players", true), "General");

	public final BooleanSetting chunkLabels = group(new BooleanSetting("Chunk labels", "Show how many entities each chunk holds", false), "Chunks");
	public final EnumSetting<Kind> kind = group(new EnumSetting<>("Count", "Which entities the chunk labels count", Kind.ALL), "Chunks");
	public final EntityTypeListSetting custom = group(new EntityTypeListSetting("Custom types", "Entity types counted by Custom", List.of("minecraft:villager")), "Chunks");
	public final NumberSetting chunkRange = group(new NumberSetting("Chunk range", "Label chunks this many chunks around you", 8, 1, 32, 1), "Chunks");
	public final NumberSetting threshold = group(new NumberSetting("Threshold", "A chunk with at least this many is crowded (yellow)", 20, 1, 500, 1), "Chunks");
	public final BooleanSetting onlyCrowded = group(new BooleanSetting("Only crowded", "Hide labels for chunks under the threshold", false), "Chunks");
	public final BooleanSetting busiest = group(new BooleanSetting("Highlight busiest", "Show the chunk with the most in red", true), "Chunks");
	public final BooleanSetting lazyChunks = group(new BooleanSetting("Lazy chunks", "Also count chunks whose data isn't loaded here, in blue", true), "Chunks");
	public final NumberSetting labelHeight = group(new NumberSetting("Label height", "Blocks above your eyes the labels float", 2, -16, 32, 1), "Chunks");
	public final NumberSetting scale = group(new NumberSetting("Label scale", "Label size", 1.0, 0.5, 2, 0.05), "Chunks");
	public final BooleanSetting alerts = group(new BooleanSetting("Alerts", "Chat line and a ping when a crowded chunk is within 2 chunks of you", false), "Chunks");
	public final BooleanSetting chunkTracers = group(new BooleanSetting("Chunk tracers", "Draw tracers to the chunk labels shown", false), "Chunks");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Chunks");

	private record ChunkCount(ChunkPos pos, int count, boolean lazy) {
	}

	private List<String[]> rows = List.of();
	private List<ChunkCount> chunks = List.of();
	private int most;
	/** Set after an alert; cleared once no crowded chunk is near, so the next one alerts again. */
	private boolean alerted;
	private int ticks;

	public EntityCounter() {
		super("EntityCounter", Category.RENDER, "Counts loaded entities by type.");
		for (Setting<?> s : List.of(kind, chunkRange, threshold, onlyCrowded, busiest, lazyChunks, labelHeight, scale, alerts, chunkTracers)) s.visibleWhen(chunkLabels::get);
		custom.visibleWhen(() -> chunkLabels.get() && kind.get() == Kind.CUSTOM);
		tracerStyle.visibleWhen(() -> chunkLabels.get() && chunkTracers.get());
	}

	@Override
	protected void onDisable() {
		chunks = List.of();
		alerted = false;
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.level == null || mc.player == null || ++ticks % 20 != 0) return;
		Map<String, Integer> counts = new HashMap<>();
		Map<ChunkPos, Integer> perChunk = new HashMap<>();
		ChunkPos here = mc.player.chunkPosition();
		int reach = chunkRange.intValue();
		int total = 0;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player) continue;
			if (!items.get() && e instanceof ItemEntity) continue;
			if (!players.get() && e instanceof Player) continue;
			if (chunkLabels.get() && counts(kind.get(), e)) {
				ChunkPos pos = e.chunkPosition();
				if (Math.abs(pos.x() - here.x()) <= reach && Math.abs(pos.z() - here.z()) <= reach) perChunk.merge(pos, 1, Integer::sum);
			}
			if (range.get() > 0 && e.distanceTo(mc.player) > range.get()) continue;
			counts.merge(e.getType().getDescription().getString(), 1, Integer::sum);
			total++;
		}
		List<String[]> out = new ArrayList<>();
		out.add(ModuleHudElement.row("Entities", total));
		counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(topN.intValue())
			.forEach(e -> out.add(ModuleHudElement.row(e.getKey(), e.getValue())));
		rows = out;

		List<ChunkCount> list = new ArrayList<>();
		int max = 0;
		ChunkCount near = null;
		for (Map.Entry<ChunkPos, Integer> e : perChunk.entrySet()) {
			boolean lazy = !mc.level.getChunkSource().hasChunk(e.getKey().x(), e.getKey().z());
			if (lazy && !lazyChunks.get()) continue;
			ChunkCount c = new ChunkCount(e.getKey(), e.getValue(), lazy);
			list.add(c);
			max = Math.max(max, c.count());
			if (c.count() >= threshold.intValue() && Math.abs(c.pos().x() - here.x()) <= 2 && Math.abs(c.pos().z() - here.z()) <= 2
				&& (near == null || c.count() > near.count())) near = c;
		}
		chunks = list;
		most = max;
		if (near == null) {
			alerted = false;
		} else if (alerts.get() && !alerted) {
			alerted = true;
			info(near.count() + " " + kind.get().toString().toLowerCase(Locale.ROOT) + " in the chunk at "
				+ near.pos().getMiddleBlockX() + ", " + near.pos().getMiddleBlockZ());
			Sounds.play("minecraft:block.note_block.pling", 1.0f, 1.0f);
		}
	}

	/** Whether the chunk labels count this entity. */
	public boolean counts(Kind kind, Entity e) {
		return switch (kind) {
			case ALL -> true;
			case VILLAGERS -> e instanceof AbstractVillager;
			case MOBS -> e.getType().getCategory() == MobCategory.MONSTER;
			case ANIMALS -> e instanceof Animal;
			case FARM -> e instanceof Chicken || e instanceof Cow || e instanceof Pig || e instanceof Sheep;
			case CUSTOM -> custom.has(e);
		};
	}

	/** The latest count for a chunk, or 0 (refreshed once a second while chunk labels are on). */
	public int chunkCount(ChunkPos pos) {
		for (ChunkCount c : chunks) if (c.pos().equals(pos)) return c.count();
		return 0;
	}

	/** The chunk labels to draw now (Only crowded hides the ones under the threshold). */
	private List<ChunkCount> shownChunks() {
		return chunks.stream().filter(c -> !onlyCrowded.get() || c.count() >= threshold.intValue()).toList();
	}

	/** Busiest chunk red, lazy chunks blue, crowded yellow, the rest white. */
	private int chunkColor(ChunkCount c) {
		if (busiest.get() && c.count() == most && most > 1) return 0xFFFF5555;
		if (c.lazy()) return 0xFF5599FF;
		return c.count() >= threshold.intValue() ? 0xFFFFFF55 : 0xFFFFFFFF;
	}

	/** Height of the chunk labels and their tracer end, above the eyes. */
	private double labelY() {
		return mc().player.getEyeY() + labelHeight.get();
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		var mc = mc();
		if (!chunkLabels.get() || mc.player == null) return;
		double y = labelY();
		for (ChunkCount c : shownChunks()) {
			float[] point = projector.project(c.pos().getMiddleBlockX() + 0.5, y, c.pos().getMiddleBlockZ() + 0.5);
			if (!projector.onScreen(point, 20)) continue;
			Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line(Integer.toString(c.count()), chunkColor(c))),
				scale.floatValue(), true, -1, 0, null);
		}
		if (chunkTracers.get() && tracerStyle.get() == Tracers.Style.Classic) {
			for (ChunkCount c : shownChunks()) {
				Tracers.pixel(ui, projector, c.pos().getMiddleBlockX() + 0.5, y, c.pos().getMiddleBlockZ() + 0.5, 0xB0000000 | chunkColor(c) & 0xFFFFFF, 1);
			}
		}
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		var mc = mc();
		if (!chunkLabels.get() || !chunkTracers.get() || tracerStyle.get() != Tracers.Style.Smooth || mc.player == null) return;
		VertexConsumer lines = EspRender.tracerBuffer();
		double y = labelY();
		for (ChunkCount c : shownChunks()) {
			EspRender.tracer(lines, c.pos().getMiddleBlockX() + 0.5, y, c.pos().getMiddleBlockZ() + 0.5, 0xB0000000 | chunkColor(c) & 0xFFFFFF, 1f, cameraPos);
		}
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "entity_counter", "Entity counter", () -> rows, (w, h) -> new int[] {w - 110, h / 2 + 10}));
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
