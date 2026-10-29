package dev.coffeeclient.module.modules.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Alarm;
import dev.coffeeclient.util.Jingle;
import dev.coffeeclient.util.ChunkActivity;
import dev.coffeeclient.util.ChunkMemory;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spots players you cannot see, from what the server tells you about the world.
 *
 * <p>Blocks: a block placed, broken or toggled far outside your player-ESP range was changed by
 * someone, so its position is marked.
 *
 * <p>Simulation: the server only ticks chunks within the simulation distance of some player, and
 * entities in the rest stand still. A mob that moves in the ring between your simulation distance
 * and your render distance is therefore being ticked for somebody else (a player, or a chunk loader,
 * a portal or spawn chunks, which look the same). Movement is scored per chunk with a decay, and the
 * chunk is marked once the score reaches a confidence level.
 *
 * <p>Extra hints: chests, ender chests and shulker boxes opening far away, player sounds where no
 * player is in sight, jumps in entity ids (the server is busy creating things elsewhere), and a run
 * of marked chunks in a line (something travelling).
 */
public final class Sonar extends Module {
	public enum Mode { Blocks, Simulation, Both }

	public enum Confidence {
		Low, Medium, High
	}

	private static final double ESP_RANGE_SQ = 128.0 * 128.0;
	private static final long LOAD_GRACE_MS = 3000;
	private static final long QUIET_AFTER_JOIN_MS = 5000;
	private static final long ALERT_GAP_MS = 3000;
	private static final long CELL_ALERT_GAP_MS = 30_000;
	/** Plants and growths that appear and spread by themselves, so they never count as a player's work. */
	private static final Set<String> GROWTH = Set.of("vine", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant",
		"cave_vines", "cave_vines_plant", "kelp", "kelp_plant", "sugar_cane", "cactus", "bamboo", "bamboo_sapling", "pointed_dripstone",
		"small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "cocoa", "nether_wart", "sweet_berry_bush", "brown_mushroom", "red_mushroom");
	private static final BooleanProperty[] TOGGLES = {BlockStateProperties.OPEN, BlockStateProperties.POWERED};

	public final EnumSetting<Mode> mode = group(new EnumSetting<>("Mode", "Blocks: marks block changes far away. Simulation: marks chunks that are ticking beyond your simulation distance. Both does both", Mode.Both), "General");
	public final EnumSetting<Confidence> minConfidence = group(new EnumSetting<>("Min confidence", "Weakest simulation reading that is marked", Confidence.Low), "General");
	public final BooleanSetting beyondEsp = group(new BooleanSetting("Only beyond 128 blocks", "Block changes closer than player-ESP range are ignored, since you can see who made them", true), "General");
	public final BooleanSetting interactions = group(new BooleanSetting("Interactions", "Also mark doors, trapdoors and gates opened and levers or buttons switched, as weaker hints", true), "General");
	public final BooleanSetting containers = group(new BooleanSetting("Containers", "Mark chests, ender chests and shulker boxes opened far away", true), "Hints");
	public final BooleanSetting playerSounds = group(new BooleanSetting("Player sounds", "Mark player sounds with no player in sight of them", true), "Hints");
	public final BooleanSetting idJumps = group(new BooleanSetting("Entity ID jumps", "Say when entity ids leap ahead: a lot is being created somewhere else", false), "Hints");
	public final NumberSetting idJumpSize = group(new NumberSetting("Jump size", "Ids skipped between two spawns you see that count as a jump", 2000, 100, 50000, 100), "Hints");
	public final BooleanSetting streaks = group(new BooleanSetting("Chunk streaks", "Say when marked chunks line up one after another, like someone travelling", false), "Hints");
	public final NumberSetting window = group(new NumberSetting("Window (s)", "How long simulation activity counts before it fades", 8, 2, 30, 1), "General");
	public final NumberSetting lifetime = group(new NumberSetting("Marker lifetime (s)", "How long a marker stays after its last sign of activity", 35, 5, 180, 5), "General");
	public final NumberSetting maxMarkers = group(new NumberSetting("Max markers", "Most block markers kept; the oldest go first", 60, 10, 300, 10), "General");
	public final NumberSetting height = group(new NumberSetting("Column height", "Height of the box drawn over a simulated chunk, centred on you", 64, 16, 320, 16), "Render");
	public final BooleanSetting chatAlerts = group(new BooleanSetting("Chat alerts", "Say what was detected and which way it is", true), "Render");
	public final BooleanSetting sound = group(new BooleanSetting("Sound", "Play a warning sound when something is detected", false), "Alerts");
	public final IdListSetting alarmSound = group(Alarm.soundSetting(), "Alerts");
	public final BooleanSetting useJingle = group(Alarm.useJingleSetting(), "Alerts");
	public final EnumSetting<Jingle> jingle = group(Alarm.jingleSetting(), "Alerts");
	public final NumberSetting soundCount = group(new NumberSetting("Sound count", "How many times the sound plays, half a second apart", 1, 1, 10, 1), "Alerts");
	public final NumberSetting volume = group(new NumberSetting("Volume", "How loud each copy is (above 100% is boosted)", 0.5, 0.05, 2, 0.05).format(v -> Math.round(v * 100) + "%"), "Alerts");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Draw tracers to markers", true), "Render");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth)
		.visibleWhen(tracers::get), "Render");
	public final ColorSetting placeColor = group(new ColorSetting("Place color", "Block placed", 0xFFFF5050), "Render");
	public final ColorSetting breakColor = group(new ColorSetting("Break color", "Block broken", 0xFF50C8FF), "Render");
	public final ColorSetting interactColor = group(new ColorSetting("Interact color", "Door, trapdoor, gate, lever or button used", 0xFFFFC850), "Render");
	public final ColorSetting soundColor = group(new ColorSetting("Sound color", "Player sound with nobody in sight", 0xFFB070FF), "Render");
	public final ColorSetting simColor = group(new ColorSetting("Simulation color", "Chunk ticking beyond your simulation distance", 0xFFFF2ED1), "Render");

	private record Ping(BlockPos pos, String text, int rgb, long at) {
	}

	/** Movement score of one chunk. */
	private static final class Cell {
		final ChunkPos pos;
		double score;
		long scoreAt, shownUntil, alertedAt;
		Confidence shown, alerted;

		Cell(ChunkPos pos) {
			this.pos = pos;
		}
	}

	private final Map<Long, Ping> pings = new HashMap<>();
	private final Map<Long, Cell> cells = new HashMap<>();
	private final Map<Long, Long> loadedAt = new HashMap<>();
	private final Alarm alarm = new Alarm(alarmSound, useJingle, jingle, soundCount, volume);
	private long quietUntil, lastAlert;
	private int maxEntityId;
	private long idAlertAt, streakAlertAt;
	/** Simulation cells as they were first marked, newest last, for streaks. */
	private final List<ChunkPos> marked = new ArrayList<>();

	public Sonar() {
		super("Sonar", List.of("PlayerSonar", "SimulationSonar"), Category.WORLD, "Spots players beyond ESP range from far block changes and from chunks ticking outside your simulation distance.");
		beyondEsp.visibleWhen(() -> mode.get() != Mode.Simulation);
		interactions.visibleWhen(() -> mode.get() != Mode.Simulation);
		maxMarkers.visibleWhen(() -> mode.get() != Mode.Simulation);
		minConfidence.visibleWhen(() -> mode.get() != Mode.Blocks);
		idJumpSize.visibleWhen(idJumps::get);
		streaks.visibleWhen(() -> mode.get() != Mode.Blocks);
		window.visibleWhen(() -> mode.get() != Mode.Blocks);
		height.visibleWhen(() -> mode.get() != Mode.Blocks);
		useJingle.visibleWhen(sound::get);
		jingle.visibleWhen(() -> sound.get() && useJingle.get());
		alarmSound.visibleWhen(() -> sound.get() && !useJingle.get());
		soundCount.visibleWhen(sound::get);
		volume.visibleWhen(sound::get);

		listen(Events.PacketApply.Pre.class, e -> onPacket(e.packet));
		listen(Events.ChunkLoad.class, e -> loadedAt.put(e.chunk().getPos().pack(), System.currentTimeMillis()));
		listen(Events.ChunkUnload.class, e -> loadedAt.remove(e.chunk().getPos().pack()));
		listen(Events.ServerTeleport.class, e -> quiet());
		listen(Events.WorldChange.class, e -> {
			clear();
			quiet();
		});
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return sound.get() && !useJingle.get() ? alarmSound : null;
	}

	@Override
	protected void onEnable() {
		clear();
	}

	@Override
	protected void onDisable() {
		clear();
	}

	private void clear() {
		pings.clear();
		cells.clear();
		loadedAt.clear();
		marked.clear();
		maxEntityId = 0;
		alarm.reset();
	}

	/** Teleports and joins move the ring under every entity at once, so nothing counts for a moment. */
	private void quiet() {
		quietUntil = System.currentTimeMillis() + QUIET_AFTER_JOIN_MS;
	}

	@Override
	public String infoString() {
		int n = pings.size() + (int) cells.values().stream().filter(c -> c.shown != null && c.shownUntil > System.currentTimeMillis()).count();
		return n == 0 ? null : Integer.toString(n);
	}

	// ---- packets ----

	private void onPacket(Packet<?> packet) {
		ClientLevel level = mc().level;
		if (packet instanceof ClientboundLoginPacket) quiet();
		if (level == null || mc().player == null || mc().isLocalServer()) return;
		boolean blocks = mode.get() != Mode.Simulation, sim = mode.get() != Mode.Blocks;
		if (packet instanceof ClientboundBlockUpdatePacket update) {
			blockChange(level, update.getPos(), update.getBlockState(), blocks, sim);
		} else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
			update.runUpdates((pos, state) -> blockChange(level, pos, state, blocks, sim));
		} else if (blocks && containers.get() && packet instanceof ClientboundBlockEventPacket event && event.getB0() == 1 && event.getB1() > 0
			&& (event.getBlock() instanceof ChestBlock || event.getBlock() instanceof EnderChestBlock || event.getBlock() instanceof ShulkerBoxBlock)) {
			if (far(event.getPos())) ping(event.getPos(), "Open " + event.getBlock().getName().getString(), interactColor.argb() & 0xFFFFFF);
		} else if (blocks && playerSounds.get() && packet instanceof ClientboundSoundPacket sound && sound.getSource() == SoundSource.PLAYERS) {
			Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
			if (ChunkMemory.nobodyNear(level, at, 16, null)) ping(BlockPos.containing(at), "Player sound", soundColor.argb() & 0xFFFFFF);
		} else if (idJumps.get() && packet instanceof ClientboundAddEntityPacket add) {
			idJump(add.getId());
		} else if (sim && packet instanceof ClientboundMoveEntityPacket move && move.hasPosition()) {
			Entity entity = move.getEntity(level);
			if (entity != null) movement(entity, entity.chunkPosition());
		} else if (sim && packet instanceof ClientboundEntityPositionSyncPacket sync) {
			Entity entity = level.getEntity(sync.id());
			if (entity != null) movement(entity, ChunkPos.containing(BlockPos.containing(sync.values().position())));
		}
	}

	private void blockChange(ClientLevel level, BlockPos pos, BlockState next, boolean blocks, boolean sim) {
		BlockState old = level.getBlockState(pos);
		if (old == next) return;
		String kind = null;
		BlockState named = next;
		double weight = 0;
		if (!natural(old) && !natural(next)) {
			boolean wasClear = old.isAir() || old.is(BlockTags.REPLACEABLE), isClear = next.isAir() || next.is(BlockTags.REPLACEABLE);
			if (wasClear && !isClear) {
				kind = "Place";
				weight = 4;
			} else if (!wasClear && isClear) {
				kind = "Break";
				named = old;
				weight = 4;
			} else if (interactions.get() && old.getBlock() == next.getBlock() && toggled(old, next)) {
				kind = "Use";
				weight = 1.7;
			}
		}
		if (kind == null) return;
		long now = System.currentTimeMillis();
		if (sim && weight >= 4) score(ChunkPos.containing(pos), 2.0, now);
		if (!blocks || !far(pos)) return;
		int rgb = (kind.equals("Place") ? placeColor : kind.equals("Break") ? breakColor : interactColor).argb() & 0xFFFFFF;
		ping(pos, kind + " " + named.getBlock().getName().getString(), rgb);
	}

	/** Outside player-ESP range, when that filter is on. */
	private boolean far(BlockPos pos) {
		return !beyondEsp.get() || mc().player.position().distanceToSqr(Vec3.atCenterOf(pos)) > ESP_RANGE_SQ;
	}

	/** Marks {@code pos} and alerts (with the alert gap). */
	private void ping(BlockPos pos, String text, int rgb) {
		long now = System.currentTimeMillis();
		pings.put(pos.asLong(), new Ping(pos.immutable(), text, rgb, now));
		trim();
		if (now - lastAlert < ALERT_GAP_MS) return;
		lastAlert = now;
		if (sound.get()) alarm.trigger();
		if (chatAlerts.get()) info(text + " at " + Coords.format(pos.getX()) + ", " + Coords.format(pos.getY()) + ", " + Coords.format(pos.getZ()) + " " + where(Vec3.atCenterOf(pos)));
	}

	/** Entity ids count up server-wide, so a big leap between two spawns you see means a lot was created elsewhere. */
	private void idJump(int id) {
		long now = System.currentTimeMillis();
		int jump = id - maxEntityId;
		if (maxEntityId > 0 && jump > idJumpSize.get() && now - idAlertAt > CELL_ALERT_GAP_MS) {
			idAlertAt = now;
			info("Entity ids jumped by " + jump + ": the server is busy somewhere else");
		}
		maxEntityId = Math.max(maxEntityId, id);
	}

	/** Three newly marked chunks, each close to the last and heading the same way: something on the move. */
	private void streak(ChunkPos chunk) {
		marked.add(chunk);
		if (marked.size() > 3) marked.removeFirst();
		long now = System.currentTimeMillis();
		if (!streaks.get() || marked.size() < 3 || now - streakAlertAt < CELL_ALERT_GAP_MS) return;
		ChunkPos a = marked.get(0), b = marked.get(1), c = marked.get(2);
		int abx = b.x() - a.x(), abz = b.z() - a.z(), bcx = c.x() - b.x(), bcz = c.z() - b.z();
		boolean close = Math.max(Math.abs(abx), Math.abs(abz)) <= 2 && Math.max(Math.abs(bcx), Math.abs(bcz)) <= 2;
		if (!close || abx * bcx + abz * bcz <= 0) return;
		streakAlertAt = now;
		info("Something is moving through chunks " + a.x() + "," + a.z() + " -> " + c.x() + "," + c.z() + " " + where(columnCenter(c)));
	}

	/** Growth and spread, fire, fluids, falling blocks and decaying leaves: changes nobody made. */
	private static boolean natural(BlockState state) {
		if (!state.getFluidState().isEmpty() || state.is(BlockTags.FIRE) || state.is(BlockTags.LEAVES) || state.getBlock() instanceof Fallable
			|| state.getBlock() instanceof BonemealableBlock) {
			return true;
		}
		return state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || GROWTH.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
	}

	/** A door, trapdoor, gate, button, lever or barrel switched by hand; repeaters, rails and plates flip on their own. */
	private static boolean toggled(BlockState old, BlockState next) {
		if (!(old.is(BlockTags.DOORS) || old.is(BlockTags.TRAPDOORS) || old.is(BlockTags.FENCE_GATES) || old.is(BlockTags.BUTTONS)
			|| old.is(Blocks.LEVER) || old.is(Blocks.BARREL))) {
			return false;
		}
		for (BooleanProperty property : TOGGLES) {
			if (old.hasProperty(property) && old.getValue(property) != next.getValue(property)) return true;
		}
		return false;
	}

	private void trim() {
		while (pings.size() > maxMarkers.intValue()) {
			long oldest = Long.MAX_VALUE, key = 0;
			for (Map.Entry<Long, Ping> entry : pings.entrySet()) {
				if (entry.getValue().at() < oldest) {
					oldest = entry.getValue().at();
					key = entry.getKey();
				}
			}
			pings.remove(key);
		}
	}

	// ---- simulation ----

	private void movement(Entity entity, ChunkPos chunk) {
		// A visible player is its own explanation.
		if (entity instanceof Player) return;
		score(chunk, entity instanceof Mob ? 1.0 : 0.5, System.currentTimeMillis());
	}

	/** Adds movement to a chunk if it lies in the ring the server should not be ticking for you. */
	private void score(ChunkPos chunk, double amount, long now) {
		int sim = mc().level.getServerSimulationDistance(), view = mc().options.getEffectiveRenderDistance();
		ChunkPos me = mc().player.chunkPosition();
		int d = Math.max(Math.abs(chunk.x() - me.x()), Math.abs(chunk.z() - me.z()));
		if (!ChunkActivity.inOuterRing(d, sim, view) || now < quietUntil) return;
		Long loaded = loadedAt.get(chunk.pack());
		if (loaded != null && now - loaded < LOAD_GRACE_MS) return;

		Cell cell = cells.computeIfAbsent(chunk.pack(), k -> new Cell(chunk));
		decay(cell, now);
		cell.score += amount;
		int index = ChunkActivity.level(cell.score);
		if (index < 0 || index < minConfidence.get().ordinal()) return;
		Confidence level = Confidence.values()[index];
		if (cell.shown == null) streak(chunk);
		cell.shown = level;
		cell.shownUntil = now + lifetime.intValue() * 1000L;
		if (cell.alerted == null || level.compareTo(cell.alerted) > 0 || now - cell.alertedAt > CELL_ALERT_GAP_MS) {
			cell.alerted = level;
			cell.alertedAt = now;
			if (sound.get()) alarm.trigger();
			if (chatAlerts.get()) info("Chunk " + Coords.format(chunk.x()) + ", " + Coords.format(chunk.z()) + " is ticking outside your simulation distance (" + level + ") " + where(columnCenter(chunk)));
		}
	}

	/** Halves the score every half window. */
	private void decay(Cell cell, long now) {
		cell.score = ChunkActivity.decay(cell.score, now - cell.scoreAt, window.get());
		cell.scoreAt = now;
	}

	@Override
	public void onTick() {
		alarm.tick();
		if (mc().player == null) return;
		long now = System.currentTimeMillis();
		long keep = lifetime.intValue() * 1000L;
		Vec3 me = mc().player.position();
		pings.values().removeIf(p -> now - p.at() > keep || beyondEsp.get() && me.distanceToSqr(Vec3.atCenterOf(p.pos())) <= ESP_RANGE_SQ);
		cells.values().removeIf(c -> {
			decay(c, now);
			return c.shownUntil < now && c.score < 1;
		});
	}

	// ---- output ----

	private Vec3 columnCenter(ChunkPos chunk) {
		return new Vec3(chunk.getMiddleBlockX() + 0.5, mc().player.getY(), chunk.getMiddleBlockZ() + 0.5);
	}

	/** "(312 blocks NE)". */
	private String where(Vec3 target) {
		Vec3 me = mc().player.position();
		double dx = target.x - me.x, dz = target.z - me.z;
		String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
		int sector = (int) Math.floorMod(Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
		return "(" + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks " + names[sector] + ")";
	}

	private List<Cell> shownCells() {
		long now = System.currentTimeMillis();
		List<Cell> out = new ArrayList<>();
		for (Cell c : cells.values()) if (c.shown != null && c.shownUntil > now) out.add(c);
		return out;
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (mc().player == null) return;
		List<Cell> shown = shownCells();
		if (pings.isEmpty() && shown.isEmpty()) return;
		List<AABB> boxes = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		List<Vec3> ends = new ArrayList<>();
		for (Ping p : pings.values()) {
			boxes.add(new AABB(p.pos()));
			colors.add(p.rgb());
			ends.add(Vec3.atCenterOf(p.pos()));
		}
		double y = mc().player.getY(), half = height.intValue() / 2.0;
		int rgb = simColor.argb() & 0xFFFFFF;
		for (Cell c : shown) {
			int x = c.pos.getMinBlockX(), z = c.pos.getMinBlockZ();
			boxes.add(new AABB(x, y - half, z, x + 16, y + half, z + 16));
			colors.add(rgb);
			ends.add(new Vec3(x + 8, y, z + 8));
		}
		EspRender.boxes(boxes, colors.stream().mapToInt(Integer::intValue).toArray(), WorldOverlays.Shape.Outline, true, 1.5f, 0, cameraPos);
		if (!tracers.get() || tracerStyle.get() != Tracers.Style.Smooth) return;
		VertexConsumer lines = EspRender.tracerBuffer();
		for (int i = 0; i < ends.size(); i++) {
			EspRender.tracer(lines, ends.get(i).x, ends.get(i).y, ends.get(i).z, 0xB0000000 | colors.get(i), 1f, cameraPos);
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (mc().player == null) return;
		Vec3 me = mc().player.position();
		for (Ping p : pings.values()) {
			label(ui, projector, Vec3.atCenterOf(p.pos()), p.pos().getY() + 1.2, p.text(), p.rgb(), me);
		}
		for (Cell c : shownCells()) {
			label(ui, projector, columnCenter(c.pos), mc().player.getY() + 2, "Simulation " + c.shown, simColor.argb() & 0xFFFFFF, me);
		}
	}

	private void label(Ui ui, WorldProjector projector, Vec3 at, double labelY, String text, int rgb, Vec3 me) {
		int argb = 0xFF000000 | rgb;
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Classic) Tracers.pixel(ui, projector, at.x, at.y, at.z, 0xB0000000 | rgb, 1);
		float[] point = projector.project(at.x, labelY, at.z);
		if (!projector.onScreen(point, 30)) return;
		Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line(text + " [" + Math.round(me.distanceTo(at)) + "]", argb)),
			1f, true, ui.theme.panel, -1, 0, null);
	}
}
