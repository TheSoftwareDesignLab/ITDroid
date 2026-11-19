package uniandes.tsdl.itdroid.model;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import uniandes.tsdl.itdroid.helper.Helper;

/**
 * Compares the source screens with the same screens in another language and reports the
 * internationalization presentation failures (IPFs), elements whose relations to the others changed
 * or that only one screen has. Elements are paired by {@link NodePairing}. Alignment changes next to
 * a text are ignored, and between opposite writing directions the siblings that did not swap are reported.
 * A relation that an edge moving less than a hundredth of the screen would restore did not change.
 * The screenshots decide what is seen. An overlap counts when the two drawings meet, an element that
 * draws nothing has no finding of its own, nested elements with one box are one element, and a
 * change between two elements is reported on the one that moved or changed size.
 */
public class LayoutGraphComparision {

	/** What happened to an element, in the taxonomy of the paper. */
	public enum Change {
		POSITION_LOST("positionLost"),
		POSITION_ADDED("positionAdded"),
		ALIGNMENT_LOST("alignmentLost"),
		ALIGNMENT_ADDED("alignmentAdded"),
		OVERLAP_ADDED("overlapAdded"),
		OVERLAP_REMOVED("overlapRemoved"),
		MISSING_MIRRORING("missingMirroring"),
		COMPONENT_LOST("componentLost"),
		COMPONENT_ADDED("componentAdded"),
		/** The text of the element is drawn over a graphic that is not an element (see {@link TextOverGraphic}). */
		TEXT_OVER_GRAPHIC("textOverGraphic"),
		/** The element reaches past the left or right side of its window, or a window such as a menu past the side of the app. */
		OUT_OF_FRAME("outOfFrame"),
		/** The text of the element takes more height, so it wraps into more lines. */
		LINES_ADDED("linesAdded"),
		/** The text of the element takes less height, so it fits in fewer lines. */
		LINES_REMOVED("linesRemoved"),
		/** The element is cut by the bottom of the scrolling area it is in and the translated screen shows more of it. */
		MORE_ON_SCREEN("moreOnScreen"),
		/** The translated screen shows less of the element, past the bottom of its scrolling area or under an element drawn over it. */
		LESS_ON_SCREEN("lessOnScreen"),
		/** What the element draws reaches out of an element that holds it, or it is cut by its side, and was not in the source. */
		OUT_OF_CONTAINER("outOfContainer"),
		/** The translated text does not fit in its box, which kept its size, and part of it is not drawn (see {@link TextCut}). */
		TEXT_CUT("textCut");

		/** Changes that break the screen rather than rearrange it. */
		boolean severe() {
			return this == TEXT_OVER_GRAPHIC || this == OVERLAP_ADDED || this == OUT_OF_FRAME || this == OUT_OF_CONTAINER || this == TEXT_CUT;
		}

		/** Changes of the element alone, each counted once in the score, the others count the elements they involve. */
		boolean alone() {
			return this == OUT_OF_FRAME || this == LINES_ADDED || this == LINES_REMOVED || this == MORE_ON_SCREEN
					|| this == LESS_ON_SCREEN || this == TEXT_CUT;
		}

		/** Name used in report.json. */
		public final String key;

		Change(String key) {
			this.key = key;
		}
	}

	/** An element with relations lost or gained (or not mirrored). */
	public static final String KIND_RELATIONS = "relations";
	/** An element of the source screen that the translated screen does not have. */
	public static final String KIND_LOST = "componentLost";
	/** An element of the translated screen that the source screen does not have. */
	public static final String KIND_ADDED = "componentAdded";
	/** An element that is where it was and whose text is now drawn over a graphic of the app. */
	public static final String KIND_TEXT_OVER_GRAPHIC = "textOverGraphic";
	/** An element that is where it was and whose translated text is cut inside its box. */
	public static final String KIND_TEXT_CUT = "textCut";

	/** Whether the screenshots are searched for text over a graphic, config key {@code comparison.textOverGraphic}. */
	private static boolean textOverGraphic = true;

	/** The two horizontal positions, compared only between elements of one row. */
	private static final Set<GraphEdgeType> SIDES = EnumSet.of(GraphEdgeType.LEFT, GraphEdgeType.RIGHT);
	/** Where one element is with respect to another, apart from alignments. */
	private static final Set<GraphEdgeType> PLACES = EnumSet.of(GraphEdgeType.ABOVE, GraphEdgeType.BELOW, GraphEdgeType.LEFT, GraphEdgeType.RIGHT);
	/** The relations of two boxes that share area. */
	private static final Set<GraphEdgeType> OVERLAPS = EnumSet.of(GraphEdgeType.CONTAINS, GraphEdgeType.CONTAINED, GraphEdgeType.INTERSECTS);
	/** The relations of boxes, which say nothing between an element and one that holds it. */
	private static final Set<GraphEdgeType> BOX_RELATIONS = EnumSet.of(GraphEdgeType.ABOVE, GraphEdgeType.BELOW, GraphEdgeType.LEFT,
			GraphEdgeType.RIGHT, GraphEdgeType.CONTAINS, GraphEdgeType.CONTAINED, GraphEdgeType.INTERSECTS);

	/** Fewest pixels an element may pass the side of its frame, or of an element holding it, and still be inside. */
	static final int FRAME_SLACK = 6;

	/**
	 * Height an element must gain or lose to count, as a share of the height of the screen and of its
	 * own height. A line of text is two to three hundredths of the screen, while a script with taller
	 * letters (Arabic, Devanagari) adds less than one.
	 */
	static final double MIN_HEIGHT_CHANGE = 0.01;
	static final double MIN_HEIGHT_SHARE = 0.2;

	/**
	 * Share of the screen an edge may move with nothing changed, the same as {@link #MIN_HEIGHT_CHANGE}.
	 * Of the width it is how far an element may pass the side of its frame or of the one holding it,
	 * about 4 dp on a phone and never less than {@link #FRAME_SLACK}. An overlap must go deeper than
	 * that past the {@link State#SLACK} sliver. A relation changes when an edge moves more than that,
	 * or across the screen more than this share of its height. An icon a wider label makes a little
	 * shorter or an edge the layout rounds another way changes nothing.
	 */
	static final double MIN_EDGE_SHIFT = MIN_HEIGHT_CHANGE;

	/** Pixels from the bottom of a scrolling area within which an element counts as cut by it. */
	static final int CUT_SLACK = 3;

	/**
	 * Fewest pixels where the drawings of two elements meet for their overlap to be seen. A few
	 * strokes of a letter over an icon are more, the anti-aliased edge of a box next to another is less.
	 */
	static final int MIN_OVERLAP_PIXELS = 20;

	/** Fewest pixels drawn past the side of an element holding it for an element to be out of it. */
	static final int MIN_OUTSIDE_PIXELS = 20;

	/** Pixels of the sides of a box left out when looking at what it draws, the width of a border. */
	static final int BORDER = 3;

	/** The sides of a box in the order of its corners, as a cut relation names them. */
	private static final String[] SIDE_NAMES = { "left", "top", "right", "bottom" };

	/** Whether the screenshots are searched for texts cut inside their box, config key {@code comparison.textCut}. */
	private static boolean textCut = true;

	public static void setTextCut(boolean on) {
		textCut = on;
	}

	public static boolean isTextCut() {
		return textCut;
	}

	public static void setTextOverGraphic(boolean on) {
		textOverGraphic = on;
	}

	public static boolean isTextOverGraphic() {
		return textOverGraphic;
	}

	/**
	 * Share of the elements of the larger screen that must pair for a replayed screen to be the same
	 * screen. Below it the app showed something else there. Config key {@code comparison.minSimilarity}.
	 */
	public static final double DEFAULT_MIN_SIMILARITY = 0.5;

	private static double minSimilarity = DEFAULT_MIN_SIMILARITY;

	/** Sets the share of paired elements, from 0 to 1, that makes two screens the same screen. */
	public static void setMinSimilarity(double share) {
		if (!(share >= 0 && share <= 1)) {
			throw new IllegalArgumentException("The minimum similarity of two screens must be between 0 and 1, got " + share + ".");
		}
		minSimilarity = share;
	}

	public static double getMinSimilarity() {
		return minSimilarity;
	}

	/** One element of a screen with something to report. */
	private static final class Finding {
		final int stateId;
		/** Position of the element in the source screen, -1 when only the translated screen has it. */
		int sourceNode;
		/** Position of the element in the translated screen, -1 when only the source screen has it. */
		int languageNode;
		String kind;
		final EnumSet<Change> changes = EnumSet.noneOf(Change.class);
		final List<JSONObject> relations = new ArrayList<>();
		String geometry = "";
		/** Elements it changed against and changes of its own, or for a lost or added component the elements it is made of. */
		int score;
		/** Graphics its text is drawn over in the translated screen, each as x1, y1, x2, y2. */
		final List<int[]> graphics = new ArrayList<>();
		/** Pixels of height it gained (or lost, negative) when that is the change. */
		int heightChange;
		/** For a cut text, the pixels its whole text needs and the pixels it is drawn in. */
		int neededWidth;
		int drawnWidth;

		boolean severe() {
			return changes.stream().anyMatch(Change::severe);
		}

		Finding(int stateId, int sourceNode, int languageNode, String kind) {
			this.stateId = stateId;
			this.sourceNode = sourceNode;
			this.languageNode = languageNode;
			this.kind = kind;
		}

		/** The score of a finding of a paired element, from what it carries. */
		void rescore() {
			Set<Object> others = new HashSet<>();
			for (JSONObject r : relations) {
				if (r.get("moved") == null && r.get("under") == null) {
					others.add(r.get("relNode"));
				}
			}
			int alone = 0;
			for (Change c : changes) {
				alone += c.alone() ? 1 : 0;
			}
			score = others.size() + alone + graphics.size();
		}
	}

	public String defltLanguage;
	public LayoutGraph defltLayoutGraph;
	public String destLanguage;
	public LayoutGraph destLangLayoutGraph;
	/** Source graph list index to language graph list index of the states compared with each other. */
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();

	private final Map<Integer, NodePairing> nodePairings = new HashMap<>();
	/** True when one language is written left to right and the other right to left. */
	private final boolean mirrorExpected;
	/** True when the source language is written right to left. */
	private final boolean sourceRtl;
	/** True when the target language is written right to left. */
	private final boolean languageRtl;
	/**
	 * Device screen width, the furthest right any element of either exploration reaches, since the
	 * dump of a dialog only reaches the edges of the dialog.
	 */
	private final int screenWidth;
	/** Pixels an element may pass the side of the one holding it, see {@link #MIN_EDGE_SHIFT}. */
	private final int insideSlack;
	/** Pixels one element may reach into another and not be over it. */
	private final int overlapSlack;
	/** Pixels an edge may move along each axis with its relations kept. */
	private final int slackX;
	private final int slackY;
	/** Left and right side of the app window, the widest top-level elements of the source screens. */
	private final int appLeft;
	private final int appRight;
	private final List<Finding> findings = new ArrayList<>();
	private long nodesCompared;
	private long siblingPairsChecked;
	private long siblingPairsNotMirrored;

	/**
	 * @param deftLanguage code of the source language
	 * @param lang name of the target language, as it is shown in the reports
	 * @param rawLang code of the target language
	 * @param dfltLangJSONTrans the entry of this language in report.json, which this fills in
	 */
	@SuppressWarnings("unchecked")
	public LayoutGraphComparision(String deftLanguage, LayoutGraph defltGraph, String lang, String rawLang, LayoutGraph langGraph, JSONObject dfltLangJSONTrans) {

		defltLanguage = deftLanguage;
		defltLayoutGraph = defltGraph;
		destLanguage = lang;
		destLangLayoutGraph = langGraph;
		sourceRtl = Helper.languageIsRTL(deftLanguage);
		languageRtl = Helper.languageIsRTL(rawLang);
		mirrorExpected = sourceRtl != languageRtl;
		screenWidth = Math.max(widthOf(defltGraph), widthOf(langGraph));
		int screenHeight = Math.max(heightOf(defltGraph), heightOf(langGraph));
		insideSlack = Math.max(FRAME_SLACK, (int) Math.round(MIN_EDGE_SHIFT * screenWidth));
		overlapSlack = insideSlack + State.SLACK;
		slackX = overlapSlack;
		slackY = Math.max(overlapSlack, (int) Math.round(MIN_EDGE_SHIFT * screenHeight));
		int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
		for (State state : defltGraph.getStates()) {
			for (AndroidNode n : state.getStateNodes()) {
				if (n.getParentPosition() < 0) {
					left = Math.min(left, n.getPoint1()[0]);
					right = Math.max(right, n.getPoint2()[0]);
				}
			}
		}
		appLeft = left;
		appRight = right;

		pairStates(dfltLangJSONTrans);

		List<Integer> dfltIndexes = new ArrayList<>(statePairing.keySet());
		java.util.Collections.sort(dfltIndexes);
		ScreenTexts texts = new ScreenTexts();
		List<TextCut.Sample> textSamples = new ArrayList<>();
		for (int defltState : dfltIndexes) {
			State source = defltLayoutGraph.getState(defltState);
			State language = destLangLayoutGraph.getState(statePairing.get(defltState));
			NodePairing pairing = nodePairings.get(defltState);
			if (pairing == null) {
				pairing = NodePairing.of(source, language, mirrorExpected, screenWidth);
			}
			DrawnScreen s = new DrawnScreen(source, screenshot(defltLayoutGraph, source));
			DrawnScreen l = new DrawnScreen(language, screenshot(destLangLayoutGraph, language));
			Map<Integer, Finding> byNode = compareStates(source, language, pairing, s, l);
			if (textOverGraphic) {
				addTextOverGraphic(byNode, source, s.ink, language, l.ink, pairing);
			}
			findings.addAll(finish(byNode, language.getId(), pairing, s, l));
			if (textCut) {
				textSamples.addAll(TextCut.measure(language.getId(), source, s.ink, language, l.ink, pairing, sourceRtl, languageRtl));
			}
			texts.add(source, language);
		}
		addTextCuts(textSamples);
		// When the screens show the source text, finding nothing says nothing about how a translation fits.
		dfltLangJSONTrans.put("screenText", texts.toJson());
		if (texts.sameAsSource()) {
			System.out.println("WARNING: the "+destLanguage+" version shows the same text as the source on the screens compared ("
					+texts.unchanged()+" of "+texts.sourceTexts()+" texts): the language did not reach those screens, or they are mostly data."
					+" Finding nothing there does not mean a translation fits.");
		}
		if (!texts.notInApk().isEmpty()) {
			System.out.println("NOTE: "+texts.notInApk().size()+" of the "+texts.unchanged()+" texts that stayed the same on the "+destLanguage
					+" screens are not in the APK: they come from the network, the device or the app's data (ads, lists, dates), and ITDroid does not translate them. For example: "
					+String.join(" | ", new ArrayList<>(texts.notInApk()).subList(0, Math.min(ScreenTexts.EXAMPLES, texts.notInApk().size()))));
		}

		// Most affected first, then by screen and element, so the same data always gives the same file.
		findings.sort((a, b) -> {
			if (a.score != b.score) {
				return Integer.compare(b.score, a.score);
			}
			if (a.stateId != b.stateId) {
				return Integer.compare(a.stateId, b.stateId);
			}
			if (a.sourceNode != b.sourceNode) {
				return Integer.compare(a.sourceNode, b.sourceNode);
			}
			return Integer.compare(a.languageNode, b.languageNode);
		});

		dfltLangJSONTrans.put("amIPFs", findings.size());
		dfltLangJSONTrans.put("ipfSummary", summary());
		if (findings.isEmpty()) {
			System.out.println("There are not Internationalization Presentation Failures for "+destLanguage+" app version.");
			return;
		}
		JSONArray ipfs = new JSONArray();
		for (Finding f : findings) {
			JSONObject o = new JSONObject();
			o.put("stateID", f.stateId);
			o.put("nodeID", f.sourceNode);
			o.put("langNodeID", f.languageNode);
			o.put("kind", f.kind);
			o.put("severity", f.severe() ? "severe" : "moderate");
			JSONArray types = new JSONArray();
			for (Change c : f.changes) {
				types.add(c.key);
			}
			o.put("types", types);
			if (!f.geometry.isEmpty()) {
				o.put("geometry", f.geometry);
			}
			if (f.heightChange != 0) {
				o.put("heightChange", f.heightChange);
			}
			if (f.neededWidth > 0) {
				o.put("neededWidth", f.neededWidth);
				o.put("drawnWidth", f.drawnWidth);
			}
			o.put("score", f.score);
			JSONArray relations = new JSONArray();
			relations.addAll(f.relations);
			o.put("relations", relations);
			if (!f.graphics.isEmpty()) {
				JSONArray graphics = new JSONArray();
				for (int[] g : f.graphics) {
					JSONArray rect = new JSONArray();
					for (int v : g) {
						rect.add((long) v);
					}
					graphics.add(rect);
				}
				o.put("graphics", graphics);
			}
			ipfs.add(o);
		}
		dfltLangJSONTrans.put("ipfs", ipfs);

		System.out.println("There are "+findings.size()+" Internationalization Presentation Failures for "+destLanguage+" app version.");
	}

	/** Counts of the findings of this language by kind and by change. */
	@SuppressWarnings("unchecked")
	private JSONObject summary() {
		long changed = 0;
		long affected = 0;
		long lost = 0;
		long added = 0;
		long overGraphic = 0;
		long cut = 0;
		Map<Change, Long> byType = new EnumMap<>(Change.class);
		for (Change c : Change.values()) {
			byType.put(c, 0L);
		}
		for (Finding f : findings) {
			for (Change c : f.changes) {
				byType.merge(c, 1L, Long::sum);
			}
			if (KIND_LOST.equals(f.kind)) {
				lost++;
			} else if (KIND_ADDED.equals(f.kind)) {
				added++;
			} else if (KIND_TEXT_OVER_GRAPHIC.equals(f.kind)) {
				overGraphic++;
			} else if (KIND_TEXT_CUT.equals(f.kind)) {
				cut++;
			} else if (f.geometry.startsWith("moved") || f.geometry.equals("resized")) {
				changed++;
			} else {
				affected++;
			}
		}
		JSONObject o = new JSONObject();
		o.put("nodesCompared", nodesCompared);
		// the element itself moved or changed size
		o.put("nodesChanged", changed);
		// or it stayed (or mirrored as expected) and something of its own is wrong, such as a missing mirroring
		o.put("nodesAffected", affected);
		o.put("componentsLost", lost);
		o.put("componentsAdded", added);
		// or only its text is drawn over a graphic, or cut in its box (one that also moved counts above)
		o.put("textOverGraphic", overGraphic);
		o.put("textCut", cut);
		long severe = findings.stream().filter(Finding::severe).count();
		o.put("severe", severe);
		o.put("moderate", findings.size() - severe);
		JSONObject types = new JSONObject();
		for (Map.Entry<Change, Long> e : byType.entrySet()) {
			types.put(e.getKey().key, e.getValue());
		}
		o.put("byType", types);
		o.put("mirrorExpected", mirrorExpected);
		if (mirrorExpected) {
			o.put("siblingPairsChecked", siblingPairsChecked);
			o.put("siblingPairsNotMirrored", siblingPairsNotMirrored);
		}
		return o;
	}

	/** The findings of the paired elements of one screen, by position in the source screen. */
	private Map<Integer, Finding> compareStates(State source, State language, NodePairing pairing, DrawnScreen s, DrawnScreen l) {
		Set<GraphEdgeType>[][] sourceGraph = source.getGraph();
		Set<GraphEdgeType>[][] languageGraph = language.getGraph();
		List<AndroidNode> sourceNodes = source.getStateNodes();
		List<AndroidNode> languageNodes = language.getStateNodes();
		int stateId = language.getId();
		Map<Integer, Finding> byNode = new TreeMap<>();
		Set<GraphEdgeType> alignments = GraphEdgeType.getAligmentTypes();
		Set<GraphEdgeType> mirrored = GraphEdgeType.getRtlChangeExpectedTypes();
		nodesCompared += pairing.paired;
		boolean[] moved = new boolean[sourceNodes.size()];
		for (int i = 0; i < sourceNodes.size(); i++) {
			moved[i] = pairing.targetOfSource[i] >= 0 && movedOrResized(s, l, i, pairing.targetOfSource[i]);
		}
		Boolean[] redrawn = new Boolean[sourceNodes.size()];

		for (int i = 0; i < sourceNodes.size(); i++) {
			int pi = pairing.targetOfSource[i];
			if (pi < 0) {
				continue;
			}
			for (int j = i + 1; j < sourceNodes.size(); j++) {
				int pj = pairing.targetOfSource[j];
				if (pj < 0) {
					continue;
				}
				EnumSet<GraphEdgeType> lostAB = minus(sourceGraph[i][j], languageGraph[pi][pj]);
				EnumSet<GraphEdgeType> lostBA = minus(sourceGraph[j][i], languageGraph[pj][pi]);
				EnumSet<GraphEdgeType> addedAB = minus(languageGraph[pi][pj], sourceGraph[i][j]);
				EnumSet<GraphEdgeType> addedBA = minus(languageGraph[pj][pi], sourceGraph[j][i]);
				List<EnumSet<GraphEdgeType>> all = List.of(lostAB, lostBA, addedAB, addedBA);

				// A relation lost that still nearly holds, or gained that nearly held before, did not change.
				// A shallow overlap neither appears nor goes away.
				AndroidNode si = sourceNodes.get(i), sj = sourceNodes.get(j), li = languageNodes.get(pi), lj = languageNodes.get(pj);
				boolean shallowBefore = shallow(si, sj), shallowNow = shallow(li, lj);
				lostAB.removeIf(e -> nearly(e, li, lj) || (e == GraphEdgeType.INTERSECTS && shallowBefore));
				lostBA.removeIf(e -> nearly(e, lj, li) || (e == GraphEdgeType.INTERSECTS && shallowBefore));
				addedAB.removeIf(e -> e == GraphEdgeType.INTERSECTS ? shallowNow : nearly(e, si, sj));
				addedBA.removeIf(e -> e == GraphEdgeType.INTERSECTS ? shallowNow : nearly(e, sj, si));

				// A translated text changes width, so its alignments are not compared. A layer with the box of a text is that text.
				boolean text = s.isText(i) || s.isText(j) || l.isText(pi) || l.isText(pj);
				if (text) {
					all.forEach(set -> set.removeAll(alignments));
				}
				// Left and right only say something of two elements in one row. Between two rows any text
				// that gets wider or narrower flips them, which is not a change of the layout.
				boolean sameRow = State.sameRow(si, sj) || State.sameRow(li, lj);
				if (!sameRow) {
					all.forEach(set -> set.removeAll(SIDES));
				}
				// The bottom of a box cut by its scrolling area is the bottom of that area, not of the element.
				if (s.cutAtBottom(i, CUT_SLACK) || s.cutAtBottom(j, CUT_SLACK) || l.cutAtBottom(pi, CUT_SLACK) || l.cutAtBottom(pj, CUT_SLACK)) {
					all.forEach(set -> set.remove(GraphEdgeType.BOTTOM_ALIGNED));
				}
				// An element and one that holds it never overlap. Whether it leaves it is judged below by its ink.
				boolean related = s.related(i, j) || l.related(pi, pj);
				// Two edges that meet by chance break nothing, as an arrow a longer text pushes in line with
				// an element of another row. Only reaching the side of an element that holds it counts.
				if (!related) {
					addedAB.removeAll(alignments);
					addedBA.removeAll(alignments);
				}
				if (related) {
					all.forEach(set -> set.removeAll(BOX_RELATIONS));
				} else if (addedAB.contains(GraphEdgeType.INTERSECTS)) {
					int under = scrolledUnder(l, pi, pj);
					boolean seen = under < 0 && (DrawnScreen.textOrImage(li) || DrawnScreen.textOrImage(lj))
							&& overlapIsVisible(s, l, i, pi, j, pj);
					if (!seen) {
						// the boxes overlap where nothing is drawn, or scrolled content passes under a bar
						all.forEach(set -> set.removeAll(OVERLAPS));
						lostAB.removeAll(PLACES);
						lostBA.removeAll(PLACES);
					}
					if (under >= 0) {
						boolean firstUnder = under == pi;
						hiddenUnder(byNode, stateId, s, l, firstUnder ? i : j, under, firstUnder ? j : i, firstUnder ? pj : pi);
					}
				} else if ((addedAB.contains(GraphEdgeType.CONTAINS) || addedAB.contains(GraphEdgeType.CONTAINED))
						&& !overlapIsVisible(s, l, i, pi, j, pj)) {
					// one box now holds the other where neither draws over the other, as a hint and an empty box
					all.forEach(set -> set.removeAll(OVERLAPS));
					lostAB.removeAll(PLACES);
					lostBA.removeAll(PLACES);
				}

				EnumSet<GraphEdgeType> missingAB = EnumSet.noneOf(GraphEdgeType.class);
				EnumSet<GraphEdgeType> missingBA = EnumSet.noneOf(GraphEdgeType.class);
				if (mirrorExpected) {
					all.forEach(set -> set.removeAll(mirrored));
					if (source.nodesAreSiblings(i, j) && !sourceGraph[i][j].contains(GraphEdgeType.CONTAINS)
							&& !sourceGraph[i][j].contains(GraphEdgeType.CONTAINED)) {
						boolean row = State.sameRow(si, sj);
						missingAB = notMirrored(sourceGraph[i][j], languageGraph[pi][pj], text, row, li, lj);
						missingBA = notMirrored(sourceGraph[j][i], languageGraph[pj][pi], text, row, lj, li);
						if (row && (sourceGraph[i][j].contains(GraphEdgeType.LEFT) || sourceGraph[i][j].contains(GraphEdgeType.RIGHT))) {
							siblingPairsChecked++;
							if (missingAB.contains(GraphEdgeType.LEFT) || missingAB.contains(GraphEdgeType.RIGHT)) {
								siblingPairsNotMirrored++;
							}
						}
					}
				}

				if (lostAB.isEmpty() && lostBA.isEmpty() && addedAB.isEmpty() && addedBA.isEmpty()
						&& missingAB.isEmpty() && missingBA.isEmpty()) {
					continue;
				}
				// The change goes to the element that moved or changed size, the other one is only what it
				// relates to. When neither did, to the one whose text is drawn otherwise, and else to both.
				// A missing mirroring is of the pair.
				boolean blameI, blameJ;
				if (moved[i] || moved[j]) {
					blameI = moved[i];
					blameJ = moved[j];
				} else {
					if (redrawn[i] == null) {
						redrawn[i] = redrawn(s, l, i, pi);
					}
					if (redrawn[j] == null) {
						redrawn[j] = redrawn(s, l, j, pj);
					}
					blameI = redrawn[i] || !redrawn[j];
					blameJ = redrawn[j] || !redrawn[i];
				}
				// A box that holds the other one in either screen does not take the change of what moved inside it.
				boolean iHoldsJ = sourceGraph[i][j].contains(GraphEdgeType.CONTAINS) || languageGraph[pi][pj].contains(GraphEdgeType.CONTAINS);
				boolean jHoldsI = sourceGraph[j][i].contains(GraphEdgeType.CONTAINS) || languageGraph[pj][pi].contains(GraphEdgeType.CONTAINS);
				if (!iHoldsJ) {
					record(byNode, stateId, i, pi, j, pj, carried(lostAB, blameI, si, li, sj, lj), carried(addedAB, blameI, si, li, sj, lj), missingAB);
				}
				if (!jHoldsI) {
					record(byNode, stateId, j, pj, i, pi, carried(lostBA, blameJ, sj, lj, si, li), carried(addedBA, blameJ, sj, lj, si, li), missingBA);
				}
			}
		}

		// Elements past the side of the window that holds them, and windows past the side of the app.
		// A menu that grows within the app is not out of it. Only the highest is reported, as a menu
		// wider than the app carries its rows with it.
		boolean[] outside = new boolean[sourceNodes.size()];
		for (int i = 0; i < sourceNodes.size(); i++) {
			int pi = pairing.targetOfSource[i];
			if (pi < 0 || appLeft > appRight) {
				continue;
			}
			outside[i] = inFrame(sourceNodes, i) && !inFrame(languageNodes, pi);
			int parent = sourceNodes.get(i).getParentPosition();
			if (outside[i] && (parent < 0 || !outside[parent])) {
				Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
				f.changes.add(Change.OUT_OF_FRAME);
			}
		}
		outOfContainers(byNode, stateId, pairing, s, l, outside);
		heightChanges(byNode, stateId, sourceNodes, languageNodes, pairing, s, l);
		return byNode;
	}

	/**
	 * The changed relations an element carries. An alignment goes to the element whose edge moved
	 * when only one of the two did, as a chip that a reflow takes to another row and not the rows
	 * below it, which only moved down. Else the element carries what the pair blames on it.
	 */
	private EnumSet<GraphEdgeType> carried(Set<GraphEdgeType> relations, boolean blamed, AndroidNode was, AndroidNode now,
			AndroidNode otherWas, AndroidNode otherNow) {
		EnumSet<GraphEdgeType> out = EnumSet.noneOf(GraphEdgeType.class);
		for (GraphEdgeType e : relations) {
			if (e.isAlignment()) {
				boolean mine = edgeMoved(e, was, now), theirs = edgeMoved(e, otherWas, otherNow);
				if (mine != theirs) {
					if (mine) {
						out.add(e);
					}
					continue;
				}
			}
			if (blamed) {
				out.add(e);
			}
		}
		return out;
	}

	/** Whether the edge of an element that an alignment is about moved beyond the slack of its axis. */
	private boolean edgeMoved(GraphEdgeType e, AndroidNode was, AndroidNode now) {
		switch (e) {
			case LEFT_ALIGNED:
				return Math.abs(now.getPoint1()[0] - was.getPoint1()[0]) > slackX;
			case RIGHT_ALIGNED:
				return Math.abs(now.getPoint2()[0] - was.getPoint2()[0]) > slackX;
			case TOP_ALIGNED:
				return Math.abs(now.getPoint1()[1] - was.getPoint1()[1]) > slackY;
			default:
				return Math.abs(now.getPoint2()[1] - was.getPoint2()[1]) > slackY;
		}
	}

	/** Whether an element moved or changed size beyond the slack. Its mirrored place is no change when the layout is expected to mirror. */
	private boolean movedOrResized(DrawnScreen s, DrawnScreen l, int i, int pi) {
		int[] a = s.box(i), b = l.box(pi);
		return !near(a, b) && !(mirrorExpected && near(new int[] { screenWidth - a[2], a[1], screenWidth - a[0], a[3] }, b));
	}

	/** Whether an element kept its box and its changed text or description is drawn in another place of it. */
	private boolean redrawn(DrawnScreen s, DrawnScreen l, int i, int pi) {
		int[] a = s.box(i), b = l.box(pi);
		AndroidNode sn = s.nodes.get(i), ln = l.nodes.get(pi);
		if (sn.getText().equals(ln.getText()) && sn.getContentDesc().equals(ln.getContentDesc())) {
			return false;
		}
		if (!s.ink.available() || !l.ink.available()) {
			return false;
		}
		int[] inkA = s.inkBox(i), inkB = l.inkBox(pi);
		if (inkA == null || inkB == null) {
			return inkA != inkB;
		}
		return !near(Boxes.shift(inkA, -a[0], -a[1]), Boxes.shift(inkB, -b[0], -b[1]));
	}

	/** True when no edge of b is further from that of a than the slack of its axis. */
	private boolean near(int[] a, int[] b) {
		return Math.abs(a[0] - b[0]) <= slackX && Math.abs(a[2] - b[2]) <= slackX && Math.abs(a[1] - b[1]) <= slackY && Math.abs(a[3] - b[3]) <= slackY;
	}

	/**
	 * Of two overlapping elements, the one held by a scrolling area that does not hold the other, such
	 * as a list under a bar or a floating button. -1 when neither.
	 */
	private static int scrolledUnder(DrawnScreen l, int a, int b) {
		int sa = l.scrollingAncestor(a);
		if (sa >= 0 && sa != b && !l.isAncestor(sa, b)) {
			return a;
		}
		int sb = l.scrollingAncestor(b);
		if (sb >= 0 && sb != a && !l.isAncestor(sb, a)) {
			return b;
		}
		return -1;
	}

	/**
	 * Scrolled content that now passes under an element outside its scrolling area can be reached by
	 * scrolling. It shows less of itself only when part of what it drew in the source is now covered.
	 */
	@SuppressWarnings("unchecked")
	private void hiddenUnder(Map<Integer, Finding> byNode, int stateId, DrawnScreen s, DrawnScreen l, int i, int pi, int j, int pj) {
		int[] box = l.box(pi);
		int[] covered = Boxes.intersection(box, l.box(pj));
		if (covered == null) {
			return;
		}
		int[] drawn = s.inkBox(i);
		if (drawn == null) {
			return;
		}
		// what it drew in the source, placed on its translated box
		int[] was = s.box(i);
		int[] hidden = Boxes.intersection(Boxes.shift(drawn, box[0] - was[0], box[1] - was[1]), covered);
		if (hidden == null || Math.min(hidden[2] - hidden[0], hidden[3] - hidden[1]) <= State.SLACK) {
			return;
		}
		Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
		f.changes.add(Change.LESS_ON_SCREEN);
		if (f.heightChange == 0) {
			f.heightChange = -(hidden[3] - hidden[1]);
		}
		JSONObject relation = new JSONObject();
		relation.put("relNode", j);
		relation.put("langRelNode", pj);
		relation.put("added", "");
		relation.put("removed", "");
		relation.put("under", true);
		f.relations.add(relation);
	}

	/**
	 * Whether the drawings of two elements whose boxes now overlap meet on the translated screenshot.
	 * When one of them kept its size and content, what it drew in the source in the shared part, out
	 * of the box the other had then, must now look otherwise, as a translated text drawn over an icon
	 * does. Without one, the shared part must hold pixels unlike the background of either. Without a
	 * screenshot the boxes decide.
	 */
	private boolean overlapIsVisible(DrawnScreen s, DrawnScreen l, int i, int pi, int j, int pj) {
		if (!l.ink.available()) {
			return true;
		}
		int[] a = l.visibleBox(pi), b = l.visibleBox(pj);
		int[] shared = a == null || b == null ? null : l.ink.clip(Boxes.intersection(a, b));
		if (shared == null) {
			return false;
		}
		int ref = -1, refSource = -1;
		long bestShift = Long.MAX_VALUE;
		if (s.ink.sameSize(l.ink)) {
			for (int[] c : new int[][] { { i, pi }, { j, pj } }) {
				AndroidNode was = s.nodes.get(c[0]), now = l.nodes.get(c[1]);
				if (was.getWidth() == now.getWidth() && was.getHeight() == now.getHeight() && was.getText().equals(now.getText())
						&& was.getContentDesc().equals(now.getContentDesc())) {
					long shift = Math.abs(now.getPoint1()[0] - was.getPoint1()[0]) + (long) Math.abs(now.getPoint1()[1] - was.getPoint1()[1]);
					if (shift < bestShift) {
						bestShift = shift;
						ref = c[1];
						refSource = c[0];
					}
				}
			}
		}
		if (ref < 0) {
			return l.ink.count(shared, l.ink.background(l.box(pi)), l.ink.background(l.box(pj))) >= MIN_OVERLAP_PIXELS;
		}
		int met = metOverReference(s, l, refSource, ref, refSource == i ? j : i, shared, false);
		// in a mirrored layout its drawing may be mirrored with it or not, the closer reading counts
		if (mirrorExpected && met >= MIN_OVERLAP_PIXELS) {
			met = Math.min(met, metOverReference(s, l, refSource, ref, refSource == i ? j : i, shared, true));
		}
		return met >= MIN_OVERLAP_PIXELS;
	}

	/**
	 * Pixels of the shared part where the reference drew something in the source, out of the box the
	 * other element had then, and the translated screen shows something else. The reference is read
	 * at the same place of its box, or at the mirrored place.
	 */
	private static int metOverReference(DrawnScreen s, DrawnScreen l, int refSource, int ref, int otherSource, int[] shared, boolean mirror) {
		int[] was = s.box(refSource), now = l.box(ref);
		// what the other one drew there in the source, when it already stood on it, is not the reference's
		int[] other = s.box(otherSource);
		boolean filled = s.filled(refSource);
		int background = s.ink.background(was);
		int met = 0;
		for (int y = shared[1]; y < shared[3]; y++) {
			for (int x = shared[0]; x < shared[2]; x++) {
				int sx = mirror ? was[0] + (now[2] - 1 - x) : x - now[0] + was[0], sy = y - now[1] + was[1];
				if (!s.ink.inside(sx, sy) || (sx >= other[0] && sy >= other[1] && sx < other[2] && sy < other[3])) {
					continue;
				}
				int before = s.ink.at(sx, sy);
				boolean drawn = filled || Ink.distance(before, background) > Ink.INK;
				if (drawn && Ink.distance(l.ink.at(x, y), before) > Ink.SAME) {
					met++;
				}
			}
		}
		return met;
	}

	/**
	 * Elements whose box leaves an element that holds it, which held them in the source. They count
	 * when what they draw is past its side, not their empty box. Pixels like what stands around the
	 * holder, or like the source there, are not theirs. Only the highest one that left is reported.
	 */
	@SuppressWarnings("unchecked")
	private void outOfContainers(Map<Integer, Finding> byNode, int stateId, NodePairing pairing, DrawnScreen s, DrawnScreen l, boolean[] outOfFrame) {
		boolean[] reported = new boolean[l.nodes.size()];
		for (int i = 0; i < s.nodes.size(); i++) {
			int pi = pairing.targetOfSource[i];
			if (pi < 0 || outOfFrame[i]) {
				continue;
			}
			int parent = l.parent(pi);
			if (parent >= 0 && reported[parent]) {
				reported[pi] = true;
				continue;
			}
			boolean nearest = true;
			for (int a = parent; a >= 0; a = l.parent(a)) {
				int sa = pairing.sourceOfTarget[a];
				if (sa < 0 || !s.isAncestor(sa, i) || !Boxes.inside(s.box(i), s.box(sa), insideSlack)) {
					continue;
				}
				if (Boxes.inside(l.box(pi), l.box(a), insideSlack)) {
					int side = nearest ? cutSide(s, l, i, pi, sa, a) : -1;
					nearest = false;
					if (side < 0) {
						continue;
					}
					reported[pi] = true;
					Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
					f.changes.add(Change.OUT_OF_CONTAINER);
					JSONObject relation = new JSONObject();
					relation.put("relNode", sa);
					relation.put("langRelNode", a);
					relation.put("added", "");
					relation.put("removed", "");
					relation.put("cut", SIDE_NAMES[side]);
					f.relations.add(relation);
					break;
				}
				if (drawsOutside(s, l, pi, a)) {
					reported[pi] = true;
					Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
					f.changes.add(Change.OUT_OF_CONTAINER);
					JSONObject relation = new JSONObject();
					relation.put("relNode", sa);
					relation.put("langRelNode", a);
					relation.put("added", "");
					relation.put("removed", GraphEdgeType.CONTAINED.name() + ";");
					f.relations.add(relation);
				}
				break;
			}
		}
	}

	/** Whether an element draws past the side of the one holding it, beyond the slack. */
	private boolean drawsOutside(DrawnScreen s, DrawnScreen l, int d, int holder) {
		int[] visible = l.visibleBox(d);
		if (visible == null) {
			return false;
		}
		int[] limit = Boxes.grow(l.box(holder), insideSlack);
		if (!l.ink.available()) {
			return !Boxes.inside(visible, limit, 0);
		}
		int[] region = l.ink.clip(visible);
		if (region == null) {
			return false;
		}
		int around = l.ink.surroundings(l.box(holder), DrawnScreen.RING);
		boolean compare = !mirrorExpected && s.ink.sameSize(l.ink);
		int out = 0;
		for (int y = region[1]; y < region[3]; y++) {
			for (int x = region[0]; x < region[2]; x++) {
				if (x >= limit[0] && y >= limit[1] && x < limit[2] && y < limit[3]) {
					continue;
				}
				int c = l.ink.at(x, y);
				if ((around < 0 || Ink.distance(c, around) > Ink.INK) && (!compare || Ink.distance(c, s.ink.at(x, y)) > Ink.SAME)) {
					out++;
				}
			}
		}
		return out >= MIN_OUTSIDE_PIXELS;
	}

	/**
	 * The side of the element holding it that cuts an element, as a dump writes a box only as far as
	 * the one holding it shows. The element had room to that side in the source, now it is smaller,
	 * its box ends at that side and what it draws reaches it, as an icon a longer label pushes onto
	 * the border of its button. A text changes its size with the translation, and a scrolling area
	 * cuts what it holds on purpose, so neither counts. -1 when no side cuts it.
	 */
	private int cutSide(DrawnScreen s, DrawnScreen l, int i, int pi, int sa, int a) {
		if (s.isText(i) || l.isText(pi) || l.nodes.get(a).isScrollable() || !l.ink.available()) {
			return -1;
		}
		int[] was = s.box(i), now = l.box(pi), heldWas = s.box(sa), held = l.box(a);
		for (int side = 0; side < 4; side++) {
			int axis = side % 2;
			int sizeWas = was[axis + 2] - was[axis], sizeNow = now[axis + 2] - now[axis];
			int roomWas = side < 2 ? was[side] - heldWas[side] : heldWas[side] - was[side];
			int roomNow = side < 2 ? now[side] - held[side] : held[side] - now[side];
			if (sizeWas - sizeNow > insideSlack && roomWas > insideSlack && roomNow <= CUT_SLACK
					&& !scrolledAt(l, pi, side, now[side]) && drawsTo(l, pi, held, side)) {
				return side;
			}
		}
		return -1;
	}

	/** Whether a scrolling area holding the element has a side where its box ends. */
	private static boolean scrolledAt(DrawnScreen l, int pi, int side, int edge) {
		for (int p = l.parent(pi); p >= 0; p = l.parent(p)) {
			if (l.nodes.get(p).isScrollable() && Math.abs(l.box(p)[side] - edge) <= CUT_SLACK) {
				return true;
			}
		}
		return false;
	}

	/** Whether what an element draws reaches a side of the one holding it, leaving out the border that side may draw. */
	private boolean drawsTo(DrawnScreen l, int pi, int[] held, int side) {
		int[] visible = l.visibleBox(pi);
		int[] inner = visible == null ? null : l.ink.clip(Boxes.grow(visible, -BORDER));
		if (inner == null || Boxes.empty(inner)) {
			return false;
		}
		int[] ink = l.ink.inkBox(inner, l.ink.background(inner), List.of());
		if (ink == null) {
			return false;
		}
		int gap = side < 2 ? ink[side] - held[side] : held[side] - ink[side];
		return gap <= insideSlack;
	}

	/**
	 * Elements whose height changed with the translation, which no relation shows when what follows
	 * moves with them. A text that takes more or fewer lines, with the element it pushed, and an
	 * element cut by the bottom of its scrolling area that the translated screen shows more or less
	 * of, when what it draws reaches the part shown or hidden. A container grows with what it holds,
	 * so only elements with a text or with no children count.
	 */
	@SuppressWarnings("unchecked")
	private void heightChanges(Map<Integer, Finding> byNode, int stateId, List<AndroidNode> sourceNodes,
			List<AndroidNode> languageNodes, NodePairing pairing, DrawnScreen s, DrawnScreen l) {
		int screen = 1;
		for (AndroidNode n : sourceNodes) {
			screen = Math.max(screen, n.getPoint2()[1]);
		}
		for (int i = 0; i < sourceNodes.size(); i++) {
			int pi = pairing.targetOfSource[i];
			if (pi < 0) {
				continue;
			}
			AndroidNode sn = sourceNodes.get(i), ln = languageNodes.get(pi);
			boolean text = sn.hasText() || ln.hasText();
			if (!text && !sn.getChildPositions().isEmpty()) {
				continue;
			}
			int dh = ln.getHeight() - sn.getHeight();
			if (sn.getHeight() <= 0 || ln.getHeight() <= 0
					|| Math.abs(dh) < Math.max(MIN_HEIGHT_CHANGE * screen, MIN_HEIGHT_SHARE * Math.min(sn.getHeight(), ln.getHeight()))) {
				continue;
			}
			boolean cutS = s.cutAtBottom(i, CUT_SLACK), cutL = l.cutAtBottom(pi, CUT_SLACK);
			Change c;
			if ((cutS && dh > 0) || (cutS && cutL)) {
				c = dh > 0 ? Change.MORE_ON_SCREEN : Change.LESS_ON_SCREEN;
			} else if (cutL && dh < 0) {
				c = Change.LESS_ON_SCREEN;
			} else if (text) {
				c = dh > 0 ? Change.LINES_ADDED : Change.LINES_REMOVED;
			} else {
				continue;
			}
			if ((c == Change.MORE_ON_SCREEN || c == Change.LESS_ON_SCREEN) && !drawsWhereShownOrHidden(s, l, i, pi, dh)) {
				continue;
			}
			Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
			f.changes.add(c);
			f.heightChange = dh;
			if (c == Change.LINES_ADDED || c == Change.LINES_REMOVED) {
				int pushed = pushed(sourceNodes, languageNodes, pairing, i, dh);
				if (pushed >= 0) {
					int pj = pairing.targetOfSource[pushed];
					JSONObject relation = new JSONObject();
					relation.put("relNode", pushed);
					relation.put("langRelNode", pj);
					relation.put("added", "");
					relation.put("removed", "");
					relation.put("moved", (long) (languageNodes.get(pj).getPoint1()[1] - sourceNodes.get(pushed).getPoint1()[1]));
					f.relations.add(relation);
				}
			}
		}
	}

	/**
	 * Whether the part of an element that one screen shows and the other cuts off holds some of its
	 * drawing. A text whose box loses an empty bottom shows all of itself.
	 */
	private static boolean drawsWhereShownOrHidden(DrawnScreen s, DrawnScreen l, int i, int pi, int dh) {
		if (!s.ink.available() || !l.ink.available()) {
			return true;
		}
		// the taller of the two shows a part the shorter one hides
		DrawnScreen tall = dh > 0 ? l : s;
		int node = dh > 0 ? pi : i;
		int shorter = dh > 0 ? s.nodes.get(i).getHeight() : l.nodes.get(pi).getHeight();
		int[] drawn = tall.inkBox(node);
		int top = tall.box(node)[1];
		return drawn != null && drawn[3] - top > shorter + State.SLACK;
	}

	/**
	 * The nearest element below one whose height changed that moved by as much, the element it pushed,
	 * preferring one with a text or with no children. -1 when there is none.
	 */
	private static int pushed(List<AndroidNode> sourceNodes, List<AndroidNode> languageNodes, NodePairing pairing, int i, int dh) {
		AndroidNode s = sourceNodes.get(i), l = languageNodes.get(pairing.targetOfSource[i]);
		int ownShift = l.getPoint1()[1] - s.getPoint1()[1];
		int best = -1, bestGap = Integer.MAX_VALUE;
		boolean bestLeaf = false;
		for (int j = 0; j < sourceNodes.size(); j++) {
			int pj = pairing.targetOfSource[j];
			if (j == i || pj < 0) {
				continue;
			}
			AndroidNode a = sourceNodes.get(j), b = languageNodes.get(pj);
			int gap = a.getPoint1()[1] - s.getPoint2()[1];
			boolean sameColumn = a.getPoint1()[0] < s.getPoint2()[0] && a.getPoint2()[0] > s.getPoint1()[0];
			int shift = b.getPoint1()[1] - a.getPoint1()[1] - ownShift;
			if (gap < -2 || !sameColumn || Math.abs(shift - dh) > Math.max(3, Math.abs(dh) / 10)) {
				continue;
			}
			boolean leaf = a.hasText() || a.getChildPositions().isEmpty();
			if (gap < bestGap || (gap == bestGap && leaf && !bestLeaf)) {
				best = j;
				bestGap = gap;
				bestLeaf = leaf;
			}
		}
		return best;
	}

	/**
	 * Adds the texts drawn over a graphic of the app to the finding of their element, or as a new
	 * finding. Nothing happens when a screenshot cannot be read.
	 */
	private void addTextOverGraphic(Map<Integer, Finding> byNode, State source, Ink sourceShot, State language,
			Ink languageShot, NodePairing pairing) {
		for (TextOverGraphic.Hit hit : TextOverGraphic.find(source, sourceShot, language, languageShot, pairing)) {
			Finding f = byNode.get(hit.sourceNode);
			if (f == null) {
				f = new Finding(language.getId(), hit.sourceNode, hit.languageNode, KIND_TEXT_OVER_GRAPHIC);
				byNode.put(hit.sourceNode, f);
			}
			f.changes.add(Change.TEXT_OVER_GRAPHIC);
			f.graphics.add(hit.graphic);
		}
	}

	/**
	 * The findings of one screen as they are reported. Nested elements with one box are one element,
	 * reported once on the one with a text or else the outermost, and the relations to them name that
	 * one. An element that draws nothing of its own has no finding. Then the elements only one screen
	 * has, reported once at the top of what is missing or new.
	 */
	private List<Finding> finish(Map<Integer, Finding> byNode, int stateId, NodePairing pairing, DrawnScreen s, DrawnScreen l) {
		Map<Integer, Finding> byRepresentative = new LinkedHashMap<>();
		for (Finding f : byNode.values()) {
			int rep = l.representative(f.languageNode, k -> pairing.sourceOfTarget[k] >= 0);
			Finding into = byRepresentative.get(rep);
			if (into == null) {
				f.languageNode = rep;
				f.sourceNode = pairing.sourceOfTarget[rep];
				byRepresentative.put(rep, f);
			} else {
				merge(into, f);
			}
		}
		List<Finding> out = new ArrayList<>();
		for (Finding f : byRepresentative.values()) {
			collapseRelations(f, pairing, l);
			if (!l.drawsSomething(f.languageNode)) {
				continue;
			}
			f.geometry = geometry(s.nodes.get(f.sourceNode), l.nodes.get(f.languageNode));
			f.rescore();
			if (f.score > 0 || !f.changes.isEmpty()) {
				out.add(f);
			}
		}
		for (int i = 0; i < s.nodes.size(); i++) {
			int parent = s.parent(i);
			if (pairing.targetOfSource[i] < 0 && (parent < 0 || pairing.targetOfSource[parent] >= 0) && s.drawsAnything(i)) {
				Finding f = new Finding(stateId, i, -1, KIND_LOST);
				f.changes.add(Change.COMPONENT_LOST);
				f.score = subtreeSize(s.nodes, i);
				out.add(f);
			}
		}
		for (int k = 0; k < l.nodes.size(); k++) {
			int parent = l.parent(k);
			if (pairing.sourceOfTarget[k] < 0 && (parent < 0 || pairing.sourceOfTarget[parent] >= 0) && l.drawsAnything(k)) {
				Finding f = new Finding(stateId, -1, k, KIND_ADDED);
				f.changes.add(Change.COMPONENT_ADDED);
				f.score = subtreeSize(l.nodes, k);
				out.add(f);
			}
		}
		return out;
	}

	/** Puts what one finding of a chain says into the finding of its representative. */
	private static void merge(Finding into, Finding f) {
		into.changes.addAll(f.changes);
		into.relations.addAll(f.relations);
		into.graphics.addAll(f.graphics);
		if (into.heightChange == 0) {
			into.heightChange = f.heightChange;
		}
		if (KIND_RELATIONS.equals(f.kind)) {
			into.kind = KIND_RELATIONS;
		}
	}

	/**
	 * Points the relations of a finding to the representative of each chain they name, and joins two
	 * relations to the same element into one. A relation to itself, from inside its own chain, goes.
	 */
	@SuppressWarnings("unchecked")
	private static void collapseRelations(Finding f, NodePairing pairing, DrawnScreen l) {
		Map<String, JSONObject> byOther = new LinkedHashMap<>();
		for (JSONObject r : f.relations) {
			int lang = ((Number) r.get("langRelNode")).intValue();
			int rep = l.representative(lang, k -> pairing.sourceOfTarget[k] >= 0);
			if (rep == f.languageNode) {
				continue;
			}
			JSONObject relation = new JSONObject();
			relation.putAll(r);
			relation.put("langRelNode", rep);
			relation.put("relNode", pairing.sourceOfTarget[rep]);
			String key = rep + (r.get("moved") != null ? "/moved" : r.get("under") != null ? "/under" : "");
			JSONObject known = byOther.get(key);
			if (known == null) {
				byOther.put(key, relation);
				continue;
			}
			if (relation.get("cut") != null) {
				known.put("cut", relation.get("cut"));
			}
			for (String field : new String[] { "added", "removed", "missing" }) {
				if (relation.get(field) != null || known.get(field) != null) {
					known.put(field, union(String.valueOf(known.getOrDefault(field, "")), String.valueOf(relation.getOrDefault(field, ""))));
				}
			}
		}
		f.relations.clear();
		f.relations.addAll(byOther.values());
	}

	/** Two lists of relation names joined, in enum order. */
	private static String union(String a, String b) {
		EnumSet<GraphEdgeType> set = EnumSet.noneOf(GraphEdgeType.class);
		for (String part : (a + ";" + b).split(";")) {
			if (!part.isEmpty()) {
				set.add(GraphEdgeType.valueOf(part));
			}
		}
		return join(set);
	}

	/**
	 * Adds the translated texts cut inside their box, measured over every screen of the language, to
	 * the finding of their element or as a finding of their own.
	 */
	private void addTextCuts(List<TextCut.Sample> samples) {
		double ratio = TextCut.ratio(samples);
		for (TextCut.Hit hit : TextCut.cuts(samples, ratio)) {
			Finding f = null;
			for (Finding g : findings) {
				if (g.stateId == hit.stateId && g.languageNode == hit.languageNode && g.sourceNode == hit.sourceNode) {
					f = g;
					break;
				}
			}
			if (f == null) {
				f = new Finding(hit.stateId, hit.sourceNode, hit.languageNode, KIND_TEXT_CUT);
				State source = null, language = null;
				for (Map.Entry<Integer, Integer> e : statePairing.entrySet()) {
					State st = destLangLayoutGraph.getState(e.getValue());
					if (st.getId() == hit.stateId) {
						source = defltLayoutGraph.getState(e.getKey());
						language = st;
					}
				}
				if (source == null) {
					continue;
				}
				f.geometry = geometry(source.getStateNodes().get(hit.sourceNode), language.getStateNodes().get(hit.languageNode));
				findings.add(f);
			}
			f.changes.add(Change.TEXT_CUT);
			f.neededWidth = hit.needed;
			f.drawnWidth = hit.drawn;
			f.rescore();
		}
	}

	/**
	 * The screenshot of a state from the folder of its exploration, or null when it cannot be read or
	 * does not cover the elements of its dump, as a reduced copy does not.
	 */
	private static BufferedImage screenshot(LayoutGraph graph, State state) {
		String name = state.getScreenShot();
		if (name == null || name.isEmpty()) {
			return null;
		}
		File file = new File(name.replace('\\', '/'));
		if (!file.isAbsolute() && graph.getFolder() != null) {
			file = new File(graph.getFolder(), name.replace('\\', '/'));
		}
		BufferedImage image;
		try {
			image = file.isFile() ? ImageIO.read(file) : null;
		} catch (IOException e) {
			return null;
		}
		if (image != null) {
			for (AndroidNode n : state.getStateNodes()) {
				if (n.getPoint2()[0] > image.getWidth() + State.SLACK || n.getPoint2()[1] > image.getHeight() + State.SLACK) {
					return null;
				}
			}
		}
		return image;
	}

	/** Notes on the finding of one element what changed between it and another one. Nothing when nothing changed on its side. */
	@SuppressWarnings("unchecked")
	private static void record(Map<Integer, Finding> byNode, int stateId, int node, int languageNode, int other, int languageOther,
			Set<GraphEdgeType> removed, Set<GraphEdgeType> added, Set<GraphEdgeType> missing) {
		if (removed.isEmpty() && added.isEmpty() && missing.isEmpty()) {
			return;
		}
		Finding f = byNode.computeIfAbsent(node, k -> new Finding(stateId, node, languageNode, KIND_RELATIONS));
		for (GraphEdgeType e : removed) {
			f.changes.add(e == GraphEdgeType.INTERSECTS ? Change.OVERLAP_REMOVED : e.isAlignment() ? Change.ALIGNMENT_LOST : Change.POSITION_LOST);
		}
		for (GraphEdgeType e : added) {
			f.changes.add(e == GraphEdgeType.INTERSECTS ? Change.OVERLAP_ADDED : e.isAlignment() ? Change.ALIGNMENT_ADDED : Change.POSITION_ADDED);
		}
		if (!missing.isEmpty()) {
			f.changes.add(Change.MISSING_MIRRORING);
		}
		JSONObject relation = new JSONObject();
		relation.put("relNode", other);
		relation.put("langRelNode", languageOther);
		relation.put("added", join(added));
		relation.put("removed", join(removed));
		if (!missing.isEmpty()) {
			relation.put("missing", join(missing));
		}
		f.relations.add(relation);
	}

	/**
	 * The mirrored relations two siblings should have in the other writing direction and do not, such
	 * as left becoming right. Alignments are skipped when a text is involved.
	 */
	private EnumSet<GraphEdgeType> notMirrored(Set<GraphEdgeType> sourceRelations, Set<GraphEdgeType> languageRelations, boolean text, boolean row,
			AndroidNode languageA, AndroidNode languageB) {
		EnumSet<GraphEdgeType> missing = EnumSet.noneOf(GraphEdgeType.class);
		// the edge the elements start from, the left one in a language written left to right
		GraphEdgeType start = sourceRtl ? GraphEdgeType.RIGHT_ALIGNED : GraphEdgeType.LEFT_ALIGNED;
		for (GraphEdgeType e : sourceRelations) {
			GraphEdgeType expected = e.mirrored();
			if (expected == e || (text && e.isAlignment())) {
				continue;
			}
			// between two rows only the start edge is compared, the other one moves with the width of each
			if (!row && e != start) {
				continue;
			}
			if (!languageRelations.contains(expected) && !nearly(expected, languageA, languageB)) {
				missing.add(expected);
			}
		}
		return missing;
	}

	/** Whether relation e of a to b holds, give or take the slack of each axis. */
	private boolean nearly(GraphEdgeType e, AndroidNode a, AndroidNode b) {
		int[] a1 = a.getPoint1(), a2 = a.getPoint2(), b1 = b.getPoint1(), b2 = b.getPoint2();
		switch (e) {
			case ABOVE:
				return a2[1] <= b1[1] + slackY;
			case BELOW:
				return a1[1] >= b2[1] - slackY;
			case LEFT:
				return a2[0] <= b1[0] + slackX;
			case RIGHT:
				return a1[0] >= b2[0] - slackX;
			case TOP_ALIGNED:
				return Math.abs(a1[1] - b1[1]) <= slackY;
			case BOTTOM_ALIGNED:
				return Math.abs(a2[1] - b2[1]) <= slackY;
			case LEFT_ALIGNED:
				return Math.abs(a1[0] - b1[0]) <= slackX;
			case RIGHT_ALIGNED:
				return Math.abs(a2[0] - b2[0]) <= slackX;
			case CONTAINS:
				return a1[0] <= b1[0] + insideSlack && a1[1] <= b1[1] + insideSlack && a2[0] >= b2[0] - insideSlack && a2[1] >= b2[1] - insideSlack;
			case CONTAINED:
				return nearly(GraphEdgeType.CONTAINS, b, a);
			case INTERSECTS:
				// apart by less than the slack on both axes
				return Math.min(a2[0], b2[0]) - Math.max(a1[0], b1[0]) >= -slackX
						&& Math.min(a2[1], b2[1]) - Math.max(a1[1], b1[1]) >= -slackY;
			default:
				return false;
		}
	}

	/** Whether two boxes share no more than the overlap slack or one is still nearly inside the other. */
	private boolean shallow(AndroidNode a, AndroidNode b) {
		return depth(a, b) <= overlapSlack || nearly(GraphEdgeType.CONTAINS, a, b) || nearly(GraphEdgeType.CONTAINED, a, b);
	}

	/** Pixels two boxes share along the axis where they share the least, negative when they are apart. */
	private static int depth(AndroidNode a, AndroidNode b) {
		int[] a1 = a.getPoint1(), a2 = a.getPoint2(), b1 = b.getPoint1(), b2 = b.getPoint2();
		return Math.min(Math.min(a2[0], b2[0]) - Math.max(a1[0], b1[0]), Math.min(a2[1], b2[1]) - Math.max(a1[1], b1[1]));
	}

	/** Whether the element is within the side of its window, or a window within the side of the app. */
	private boolean inFrame(List<AndroidNode> nodes, int i) {
		int top = i;
		while (nodes.get(top).getParentPosition() >= 0) {
			top = nodes.get(top).getParentPosition();
		}
		int left = top == i ? appLeft : nodes.get(top).getPoint1()[0];
		int right = top == i ? appRight : nodes.get(top).getPoint2()[0];
		AndroidNode n = nodes.get(i);
		return n.getPoint1()[0] >= left - insideSlack && n.getPoint2()[0] <= right + insideSlack;
	}

	@SuppressWarnings("unchecked")
	private void notPaired(JSONArray notCompared, int sourceStateId, String why) {
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || state "+sourceStateId
				+" was reached in "+destLanguage+" but "+why+": not paired");
		JSONObject skipped = new JSONObject();
		skipped.put("stateID", sourceStateId);
		skipped.put("reason", why);
		notCompared.add(skipped);
	}

	/**
	 * Of two source screens that got the same capture, the one it is, by elements paired, then
	 * elements in the same or mirrored place, then the one captured first.
	 */
	private int[] better(int[] a, int[] b, Map<Integer, NodePairing> pairings, List<State> defltStates, List<State> langStates) {
		NodePairing pa = pairings.get(a[0]), pb = pairings.get(b[0]);
		if (pa.paired != pb.paired) {
			return pa.paired > pb.paired ? a : b;
		}
		int ka = samePlace(pa, defltStates.get(a[0]), langStates.get(a[1]));
		int kb = samePlace(pb, defltStates.get(b[0]), langStates.get(b[1]));
		return kb > ka ? b : a;
	}

	/** Paired elements whose box is where it was in the source, or at its mirrored place. */
	private int samePlace(NodePairing pairing, State source, State language) {
		List<AndroidNode> sn = source.getStateNodes();
		List<AndroidNode> ln = language.getStateNodes();
		int same = 0;
		for (int i = 0; i < sn.size(); i++) {
			int k = pairing.targetOfSource[i];
			if (k < 0) {
				continue;
			}
			int[] s1 = sn.get(i).getPoint1(), s2 = sn.get(i).getPoint2(), l1 = ln.get(k).getPoint1(), l2 = ln.get(k).getPoint2();
			boolean sameY = s1[1] == l1[1] && s2[1] == l2[1];
			boolean sameX = s1[0] == l1[0] && s2[0] == l2[0];
			boolean mirroredX = mirrorExpected && l1[0] == screenWidth - s2[0] && l2[0] == screenWidth - s1[0];
			if (sameY && (sameX || mirroredX)) {
				same++;
			}
		}
		return same;
	}

	/** The furthest right any element of the screens of an exploration reaches. */
	private static int widthOf(LayoutGraph graph) {
		int width = 1;
		for (State state : graph.getStates()) {
			for (AndroidNode n : state.getStateNodes()) {
				width = Math.max(width, n.getPoint2()[0]);
			}
		}
		return width;
	}

	/** The furthest down any element of the screens of an exploration reaches. */
	private static int heightOf(LayoutGraph graph) {
		int height = 1;
		for (State state : graph.getStates()) {
			for (AndroidNode n : state.getStateNodes()) {
				height = Math.max(height, n.getPoint2()[1]);
			}
		}
		return height;
	}

	/** Whether the element itself is where and as it was (same, moved, resized, moved+resized or mirrored). */
	private String geometry(AndroidNode source, AndroidNode language) {
		boolean sameSize = source.getWidth() == language.getWidth() && source.getHeight() == language.getHeight();
		boolean sameY = source.getPoint1()[1] == language.getPoint1()[1];
		boolean sameX = source.getPoint1()[0] == language.getPoint1()[0];
		if (sameSize && sameX && sameY) {
			return "same";
		}
		if (mirrorExpected && sameSize && sameY && language.getPoint1()[0] == screenWidth - source.getPoint2()[0]) {
			return "mirrored";
		}
		boolean moved = !sameX || !sameY;
		return moved && !sameSize ? "moved+resized" : moved ? "moved" : "resized";
	}

	private static int subtreeSize(List<AndroidNode> nodes, int node) {
		int size = 1;
		for (int child : nodes.get(node).getChildPositions()) {
			size += subtreeSize(nodes, child);
		}
		return size;
	}

	private static EnumSet<GraphEdgeType> minus(Set<GraphEdgeType> a, Set<GraphEdgeType> b) {
		EnumSet<GraphEdgeType> out = EnumSet.noneOf(GraphEdgeType.class);
		out.addAll(a);
		out.removeAll(b);
		return out;
	}

	/** The relation names in enum order, each followed by a semicolon, as the reports write them. */
	private static String join(Set<GraphEdgeType> relations) {
		StringBuilder sb = new StringBuilder();
		for (GraphEdgeType e : relations) {
			sb.append(e.name()).append(';');
		}
		return sb.toString();
	}

	/**
	 * Pairs the states of both graphs. A replayed language graph is paired by source state id, when
	 * on the same activity and similar enough ({@link #setMinSimilarity}). An exploration that does
	 * not say which source state each of its states reproduces is not paired.
	 */
	@SuppressWarnings("unchecked")
	private void pairStates(JSONObject dfltLangJSONTrans) {

		ArrayList<State> defltStates = defltLayoutGraph.getStates();
		ArrayList<State> langStates = destLangLayoutGraph.getStates();

		boolean replay = false;
		for (State s : langStates) {
			if (s.getSourceStateId() > 0) {
				replay = true;
				break;
			}
		}

		JSONArray notCompared = new JSONArray();
		if (replay) {
			Map<Integer, Integer> dfltIndexById = new HashMap<>();
			for (int i = 0; i < defltStates.size(); i++) {
				dfltIndexById.put(defltStates.get(i).getId(), i);
			}
			// the screens that can be compared as {di, li}, in the order the replay captured them
			List<int[]> candidates = new ArrayList<>();
			Map<Integer, NodePairing> candidatePairings = new HashMap<>();
			Set<Integer> taken = new HashSet<>();
			for (int li = 0; li < langStates.size(); li++) {
				State langState = langStates.get(li);
				Integer di = dfltIndexById.get(langState.getSourceStateId());
				if (di == null || !taken.add(di)) {
					continue;
				}
				State dfltState = defltStates.get(di);
				String why = null;
				if (!dfltState.matchesReplayed(langState)) {
					why = "is on another activity ("+langState.getActivityName()+")";
				} else {
					NodePairing pairing = NodePairing.of(dfltState, langState, mirrorExpected, screenWidth);
					if (pairing.similarity() < minSimilarity) {
						why = "shows a different screen (only "+pairing.paired+" of its "+langState.getStateNodes().size()
								+" elements match the "+dfltState.getStateNodes().size()+" of the source)";
					} else {
						candidates.add(new int[] { di, li });
						candidatePairings.put(di, pairing);
					}
				}
				if (why != null) {
					notPaired(notCompared, langState.getSourceStateId(), why);
				}
			}
			// A replay that lost its way may keep one capture for two source screens. It is compared
			// only with the one it matches best, and the other is left out with a reason.
			Map<String, int[]> byCapture = new HashMap<>();
			for (int[] c : candidates) {
				String capture = langStates.get(c[1]).getRawXML();
				int[] best = byCapture.get(capture);
				if (best == null) {
					byCapture.put(capture, c);
					continue;
				}
				int[] keep = better(best, c, candidatePairings, defltStates, langStates);
				int[] drop = keep == best ? c : best;
				byCapture.put(capture, keep);
				notPaired(notCompared, defltStates.get(drop[0]).getId(), "shows the same capture as screen " + defltStates.get(keep[0]).getId()
						+ ", which it matches better: the replay did not tell the two apart");
			}
			for (int[] c : candidates) {
				if (byCapture.get(langStates.get(c[1]).getRawXML()) == c) {
					statePairing.put(c[0], c[1]);
					nodePairings.put(c[0], candidatePairings.get(c[0]));
				}
			}
		} else if (!langStates.isEmpty()) {
			System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || the "+destLanguage
					+" exploration does not say which source screen each of its screens reproduces: no screen is paired");
		}

		int paired = statePairing.size();
		dfltLangJSONTrans.put("pairing", replay ? "replay" : "none");
		dfltLangJSONTrans.put("pairedStates", paired);
		// the screens that were reached and not compared, each with why
		dfltLangJSONTrans.put("statesNotCompared", notCompared);
		dfltLangJSONTrans.put("missingDfltStates", (defltStates.size()-paired) );
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || "+paired+" states paired; "+(defltStates.size()-paired)+" states in "+defltLanguage+" Graph were not paired");
		dfltLangJSONTrans.put("missingLangStates", (langStates.size()-paired) );
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || "+(langStates.size()-paired)+" states in "+destLanguage+" Graph were not paired");
	}

}
