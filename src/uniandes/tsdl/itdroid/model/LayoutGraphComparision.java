package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class LayoutGraphComparision {

	public String defltLanguage;
	public LayoutGraph defltLayoutGraph;
	public String destLanguage;
	public LayoutGraph destLangLayoutGraph;
	/** default-graph list index -> language-graph list index of the states compared with each other. */
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();
	public ArrayList<IPF> ipfs = new ArrayList<IPF>();
	public Set<Integer> defltStatesNotProcessed = new HashSet<Integer>();
	public Set<Integer> langStatesNotProcessed = new HashSet<Integer>();
	public HashMap<Integer,Set<GraphEdgeType>[][][]> results = new HashMap<Integer,Set<GraphEdgeType>[][][]>();

	@SuppressWarnings("unchecked")
	public LayoutGraphComparision(String deftLanguage, LayoutGraph defltGraph, String lang, String rawLang, LayoutGraph langGraph, String resultFolderPath, String outputFolder, JSONObject dfltLangJSONTrans) throws IOException {

		defltLanguage = deftLanguage;
		defltLayoutGraph = defltGraph;
		destLanguage = lang;
		destLangLayoutGraph = langGraph;

		//Generate the pairing from the states
		pairStates(dfltLangJSONTrans);

		//Compare the states looking for IPFs
		List<Integer> dfltIndexes = new ArrayList<>(statePairing.keySet());
		java.util.Collections.sort(dfltIndexes);
		for (int defltState : dfltIndexes) {
			int langState = statePairing.get(defltState);
			ArrayList<IPF> stateIPFs = compareStates(defltState, defltLayoutGraph.getState(defltState), destLangLayoutGraph.getState(langState));
			ipfs.addAll(stateIPFs);
		}

		if(ipfs.size()>0) {

			Map<String, Long> result = ipfs.stream().collect(Collectors.groupingBy(w -> w.getID(), Collectors.counting() ));
			Set<IPF> uniqueIPFs = new HashSet<IPF>();
			Iterator<String> iter = result.keySet().iterator();
			while(iter.hasNext()) {
				uniqueIPFs.add(new IPF(iter.next()));
			}
			dfltLangJSONTrans.put("amIPFs", uniqueIPFs.size());
			ArrayList<IPF> uniqueIPFsList = new ArrayList<IPF>(uniqueIPFs);
			IPFComparator comp = new IPFComparator(result);
			uniqueIPFsList.sort(comp);
			JSONArray ipfs = new JSONArray();
			for (int i = 0; i < uniqueIPFsList.size(); i++) {
				IPF tempIPF = uniqueIPFsList.get(i);
				JSONObject tempIPFJSON = new JSONObject();
				tempIPFJSON.put("stateID", tempIPF.getStateId());
				tempIPFJSON.put("nodeID", tempIPF.getNodePos());
				JSONArray relations = new JSONArray();
				// Read using the same key the write side stores under (the state id) so relations are
				// not silently empty when a state's JSON id != its list index + 1.
				Set<GraphEdgeType>[][][] relationss = results.get(tempIPF.getStateId());
				for (int j = 0; relationss!= null && relationss[0][tempIPF.getNodePos()] != null && j < relationss[0][tempIPF.getNodePos()].length; j++) {
					if((relationss[0][tempIPF.getNodePos()][j]!= null && relationss[0][tempIPF.getNodePos()][j].size()>0) ||(relationss[1][tempIPF.getNodePos()][j] != null && relationss[1][tempIPF.getNodePos()][j].size()>0)) {
						JSONObject relationsJ = new JSONObject();
						relationsJ.put("relNode", j);
						String added = "";
						Iterator<GraphEdgeType> iterr = relationss[1][tempIPF.getNodePos()][j].iterator();
						while(iterr.hasNext()) {
							added += iterr.next().name()+";";
						}
						relationsJ.put("added", added);
						String removed = "";
						iterr = relationss[0][tempIPF.getNodePos()][j].iterator();
						while(iterr.hasNext()) {
							removed += iterr.next().name()+";";
						}
						relationsJ.put("removed", removed);
						relations.add(relationsJ);
					}
				}
				tempIPFJSON.put("relations", relations);
				ipfs.add(tempIPFJSON);
			}
			dfltLangJSONTrans.put("ipfs", ipfs);

			System.out.println("There are "+uniqueIPFsList.size()+" Internationalization Presentation Failures for "+destLanguage+" app version.");
			// try-with-resources so the writer is always closed even if a write throws.
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFolder+File.separator+"ipfs.csv", true))) {
				for (int i = 0; i < uniqueIPFsList.size(); i++) {
					IPF tempIPF = uniqueIPFsList.get(i);
					bw.write(destLanguage+";"+tempIPF.getStateId()+";"+tempIPF.getNodePos()+";"+result.get(tempIPF.getID()));
					bw.newLine();
				}
			}

		} else {
			dfltLangJSONTrans.put("amIPFs", 0);
			System.out.println("There are not Internationalization Presentation Failures for "+destLanguage+" app version.");
		}


	}

	@SuppressWarnings("unchecked")
	private ArrayList<IPF> compareStates(int defltState, State dfltState, State langState) {

		Set<GraphEdgeType>[][] dfltGraph = dfltState.getGraph();
		Set<GraphEdgeType>[][] langGraph = langState.getGraph();

		// A paired state can have zero <node> elements, giving a new Set[0][0] graph; the [0]
		// dereferences below (and Math.min on [0].length) would throw. Nothing to compare here.
		if (dfltGraph.length == 0 || langGraph.length == 0) {
			return new ArrayList<IPF>();
		}

		int[] pairedStateNodes = pairStateNodes(dfltState, langState);

		ArrayList<IPF> ipfss = new ArrayList<IPF>();
		Set<GraphEdgeType>[][][] resultts = (Set<GraphEdgeType>[][][]) new Set[2][dfltGraph.length][dfltGraph[0].length];

		int maxI = Math.min(dfltGraph.length,langGraph.length);
		int maxJ = Math.min(dfltGraph[0].length,langGraph[0].length);
		for (int i = 0; i < maxI ; i++) {
			for (int j = i; j < maxJ; j++) {

				Set<GraphEdgeType> lostRelationsAB = new HashSet<GraphEdgeType>(dfltGraph[i][j]);
				// Use pairedStateNodes[i] for the lang row so both endpoints are remapped consistently,
				// matching the addedRelationsAB read below (langGraph[pairedStateNodes[i]][pairedStateNodes[j]]).
				lostRelationsAB.removeAll(langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);

				Set<GraphEdgeType> lostRelationsBA = new HashSet<GraphEdgeType>(dfltGraph[j][i]);
				lostRelationsBA.removeAll(langGraph[pairedStateNodes[j]][pairedStateNodes[i]]);

				Set<GraphEdgeType> addedRelationsAB = new HashSet<GraphEdgeType>(langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);
				addedRelationsAB.removeAll(dfltGraph[i][j]);

				Set<GraphEdgeType> addedRelationsBA = new HashSet<GraphEdgeType>(langGraph[pairedStateNodes[j]][pairedStateNodes[i]]);
				addedRelationsBA.removeAll(dfltGraph[j][i]);

				// Compare the SAME endpoint on both sides (dflt node i vs its pair langNode pairedStateNodes[i]),
				// and null-guard getpClass() so a <node> without a class attribute can't NPE here.
				boolean dfltIIsTextView = isTextView(dfltState.getStateNodes().get(i));
				boolean langIIsTextView = isTextView(langState.getStateNodes().get(pairedStateNodes[i]));
				if((dfltIIsTextView && !langIIsTextView)
						|| (!dfltIIsTextView && langIIsTextView)
						|| (dfltIIsTextView && langIIsTextView)) {
					lostRelationsAB.removeAll(GraphEdgeType.getAligmentTypes());
					lostRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
					addedRelationsAB.removeAll(GraphEdgeType.getAligmentTypes());
					addedRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
				}

				resultts[0][i][j]=lostRelationsAB;
				resultts[0][j][i]=lostRelationsBA;
				resultts[1][i][j]=addedRelationsAB;
				resultts[1][j][i]=addedRelationsBA;

				if((lostRelationsAB.size()+lostRelationsBA.size()+addedRelationsAB.size()+addedRelationsBA.size())>0) {
					AndroidNode iLangNode = langState.getStateNodes().get(pairedStateNodes[i]);
					AndroidNode jLangNode = langState.getStateNodes().get(pairedStateNodes[j]);
					ipfss.add(new IPF(destLanguage, langState, iLangNode, i));
					ipfss.add(new IPF(destLanguage, langState, jLangNode, j));

				}
			}
		}

		// Key results by the state id (the same value the read side uses via IPF.getStateId()) so the
		// emitted relations aren't looked up under a mismatched key.
		results.put(langState.getId(), resultts);

		return ipfss;
	}

	/** Null-safe TextView test: a &lt;node&gt; may have no class attribute (getpClass()==""), and the
	 *  node reference itself may be absent. */
	private static boolean isTextView(AndroidNode node) {
		return node != null && node.getpClass() != null && node.getpClass().contains("TextView");
	}

	private int[] pairStateNodes(State dfltState, State langState) {

		List<AndroidNode> dfltStateNodes = dfltState.getStateNodes();
		// INTENTIONAL: both lists are the DEFAULT state's nodes, which makes the pairing the identity
		// [0,1,2,...] so the default and translated graphs are compared POSITIONALLY. Pairing against
		// langState by Levenshtein text similarity was tried and reverted: translated text differs
		// across languages and paired the wrong nodes. Do NOT change this to langState.getStateNodes().
		List<AndroidNode> langStateNodes = dfltState.getStateNodes();
		int[] result = new int[Math.min(dfltStateNodes.size(), langStateNodes.size())];

		for (int i = 0; i < result.length; i++) {

			AndroidNode dfltNode = dfltStateNodes.get(i);

			int minIndex = i;
			double minValue = Integer.MAX_VALUE;

			for (int j = 0; j < langStateNodes.size(); j++) {
				AndroidNode langNode = langStateNodes.get(j);
				if(dfltNode.compare(langNode)<minValue) {
					minValue = dfltNode.compare(langNode);
					minIndex = j;
				}
			}
			result[i]=minIndex;
		}
		return result;
	}

	/**
	 * Pairs the states of both graphs. When the language graph was produced by REPLAYING the source
	 * exploration, every state carries the source state id it reproduces, so the pairing is by id
	 * (same screen reached through the same taps), checked only loosely (same activity, similar
	 * node count). Otherwise falls back to the historical positional pairing with the raw-XML
	 * similarity test, which stops at the first mismatch.
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
				if (defltStates.get(di).matchesReplayed(langState)) {
					statePairing.put(di, li);
				} else {
					System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || state "+langState.getSourceStateId()
							+" was reached in "+destLanguage+" but shows a different screen ("+langState.getActivityName()+", "
							+langState.getStateNodes().size()+" nodes vs "+defltStates.get(di).getStateNodes().size()+"): not paired");
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
