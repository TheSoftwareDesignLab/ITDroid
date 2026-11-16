package uniandes.tsdl.itdroid.model;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Text of the translated screen drawn over a graphic of the app that is not an element of the dump.
 *
 * <p>The comparison works on the boxes of the elements, and some failures never move a box. The
 * arrow of a drop-down list is part of the list's background image; when a translated text grows
 * or is aligned to the other side (Arabic text aligns right by itself in a layout that is not
 * mirrored), the text runs over the arrow and every box is still where it was. Only the pixels show
 * it, so this looks at the two screenshots.
 *
 * <p>For each element with text that is where it was in the source screen, it takes the box of its
 * parent (or its own box, when the parent is the whole screen) in both screenshots:
 * <ul>
 * <li>ink: the pixels that differ from the median colour of their row, which is the background,
 * gradients included;</li>
 * <li>a graphic: ink of the source that is in the translated screenshot too, with the same colour,
 * and stands alone (a letter has letters of its height next to it on its line);</li>
 * <li>a collision: the source text did not touch the graphic and the translated text is drawn
 * over a good part of it, in thin strokes (a block of colour over it is a widget redrawn, not
 * text).</li>
 * </ul>
 * A graphic that is an element of the dump, or that is part of an element that moved or changed
 * size, is left to the comparison of boxes, which already sees it.
 *
 * <p>It is a heuristic: on the 16 apps it was tuned on it found the arrow and the border of
 * Forest Pond's drop-down in Arabic and nothing else.
 */
public final class TextOverGraphic {

	/** A pixel is ink when one of its channels is this far from the median of its row. */
	static final int INK = 50;
	/** Two pixels are the same colour when no channel differs by this much. */
	static final int SAME = 40;
	/** Elements are where they were when no edge moved more than this many pixels. */
	static final int NEAR = 6;
	/** A parent bigger than this share of the screen is a container of everything, not a widget. */
	static final double PARENT_SHARE_OF_SCREEN = 0.2;
	/** Smallest group of unchanged pixels taken as part of a graphic. */
	static final int MIN_BLOB = 40;
	/** Smallest graphic, in pixels of ink of the source. */
	static final int MIN_GRAPHIC = 60;
	/** A graphic whose box is larger than this share of the box looked at is a border or a background. */
	static final double MAX_SHARE_OF_BOX = 0.25;
	/** Share of the graphic that must look the same in both screenshots. */
	static final double UNCHANGED_SHARE = 0.8;
	/** Ink of the source near the graphic, as a share of the graphic, above which the source text already touched it. */
	static final double SOURCE_TEXT_SHARE = 0.05;
	/** Pixels of translated text over the graphic: at least this many... */
	static final int MIN_TEXT = 25;
	/** ...and at least this share of the graphic... */
	static final double TEXT_SHARE = 0.15;
	/** ...and at most this share of the graphic's box: more is a block of colour, not strokes of text. */
	static final double MAX_FILL = 0.5;
	/** A graphic whose box covers this share of an element's box (intersection over union) is that element. */
	static final double SAME_ELEMENT = 0.5;

	/** One text drawn over a graphic. */
	public static final class Hit {
		/** The element with the text, in the source and in the translated screen. */
		public final int sourceNode;
		public final int languageNode;
		/** Where the graphic is in the translated screen: x1, y1, x2, y2. */
		public final int[] graphic;
		/** Pixels of the graphic, and pixels of translated text drawn over it. */
		public final int graphicPixels;
		public final int textPixels;

		Hit(int sourceNode, int languageNode, int[] graphic, int graphicPixels, int textPixels) {
			this.sourceNode = sourceNode;
			this.languageNode = languageNode;
			this.graphic = graphic;
			this.graphicPixels = graphicPixels;
			this.textPixels = textPixels;
		}
	}

	private TextOverGraphic() {
	}

	/** The collisions of one translated screen with its source screen; empty when a screenshot is missing. */
	public static List<Hit> find(State source, BufferedImage sourceShot, State language, BufferedImage languageShot, NodePairing pairing) {
		List<Hit> hits = new ArrayList<>();
		if (sourceShot == null || languageShot == null
				|| sourceShot.getWidth() != languageShot.getWidth() || sourceShot.getHeight() != languageShot.getHeight()) {
			return hits;
		}
		List<AndroidNode> sn = source.getStateNodes();
		List<AndroidNode> tn = language.getStateNodes();
		long screen = (long) sourceShot.getWidth() * sourceShot.getHeight();

		// what moved or changed size in this language, in source coordinates; and every element of the source
		List<int[]> moved = new ArrayList<>();
		List<int[]> elements = new ArrayList<>();
		for (int i = 0; i < sn.size(); i++) {
			int[] b = box(sn.get(i));
			elements.add(b);
			int k = pairing.targetOfSource[i];
			if (k < 0 || !near(b, box(tn.get(k)))) {
				moved.add(b);
			}
		}

		// the boxes to look at: the parent of each element with text that stayed where it was
		List<int[]> regions = new ArrayList<>();
		List<int[]> languageRegions = new ArrayList<>();
		List<List<int[]>> texts = new ArrayList<>();   // per region: {sourceNode, languageNode} of its texts
		for (int i = 0; i < sn.size(); i++) {
			int k = pairing.targetOfSource[i];
			if (k < 0 || !hasText(tn.get(k)) || !near(box(sn.get(i)), box(tn.get(k)))) {
				continue;
			}
			int[] region = regionOf(sn, i, screen);
			int[] languageRegion = regionOf(tn, k, screen);
			int at = indexOf(regions, region);
			if (at < 0) {
				regions.add(region);
				languageRegions.add(languageRegion);
				texts.add(new ArrayList<>());
				at = regions.size() - 1;
			}
			texts.get(at).add(new int[] { i, k });
		}

		for (int r = 0; r < regions.size(); r++) {
			for (int[] g : collisions(sourceShot, regions.get(r), languageShot, languageRegions.get(r), elements, moved)) {
				int[] graphic = { g[0], g[1], g[2], g[3] };
				int[] owner = closest(texts.get(r), tn, graphic);
				hits.add(new Hit(owner[0], owner[1], graphic, g[4], g[5]));
			}
		}
		return hits;
	}

	/**
	 * The collisions in one box: each as x1, y1, x2, y2 of the graphic in the translated screen, its
	 * pixels and the pixels of text over it.
	 */
	static List<int[]> collisions(BufferedImage sourceShot, int[] sourceBox, BufferedImage languageShot, int[] languageBox,
			List<int[]> elements, List<int[]> moved) {
		List<int[]> out = new ArrayList<>();
		int w = Math.min(sourceBox[2] - sourceBox[0], languageBox[2] - languageBox[0]);
		int h = Math.min(sourceBox[3] - sourceBox[1], languageBox[3] - languageBox[1]);
		w = Math.min(w, Math.min(sourceShot.getWidth() - Math.max(0, sourceBox[0]), languageShot.getWidth() - Math.max(0, languageBox[0])));
		h = Math.min(h, Math.min(sourceShot.getHeight() - Math.max(0, sourceBox[1]), languageShot.getHeight() - Math.max(0, languageBox[1])));
		if (w <= 0 || h <= 0 || sourceBox[0] < 0 || sourceBox[1] < 0 || languageBox[0] < 0 || languageBox[1] < 0) {
			return out;
		}
		int[] s = sourceShot.getRGB(sourceBox[0], sourceBox[1], w, h, null, 0, w);
		int[] t = languageShot.getRGB(languageBox[0], languageBox[1], w, h, null, 0, w);
		boolean[] inkS = ink(s, w, h);
		boolean[] inkT = ink(t, w, h);
		int n = w * h;
		boolean[] unchanged = new boolean[n];
		boolean[] differs = new boolean[n];
		for (int p = 0; p < n; p++) {
			int d = distance(s[p], t[p]);
			unchanged[p] = inkS[p] && inkT[p] && d < SAME;
			differs[p] = inkT[p] && d > SAME;
		}
		int[] sourceLabels = new int[n];
		List<int[]> sourceParts = label(inkS, w, h, sourceLabels);   // per component: x1, y1, x2, y2 (inclusive), size
		int[] blobLabels = new int[n];
		List<int[]> blobs = label(unchanged, w, h, blobLabels);
		Set<String> done = new LinkedHashSet<>();
		for (int b = 0; b < blobs.size(); b++) {
			if (blobs.get(b)[4] < MIN_BLOB) {
				continue;
			}
			// the source components the unchanged blob belongs to: the whole graphic as the source draws it
			Set<Integer> ids = new LinkedHashSet<>();
			int[] bb = blobs.get(b);
			for (int y = bb[1]; y <= bb[3]; y++) {
				for (int x = bb[0]; x <= bb[2]; x++) {
					int p = y * w + x;
					if (blobLabels[p] == b + 1 && sourceLabels[p] > 0) {
						ids.add(sourceLabels[p]);
					}
				}
			}
			String key = ids.toString();
			if (ids.isEmpty() || !done.add(key)) {
				continue;
			}
			int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = -1, y2 = -1, size = 0;
			for (int id : ids) {
				int[] c = sourceParts.get(id - 1);
				x1 = Math.min(x1, c[0]);
				y1 = Math.min(y1, c[1]);
				x2 = Math.max(x2, c[2]);
				y2 = Math.max(y2, c[3]);
				size += c[4];
			}
			int ya = Math.max(0, y1 - 2), yb = Math.min(h, y2 + 3), xa = Math.max(0, x1 - 2), xb = Math.min(w, x2 + 3);
			int area = (yb - ya) * (xb - xa);
			if (area > MAX_SHARE_OF_BOX * n || size < MIN_GRAPHIC) {
				continue;   // borders and backgrounds as big as the box; specks
			}
			int[] inSource = { sourceBox[0] + xa, sourceBox[1] + ya, sourceBox[0] + xb, sourceBox[1] + yb };
			if (isAnElement(inSource, elements) || insideOne(inSource, moved)) {
				continue;   // the box comparison sees that one
			}
			if (isALetter(ids, x1, y1, x2, y2, sourceParts)) {
				continue;
			}
			int before = 0, kept = 0, after = 0;
			for (int y = ya; y < yb; y++) {
				for (int x = xa; x < xb; x++) {
					int p = y * w + x;
					boolean inGraphic = sourceLabels[p] > 0 && ids.contains(sourceLabels[p]);
					if (inkS[p] && !inGraphic) {
						before++;
					}
					if (inGraphic && unchanged[p]) {
						kept++;
					}
					if (differs[p]) {
						after++;
					}
				}
			}
			if (before > SOURCE_TEXT_SHARE * size || kept < UNCHANGED_SHARE * size) {
				continue;
			}
			if (after >= Math.max(MIN_TEXT, TEXT_SHARE * size) && after <= MAX_FILL * area) {
				out.add(new int[] { languageBox[0] + xa, languageBox[1] + ya, languageBox[0] + xb, languageBox[1] + yb, size, after });
			}
		}
		return out;
	}

	/** A letter has others of about its height beside it on its line; a graphic (an arrow, an icon) stands alone. */
	private static boolean isALetter(Set<Integer> ids, int x1, int y1, int x2, int y2, List<int[]> parts) {
		int height = y2 - y1 + 1;
		for (int j = 0; j < parts.size(); j++) {
			if (ids.contains(j + 1)) {
				continue;
			}
			int[] o = parts.get(j);
			int oh = o[3] - o[1] + 1;
			int overlap = Math.min(o[3], y2) - Math.max(o[1], y1) + 1;
			int gap = Math.max(Math.max(o[0] - (x2 + 1), x1 - (o[2] + 1)), 0);
			if (overlap >= 0.5 * Math.min(height, oh) && gap < height && oh >= 0.5 * height && oh <= 2 * height) {
				return true;
			}
		}
		return false;
	}

	private static boolean isAnElement(int[] g, List<int[]> elements) {
		for (int[] b : elements) {
			long inter = intersection(g, b);
			long union = area(g) + area(b) - inter;
			if (union > 0 && inter >= SAME_ELEMENT * union) {
				return true;
			}
		}
		return false;
	}

	private static boolean insideOne(int[] g, List<int[]> boxes) {
		for (int[] b : boxes) {
			if (intersection(g, b) >= 0.5 * area(g)) {
				return true;
			}
		}
		return false;
	}

	private static long intersection(int[] a, int[] b) {
		long x = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
		long y = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
		return x * y;
	}

	private static long area(int[] b) {
		return (long) Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
	}

	/** Pixels far from the median colour of their row, per channel. */
	static boolean[] ink(int[] rgb, int w, int h) {
		boolean[] out = new boolean[w * h];
		int[] r = new int[w], g = new int[w], b = new int[w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = rgb[y * w + x];
				r[x] = (c >> 16) & 0xff;
				g[x] = (c >> 8) & 0xff;
				b[x] = c & 0xff;
			}
			int mr = median(r), mg = median(g), mb = median(b);
			for (int x = 0; x < w; x++) {
				int c = rgb[y * w + x];
				int d = Math.max(Math.abs(((c >> 16) & 0xff) - mr), Math.max(Math.abs(((c >> 8) & 0xff) - mg), Math.abs((c & 0xff) - mb)));
				out[y * w + x] = d > INK;
			}
		}
		return out;
	}

	private static int median(int[] values) {
		int[] v = values.clone();
		Arrays.sort(v);
		return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
	}

	private static int distance(int a, int b) {
		return Math.max(Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff)),
				Math.max(Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)), Math.abs((a & 0xff) - (b & 0xff))));
	}

	/** Connected groups of true pixels (8 neighbours), numbered from 1 in {@code labels}; per group x1, y1, x2, y2 and size. */
	static List<int[]> label(boolean[] on, int w, int h, int[] labels) {
		List<int[]> parts = new ArrayList<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int start = 0; start < on.length; start++) {
			if (!on[start] || labels[start] != 0) {
				continue;
			}
			int id = parts.size() + 1;
			int[] part = { start % w, start / w, start % w, start / w, 0 };
			labels[start] = id;
			queue.add(start);
			while (!queue.isEmpty()) {
				int p = queue.poll();
				int px = p % w, py = p / w;
				part[0] = Math.min(part[0], px);
				part[1] = Math.min(part[1], py);
				part[2] = Math.max(part[2], px);
				part[3] = Math.max(part[3], py);
				part[4]++;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = px + dx, ny = py + dy;
						if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < w && ny < h) {
							int q = ny * w + nx;
							if (on[q] && labels[q] == 0) {
								labels[q] = id;
								queue.add(q);
							}
						}
					}
				}
			}
			parts.add(part);
		}
		return parts;
	}

	private static int[] box(AndroidNode n) {
		return new int[] { n.getPoint1()[0], n.getPoint1()[1], n.getPoint2()[0], n.getPoint2()[1] };
	}

	private static boolean near(int[] a, int[] b) {
		for (int i = 0; i < 4; i++) {
			if (Math.abs(a[i] - b[i]) > NEAR) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasText(AndroidNode n) {
		return n.getText() != null && !n.getText().trim().isEmpty();
	}

	/** The box of the element's parent, or the element's own box when the parent is (nearly) the whole screen. */
	private static int[] regionOf(List<AndroidNode> nodes, int i, long screen) {
		int parent = nodes.get(i).getParentPosition();
		if (parent >= 0) {
			int[] p = box(nodes.get(parent));
			if (area(p) < PARENT_SHARE_OF_SCREEN * screen) {
				return p;
			}
		}
		return box(nodes.get(i));
	}

	private static int indexOf(List<int[]> boxes, int[] b) {
		for (int i = 0; i < boxes.size(); i++) {
			if (Arrays.equals(boxes.get(i), b)) {
				return i;
			}
		}
		return -1;
	}

	/** Of the texts of a box, the one nearest the graphic: the element the finding is reported on. */
	private static int[] closest(List<int[]> texts, List<AndroidNode> tn, int[] graphic) {
		int[] best = texts.get(0);
		double bestDistance = Double.MAX_VALUE;
		double gx = (graphic[0] + graphic[2]) / 2.0, gy = (graphic[1] + graphic[3]) / 2.0;
		for (int[] t : texts) {
			int[] b = box(tn.get(t[1]));
			double dx = Math.max(Math.max(b[0] - gx, gx - b[2]), 0), dy = Math.max(Math.max(b[1] - gy, gy - b[3]), 0);
			double d = dx * dx + dy * dy;
			if (d < bestDistance) {
				bestDistance = d;
				best = t;
			}
		}
		return best;
	}
}
