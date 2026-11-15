package uniandes.tsdl.itdroid.model;

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

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import uniandes.tsdl.itdroid.helper.Helper;

/**
 * Compares the screens of the source language with the same screens in another language and
 * reports the internationalization presentation failures (IPFs): the elements whose place on the
 * screen, with respect to the other elements, is not the one they had.
 *
 * <p>Screens are paired by the source screen each one reproduces; their elements by
 * {@link NodePairing}. For every two paired elements the relations they had in the source are set
 * against the ones they have now. An element with a relation lost or gained is a finding, and so is
 * one that only one of the two screens has. Two rules keep out what is not a failure:
 * <ul>
 *   <li>a change of alignment is ignored when either element is a text: a text gets wider or
 *       narrower when translated, which moves its edges without anything being wrong;</li>
 *   <li>between a left-to-right and a right-to-left language the layout is expected to be
 *       mirrored, so who is left of whom and which side is aligned are expected to swap. What is
 *       reported there is the opposite: two sibling elements that did NOT swap.</li>
 * </ul>
 */
public class LayoutGraphComparision {

	/** What happened to an element, in the terms of the taxonomy of the paper. */
	public enum Change {
		POSITION_LOST("positionLost"),
		POSITION_ADDED("positionAdded"),
		ALIGNMENT_LOST("alignmentLost"),
		ALIGNMENT_ADDED("alignmentAdded"),
		OVERLAP_ADDED("overlapAdded"),
		OVERLAP_REMOVED("overlapRemoved"),
		MISSING_MIRRORING("missingMirroring"),
		COMPONENT_LOST("componentLost"),
		COMPONENT_ADDED("componentAdded");

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

	/**
	 * A screen reached by replaying the source path is taken for the same screen when at least this
	 * share of the elements of the larger of the two can be paired. Below it the app showed
	 * something else there (a dialog, an error, another step of a flow) and comparing the two would
	 * report every element of both. Config key {@code comparison.minSimilarity}.
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
		/** Pairs in which this element changed; for a lost or added component, the elements it is made of. */
		int score;

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
	/** default-graph list index -> language-graph list index of the states compared with each other. */
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();
	public Set<Integer> defltStatesNotProcessed = new HashSet<Integer>();
	public Set<Integer> langStatesNotProcessed = new HashSet<Integer>();

	private final Map<Integer, NodePairing> nodePairings = new HashMap<>();
	/** True when one language is written left to right and the other right to left. */
	private final boolean mirrorExpected;
	/**
	 * Width of the device screen: the furthest right any element of either exploration reaches. The
	 * dump of one screen does not tell it, because that of a dialog only reaches the edges of the
	 * dialog, and the mirror image of an element is taken across the middle of the screen.
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
			findings.addAll(compareStates(source, language, pairing));
			texts.add(source, language);
		}
		// Did this version show other text than the source? When it did not, nothing was translated on
		// screen, and "no findings" says nothing about how a translation would fit.
		dfltLangJSONTrans.put("screenText", texts.toJson());
		if (texts.sameAsSource()) {
			System.out.println("WARNING: the "+destLanguage+" version shows the same text as the source on the screens compared ("
					+texts.unchanged()+" of "+texts.sourceTexts()+" texts): the language did not reach those screens, or they are mostly data."
					+" Finding nothing there does not mean a translation fits.");
		}

		// Most affected first; then by screen and element, so the same data always gives the same file.
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
			ipfs.add(o);
		}
		dfltLangJSONTrans.put("ipfs", ipfs);

		System.out.println("There are "+findings.size()+" Internationalization Presentation Failures for "+destLanguage+" app version.");
		// try-with-resources so the writer is always closed even if a write throws.
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFolder+File.separator+"ipfs.csv", true))) {
			for (Finding f : findings) {
				bw.write(destLanguage+";"+f.stateId+";"+f.sourceNode+";"+f.score+";"+f.languageNode+";"+f.kind);
				bw.newLine();
			}
		}
	}

	/** The breakdown of the findings of this language: how many elements, of which kind, changed in what way. */
	@SuppressWarnings("unchecked")
	private JSONObject summary() {
		long changed = 0;
		long affected = 0;
		long lost = 0;
		long added = 0;
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
			} else if (f.geometry.startsWith("moved") || f.geometry.equals("resized")) {
				changed++;
			} else {
				affected++;
			}
		}
		JSONObject o = new JSONObject();
		o.put("nodesCompared", nodesCompared);
		// the element itself moved or changed size...
		o.put("nodesChanged", changed);
		// ...or it stayed as it was (or was mirrored as expected) and it is its neighbours that changed around it
		o.put("nodesAffected", affected);
		o.put("componentsLost", lost);
		o.put("componentsAdded", added);
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

				// A text changes width when it is translated: its edges move and nothing is wrong.
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
				record(byNode, stateId, i, pi, j, pj, lostAB, addedAB, missingAB);
				record(byNode, stateId, j, pj, i, pi, lostBA, addedBA, missingBA);
			}
		}

		List<Finding> out = new ArrayList<>();
		for (Finding f : byNode.values()) {
			f.geometry = geometry(sourceNodes.get(f.sourceNode), languageNodes.get(f.languageNode));
			out.add(f);
		}

		// An element one screen has and the other does not. Reported once, at the top of what is
		// missing or new: its descendants went or came with it.
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
			return;   // the change is on the other element's side of the pair; this one is only counted
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
	 * The mirrored relations that two sibling elements should have in the other writing direction
	 * and do not have: the one that was left of the other should now be right of it. Alignments are
	 * left out when a text is involved, for the same reason as everywhere else.
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

	/** Whether the element itself is where and as it was: same, moved, resized, moved+resized, or mirrored. */
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

	/** The relations as the reports write them: names in the order of the enum, each followed by a semicolon. */
	private static String join(Set<GraphEdgeType> relations) {
		StringBuilder sb = new StringBuilder();
		for (GraphEdgeType e : relations) {
			sb.append(e.name()).append(';');
		}
		return sb.toString();
	}

	/** Null-safe TextView test: a &lt;node&gt; may have no class attribute (getpClass()==""), and the
	 *  node reference itself may be absent. */
	private static boolean isTextView(AndroidNode node) {
		return node != null && node.getpClass() != null && node.getpClass().contains("TextView");
	}

	/**
	 * Pairs the states of both graphs. When the language graph was produced by REPLAYING the source
	 * exploration, every state carries the source state id it reproduces, so the pairing is by id
	 * (same screen reached through the same taps), and it is accepted when the screen is on the
	 * same activity and enough of its elements can be paired with those of the source
	 * ({@link #setMinSimilarity}). Otherwise falls back to the historical positional pairing with
	 * the raw-XML similarity test, which stops at the first mismatch.
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
			for (int li = 0; li < langStates.size(); li++) {
				State langState = langStates.get(li);
				Integer di = dfltIndexById.get(langState.getSourceStateId());
				if (di == null || statePairing.containsKey(di)) {
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
						statePairing.put(di, li);
						nodePairings.put(di, pairing);
					}
				}
				if (why != null) {
					System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || state "+langState.getSourceStateId()
							+" was reached in "+destLanguage+" but "+why+": not paired");
					JSONObject skipped = new JSONObject();
					skipped.put("stateID", langState.getSourceStateId());
					skipped.put("reason", why);
					notCompared.add(skipped);
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
