package dev.coffeeclient.util;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * The frames of an animated GIF, each drawn over the last the way a browser plays them (disposal honoured),
 * scaled so the longest side is at most the size asked for. Frames are ARGB pixels, row by row.
 */
public record GifFrames(int width, int height, int[] delays, int[][] frames) {
	/** All frames together stay under this many pixels (4 bytes each), so a long GIF cannot use unbounded memory. */
	private static final long PIXEL_BUDGET = 8_000_000L;
	/** Longest side a packed sheet may have; graphics cards refuse textures wider or taller than this. */
	private static final int SHEET_SIDE = 4096;

	/**
	 * Frames per row when {@code frames} frames of {@code width} x {@code height} pixels are packed into a grid.
	 * Enough columns that the grid is no taller than {@link #SHEET_SIDE}, and no wider than it either.
	 */
	public static int columns(int frames, int width, int height) {
		int square = (int) Math.ceil(Math.sqrt(frames));
		int fitWidth = Math.max(1, SHEET_SIDE / width);
		int fitHeight = (int) Math.ceil(frames * (double) height / SHEET_SIDE);
		return Math.max(1, Math.min(fitWidth, Math.max(square, fitHeight)));
	}

	/** Index of the frame showing {@code ms} milliseconds in, for per-frame {@code delays}; the animation loops forever. */
	public static int frameAt(int[] delays, long ms) {
		if (delays.length < 2) return 0;
		long total = 0;
		for (int d : delays) total += d;
		long t = ms % total;
		for (int i = 0; ; i++) {
			if (t < delays[i]) return i;
			t -= delays[i];
		}
	}

	public static GifFrames read(InputStream in, int maxSide) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream stream = new MemoryCacheImageInputStream(in)) {
			reader.setInput(stream);
			int count = reader.getNumImages(true);
			int w = reader.getWidth(0), h = reader.getHeight(0);
			float s = Math.min(1f, maxSide / (float) Math.max(w, h));
			s = Math.min(s, (float) Math.sqrt(PIXEL_BUDGET / ((double) count * w * h)));
			int outW = Math.max(1, Math.round(w * s)), outH = Math.max(1, Math.round(h * s));
			int[] delays = new int[count];
			int[][] frames = new int[count][];
			BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i < count; i++) {
				Element root = (Element) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
				int left = attr(root, "ImageDescriptor", "imageLeftPosition", 0);
				int top = attr(root, "ImageDescriptor", "imageTopPosition", 0);
				int delay = attr(root, "GraphicControlExtension", "delayTime", 0);
				String disposal = text(root, "GraphicControlExtension", "disposalMethod");
				BufferedImage part = reader.read(i);
				BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
				Graphics2D g = canvas.createGraphics();
				g.drawImage(part, left, top, null);
				g.dispose();
				frames[i] = scaled(canvas, outW, outH);
				// browsers play a delay of 0 or 1 (1/100 s) as 100 ms
				delays[i] = delay < 2 ? 100 : delay * 10;
				// the next frame starts from what this one's disposal leaves behind
				if ("restoreToBackgroundColor".equals(disposal)) clear(canvas, left, top, part.getWidth(), part.getHeight());
				else if (before != null) canvas = before;
			}
			return new GifFrames(outW, outH, delays, frames);
		} finally {
			reader.dispose();
		}
	}

	private static int attr(Element root, String tag, String name, int fallback) {
		String value = text(root, tag, name);
		return value == null ? fallback : Integer.parseInt(value);
	}

	/** An attribute of the first {@code tag} element, or null when the GIF has none. */
	private static String text(Element root, String tag, String name) {
		Node node = root.getElementsByTagName(tag).item(0);
		return node == null ? null : ((Element) node).getAttribute(name);
	}

	private static BufferedImage copy(BufferedImage image) {
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		out.setData(image.getRaster());
		return out;
	}

	private static void clear(BufferedImage image, int x, int y, int w, int h) {
		Graphics2D g = image.createGraphics();
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(x, y, w, h);
		g.dispose();
	}

	private static int[] scaled(BufferedImage frame, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(frame.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
		g.dispose();
		return out.getRGB(0, 0, w, h, null, 0, w);
	}
}
