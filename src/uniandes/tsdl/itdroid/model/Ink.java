package uniandes.tsdl.itdroid.model;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a screenshot shows inside the box of an element. The element's own background is the colour
 * most of the inside of its border has, and its ink is every pixel that differs from it, so the box
 * of what it draws can be far smaller than its layout box. Boxes are x1, y1, x2, y2 with the second
 * corner outside, as in the dump. Without a screenshot the layout box stands for what is drawn.
 */
public final class Ink {

	/** A pixel is ink when one of its channels is this far from the background. */
	static final int INK = 50;
	/** Two pixels are the same colour when no channel differs by this much. */
	static final int SAME = 40;
	/** Fewest pixels of ink that make a box draw something, so a stray pixel of rounding draws nothing. */
	static final int MIN_PIXELS = 3;

	private final int width;
	private final int height;
	/** Every pixel of the screenshot, read once. */
	private final int[] rgb;

	public Ink(BufferedImage image) {
		width = image == null ? 0 : image.getWidth();
		height = image == null ? 0 : image.getHeight();
		rgb = image == null ? null : image.getRGB(0, 0, width, height, null, 0, width);
	}

	/** True when there is a screenshot to look at. */
	public boolean available() {
		return rgb != null;
	}

	/** True when both screenshots are there with the same size, so a pixel of one is the same place of the other. */
	boolean sameSize(Ink other) {
		return available() && other.available() && width == other.width && height == other.height;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The colour of a pixel as red, green and blue, which must be inside the screenshot. */
	public int at(int x, int y) {
		return rgb[y * width + x] & 0xffffff;
	}

	public boolean inside(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	/** The colours of a w by h region from x, y, row after row, which must be inside the screenshot. */
	int[] pixels(int x, int y, int w, int h) {
		int[] out = new int[w * h];
		for (int row = 0; row < h; row++) {
			System.arraycopy(rgb, (y + row) * width + x, out, row * w, w);
		}
		return out;
	}

	/** The part of a box inside the screenshot, null when none. */
	public int[] clip(int[] box) {
		return Boxes.intersection(box, new int[] { 0, 0, width, height });
	}

	/** Largest difference of one channel between two colours. */
	public static int distance(int a, int b) {
		return Math.max(Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff)),
				Math.max(Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)), Math.abs((a & 0xff) - (b & 0xff))));
	}

	/** The colour most of the inside of the border of a box has, or -1 without a screenshot or for an empty box. */
	public int background(int[] box) {
		int[] b = available() ? clip(box) : null;
		if (b == null) {
			return -1;
		}
		Map<Integer, int[]> counts = new HashMap<>();
		for (int x = b[0]; x < b[2]; x++) {
			tally(counts, at(x, b[1]));
			if (b[3] - 1 > b[1]) {
				tally(counts, at(x, b[3] - 1));
			}
		}
		for (int y = b[1] + 1; y < b[3] - 1; y++) {
			tally(counts, at(b[0], y));
			if (b[2] - 1 > b[0]) {
				tally(counts, at(b[2] - 1, y));
			}
		}
		return mostFrequent(counts);
	}

	/**
	 * The colour most of the ring of {@code ring} pixels just outside a box has, what the box stands
	 * on, or -1 when the ring is out of the screenshot.
	 */
	public int surroundings(int[] box, int ring) {
		if (!available()) {
			return -1;
		}
		Map<Integer, int[]> counts = new HashMap<>();
		for (int y = box[1] - ring; y < box[3] + ring; y++) {
			for (int x = box[0] - ring; x < box[2] + ring; x++) {
				boolean outside = x < box[0] || y < box[1] || x >= box[2] || y >= box[3];
				if (outside && inside(x, y)) {
					tally(counts, at(x, y));
				}
			}
		}
		return counts.isEmpty() ? -1 : mostFrequent(counts);
	}

	/** The box of what an element draws in its box, null when nothing. Without a screenshot, the box itself. */
	public int[] box(int[] box) {
		return box(box, List.of());
	}

	/**
	 * The box of the ink inside a box, leaving out the holes, null when there is too little. Without
	 * a screenshot the box itself, or null when it is empty.
	 */
	public int[] box(int[] box, List<int[]> holes) {
		if (!available()) {
			return Boxes.empty(box) ? null : box.clone();
		}
		int[] b = clip(box);
		if (b == null) {
			return null;
		}
		return inkBox(b, background(b), holes);
	}

	/** The box of the pixels of a region that differ from a colour, outside the holes, null when too few. */
	public int[] inkBox(int[] region, int background, List<int[]> holes) {
		int[] b = clip(region);
		if (b == null || background < 0) {
			return null;
		}
		int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = -1, y2 = -1, n = 0;
		for (int y = b[1]; y < b[3]; y++) {
			for (int x = b[0]; x < b[2]; x++) {
				if (distance(at(x, y), background) > INK && !inAny(x, y, holes)) {
					n++;
					x1 = Math.min(x1, x);
					y1 = Math.min(y1, y);
					x2 = Math.max(x2, x + 1);
					y2 = Math.max(y2, y + 1);
				}
			}
		}
		return n < MIN_PIXELS ? null : new int[] { x1, y1, x2, y2 };
	}

	/** Pixels of a region that differ from every one of the colours. */
	public int count(int[] region, int... colours) {
		int[] b = available() ? clip(region) : null;
		if (b == null) {
			return 0;
		}
		int n = 0;
		for (int y = b[1]; y < b[3]; y++) {
			for (int x = b[0]; x < b[2]; x++) {
				boolean ink = true;
				for (int c : colours) {
					if (c >= 0 && distance(at(x, y), c) <= INK) {
						ink = false;
						break;
					}
				}
				n += ink ? 1 : 0;
			}
		}
		return n;
	}

	private static boolean inAny(int x, int y, List<int[]> boxes) {
		for (int[] h : boxes) {
			if (x >= h[0] && y >= h[1] && x < h[2] && y < h[3]) {
				return true;
			}
		}
		return false;
	}

	/** A colour with 5 bits per channel, so colours a step of rounding apart count as one. */
	static int key(int c) {
		return ((c >> 19) & 0x1f) << 10 | ((c >> 11) & 0x1f) << 5 | ((c >> 3) & 0x1f);
	}

	/** Counts a colour under its {@link #key}, keeping the sum of each channel to average them. */
	private static void tally(Map<Integer, int[]> counts, int c) {
		int[] v = counts.computeIfAbsent(key(c), k -> new int[4]);
		v[0]++;
		v[1] += (c >> 16) & 0xff;
		v[2] += (c >> 8) & 0xff;
		v[3] += c & 0xff;
	}

	private static int mostFrequent(Map<Integer, int[]> counts) {
		int[] best = null;
		int bestKey = Integer.MAX_VALUE;
		for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
			int[] v = e.getValue();
			if (best == null || v[0] > best[0] || (v[0] == best[0] && e.getKey() < bestKey)) {
				best = v;
				bestKey = e.getKey();
			}
		}
		return best == null ? -1 : (best[1] / best[0]) << 16 | (best[2] / best[0]) << 8 | (best[3] / best[0]);
	}
}
