package dev.coffeeclient.util;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;

import java.util.List;

/** Plays the chosen warning sound or noteblock jingle a set number of times, half a second apart. Call {@link #tick()} every tick. */
public final class Alarm {
	/** A crowd arriving at once must not queue half a minute of noise. */
	private static final int MAX_QUEUED = 10;

	private final IdListSetting sound;
	private final BooleanSetting useJingle;
	private final EnumSetting<Jingle> jingle;
	private final NumberSetting count, volume;
	/** Plays still to come, the notes of the play in progress, and ticks until the next note. */
	private int left, index, wait;
	private Jingle.Note[] playing;

	public Alarm(IdListSetting sound, BooleanSetting useJingle, EnumSetting<Jingle> jingle, NumberSetting count, NumberSetting volume) {
		this.sound = sound;
		this.useJingle = useJingle;
		this.jingle = jingle;
		this.count = count;
		this.volume = volume;
	}

	/** The "Alarm sound" picker every alarm shares. */
	public static IdListSetting soundSetting() {
		return new IdListSetting("Alarm sound", "Sound played by the alarm (click ▶ to preview)", List.of("minecraft:block.anvil.land"), Sounds::allIds, true)
			.labels(Sounds::label).preview(id -> {
				Sounds.play(id, 1f, 1f);
				return id;
			});
	}

	public static BooleanSetting useJingleSetting() {
		return new BooleanSetting("Jingle", "Play a noteblock jingle instead of the picked sound", false);
	}

	public static EnumSetting<Jingle> jingleSetting() {
		return new EnumSetting<>("Jingle tune", "Ding: three noteblock bells. Bell: the village bell. Fanfare: a rising pling tune", Jingle.Ding);
	}

	public void trigger() {
		if (left == 0 && playing == null) wait = 0;
		left = Math.min(left + count.intValue(), MAX_QUEUED);
	}

	public int pending() {
		return left;
	}

	/** Drops plays still to come, so a module turned off goes quiet. */
	public void reset() {
		left = 0;
		playing = null;
	}

	public void tick() {
		if (left == 0 && playing == null || --wait > 0) return;
		if (playing == null) {
			playing = useJingle.get() ? jingle.get().notes : new Jingle.Note[] {new Jingle.Note(sound.first(), 1f, 0)};
			index = 0;
			left--;
		}
		Jingle.Note note = playing[index++];
		Sounds.play(note.sound(), note.pitch(), volume.floatValue());
		if (index < playing.length) {
			wait = note.gap();
		} else {
			wait = 10;
			playing = null;
		}
	}
}
