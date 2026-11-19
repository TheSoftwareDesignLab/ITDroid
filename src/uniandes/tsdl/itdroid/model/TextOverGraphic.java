package uniandes.tsdl.itdroid.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Text of the translated screen drawn over a graphic of the app that is not an element of the dump,
 * such as the arrow of a drop-down. No box moves then, so the two screenshots are compared around
 * each text that stayed in place, looking for unchanged graphics newly covered by thin strokes. It
 * is a heuristic tuned on 16 apps.
 */
public final class TextOverGraphic {

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
	/** Share of the graphic not covered by the new text that must look the same in both screenshots. */
	static final double UNCHANGED_SHARE = 0.8;
	/** Ink of the source near the graphic, as a share of the graphic, above which the source text already touched it. */
	static final double SOURCE_TEXT_SHARE = 0.05;
	/** Fewest pixels of translated text over the graphic. */
	static final int MIN_TEXT = 25;
	/** Smallest share of the graphic covered by translated text. */
	static final double TEXT_SHARE = 0.15;
	/** Largest share of the graphic's box covered by text, as more is a block of colour, not strokes. */
	static final double MAX_FILL = 0.5;
	/** A graphic whose box covers this share of an element's box (intersection over union) is that element. */
	static final double SAME_ELEMENT = 0.5;
	/**
	 * A graphic that reaches within this many pixels of two opposite sides of the box looked at is a
	 * side of its frame, such as the outline of a drop-down, whose rounded corners stop it short.
	 */
	static final int FRAME_EDGE = 4;

	/** One text drawn over a graphic. */
	public static final class Hit {
		/** The element with the text, in the source and in the translated screen. */
		public final int sourceNode;
		public final int languageNode;
		/** The box of the graphic in the translated screen, as x1, y1, x2, y2. */
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

	/** The collisions of one translated screen with its source screen, empty when a screenshot is missing. */
	public static List<Hit> find(State source, Ink sourceShot, State language, Ink languageShot, NodePairing pairing) {
		List<Hit> hits = new ArrayList<>();
		if (!sourceShot.sameSize(languageShot)) {
			return hits;
		}
		List<AndroidNode> sn = source.getStateNodes();
		List<AndroidNode> tn = language.getStateNodes();
		long screen = (long) sourceShot.width() * sourceShot.height();

		// every element of the source, and those that moved or changed size in this language
		List<int[]> moved = new ArrayList<>();
		List<int[]> elements = new ArrayList<>();
		for (int i = 0; i < sn.size(); i++) {
			int[] b = Boxes.of(sn.get(i));
			elements.add(b);
			int k = pairing.targetOfSource[i];
			if (k < 0 || !near(b, Boxes.of(tn.get(k)))) {
				moved.add(b);
			}
		}

		// look at the parent box of each element with text that stayed where it was
		List<int[]> regions = new ArrayList<>();
		List<int[]> languageRegions = new ArrayList<>();
		List<List<int[]>> texts = new ArrayList<>();   // per region, the {sourceNode, languageNode} of its texts
		for (int i = 0; i < sn.size(); i++) {
			int k = pairing.targetOfSource[i];
			if (k < 0 || !hasText(tn.get(k)) || !near(Boxes.of(sn.get(i)), Boxes.of(tn.get(k)))) {
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
	 * The collisions in one box, each as x1, y1, x2, y2 of the graphic in the translated screen, its
	 * pixels and the pixels of text over it.
	 */
	static List<int[]> collisions(Ink sourceShot, int[] sourceBox, Ink languageShot, int[] languageBox,
			List<int[]> elements, List<int[]> moved) {
		List<int[]> out = new ArrayList<>();
		int w = Math.min(sourceBox[2] - sourceBox[0], languageBox[2] - languageBox[0]);
		int h = Math.min(sourceBox[3] - sourceBox[1], languageBox[3] - languageBox[1]);
		w = Math.min(w, Math.min(sourceShot.width() - Math.max(0, sourceBox[0]), languageShot.width() - Math.max(0, languageBox[0])));
		h = Math.min(h, Math.min(sourceShot.height() - Math.max(0, sourceBox[1]), languageShot.height() - Math.max(0, languageBox[1])));
		if (w <= 0 || h <= 0 || sourceBox[0] < 0 || sourceBox[1] < 0 || languageBox[0] < 0 || languageBox[1] < 0) {
			return out;
		}
		int[] s = sourceShot.pixels(sourceBox[0], sourceBox[1], w, h);
		int[] t = languageShot.pixels(languageBox[0], languageBox[1], w, h);
		boolean[] inkS = ink(s, w, h);
		boolean[] inkT = ink(t, w, h);
		int n = w * h;
		boolean[] unchanged = new boolean[n];
		boolean[] differs = new boolean[n];
		for (int p = 0; p < n; p++) {
			int d = Ink.distance(s[p], t[p]);
			unchanged[p] = inkS[p] && inkT[p] && d < Ink.SAME;
			differs[p] = inkT[p] && d > Ink.SAME;
		}
		int[] sourceLabels = new int[n];
		List<int[]> sourceParts = label(inkS, w, h, sourceLabels);   // per component x1, y1, x2, y2 (inclusive) and size
		int[] blobLabels = new int[n];
		List<int[]> blobs = label(unchanged, w, h, blobLabels);
		Set<String> done = new LinkedHashSet<>();
		for (int b = 0; b < blobs.size(); b++) {
			if (blobs.get(b)[4] < MIN_BLOB) {
				continue;
			}
			// the source components of the unchanged blob, the whole graphic as the source draws it
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
				continue;   // borders and backgrounds as big as the box, and specks
			}
			if ((y1 <= FRAME_EDGE && y2 >= h - 1 - FRAME_EDGE) || (x1 <= FRAME_EDGE && x2 >= w - 1 - FRAME_EDGE)) {
				continue;   // a side of the frame of the box, which reaches across it, not a graphic inside it
			}
			int[] inSource = { sourceBox[0] + xa, sourceBox[1] + ya, sourceBox[0] + xb, sourceBox[1] + yb };
			if (isAnElement(inSource, elements) || insideOne(inSource, moved)) {
				continue;   // the box comparison sees that one
			}
			if (isALetter(ids, x1, y1, x2, y2, sourceParts)) {
				continue;
			}
			int before = 0, kept = 0, covered = 0, after = 0;
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
					if (inGraphic && differs[p]) {
						covered++;
					}
					if (differs[p]) {
						after++;
					}
				}
			}
			// the part of the graphic the new text covers cannot look the same, the rest must
			if (before > SOURCE_TEXT_SHARE * size || kept < UNCHANGED_SHARE * (size - covered)) {
				continue;
			}
			if (after >= Math.max(MIN_TEXT, TEXT_SHARE * size) && after <= MAX_FILL * area) {
				out.add(new int[] { languageBox[0] + xa, languageBox[1] + ya, languageBox[0] + xb, languageBox[1] + yb, size, after });
			}
		}
		return out;
	}

	/** A letter has others of about its height beside it on its line, a graphic (an arrow, an icon) stands alone. */
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
			long inter = Boxes.overlap(g, b);
			long union = Boxes.area(g) + Boxes.area(b) - inter;
			if (union > 0 && inter >= SAME_ELEMENT * union) {
				return true;
			}
		}
		return false;
	}

	private static boolean insideOne(int[] g, List<int[]> boxes) {
		for (int[] b : boxes) {
			if (Boxes.overlap(g, b) >= 0.5 * Boxes.area(g)) {
				return true;
			}
		}
		return false;
	}

	/** Pixels far from the median colour of their row, per channel, as the background may change along the box. */
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
			int median = median(r) << 16 | median(g) << 8 | median(b);
			for (int x = 0; x < w; x++) {
				out[y * w + x] = Ink.distance(rgb[y * w + x], median) > Ink.INK;
			}
		}
		return out;
	}

	private static int median(int[] values) {
		int[] v = values.clone();
		Arrays.sort(v);
		return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
	}

	/** Connected groups of true pixels (8 neighbours), numbered from 1 in {@code labels}, each as x1, y1, x2, y2 and size. */
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
			int[] p = Boxes.of(nodes.get(parent));
			if (Boxes.area(p) < PARENT_SHARE_OF_SCREEN * screen) {
				return p;
			}
		}
		return Boxes.of(nodes.get(i));
	}

	private static int indexOf(List<int[]> boxes, int[] b) {
		for (int i = 0; i < boxes.size(); i++) {
			if (Arrays.equals(boxes.get(i), b)) {
				return i;
			}
		}
		return -1;
	}

	/** The text of a box nearest the graphic, which the finding is reported on. */
	private static int[] closest(List<int[]> texts, List<AndroidNode> tn, int[] graphic) {
		int[] best = texts.get(0);
		double bestDistance = Double.MAX_VALUE;
		double gx = (graphic[0] + graphic[2]) / 2.0, gy = (graphic[1] + graphic[3]) / 2.0;
		for (int[] t : texts) {
			int[] b = Boxes.of(tn.get(t[1]));
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
