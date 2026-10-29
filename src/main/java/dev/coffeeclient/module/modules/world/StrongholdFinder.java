package dev.coffeeclient.module.modules.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.StrongholdEstimator;
import dev.coffeeclient.util.Coords;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Finds the stronghold from Eye of Ender throws, after StrongholdFinder. Each eye you
 * throw in the Overworld is followed for a moment and its flight direction recorded; the throws are
 * matched against many possible stronghold layouts ({@link StrongholdEstimator}, off the client thread)
 * and the likeliest chunks are drawn and named in a HUD panel, with Nether coordinates for the trip.
 * One throw gives a rough guess along its line; a second throw from a few hundred blocks to the side
 * pins it down. Once portal frames come into view they are marked instead, with a line to the nearest.
 */
public final class StrongholdFinder extends Module {
	private static final double RAY_LENGTH = 2048;
	private static final int PORTAL_RADIUS = 128;
	private static final Set<net.minecraft.world.level.block.Block> PORTAL_BLOCKS = Set.of(Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);

	public final NumberSetting sigma = group(new NumberSetting("Sigma", "How precisely a throw is measured, in degrees (smaller trusts each throw more)", 0.03, 0.005, 0.2, 0.005), "Estimate");
	public final NumberSetting hypotheses = group(new NumberSetting("Hypotheses", "Stronghold layouts compared; more is steadier and slower", 20000, 2000, 100000, 1000), "Estimate");
	public final BooleanSetting sameStronghold = group(new BooleanSetting("Same stronghold", "All throws point at one stronghold (throw them within a few hundred blocks)", true), "Estimate");
	public final NumberSetting topCandidates = group(new NumberSetting("Candidates", "How many likely chunks to show", 3, 1, 10, 1), "Estimate");
	public final NumberSetting sampleDelay = group(new NumberSetting("Sample delay", "Ticks to follow an eye before reading its direction", 2, 0, 10, 1), "Throws");
	public final NumberSetting maxSampleAge = group(new NumberSetting("Max sample age", "Ticks a throw waits for its eye to appear", 20, 5, 100, 1), "Throws");
	public final BooleanSetting rays = group(new BooleanSetting("Rays", "Draw each throw's line", true), "Render");
	public final BooleanSetting bestChunk = group(new BooleanSetting("Best chunk", "Mark the likeliest chunk", true), "Render");
	public final BooleanSetting otherChunks = group(new BooleanSetting("Other chunks", "Mark the other candidates", true), "Render");
	public final BooleanSetting portal = group(new BooleanSetting("Portal", "Mark end portal frames once they are loaded, with a line to the nearest", true), "Render");
	public final BooleanSetting announce = group(new BooleanSetting("Announce", "Say in chat when the best chunk changes", true), "General");
	public final BooleanSetting resetOnWorldChange = group(new BooleanSetting("Reset on world change", "Forget throws when you change world", true), "General");
	public final ActionSetting reset = group(new ActionSetting("Reset", "Forget all throws", this::resetState), "General");

	private record PendingThrow(Vec3 from, int tick) {
	}

	private record Tracked(Vec3 from, int spawnTick) {
	}

	private final ArrayDeque<PendingThrow> pending = new ArrayDeque<>();
	private final Map<Integer, Tracked> tracked = new HashMap<>();
	/** Measured throws (client thread); the estimate works on a copy. */
	private final List<StrongholdEstimator.Throw> samples = new ArrayList<>();
	private final List<Vec3> origins = new ArrayList<>();
	private volatile StrongholdEstimator.Result result;
	private CompletableFuture<?> job;
	private int generation;
	private StrongholdEstimator.Candidate announced;
	private BlockSearch.Handle frames;

	public StrongholdFinder() {
		super("StrongholdFinder", List.of("Stronghold Finder", "Triangulation", "EyeFinder"), Category.WORLD,
			"Estimates the stronghold from Eye of Ender throws.");
		for (Setting<?> s : List.of(sigma, hypotheses, sameStronghold, topCandidates)) s.onChange(v -> recompute(false));

		listen(Events.PacketOut.class, e -> {
			if (e.packet() instanceof ServerboundUseItemPacket use) onUse(use);
		});
		listen(Events.EntityAdded.class, e -> {
			if (e.entity() instanceof EyeOfEnder eye) onEye(eye);
		});
		listen(Events.WorldChange.class, e -> {
			if (resetOnWorldChange.get()) resetState();
		});
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		if (portal.get()) frames = BlockSearch.acquire("stronghold-finder-portal", state -> PORTAL_BLOCKS.contains(state.getBlock()));
	}

	@Override
	protected void onDisable() {
		if (frames != null) frames.release();
		frames = null;
		pending.clear();
		tracked.clear();
	}

	private boolean overworld() {
		return mc().level != null && mc().level.dimension() == Level.OVERWORLD;
	}

	// ---- throws ----

	private void onUse(ServerboundUseItemPacket use) {
		var player = mc().player;
		if (player == null || !overworld() || !player.getItemInHand(use.getHand()).is(Items.ENDER_EYE)) return;
		mc().execute(() -> pending.addLast(new PendingThrow(player.position(), player.tickCount)));
	}

	/** The eye spawned near a recent throw: follow it. */
	private void onEye(EyeOfEnder eye) {
		var player = mc().player;
		if (player == null || !overworld()) return;
		trimPending(player.tickCount);
		PendingThrow best = null;
		double bestDistance = 8 * 8;
		for (PendingThrow t : pending) {
			double dx = t.from.x - eye.getX(), dz = t.from.z - eye.getZ();
			double distance = dx * dx + dz * dz;
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = t;
			}
		}
		if (best == null) return;
		pending.remove(best);
		tracked.put(eye.getId(), new Tracked(best.from, player.tickCount));
	}

	private void trimPending(int now) {
		while (!pending.isEmpty() && now - pending.peekFirst().tick > maxSampleAge.intValue()) pending.removeFirst();
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null || mc().level == null) return;
		trimPending(player.tickCount);
		var it = tracked.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Tracked t = entry.getValue();
			if (player.tickCount - t.spawnTick < sampleDelay.intValue()) continue;
			Entity eye = mc().level.getEntity(entry.getKey());
			if (!(eye instanceof EyeOfEnder)) {
				it.remove();
				continue;
			}
			if (eye.getDeltaMovement().horizontalDistance() < 0.02) continue;
			Vec3 offset = eye.position().subtract(t.from);
			samples.add(new StrongholdEstimator.Throw(t.from.x, t.from.z, StrongholdEstimator.yaw(offset.x, offset.z)));
			origins.add(t.from);
			it.remove();
			info("Throw " + samples.size() + " recorded");
			recompute(true);
		}
	}

	// ---- estimate ----

	private void recompute(boolean announceIt) {
		if (samples.isEmpty()) return;
		List<StrongholdEstimator.Throw> copy = List.copyOf(samples);
		int count = hypotheses.intValue(), top = topCandidates.intValue();
		double sigmaDeg = sigma.get();
		boolean same = sameStronghold.get();
		int mine = ++generation;
		if (job != null) job.cancel(false);
		job = CompletableFuture.supplyAsync(() -> StrongholdEstimator.estimate(copy, count, sigmaDeg, same, top))
			.thenAccept(r -> Minecraft.getInstance().execute(() -> {
				if (mine != generation) return;
				result = r;
				if (r == null || !announceIt || !announce.get() || !isEnabled()) return;
				StrongholdEstimator.Candidate best = r.best();
				if (announced != null && announced.chunkX() == best.chunkX() && announced.chunkZ() == best.chunkZ()) return;
				announced = best;
				info(String.format(Locale.ROOT, "Best guess %s, %s (%.0f%%), Nether %s, %s",
					Coords.format(best.blockX()), Coords.format(best.blockZ()), best.probability() * 100, Coords.format(best.blockX() / 8), Coords.format(best.blockZ() / 8)));
			}));
	}

	private void resetState() {
		pending.clear();
		tracked.clear();
		samples.clear();
		origins.clear();
		generation++;
		result = null;
		announced = null;
	}

	// ---- render ----

	private List<BlockPos> portalBlocks() {
		var player = mc().player;
		if (frames == null || player == null) return List.of();
		return frames.collect(player.blockPosition(), PORTAL_RADIUS, 256);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		var player = mc().player;
		if (player == null || !overworld()) return;
		List<BlockPos> portalBlocks = portal.get() ? portalBlocks() : List.of();
		if (!portalBlocks.isEmpty()) {
			renderPortal(portalBlocks, cam);
			return;
		}
		if (rays.get() && !origins.isEmpty()) {
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(true));
			for (int i = 0; i < samples.size(); i++) {
				Vec3 from = origins.get(i).add(0, 1.5, 0);
				Vec3 dir = Vec3.directionFromRotation(0, (float) samples.get(i).yaw());
				Vec3 to = from.add(dir.scale(RAY_LENGTH));
				WorldOverlays.line(lines, (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z),
					(float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z),
					(float) dir.x, (float) dir.y, (float) dir.z, 0xAAFFFFFF, 1.5f);
			}
		}
		StrongholdEstimator.Result r = result;
		if (r == null) return;
		List<AABB> boxes = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		double y = player.getY();
		for (int i = 0; i < r.candidates().size(); i++) {
			if (i == 0 ? !bestChunk.get() : !otherChunks.get()) continue;
			var c = r.candidates().get(i);
			boxes.add(new AABB(c.chunkX() * 16, y, c.chunkZ() * 16, c.chunkX() * 16 + 16, y + 0.05, c.chunkZ() * 16 + 16));
			colors.add(i == 0 ? 0x00AAFF : 0xFFAA00);
		}
		EspRender.boxes(boxes, colors.stream().mapToInt(Integer::intValue).toArray(), WorldOverlays.Shape.Both, true, 2f, 60, cam);
	}

	private void renderPortal(List<BlockPos> blocks, Vec3 cam) {
		List<AABB> boxes = new ArrayList<>(blocks.size());
		int[] colors = new int[blocks.size()];
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		Vec3 eyes = mc().player.getEyePosition();
		for (int i = 0; i < blocks.size(); i++) {
			BlockPos pos = blocks.get(i);
			boxes.add(new AABB(pos));
			colors[i] = mc().level.getBlockState(pos).is(Blocks.END_PORTAL) ? 0x00DCFF : 0xFFD700;
			double distance = pos.distToCenterSqr(eyes);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = pos;
			}
		}
		EspRender.boxes(boxes, colors, WorldOverlays.Shape.Both, true, 1.5f, 50, cam);
		if (nearest != null) EspRender.tracer(EspRender.tracerBuffer(), nearest.getX() + 0.5, nearest.getY() + 0.5, nearest.getZ() + 0.5, 0xDCFF5050, 1.5f, cam);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "stronghold_finder", "Stronghold finder", () -> {
			List<String[]> rows = new ArrayList<>();
			List<BlockPos> portalBlocks = portal.get() ? portalBlocks() : List.of();
			if (!portalBlocks.isEmpty()) {
				rows.add(ModuleHudElement.row("Portal", portalBlocks.size() + " blocks in view"));
				return rows;
			}
			StrongholdEstimator.Result r = result;
			rows.add(ModuleHudElement.row("Throws", samples.isEmpty() ? "throw an Eye of Ender" : String.valueOf(samples.size())));
			if (r != null) {
				var best = r.best();
				rows.add(ModuleHudElement.row("Stronghold", Coords.format(best.blockX()) + ", " + Coords.format(best.blockZ()) + String.format(Locale.ROOT, " (%.0f%%)", best.probability() * 100)));
				rows.add(ModuleHudElement.row("Nether", Coords.format(best.blockX() / 8) + ", " + Coords.format(best.blockZ() / 8)));
				var player = mc().player;
				if (player != null) rows.add(ModuleHudElement.row("Distance", (int) Math.hypot(best.blockX() - player.getX(), best.blockZ() - player.getZ()) + " blocks"));
			}
			return rows;
		}, (w, h) -> new int[] {w / 2 - 50, h / 2 + 12}).when(this::overworld));
	}
}
