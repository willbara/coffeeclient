package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifFramesTest {
	@Test
	void bundledPetsDecodeToFittedFrames() throws IOException {
		for (String name : new String[] {"chiikawa", "mario", "neco", "rabbit", "tungtung"}) {
			try (InputStream in = GifFramesTest.class.getResourceAsStream("/assets/coffeeclient/pets/" + name + ".gif")) {
				GifFrames gif = GifFrames.read(in, 256);
				assertTrue(gif.frames().length > 1, name);
				assertTrue(Math.max(gif.width(), gif.height()) <= 256, name);
				assertEquals(gif.frames().length, gif.delays().length, name);
				assertEquals(gif.width() * gif.height(), gif.frames()[0].length, name);
			}
		}
	}

	@Test
	void necoKeepsItsSizeFramesAndTiming() throws IOException {
		try (InputStream in = GifFramesTest.class.getResourceAsStream("/assets/coffeeclient/pets/neco.gif")) {
			GifFrames gif = GifFrames.read(in, 256);
			assertEquals(152, gif.width());
			assertEquals(160, gif.height());
			assertEquals(22, gif.frames().length);
			assertEquals(80, gif.delays()[0]);
		}
	}

	@Test
	void longGifsShrinkToThePixelBudget() throws IOException {
		BufferedImage frame = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < 300; i++) writer.writeToSequence(new IIOImage(frame, null, null), null);
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
		GifFrames gif = GifFrames.read(new ByteArrayInputStream(bytes.toByteArray()), 256);
		assertEquals(300, gif.frames().length);
		assertTrue(gif.width() < 256);
		assertTrue((long) gif.width() * gif.height() * gif.frames().length <= 8_000_000L);
	}

	@Test
	void packedSheetsStayWithinTheGpuLimit() {
		// frames, width, height: a wide strip of 1-pixel frames, a tall strip, a Konata-sized sheet, a small GIF
		int[][] cases = {{8000, 256, 1}, {31250, 1, 256}, {147, 256, 154}, {5, 112, 112}};
		for (int[] c : cases) {
			int columns = GifFrames.columns(c[0], c[1], c[2]);
			int rows = (c[0] + columns - 1) / columns;
			assertTrue(columns * c[1] <= 4352, "width of " + c[0] + " frames");
			assertTrue(rows * c[2] <= 4352, "height of " + c[0] + " frames");
		}
		assertEquals(13, GifFrames.columns(147, 256, 154));
	}

	@Test
	void frameAtLoopsThroughTheDelays() {
		int[] delays = {100, 50};
		assertEquals(0, GifFrames.frameAt(delays, 0));
		assertEquals(0, GifFrames.frameAt(delays, 99));
		assertEquals(1, GifFrames.frameAt(delays, 100));
		assertEquals(1, GifFrames.frameAt(delays, 149));
		assertEquals(0, GifFrames.frameAt(delays, 150));
	}
}
