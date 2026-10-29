package dev.coffeeclient.module.modules.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.mixin.AbstractSignEditScreenAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.JsonFiles;
import dev.coffeeclient.util.Coords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

/**
 * Writes signs for you: the text you set here (or saved as a preset), or else the last sign you wrote
 * by hand. Sign aura right-clicks signs in reach to fill them. Presets, aura and feedback follow
 * AutoSign.
 */
public final class AutoSign extends Module {
	/** A sign the aura wrote is left alone for this long. */
	private static final long REVISIT_MS = 10_000;

	public final StringSetting line1 = group(new StringSetting("Line 1", "First line (all four empty = repeat the last sign you wrote)", "", 90), "Text");
	public final StringSetting line2 = group(new StringSetting("Line 2", "Second line", "", 90), "Text");
	public final StringSetting line3 = group(new StringSetting("Line 3", "Third line", "", 90), "Text");
	public final StringSetting line4 = group(new StringSetting("Line 4", "Fourth line", "", 90), "Text");
	public final NumberSetting delay = group(new NumberSetting("Delay", "Tick delay between sign update packets", 10, 0, 100, 1), "Text");
	public final BooleanSetting feedback = group(new BooleanSetting("Chat feedback", "Say in chat which sign was written", true), "Text");
	public final ActionSetting applyNow = group(new ActionSetting("Apply now", "Write the text onto the sign you're looking at", this::applyToCrosshair), "Text");

	public final IdListSetting preset = group(new IdListSetting("Preset", "A saved text", List.of(), this::presetNames, true), "Presets");
	public final ActionSetting loadPreset = group(new ActionSetting("Load", "Put the chosen preset's text into the lines", this::loadPreset), "Presets");
	public final ActionSetting deletePreset = group(new ActionSetting("Delete", "Forget the chosen preset", this::deletePreset), "Presets");
	public final StringSetting presetName = group(new StringSetting("Save as", "Name for saving the current lines", "", 32), "Presets");
	public final ActionSetting savePreset = group(new ActionSetting("Save", "Save the current lines under that name", this::savePreset), "Presets");

	public final BooleanSetting aura = group(new BooleanSetting("Sign aura", "Right-click signs in reach to write them (changes the world)", false), "Sign aura");
	public final NumberSetting auraRange = group(new NumberSetting("Range", "Reach for the aura", 4, 1, 6, 0.5), "Sign aura");
	public final NumberSetting auraDelay = group(new NumberSetting("Aura delay", "Ticks between signs", 10, 1, 40, 1), "Sign aura");

	public final BooleanSetting waxAura = group(new BooleanSetting("Wax aura", "Wax unwaxed signs in reach with honeycomb from your hotbar (changes the world)", false), "Wax aura");
	public final NumberSetting waxRange = group(new NumberSetting("Wax range", "Reach for waxing", 4, 1, 6, 0.5), "Wax aura");
	public final NumberSetting waxDelay = group(new NumberSetting("Wax delay", "Ticks between waxed signs", 10, 1, 40, 1), "Wax aura");
	public final BooleanSetting waxHanging = group(new BooleanSetting("Hanging signs", "Wax hanging signs too", true), "Wax aura");
	public final BooleanSetting waxStill = group(new BooleanSetting("Only standing still", "Wait until you stop moving", true), "Wax aura");
	public final BooleanSetting waxEmpty = group(new BooleanSetting("Empty signs", "Wax signs with no text too", false), "Wax aura");
	public final StringListSetting waxSkip = group(new StringListSetting("Skip if contains", "Leave signs whose text contains any of these unwaxed", List.of(), 32), "Wax aura");

	public final BooleanSetting unwaxedEsp = group(new BooleanSetting("Unwaxed ESP", "Outline signs nearby that are not waxed", false), "Unwaxed ESP");
	public final NumberSetting espRange = group(new NumberSetting("ESP range", "How far to look for unwaxed signs", 32, 8, 128, 8), "Unwaxed ESP");
	public final ColorSetting espColor = group(new ColorSetting("ESP color", "Outline colour", 0xFFFFAA33), "Unwaxed ESP");

	private String[] lastWritten;
	private boolean lastFront;
	private final Queue<ServerboundSignUpdatePacket> queue = new ArrayDeque<>();
	private final Map<BlockPos, Long> visited = new HashMap<>();
	private Map<String, List<String>> presets;
	private int timer, auraTimer, waxTimer, espTimer;
	private List<AABB> unwaxed = List.of();

	public AutoSign() {
		super("AutoSign", Category.WORLD, "Writes signs with your text, a preset or the last sign you wrote.");
		for (Setting<?> s : List.of(auraRange, auraDelay)) s.visibleWhen(aura::get);
		for (Setting<?> s : List.of(waxRange, waxDelay, waxHanging, waxStill, waxEmpty, waxSkip)) s.visibleWhen(waxAura::get);
		for (Setting<?> s : List.of(espRange, espColor)) s.visibleWhen(unwaxedEsp::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.PacketOut.class, e -> {
			if (e.packet() instanceof ServerboundSignUpdatePacket p) {
				lastWritten = p.getLines().clone();
				lastFront = p.isFrontText();
			}
		});
		listen(Events.OpenScreen.class, e -> {
			if (!(e.screen instanceof AbstractSignEditScreen)) return;
			String[] text = text();
			if (text == null) return;
			AbstractSignEditScreenAccessor screen = (AbstractSignEditScreenAccessor) e.screen;
			// Repeating a hand-written sign keeps its side; set text goes on the side being edited.
			boolean front = hasOwnText() ? screen.coffeeclient$isFrontText() : lastFront;
			write(screen.coffeeclient$getSign(), front, text);
			e.cancel();
		});
	}

	@Override
	protected void onDisable() {
		lastWritten = null;
		queue.clear();
		visited.clear();
		timer = 0;
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			timer = 0;
			return;
		}
		if (aura.get() && ++auraTimer >= auraDelay.intValue()) {
			auraTimer = 0;
			signAura();
		}
		if (waxAura.get() && ++waxTimer >= waxDelay.intValue()) {
			waxTimer = 0;
			waxAura();
		}
		if (unwaxedEsp.get() && ++espTimer >= 20) {
			espTimer = 0;
			unwaxed = scanUnwaxed();
		} else if (!unwaxedEsp.get()) {
			unwaxed = List.of();
		}
		if (queue.peek() == null) {
			timer = 0;
			return;
		}
		if (timer < delay.intValue()) {
			timer++;
			return;
		}
		if (mc().getConnection() != null) mc().getConnection().send(queue.poll());
		timer = 0;
	}

	private boolean hasOwnText() {
		return !(line1.get().isEmpty() && line2.get().isEmpty() && line3.get().isEmpty() && line4.get().isEmpty());
	}

	/** The four lines to write, or null when nothing is set and no sign was written yet. */
	private String[] text() {
		if (hasOwnText()) return new String[]{line1.get(), line2.get(), line3.get(), line4.get()};
		return lastWritten;
	}

	private void write(SignBlockEntity sign, boolean front, String[] text) {
		BlockPos pos = sign.getBlockPos();
		queue.add(new ServerboundSignUpdatePacket(pos, front, text[0], text[1], text[2], text[3]));
		visited.put(pos, System.currentTimeMillis());
		if (feedback.get()) {
			String before = String.join(" | ", lines(sign, front));
			info("Wrote sign at " + Coords.format(pos.getX()) + ", " + Coords.format(pos.getY()) + ", " + Coords.format(pos.getZ())
				+ (before.replace(" | ", "").isEmpty() ? "" : " (was \"" + before + "\")"));
		}
	}

	private static String[] lines(SignBlockEntity sign, boolean front) {
		String[] out = new String[4];
		for (int i = 0; i < 4; i++) out[i] = sign.getText(front).getMessage(i, false).getString();
		return out;
	}

	private static boolean matches(SignBlockEntity sign, boolean front, String[] text) {
		String[] now = lines(sign, front);
		for (int i = 0; i < 4; i++) if (!now[i].equals(text[i])) return false;
		return true;
	}

	/** "Apply now": writes the sign under the crosshair without opening its screen. */
	private void applyToCrosshair() {
		Minecraft mc = mc();
		String[] text = text();
		if (mc.player == null || mc.level == null || text == null) return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
			|| !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign)) {
			info("Look at a sign first.");
			return;
		}
		write(sign, sign.isFacingFrontText(mc.player), text);
	}

	/** Right-clicks the nearest unwritten sign in reach and line of sight; the screen it opens is filled above. */
	private void signAura() {
		Minecraft mc = mc();
		String[] text = text();
		if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null || text == null || !queue.isEmpty()) return;
		// Dyes, ink and honeycomb would change the sign instead of opening it.
		ItemStack held = mc.player.getMainHandItem();
		if (held.getItem() instanceof DyeItem || held.is(Items.INK_SAC) || held.is(Items.GLOW_INK_SAC) || held.is(Items.HONEYCOMB)) return;
		long now = System.currentTimeMillis();
		visited.values().removeIf(t -> now - t > REVISIT_MS);
		Vec3 eye = mc.player.getEyePosition();
		double range = auraRange.get();
		int r = (int) Math.ceil(range);
		BlockPos center = BlockPos.containing(eye);
		SignBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!(mc.level.getBlockState(pos).getBlock() instanceof SignBlock) || visited.containsKey(pos)) continue;
			double d = Vec3.atCenterOf(pos).distanceToSqr(eye);
			if (d > range * range || d >= bestDistance) continue;
			if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign) || sign.isWaxed()) continue;
			if (matches(sign, sign.isFacingFrontText(mc.player), text)) continue;
			BlockHitResult sight = mc.level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (sight.getType() != HitResult.Type.MISS && !sight.getBlockPos().equals(pos)) continue;
			best = sign;
			bestDistance = d;
		}
		if (best == null) return;
		BlockPos pos = best.getBlockPos();
		visited.put(pos.immutable(), now);
		Vec3 hitAt = Vec3.atCenterOf(pos);
		Direction face = Direction.getApproximateNearest(eye.subtract(hitAt));
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitAt, face, pos, false));
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	/** A sign the wax aura may wax: unwaxed, a hanging one only when allowed, with text unless empty ones count, and none of the skip words. */
	private boolean waxable(SignBlockEntity sign) {
		if (sign.isWaxed()) return false;
		var block = sign.getBlockState().getBlock();
		if (!waxHanging.get() && (block instanceof CeilingHangingSignBlock || block instanceof WallHangingSignBlock)) return false;
		String text = (String.join(" ", lines(sign, true)) + " " + String.join(" ", lines(sign, false))).trim().toLowerCase(java.util.Locale.ROOT);
		if (text.isEmpty() && !waxEmpty.get()) return false;
		for (String w : waxSkip.get()) if (!w.isBlank() && text.contains(w.toLowerCase(java.util.Locale.ROOT))) return false;
		return true;
	}

	/** Honeycomb on the nearest waxable sign in reach and sight. */
	private void waxAura() {
		Minecraft mc = mc();
		if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) return;
		if (waxStill.get() && (Player.isMoving() || mc.player.getDeltaMovement().horizontalDistanceSqr() > 1e-4)) return;
		Inv.FindItemResult comb = Inv.findInHotbar(s -> s.is(Items.HONEYCOMB));
		if (!comb.found()) return;
		Vec3 eye = mc.player.getEyePosition();
		double range = waxRange.get();
		int r = (int) Math.ceil(range);
		BlockPos center = BlockPos.containing(eye);
		SignBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign) || !waxable(sign)) continue;
			double d = Vec3.atCenterOf(pos).distanceToSqr(eye);
			if (d > range * range || d >= bestDistance) continue;
			BlockHitResult sight = mc.level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (sight.getType() != HitResult.Type.MISS && !sight.getBlockPos().equals(pos)) continue;
			best = sign;
			bestDistance = d;
		}
		if (best == null) return;
		BlockPos pos = best.getBlockPos();
		Vec3 hitAt = Vec3.atCenterOf(pos);
		BlockHitResult hit = new BlockHitResult(hitAt, Direction.getApproximateNearest(eye.subtract(hitAt)), pos, false);
		boolean offhand = comb.isOffhand();
		if (!offhand) Inv.swap(comb.slot(), true);
		mc.gameMode.useItemOn(mc.player, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, hit);
		mc.player.swing(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		if (!offhand) Inv.swapBack();
	}

	private List<AABB> scanUnwaxed() {
		Minecraft mc = mc();
		if (mc.player == null || mc.level == null) return List.of();
		List<AABB> out = new ArrayList<>();
		BlockPos origin = mc.player.blockPosition();
		int r = espRange.intValue();
		long r2 = (long) r * r;
		for (int cx = (origin.getX() - r) >> 4; cx <= (origin.getX() + r) >> 4; cx++) {
			for (int cz = (origin.getZ() - r) >> 4; cz <= (origin.getZ() + r) >> 4; cz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (var be : chunk.getBlockEntities().values()) {
					if (be instanceof SignBlockEntity sign && !sign.isWaxed() && be.getBlockPos().distSqr(origin) <= r2) out.add(new AABB(be.getBlockPos()));
				}
			}
		}
		return out;
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		if (unwaxed.isEmpty()) return;
		int[] colors = new int[unwaxed.size()];
		java.util.Arrays.fill(colors, espColor.argb() & 0xFFFFFF);
		EspRender.boxes(unwaxed, colors, WorldOverlays.Shape.Outline, true, 1.5f, 0, cam);
	}

	// ---------------------------------------------------------------------------------------------
	// Presets, saved in config/coffeeclient/signpresets.json as {"name": ["l1", "l2", "l3", "l4"]}
	// ---------------------------------------------------------------------------------------------

	private static Path presetFile() {
		return CoffeeClient.config().directory().resolve("signpresets.json");
	}

	private Map<String, List<String>> presets() {
		if (presets != null) return presets;
		presets = new TreeMap<>();
		JsonObject root = JsonFiles.read(presetFile());
		if (root != null) {
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				if (!e.getValue().isJsonArray()) continue;
				List<String> lines = new ArrayList<>();
				for (JsonElement line : e.getValue().getAsJsonArray()) lines.add(line.isJsonPrimitive() ? line.getAsString() : "");
				while (lines.size() < 4) lines.add("");
				presets.put(e.getKey(), List.copyOf(lines.subList(0, 4)));
			}
		}
		return presets;
	}

	private List<String> presetNames() {
		return List.copyOf(presets().keySet());
	}

	private void savePresets() {
		JsonObject root = new JsonObject();
		presets().forEach((name, lines) -> {
			JsonArray array = new JsonArray();
			lines.forEach(array::add);
			root.add(name, array);
		});
		String json = JsonFiles.GSON.toJson(root);
		Path file = presetFile();
		Async.io(() -> {
			try {
				JsonFiles.writeAtomic(file, json);
			} catch (IOException e) {
				CoffeeClient.LOG.warn("Could not save sign presets: {}", e.getMessage());
			}
		});
	}

	private void savePreset() {
		String name = presetName.get().trim();
		if (name.isEmpty()) {
			info("Type a name in \"Save as\" first.");
			return;
		}
		presets().put(name, List.of(line1.get(), line2.get(), line3.get(), line4.get()));
		savePresets();
		preset.set(List.of(name));
		info("Saved preset \"" + name + "\".");
	}

	private void loadPreset() {
		List<String> lines = preset.first() == null ? null : presets().get(preset.first());
		if (lines == null) {
			info("Pick a preset first.");
			return;
		}
		line1.set(lines.get(0));
		line2.set(lines.get(1));
		line3.set(lines.get(2));
		line4.set(lines.get(3));
	}

	private void deletePreset() {
		String name = preset.first();
		if (name == null || presets().remove(name) == null) return;
		savePresets();
		preset.set(List.of());
		info("Deleted preset \"" + name + "\".");
	}
}
