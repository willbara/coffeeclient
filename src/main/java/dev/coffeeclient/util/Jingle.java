package dev.coffeeclient.util;

/** Short noteblock tunes for alerts. */
public enum Jingle {
	Ding("Three noteblock bell dings", new Note("minecraft:block.note_block.bell", 1.2f, 3), new Note("minecraft:block.note_block.bell", 1.2f, 3),
		new Note("minecraft:block.note_block.bell", 1.5f, 0)),
	Bell("The village bell being rung", new Note("minecraft:block.bell.use", 1f, 0)),
	Fanfare("A rising pling arpeggio ending on a high note", new Note("minecraft:block.note_block.pling", 0.84f, 2), new Note("minecraft:block.note_block.pling", 1.06f, 2),
		new Note("minecraft:block.note_block.pling", 1.26f, 2), new Note("minecraft:block.note_block.pling", 1.68f, 4), new Note("minecraft:block.note_block.pling", 1.68f, 0));

	/** One note: vanilla sound id, pitch, and ticks to wait before the next note. */
	public record Note(String sound, float pitch, int gap) {
	}

	public final String description;
	public final Note[] notes;

	Jingle(String description, Note... notes) {
		this.description = description;
		this.notes = notes;
	}
}
