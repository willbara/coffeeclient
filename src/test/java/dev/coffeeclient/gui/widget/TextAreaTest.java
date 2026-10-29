package dev.coffeeclient.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextAreaTest {
	@Test
	void wrapsAtWidthAndAtNewlines() {
		// one character is one unit of width here
		assertEquals(List.of(0, 4), TextArea.lineStarts("abc\ndef", 10, String::length));
		assertEquals(List.of(0, 4), TextArea.lineStarts("abcdef", 4, String::length));
		assertEquals(List.of(0, 2, 3), TextArea.lineStarts("a\n\nb", 10, String::length));
		assertEquals(List.of(0), TextArea.lineStarts("", 10, String::length));
	}
}
