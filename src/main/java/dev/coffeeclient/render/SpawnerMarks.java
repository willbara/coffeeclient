package dev.coffeeclient.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds the loaded mob spawners and trial spawners around the player and describes each one for BlockESP's Spawners section. */
public final class SpawnerMarks {
	/** {@code text} is the label without the distance; {@code range} is the block distance the spawner wakes up at. */
	public record Mark(BlockPos pos, boolean trial, boolean ominous, String text, int range) {
	}

	// shortcut: assumes the vanilla range of 16, a spawner with a custom required player range shows the wrong ring; upgrade with an accessor mixin
	private static final int SPAWNER_RANGE = 16;

	/** Where a trial spawner's cooldown should end, guessed when its Cooldown state is seen starting (the server does not send it). */
	private final Map<BlockPos, Long> cooldownEnds = new HashMap<>();
	private final Map<BlockPos, TrialSpawnerState> lastStates = new HashMap<>();

	public void clear() {
		cooldownEnds.clear();
		lastStates.clear();
	}

	public List<Mark> scan(ClientLevel level, BlockPos origin, int radius, boolean spawners, boolean trials) {
		List<Mark> out = new ArrayList<>();
		Set<BlockPos> seenTrials = new HashSet<>();
		int r2 = radius * radius;
		long now = level.getGameTime();
		for (int cx = (origin.getX() - radius) >> 4; cx <= (origin.getX() + radius) >> 4; cx++) {
			for (int cz = (origin.getZ() - radius) >> 4; cz <= (origin.getZ() + radius) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be.getBlockPos().distSqr(origin) > r2) continue;
					if (spawners && be instanceof SpawnerBlockEntity spawner) {
						Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, be.getBlockPos());
						out.add(new Mark(be.getBlockPos(), false, false, name(display) + " Spawner", SPAWNER_RANGE));
					} else if (trials && be instanceof TrialSpawnerBlockEntity trial) {
						seenTrials.add(be.getBlockPos());
						out.add(trialMark(level, trial, now));
					}
				}
			}
		}
		if (trials) {
			lastStates.keySet().retainAll(seenTrials);
			cooldownEnds.values().removeIf(end -> end <= now);
		}
		return out;
	}

	private Mark trialMark(ClientLevel level, TrialSpawnerBlockEntity be, long now) {
		TrialSpawner logic = be.getTrialSpawner();
		BlockPos pos = be.getBlockPos();
		TrialSpawnerState state = logic.getState();
		TrialSpawnerState before = lastStates.put(pos, state);
		if (before != null && before != TrialSpawnerState.COOLDOWN && state == TrialSpawnerState.COOLDOWN) cooldownEnds.put(pos, now + logic.getTargetCooldownLength());
		Entity display = logic.getStateData().getOrCreateDisplayEntity(logic, level, state);

		StringBuilder text = new StringBuilder(logic.isOminous() ? "Ominous trial spawner: " : "Trial spawner: ").append(name(display));
		text.append('\n').append(capitalise(state.getSerializedName()));
		Long end = cooldownEnds.get(pos);
		if (state == TrialSpawnerState.COOLDOWN && end != null && end > now) text.append(" ~").append(clock(end - now));
		return new Mark(pos, true, logic.isOminous(), text.toString(), logic.getRequiredPlayerRange());
	}

	private static String name(Entity display) {
		return display == null ? "Unknown" : display.getType().getDescription().getString();
	}

	private static String capitalise(String serialized) {
		String spaced = serialized.replace('_', ' ');
		return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
	}

	/** {@code ticks} as m:ss. */
	static String clock(long ticks) {
		long seconds = ticks / 20;
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}
}
