package uniandes.tsdl.itdroid.model;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
		/** The element reaches past the left or right edge the screen had in the source, such as a menu wider than its window. */
		OUT_OF_FRAME("outOfFrame");

		/** Changes that break the screen rather than rearrange it. */
		boolean severe() {
			return this == TEXT_OVER_GRAPHIC || this == OVERLAP_ADDED || this == OUT_OF_FRAME;
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

	/** Whether the screenshots are searched for text over a graphic, config key {@code comparison.textOverGraphic}. */
	private static boolean textOverGraphic = true;

	/** Pixels an element may pass the side of the source frame before it counts as out of it. */
	static final int FRAME_SLACK = 6;

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
		final int sourceNode;
		/** Position of the element in the translated screen, -1 when only the source screen has it. */
		final int languageNode;
		final String kind;
		final EnumSet<Change> changes = EnumSet.noneOf(Change.class);
		final List<JSONObject> relations = new ArrayList<>();
		String geometry = "";
		/** Pairs in which this element changed, or for a lost or added component the elements it is made of. */
		int score;
		/** Graphics its text is drawn over in the translated screen, each as x1, y1, x2, y2. */
		final List<int[]> graphics = new ArrayList<>();

		boolean severe() {
			return changes.stream().anyMatch(Change::severe);
		}

		Finding(int stateId, int sourceNode, int languageNode, String kind) {
			this.stateId = stateId;
			this.sourceNode = sourceNode;
			this.languageNode = languageNode;
			this.kind = kind;
		}
	}

	public String defltLanguage;
	public LayoutGraph defltLayoutGraph;
	public String destLanguage;
	public LayoutGraph destLangLayoutGraph;
	/** Source graph list index to language graph list index of the states compared with each other. */
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();
	public Set<Integer> defltStatesNotProcessed = new HashSet<Integer>();
	public Set<Integer> langStatesNotProcessed = new HashSet<Integer>();

	private final Map<Integer, NodePairing> nodePairings = new HashMap<>();
	/** True when one language is written left to right and the other right to left. */
	private final boolean mirrorExpected;
	/**
	 * Device screen width, the furthest right any element of either exploration reaches, since the
	 * dump of a dialog only reaches the edges of the dialog.
	 */
	private final int screenWidth;
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
	public LayoutGraphComparision(String deftLanguage, LayoutGraph defltGraph, String lang, String rawLang, LayoutGraph langGraph, String resultFolderPath, String outputFolder, JSONObject dfltLangJSONTrans) throws IOException {

		defltLanguage = deftLanguage;
		defltLayoutGraph = defltGraph;
		destLanguage = lang;
		destLangLayoutGraph = langGraph;
		mirrorExpected = Helper.languageIsRTL(deftLanguage) != Helper.languageIsRTL(rawLang);
		screenWidth = Math.max(widthOf(defltGraph), widthOf(langGraph));

		pairStates(dfltLangJSONTrans);

		List<Integer> dfltIndexes = new ArrayList<>(statePairing.keySet());
		java.util.Collections.sort(dfltIndexes);
		ScreenTexts texts = new ScreenTexts();
		for (int defltState : dfltIndexes) {
			State source = defltLayoutGraph.getState(defltState);
			State language = destLangLayoutGraph.getState(statePairing.get(defltState));
			NodePairing pairing = nodePairings.get(defltState);
			if (pairing == null) {
				pairing = NodePairing.of(source, language, mirrorExpected, screenWidth);
			}
			List<Finding> found = compareStates(source, language, pairing);
			if (textOverGraphic) {
				addTextOverGraphic(found, source, language, pairing);
			}
			findings.addAll(found);
			texts.add(source, language);
		}
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
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFolder+File.separator+"ipfs.csv", true))) {
			for (Finding f : findings) {
				bw.write(destLanguage+";"+f.stateId+";"+f.sourceNode+";"+f.score+";"+f.languageNode+";"+f.kind);
				bw.newLine();
			}
		}
	}

	/** Counts of the findings of this language by kind and by change. */
	@SuppressWarnings("unchecked")
	private JSONObject summary() {
		long changed = 0;
		long affected = 0;
		long lost = 0;
		long added = 0;
		long overGraphic = 0;
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
		// or it stayed (or mirrored as expected) and its neighbours changed around it
		o.put("nodesAffected", affected);
		o.put("componentsLost", lost);
		o.put("componentsAdded", added);
		// or only its text is drawn over a graphic (one that also moved counts above)
		o.put("textOverGraphic", overGraphic);
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

	private List<Finding> compareStates(State source, State language, NodePairing pairing) {
		Set<GraphEdgeType>[][] sourceGraph = source.getGraph();
		Set<GraphEdgeType>[][] languageGraph = language.getGraph();
		List<AndroidNode> sourceNodes = source.getStateNodes();
		List<AndroidNode> languageNodes = language.getStateNodes();
		int stateId = language.getId();
		Map<Integer, Finding> byNode = new TreeMap<>();
		Set<GraphEdgeType> alignments = GraphEdgeType.getAligmentTypes();
		Set<GraphEdgeType> mirrored = GraphEdgeType.getRtlChangeExpectedTypes();
		nodesCompared += pairing.paired;

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

				// A translated text changes width, so its alignments are not compared.
				boolean text = isTextView(sourceNodes.get(i)) || isTextView(sourceNodes.get(j))
						|| isTextView(languageNodes.get(pi)) || isTextView(languageNodes.get(pj));
				if (text) {
					lostAB.removeAll(alignments);
					lostBA.removeAll(alignments);
					addedAB.removeAll(alignments);
					addedBA.removeAll(alignments);
				}

				EnumSet<GraphEdgeType> missingAB = EnumSet.noneOf(GraphEdgeType.class);
				EnumSet<GraphEdgeType> missingBA = EnumSet.noneOf(GraphEdgeType.class);
				if (mirrorExpected) {
					lostAB.removeAll(mirrored);
					lostBA.removeAll(mirrored);
					addedAB.removeAll(mirrored);
					addedBA.removeAll(mirrored);
					if (source.nodesAreSiblings(i, j) && !sourceGraph[i][j].contains(GraphEdgeType.CONTAINS)
							&& !sourceGraph[i][j].contains(GraphEdgeType.CONTAINED)) {
						missingAB = notMirrored(sourceGraph[i][j], languageGraph[pi][pj], text);
						missingBA = notMirrored(sourceGraph[j][i], languageGraph[pj][pi], text);
						if (sourceGraph[i][j].contains(GraphEdgeType.LEFT) || sourceGraph[i][j].contains(GraphEdgeType.RIGHT)) {
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
				// A container does not take the finding of a child that moved inside it.
				boolean iHoldsJ = sourceGraph[i][j].contains(GraphEdgeType.CONTAINS) || languageGraph[pi][pj].contains(GraphEdgeType.CONTAINS);
				boolean jHoldsI = sourceGraph[j][i].contains(GraphEdgeType.CONTAINS) || languageGraph[pj][pi].contains(GraphEdgeType.CONTAINS);
				if (!iHoldsJ) {
					record(byNode, stateId, i, pi, j, pj, lostAB, addedAB, missingAB);
				}
				if (!jHoldsI) {
					record(byNode, stateId, j, pj, i, pi, lostBA, addedBA, missingBA);
				}
			}
		}

		// Elements past the sides of the source screen, reported only at the highest one, as a menu
		// wider than its window carries its rows with it.
		int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
		for (AndroidNode n : sourceNodes) {
			if (n.getParentPosition() < 0) {
				left = Math.min(left, n.getPoint1()[0]);
				right = Math.max(right, n.getPoint2()[0]);
			}
		}
		boolean[] outside = new boolean[sourceNodes.size()];
		for (int i = 0; i < sourceNodes.size(); i++) {
			int pi = pairing.targetOfSource[i];
			if (pi < 0 || left > right) {
				continue;
			}
			AndroidNode s = sourceNodes.get(i), l = languageNodes.get(pi);
			boolean wasInside = s.getPoint1()[0] >= left - FRAME_SLACK && s.getPoint2()[0] <= right + FRAME_SLACK;
			outside[i] = wasInside && (l.getPoint1()[0] < left - FRAME_SLACK || l.getPoint2()[0] > right + FRAME_SLACK);
			int parent = s.getParentPosition();
			if (outside[i] && (parent < 0 || !outside[parent])) {
				Finding f = byNode.computeIfAbsent(i, k -> new Finding(stateId, k, pi, KIND_RELATIONS));
				f.changes.add(Change.OUT_OF_FRAME);
				f.score++;
			}
		}

		List<Finding> out = new ArrayList<>();
		for (Finding f : byNode.values()) {
			f.geometry = geometry(sourceNodes.get(f.sourceNode), languageNodes.get(f.languageNode));
			out.add(f);
		}

		// Elements only one screen has, reported once at the top of what is missing or new.
		for (int i = 0; i < sourceNodes.size(); i++) {
			int parent = sourceNodes.get(i).getParentPosition();
			if (pairing.targetOfSource[i] < 0 && (parent < 0 || pairing.targetOfSource[parent] >= 0)) {
				Finding f = new Finding(stateId, i, -1, KIND_LOST);
				f.changes.add(Change.COMPONENT_LOST);
				f.score = subtreeSize(sourceNodes, i);
				out.add(f);
			}
		}
		for (int k = 0; k < languageNodes.size(); k++) {
			int parent = languageNodes.get(k).getParentPosition();
			if (pairing.sourceOfTarget[k] < 0 && (parent < 0 || pairing.sourceOfTarget[parent] >= 0)) {
				Finding f = new Finding(stateId, -1, k, KIND_ADDED);
				f.changes.add(Change.COMPONENT_ADDED);
				f.score = subtreeSize(languageNodes, k);
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * Adds the texts drawn over a graphic of the app to the finding of their element, or as a new
	 * finding. Nothing happens when a screenshot cannot be read.
	 */
	private void addTextOverGraphic(List<Finding> found, State source, State language, NodePairing pairing) {
		BufferedImage sourceShot = screenshot(defltLayoutGraph, source);
		BufferedImage languageShot = screenshot(destLangLayoutGraph, language);
		for (TextOverGraphic.Hit hit : TextOverGraphic.find(source, sourceShot, language, languageShot, pairing)) {
			Finding f = null;
			for (Finding g : found) {
				if (g.sourceNode == hit.sourceNode && KIND_RELATIONS.equals(g.kind)) {
					f = g;
					break;
				}
			}
			if (f == null) {
				for (Finding g : found) {
					if (g.sourceNode == hit.sourceNode && KIND_TEXT_OVER_GRAPHIC.equals(g.kind)) {
						f = g;
						break;
					}
				}
			}
			if (f == null) {
				f = new Finding(language.getId(), hit.sourceNode, hit.languageNode, KIND_TEXT_OVER_GRAPHIC);
				f.geometry = geometry(source.getStateNodes().get(hit.sourceNode), language.getStateNodes().get(hit.languageNode));
				found.add(f);
			}
			f.changes.add(Change.TEXT_OVER_GRAPHIC);
			f.graphics.add(hit.graphic);
			f.score++;
		}
	}

	/** The screenshot of a state from the folder of its exploration, or null when it cannot be read. */
	private static BufferedImage screenshot(LayoutGraph graph, State state) {
		String name = state.getScreenShot();
		if (name == null || name.isEmpty()) {
			return null;
		}
		File file = new File(name.replace('\\', '/'));
		if (!file.isAbsolute() && graph.getFolder() != null) {
			file = new File(graph.getFolder(), name.replace('\\', '/'));
		}
		try {
			return file.isFile() ? ImageIO.read(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Notes on the finding of one element what changed between it and another one. */
	@SuppressWarnings("unchecked")
	private static void record(Map<Integer, Finding> byNode, int stateId, int node, int languageNode, int other, int languageOther,
			Set<GraphEdgeType> removed, Set<GraphEdgeType> added, Set<GraphEdgeType> missing) {
		Finding f = byNode.computeIfAbsent(node, k -> new Finding(stateId, node, languageNode, KIND_RELATIONS));
		f.score++;
		for (GraphEdgeType e : removed) {
			f.changes.add(e == GraphEdgeType.INTERSECTS ? Change.OVERLAP_REMOVED : e.isAlignment() ? Change.ALIGNMENT_LOST : Change.POSITION_LOST);
		}
		for (GraphEdgeType e : added) {
			f.changes.add(e == GraphEdgeType.INTERSECTS ? Change.OVERLAP_ADDED : e.isAlignment() ? Change.ALIGNMENT_ADDED : Change.POSITION_ADDED);
		}
		if (!missing.isEmpty()) {
			f.changes.add(Change.MISSING_MIRRORING);
		}
		if (removed.isEmpty() && added.isEmpty() && missing.isEmpty()) {
			return;   // the change is on the other element's side, this one is only counted
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
	private static EnumSet<GraphEdgeType> notMirrored(Set<GraphEdgeType> sourceRelations, Set<GraphEdgeType> languageRelations, boolean text) {
		EnumSet<GraphEdgeType> missing = EnumSet.noneOf(GraphEdgeType.class);
		for (GraphEdgeType e : sourceRelations) {
			GraphEdgeType expected = e.mirrored();
			if (expected == e || (text && e.isAlignment())) {
				continue;
			}
			if (!languageRelations.contains(expected)) {
				missing.add(expected);
			}
		}
		return missing;
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

	/** Null-safe TextView test, as a node may be absent or have no class attribute. */
	private static boolean isTextView(AndroidNode node) {
		return node != null && node.getpClass() != null && node.getpClass().contains("TextView");
	}

	/**
	 * Pairs the states of both graphs. A replayed language graph is paired by source state id, when
	 * on the same activity and similar enough ({@link #setMinSimilarity}). Otherwise states are paired
	 * by position with the raw XML test, stopping at the first mismatch.
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
		} else {
			boolean sameStates = true;
			int index = 0;
			while(sameStates && index<defltStates.size() && index<langStates.size()) {
				State dfltTempState = defltStates.get(index);
				State langTempState = langStates.get(index);
				sameStates = dfltTempState.compareTo(langTempState);
				if(sameStates) {
					statePairing.put(index, index);
					index++;
				}
			}
		}

		int paired = statePairing.size();
		dfltLangJSONTrans.put("pairing", replay ? "replay" : "positional");
		dfltLangJSONTrans.put("pairedStates", paired);
		// the screens that were reached and not compared, each with why
		dfltLangJSONTrans.put("statesNotCompared", notCompared);
		dfltLangJSONTrans.put("missingDfltStates", (defltStates.size()-paired) );
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || "+paired+" states paired ("+(replay ? "by replayed id" : "positionally")+"); "+(defltStates.size()-paired)+" states in "+defltLanguage+" Graph were not paired");
		for (int i = 0; i < defltStates.size(); i++) {
			if (!statePairing.containsKey(i)) {
				defltStatesNotProcessed.add(defltStates.get(i).getId());
			}
		}
		dfltLangJSONTrans.put("missingLangStates", (langStates.size()-paired) );
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || "+(langStates.size()-paired)+" states in "+destLanguage+" Graph were not paired");
		for (int i = 0; i < langStates.size(); i++) {
			if (!statePairing.containsValue(i)) {
				langStatesNotProcessed.add(langStates.get(i).getId());
			}
		}
	}

}
