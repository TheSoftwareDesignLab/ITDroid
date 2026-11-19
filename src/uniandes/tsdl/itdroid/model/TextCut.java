package uniandes.tsdl.itdroid.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A translated text cut inside its own box. The box keeps its size and the dump holds the whole
 * text, so only the pixels show it. Each text is measured in both screenshots as the columns of
 * one line of ink, leaving out what the box draws the same in both, such as its outline or the
 * arrow of a drop-down. The width per character of the language is learned from the texts of the
 * same comparison that fit. A text is cut when the width it needs is wider than the room its box
 * gives it, it is drawn clearly narrower than that, and the pixels confirm it. Either its ink
 * reaches the end of the room, cut or ended with an ellipsis, or what is drawn is as wide as the
 * words that fit in one line and the rest went to a line the box hides. Only texts of one line in
 * both screens are judged, in a box that kept its size or grew until the side of the element holding
 * it, which kept its own.
 */
public final class TextCut {

	/** Pixels from the end of its room within which a text reaches it. */
	static final int END_GAP = 4;
	/** A text drawn narrower than this share of the width it needs is not all there. */
	static final double CUT_SHARE = 0.8;
	/** Fewest characters of a text to measure it, as two or three are a number or a unit. */
	static final int MIN_CHARS = 3;
	/** Fewest texts that fit from which the width per character of a language is learned. */
	static final int MIN_REFERENCES = 3;
	/** The words that fit in one line match what is drawn when the widths differ by less than this share. */
	static final double PREFIX_MATCH = 0.15;
	/** Pixels a box may grow and still be the box of the source. */
	static final int MAX_GROWTH = 2;
	/** Empty rows that separate two lines of text, more than the gap of the dots of Arabic letters. */
	static final int LINE_GAP = 3;
	/** Fewest empty columns between two runs of ink, wider than the gap between two letters. */
	static final int MIN_RUN_GAP = 3;
	/** Share of the height of the text that an empty stretch must reach to end a run, a space or more. */
	static final double RUN_GAP_SHARE = 0.25;
	/** Share of a row near the background of the row above for it to go on being the background. */
	static final double ROW_SHARE = 0.05;
	/** Largest difference of a channel between the background of a row and that of the row above, as a gradient changes. */
	static final int TRACK = 16;
	/** Share of the width of a box a row must have in ink to be part of a line of text, more than an outline at its sides. */
	static final double TEXT_ROW_SHARE = 0.02;
	/** An outline whose columns the other screen draws with this share of difference in ink or less is a graphic of the box. */
	static final double TWIN_SHARE = 0.15;
	/** Columns of a thin line, and the share of the height of the box it reaches, the outline of the box. */
	static final int OUTLINE = 8;
	static final double OUTLINE_HEIGHT = 0.8;
	/** Share of the ink of a run that the other screen must draw at the same pixels for the run to be a graphic of the box. */
	static final double TWIN_PIXELS = 0.9;
	/** The other screen must have about as much ink in those columns, within this share. */
	static final double TWIN_INK = 0.25;
	/** Columns the ends of a run may differ by in the other screen and still be the same run. */
	static final int TWIN_SHIFT = 2;
	/** Share of the height of the text within which two runs are words of one text, more apart they are a text and a graphic. */
	static final double CLUSTER_GAP_SHARE = 1.0;

	/** One text of a source screen and of its translated screen, as drawn. */
	static final class Sample {
		int stateId;
		int sourceNode;
		int languageNode;
		int sourceChars;
		int languageChars;
		String languageText;
		/** Width of the drawn text in each screen. */
		int sourceWidth;
		int languageWidth;
		/** Pixels from the end of the drawn text to the end of its room in each screen. */
		int sourceGap;
		int languageGap;
		/** Width from where the translated text starts to the end of its room. */
		int room;
		boolean sourceOneLine;
		boolean languageOneLine;
		/** The translated box is no wider than the source one, nor taller by a line. */
		boolean keptBox;
		/** The box was laid out again for the translated text, which then fits it. */
		boolean boxAdapted;
		/** The box grew up to the side of the element holding it, which kept its size, so it has no more room. */
		boolean bounded;

		/** A text that fits in both screens, from which the width per character is learned. */
		boolean reference() {
			return sourceOneLine && languageOneLine && sourceChars >= MIN_CHARS && languageChars >= MIN_CHARS && !bounded
					&& (boxAdapted || (sourceGap > END_GAP && languageGap > END_GAP));
		}

		/** Width of one character of the translated text, given the language's width per source character. */
		double charWidth(double ratio) {
			return ratio * sourceWidth / sourceChars;
		}
	}

	/** A text cut inside its box. */
	public static final class Hit {
		public final int stateId;
		public final int sourceNode;
		public final int languageNode;
		/** Pixels the whole text needs, pixels it is drawn in, and pixels its box gives it. */
		public final int needed;
		public final int drawn;
		public final int room;

		Hit(int stateId, int sourceNode, int languageNode, int needed, int drawn, int room) {
			this.stateId = stateId;
			this.sourceNode = sourceNode;
			this.languageNode = languageNode;
			this.needed = needed;
			this.drawn = drawn;
			this.room = room;
		}
	}

	/** The ink of a box, as runs of columns, and once the graphics of the box are known, its text. */
	static final class Drawn {
		int width;
		int height;
		boolean[] on;
		/** Pixels of ink of each column. */
		int[] column;
		/** Rows from the first to the last of the text. */
		int textHeight;
		/** Runs of columns with ink, each as first and last column from the left of the box, and its pixels of ink. */
		List<int[]> runs = new ArrayList<>();
		/** Columns of the text, the runs that are graphics of the box, and whether the text is one line. */
		int start = -1;
		int end = -1;
		List<int[]> others = new ArrayList<>();
		boolean oneLine;

		int textWidth() {
			return end - start + 1;
		}
	}

	private TextCut() {
	}

	/** The texts of one pair of screens, both screenshots needed. */
	static List<Sample> measure(int stateId, State source, Ink sourceInk, State language, Ink languageInk, NodePairing pairing,
			boolean sourceRtl, boolean languageRtl) {
		List<Sample> out = new ArrayList<>();
		if (!sourceInk.available() || !languageInk.available()) {
			return out;
		}
		List<AndroidNode> sn = source.getStateNodes();
		List<AndroidNode> ln = language.getStateNodes();
		for (int i = 0; i < sn.size(); i++) {
			int k = pairing.targetOfSource[i];
			if (k < 0) {
				continue;
			}
			AndroidNode s = sn.get(i), l = ln.get(k);
			String st = s.getText().trim(), lt = l.getText().trim();
			if (st.isEmpty() || lt.isEmpty() || st.equals(lt) || s.getpClass().contains("EditText")) {
				continue;
			}
			// only a text of the APK is a translation, a name from the network may differ between two captures
			if (!ScreenTexts.isAppString(st)) {
				continue;
			}
			int[] sBox = Boxes.of(s), lBox = Boxes.of(l);
			Drawn ds = drawn(sourceInk, sBox), dl = drawn(languageInk, lBox);
			if (ds == null || dl == null) {
				continue;
			}
			int sw = sBox[2] - sBox[0], lw = lBox[2] - lBox[0];
			// what the box draws in both at the same place, its outline or an arrow, when it kept its width
			boolean sameWidth = Math.abs(lw - sw) <= MAX_GROWTH;
			if (!settle(ds, sameWidth ? dl : null) || !settle(dl, sameWidth ? ds : null)) {
				continue;
			}
			// a box that grew until the side of the one holding it has no more room, its text may be cut there
			boolean bounded = lw > sw + MAX_GROWTH && endsAtHolder(language, k, languageRtl) && !endsAtHolder(source, i, sourceRtl)
					&& Math.abs(width(language, l.getParentPosition()) - width(source, s.getParentPosition())) <= MAX_GROWTH;
			Sample m = new Sample();
			m.stateId = stateId;
			m.sourceNode = i;
			m.languageNode = k;
			m.sourceChars = chars(st);
			m.languageChars = chars(lt);
			m.languageText = lt;
			m.sourceWidth = ds.textWidth();
			m.languageWidth = dl.textWidth();
			m.sourceOneLine = ds.oneLine;
			m.languageOneLine = dl.oneLine;
			m.sourceGap = gap(ds, sw, sourceRtl);
			m.languageGap = gap(dl, lw, languageRtl);
			m.room = languageRtl ? dl.end - roomEnd(dl, lw, true) + 1 : roomEnd(dl, lw, false) - dl.start;
			m.keptBox = lw <= sw + MAX_GROWTH || bounded;
			m.boxAdapted = !sameWidth && !bounded;
			m.bounded = bounded;
			out.add(m);
		}
		return out;
	}

	/** Whether the box of an element ends at the side of the one holding it where its text ends. */
	private static boolean endsAtHolder(State state, int node, boolean rtl) {
		List<AndroidNode> nodes = state.getStateNodes();
		int parent = nodes.get(node).getParentPosition();
		if (parent < 0) {
			return false;
		}
		int[] box = Boxes.of(nodes.get(node)), held = Boxes.of(nodes.get(parent));
		return rtl ? box[0] - held[0] <= END_GAP : held[2] - box[2] <= END_GAP;
	}

	/** Width of the box of an element, -1 for none. */
	private static int width(State state, int node) {
		return node < 0 ? -1 : state.getStateNodes().get(node).getWidth();
	}

	/** Median of the width per character of the language over that of the source, among the texts that fit. NaN with too few. */
	static double ratio(List<Sample> samples) {
		List<Double> r = new ArrayList<>();
		for (Sample m : samples) {
			if (m.reference() && m.sourceWidth > 0 && m.languageWidth > 0) {
				double v = ((double) m.languageWidth / m.languageChars) / ((double) m.sourceWidth / m.sourceChars);
				if (v > 0.2 && v < 5) {
					r.add(v);
				}
			}
		}
		if (r.size() < MIN_REFERENCES) {
			return Double.NaN;
		}
		r.sort(null);
		int n = r.size();
		return n % 2 == 1 ? r.get(n / 2) : (r.get(n / 2 - 1) + r.get(n / 2)) / 2;
	}

	/** The cut texts among the samples, given the width per character of the language. */
	static List<Hit> cuts(List<Sample> samples, double ratio) {
		List<Hit> out = new ArrayList<>();
		if (Double.isNaN(ratio)) {
			return out;
		}
		for (Sample m : samples) {
			// a box that kept its size had room left in the source, one bounded by its holder fitted its text
			if (!m.keptBox || !m.sourceOneLine || !m.languageOneLine || (m.sourceGap <= END_GAP && !m.bounded)
					|| m.sourceChars < MIN_CHARS || m.languageChars < MIN_CHARS) {
				continue;
			}
			double perChar = m.charWidth(ratio);
			double needed = perChar * m.languageChars;
			if (needed <= m.room + END_GAP || m.languageWidth >= CUT_SHARE * needed) {
				continue;
			}
			boolean reachesTheEnd = m.languageGap <= Math.max(END_GAP, perChar);
			if (reachesTheEnd || wordsThatFit(m, perChar)) {
				out.add(new Hit(m.stateId, m.sourceNode, m.languageNode, (int) Math.round(needed), m.languageWidth, m.room));
			}
		}
		return out;
	}

	/** True when the drawn text is as wide as the words that fit in its room, the rest being on a line out of view. */
	private static boolean wordsThatFit(Sample m, double perChar) {
		String[] words = m.languageText.split("\\s+");
		if (words.length < 2) {
			return false;
		}
		String prefix = "";
		double fits = -1;
		for (int w = 0; w < words.length - 1; w++) {
			prefix = w == 0 ? words[0] : prefix + " " + words[w];
			double width = perChar * chars(prefix);
			if (width > m.room) {
				break;
			}
			fits = width;
		}
		return fits > 0 && Math.abs(m.languageWidth - fits) <= PREFIX_MATCH * fits;
	}

	/** Characters that take room, leaving out marks drawn over another letter and invisible format marks. */
	static int chars(String text) {
		int n = 0;
		for (int i = 0; i < text.length();) {
			int c = text.codePointAt(i);
			int type = Character.getType(c);
			if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.FORMAT) {
				n++;
			}
			i += Character.charCount(c);
		}
		return n;
	}

	/** Pixels from the end of the drawn text to the end of its room, the left one for a text written right to left. */
	private static int gap(Drawn d, int width, boolean rtl) {
		return rtl ? d.start - roomEnd(d, width, true) : roomEnd(d, width, false) - 1 - d.end;
	}

	/** Where the room of the text ends, at the first graphic of the box past it or at the side of the box. */
	private static int roomEnd(Drawn d, int width, boolean rtl) {
		int end = rtl ? 0 : width;
		for (int[] r : d.others) {
			if (!rtl && r[0] > d.end) {
				end = Math.min(end, r[0]);
			}
			if (rtl && r[1] < d.start) {
				end = Math.max(end, r[1] + 1);
			}
		}
		return end;
	}

	/**
	 * Splits the runs of a box into its graphics, the runs whose columns the other screen draws the
	 * same, and its text, from the first of the rest to the last. Without the other screen every run
	 * is text. False when nothing is left for the text.
	 */
	static boolean settle(Drawn d, Drawn other) {
		d.start = -1;
		d.end = -1;
		d.others.clear();
		// the runs that are not graphics of the box, in groups a word apart at most, the widest being the text
		List<int[]> groups = new ArrayList<>();
		int clusterGap = Math.max(MIN_RUN_GAP, (int) (CLUSTER_GAP_SHARE * d.textHeight));
		for (int[] r : d.runs) {
			if (other != null && hasTwin(d, r, other)) {
				d.others.add(r);
				continue;
			}
			int[] last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
			if (last != null && r[0] - last[1] <= clusterGap) {
				last[1] = r[1];
			} else {
				groups.add(new int[] { r[0], r[1] });
			}
		}
		if (groups.isEmpty()) {
			return false;
		}
		int[] text = groups.get(0);
		for (int[] g : groups) {
			if (g[1] - g[0] > text[1] - text[0]) {
				text = g;
			}
		}
		for (int[] g : groups) {
			if (g != text) {
				d.others.add(g);
			}
		}
		d.start = text[0];
		d.end = text[1];
		// lines of the text, apart when more than a few rows are empty
		int lines = 0, previous = -LINE_GAP - 2;
		for (int y = 0; y < d.height; y++) {
			boolean row = false;
			for (int x = d.start; x <= d.end && !row; x++) {
				row = d.on[y * d.width + x];
			}
			if (row) {
				if (y - previous > LINE_GAP + 1) {
					lines++;
				}
				previous = y;
			}
		}
		d.oneLine = lines == 1;
		return true;
	}

	/**
	 * Whether the other screen draws the same run, from the same column to the same column. A thin
	 * line across the box, its outline, is as tall as each box, so its ink per column is compared as
	 * a share of the height. Else the same pixels must be ink in the other screen, with the box moved
	 * up or down by as much as the heights differ, as the arrow of a drop-down whose text box grew.
	 * The first word of a text that the translation starts with too is no run of the same columns.
	 */
	private static boolean hasTwin(Drawn self, int[] run, Drawn other) {
		boolean sameColumns = false;
		for (int[] r : other.runs) {
			sameColumns |= Math.abs(r[0] - run[0]) <= TWIN_SHIFT && Math.abs(r[1] - run[1]) <= TWIN_SHIFT;
		}
		if (!sameColumns) {
			return false;
		}
		int mine = 0, theirs = 0, tall = 0;
		for (int x = run[0]; x <= run[1]; x++) {
			mine += self.column[x];
			theirs += x < other.width ? other.column[x] : 0;
			tall = Math.max(tall, self.column[x]);
		}
		if (mine == 0) {
			return false;
		}
		if (run[1] - run[0] < OUTLINE && tall >= OUTLINE_HEIGHT * self.height) {
			double differs = 0;
			for (int x = run[0]; x <= run[1]; x++) {
				double theirShare = x < other.width ? (double) other.column[x] / other.height : 0;
				differs += Math.abs((double) self.column[x] / self.height - theirShare);
			}
			return differs <= TWIN_SHARE * mine / self.height;
		}
		if (Math.abs(theirs - mine) > TWIN_INK * mine) {
			return false;
		}
		int reach = Math.abs(self.height - other.height) + 2, best = 0;
		for (int dy = -reach; dy <= reach; dy++) {
			int same = 0;
			for (int y = 0; y < self.height; y++) {
				int oy = y + dy;
				if (oy < 0 || oy >= other.height) {
					continue;
				}
				for (int x = run[0]; x <= Math.min(run[1], other.width - 1); x++) {
					if (self.on[y * self.width + x] && other.on[oy * other.width + x]) {
						same++;
					}
				}
			}
			best = Math.max(best, same);
		}
		return best >= TWIN_PIXELS * mine;
	}

	/**
	 * The ink of a box as runs of columns, where ink is what differs from the background of its row.
	 * Null when the box shows nothing or is not all on the screenshot.
	 */
	static Drawn drawn(Ink ink, int[] box) {
		int[] b = ink.clip(box);
		if (b == null || !Boxes.same(b, box) || b[2] - b[0] < 4 || b[3] - b[1] < 4) {
			return null;
		}
		int w = b[2] - b[0], h = b[3] - b[1];
		boolean[] on = new boolean[w * h];
		int[] column = new int[w];
		int top = -1, bottom = -1;
		int background = -1;
		for (int y = 0; y < h; y++) {
			background = rowBackground(ink, b, y, background);
			int n = 0;
			for (int x = 0; x < w; x++) {
				if (Ink.distance(ink.at(b[0] + x, b[1] + y), background) > Ink.INK) {
					on[y * w + x] = true;
					column[x]++;
					n++;
				}
			}
			// the rows of the text, not those with only the outline of the box
			if (n >= Math.max(3, TEXT_ROW_SHARE * w)) {
				top = top < 0 ? y : top;
				bottom = y;
			}
		}
		if (top < 0) {
			return null;
		}
		Drawn d = new Drawn();
		d.width = w;
		d.height = h;
		d.on = on;
		d.column = column;
		d.textHeight = bottom - top + 1;
		int runGap = Math.max(MIN_RUN_GAP, (int) (RUN_GAP_SHARE * (bottom - top + 1)));
		int start = -1, last = -1, pixels = 0;
		for (int x = 0; x < w; x++) {
			if (column[x] == 0) {
				continue;
			}
			if (start >= 0 && x - last > runGap) {
				d.runs.add(new int[] { start, last, pixels });
				start = -1;
				pixels = 0;
			}
			if (start < 0) {
				start = x;
			}
			last = x;
			pixels += column[x];
		}
		d.runs.add(new int[] { start, last, pixels });
		return d;
	}

	/**
	 * The background of one row of a box, followed down from the row above. When a share of the row
	 * is near the background above, their mean, so a gradient is followed and a bold text that covers
	 * most of a row is not taken for it. Else the colour most of the row has, as below the outline of
	 * a button.
	 */
	private static int rowBackground(Ink ink, int[] b, int y, int above) {
		int w = b[2] - b[0];
		if (above >= 0) {
			int n = 0;
			long r = 0, g = 0, bl = 0;
			for (int x = b[0]; x < b[2]; x++) {
				int c = ink.at(x, b[1] + y);
				if (Ink.distance(c, above) <= TRACK) {
					n++;
					r += (c >> 16) & 0xff;
					g += (c >> 8) & 0xff;
					bl += c & 0xff;
				}
			}
			if (n >= ROW_SHARE * w) {
				return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
			}
		}
		Map<Integer, int[]> counts = new HashMap<>();
		int[] mode = null;
		for (int x = b[0]; x < b[2]; x++) {
			int c = ink.at(x, b[1] + y);
			int[] v = counts.computeIfAbsent(Ink.key(c), k -> new int[] { 0, c });
			v[0]++;
			if (mode == null || v[0] > mode[0]) {
				mode = v;
			}
		}
		return mode == null ? above : mode[1];
	}

	/** For the tests and the tuning, the runs of a drawn text. */
	static String describe(Drawn d) {
		return d == null ? "null" : "text " + d.start + ".." + d.end + " others " + Arrays.deepToString(d.others.toArray())
				+ " runs " + Arrays.deepToString(d.runs.toArray()) + " oneLine " + d.oneLine;
	}
}
