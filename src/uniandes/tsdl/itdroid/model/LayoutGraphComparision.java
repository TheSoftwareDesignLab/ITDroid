package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import uniandes.tsdl.itdroid.helper.Helper;

public class LayoutGraphComparision {

	public String defltLanguage;
	public LayoutGraph defltLayoutGraph;
	public String destLanguage;
	public String destLanguageCode;
	public LayoutGraph destLangLayoutGraph;
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();
	public ArrayList<IPF> ipfs = new ArrayList<IPF>();
	public Set<Integer> defltStatesNotProcessed = new HashSet<Integer>();
	public Set<Integer> langStatesNotProcessed = new HashSet<Integer>();
	public HashMap<Integer, Set<GraphEdgeType>[][][]> results = new HashMap<Integer, Set<GraphEdgeType>[][][]>();

	public LayoutGraphComparision(String deftLanguage, LayoutGraph defltGraph, String lang, String rawLang,
			LayoutGraph langGraph, String resultFolderPath, String outputFolder, JSONObject dfltLangJSONTrans)
			throws IOException {

		defltLanguage = deftLanguage;
		defltLayoutGraph = defltGraph;
		destLanguage = lang;
		destLangLayoutGraph = langGraph;
		destLanguageCode = rawLang;

		// Generate the pairing from the states
		pairStates(dfltLangJSONTrans);

		// Compare the states looking for IPFs
		Set<Integer> foundStates = statePairing.keySet();
		Iterator<Integer> fSI = foundStates.iterator();
		while (fSI.hasNext()) {
			int defltState = fSI.next();
			ArrayList<IPF> stateIPFs = compareStates(defltState, defltLayoutGraph.getState(defltState),
					destLangLayoutGraph.getState(defltState));
			ipfs.addAll(stateIPFs);
		}

		if (ipfs.size() > 0) {

			Map<String, Long> result = ipfs.stream()
					.collect(Collectors.groupingBy(w -> w.getID(), Collectors.counting()));
			Set<IPF> uniqueIPFs = new HashSet<IPF>();
			Iterator iter = result.keySet().iterator();
			while (iter.hasNext()) {
				uniqueIPFs.add(new IPF((String) iter.next()));
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
				Set<GraphEdgeType>[][][] relationss = results.get(tempIPF.getStateId() - 1);
				for (int j = 0; relationss != null && relationss[0][tempIPF.getNodePos()] != null
						&& j < relationss[0][tempIPF.getNodePos()].length; j++) {
					if ((relationss[0][tempIPF.getNodePos()][j] != null
							&& relationss[0][tempIPF.getNodePos()][j].size() > 0)
							|| (relationss[1][tempIPF.getNodePos()][j] != null
									&& relationss[1][tempIPF.getNodePos()][j].size() > 0)
							|| (relationss[2][tempIPF.getNodePos()][j] != null
									&& relationss[2][tempIPF.getNodePos()][j].size() > 0)) {
						JSONObject relationsJ = new JSONObject();
						relationsJ.put("relNode", j);
						String added = "";
						Iterator iterr = relationss[1][tempIPF.getNodePos()][j].iterator();
						while (iterr.hasNext()) {
							added += ((GraphEdgeType) iterr.next()).name() + ";";
						}
						relationsJ.put("added", added);
						String removed = "";
						iterr = relationss[0][tempIPF.getNodePos()][j].iterator();
						while (iterr.hasNext()) {
							removed += ((GraphEdgeType) iterr.next()).name() + ";";
						}
						relationsJ.put("removed", removed);
						String missing = "";
						if (relationss[2][tempIPF.getNodePos()][j] != null) {
							iterr = relationss[2][tempIPF.getNodePos()][j].iterator();
							while (iterr.hasNext()) {
								missing += ((GraphEdgeType) iterr.next()).name() + ";";
							}
						}
						relationsJ.put("missing", missing);
						relations.add(relationsJ);
					}
				}
				tempIPFJSON.put("relations", relations);
				ipfs.add(tempIPFJSON);
			}
			dfltLangJSONTrans.put("ipfs", ipfs);

			System.out.println("There are " + uniqueIPFsList.size() + " Internationalization Presentation Failures for "
					+ destLanguage + " app version.");
			BufferedWriter bw = new BufferedWriter(new FileWriter(outputFolder + File.separator + "ipfs.csv", true));
			// bw.write("state;nodePos;ipfScore");
			// bw.newLine();
			for (int i = 0; i < uniqueIPFsList.size(); i++) {
				IPF tempIPF = uniqueIPFsList.get(i);
				bw.write(destLanguage + ";" + tempIPF.getStateId() + ";" + tempIPF.getNodePos() + ";"
						+ result.get(tempIPF.getID()));
				bw.newLine();
			}
			bw.close();

		} else {
			System.out.println(
					"There are not Internationalization Presentation Failures for " + destLanguage + " app version.");
		}

	}

	private ArrayList<IPF> compareStates(int defltState, State dfltState, State langState) {

		Set<GraphEdgeType>[][] dfltGraph = dfltState.getGraph();
		Set<GraphEdgeType>[][] langGraph = langState.getGraph();

		int[] pairedStateNodes = pairStateNodes(dfltState, langState);

		ArrayList<IPF> ipfss = new ArrayList<IPF>();
		Set<GraphEdgeType>[][][] resultts = (Set<GraphEdgeType>[][][]) new Set[3][dfltGraph.length][dfltGraph[0].length];

		int maxI = Math.min(dfltGraph.length, langGraph.length);
		int maxJ = Math.min(dfltGraph[0].length, langGraph[0].length);
		for (int i = 0; i < maxI; i++) {
			for (int j = i; j < maxJ; j++) {

				Set<GraphEdgeType> lostRelationsAB = new HashSet<GraphEdgeType>(dfltGraph[i][j]);
				lostRelationsAB.removeAll(langGraph[i][pairedStateNodes[j]]);

				Set<GraphEdgeType> lostRelationsBA = new HashSet<GraphEdgeType>(dfltGraph[j][i]);
				lostRelationsBA.removeAll(langGraph[pairedStateNodes[j]][pairedStateNodes[i]]);
				// lostRelationsBA.addAll(lostRelationsAB);

				Set<GraphEdgeType> addedRelationsAB = new HashSet<GraphEdgeType>(
						langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);
				addedRelationsAB.removeAll(dfltGraph[i][j]);

				Set<GraphEdgeType> addedRelationsBA = new HashSet<GraphEdgeType>(
						langGraph[pairedStateNodes[j]][pairedStateNodes[i]]);
				addedRelationsBA.removeAll(dfltGraph[j][i]);
				// addedRelationsBA.addAll(addedRelationsAB);

				if ((dfltState.getStateNodes().get(i).getpClass().contains("TextView")
						&& !langState.getStateNodes().get(pairedStateNodes[j]).getpClass().contains("TextView"))
						|| (!dfltState.getStateNodes().get(i).getpClass().contains("TextView")
								&& langState.getStateNodes().get(pairedStateNodes[j]).getpClass().contains("TextView"))
						|| (dfltState.getStateNodes().get(i).getpClass().contains("TextView") && langState
								.getStateNodes().get(pairedStateNodes[j]).getpClass().contains("TextView"))) {
					lostRelationsAB.removeAll(GraphEdgeType.getAligmentTypes());
					lostRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
					addedRelationsAB.removeAll(GraphEdgeType.getAligmentTypes());
					addedRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
				}

				// System.out.println(i + ", " + j);
				// System.out.println("\tdfltGraph[i][j]");
				// System.out.println("\t" + dfltGraph[i][j]);
				// System.out.println("\tlangGraph[i][j]");
				// System.out.println("\t" +
				// langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);
				// System.out.println("\tdfltGraph[j][i]");
				// System.out.println("\t" + dfltGraph[j][i]);
				// System.out.println("\tlangGraph[j][i]");
				// System.out.println("\t" +
				// langGraph[pairedStateNodes[j]][pairedStateNodes[i]]);

				Set<GraphEdgeType> rtlChangeExpectedTypes = GraphEdgeType.getRtlChangeExpectedTypes();

				boolean dfltIsRTL = Helper.getInstance().languageIsRTL(defltLanguage);
				boolean destIsRTL = Helper.getInstance().languageIsRTL(destLanguageCode);

				// Check if the comparison is between RTL and LTR languages.
				if ((!dfltIsRTL && destIsRTL) || (dfltIsRTL && !destIsRTL)) {
					// System.out.println("\tRTL & LTR");
					lostRelationsAB.removeAll(rtlChangeExpectedTypes);
					lostRelationsBA.removeAll(rtlChangeExpectedTypes);
					addedRelationsAB.removeAll(rtlChangeExpectedTypes);
					addedRelationsBA.removeAll(rtlChangeExpectedTypes);

					Set<GraphEdgeType> missingRelationsAB = rtlMissingChanges(i, j, dfltState, dfltGraph[i][j],
							langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);
					if (missingRelationsAB.size() > 0) {
						// System.out.println("\tHAS AN IPF");
						// System.out.println("\t\tMissing Relations: " + missingRelationsAB);

						Set<GraphEdgeType> missingRelationsBA = rtlMissingChanges(i, j, dfltState, dfltGraph[j][i],
								langGraph[pairedStateNodes[i]][pairedStateNodes[j]]);

						// TODO: Verify if the TextFields' / EditText text are being aligned to the
						// right/left. Using XML.

						resultts[2][i][j] = missingRelationsAB;
						resultts[2][j][i] = missingRelationsBA;

						AndroidNode iLangNode = langState.getStateNodes().get(pairedStateNodes[i]);
						AndroidNode jLangNode = langState.getStateNodes().get(pairedStateNodes[j]);

						ipfss.add(new IPF(destLanguage, langState, iLangNode, i));
						ipfss.add(new IPF(destLanguage, langState, jLangNode, j));
					}
				}

				resultts[0][i][j] = lostRelationsAB;
				resultts[0][j][i] = lostRelationsBA;
				resultts[1][i][j] = addedRelationsAB;
				resultts[1][j][i] = addedRelationsBA;

				if ((lostRelationsAB.size() + lostRelationsBA.size() + addedRelationsAB.size()
						+ addedRelationsBA.size()) > 0) {
					AndroidNode iLangNode = langState.getStateNodes().get(pairedStateNodes[i]);
					AndroidNode jLangNode = langState.getStateNodes().get(pairedStateNodes[j]);
					ipfss.add(new IPF(destLanguage, langState, iLangNode, i));
					ipfss.add(new IPF(destLanguage, langState, jLangNode, j));

				}
			}
		}

		results.put(defltState, resultts);

		return ipfss;
	}

	public Set<GraphEdgeType> rtlMissingChanges(int node1, int node2, State dfltState, Set<GraphEdgeType> dfltNode,
			Set<GraphEdgeType> langNode) {
		// If relation includes "contains" then it shouldn't be checked
		if (dfltNode.contains(GraphEdgeType.CONTAINS) || !dfltState.nodesAreSiblings(node1, node2))
			return new HashSet<>();

		Set<GraphEdgeType> missingEdgeTypes = new HashSet<>();
		Set<GraphEdgeType> rtlChangeExpectedTypes = GraphEdgeType.getRtlChangeExpectedTypes();

		Iterator<GraphEdgeType> iter = dfltNode.iterator();
		while (iter.hasNext()) {
			GraphEdgeType dfltEdgeType = iter.next();

			if (rtlChangeExpectedTypes.contains(dfltEdgeType)) {
				GraphEdgeType expectedLangEdgeType = GraphEdgeType.getInverseEdgeType(dfltEdgeType);

				if (!langNode.contains(expectedLangEdgeType)) {
					missingEdgeTypes.add(expectedLangEdgeType);
				}
			}
		}

		return missingEdgeTypes;
	}

	private int[] pairStateNodes(State dfltState, State langState) {

		List<AndroidNode> dfltStateNodes = dfltState.getStateNodes();
		List<AndroidNode> langStateNodes = dfltState.getStateNodes();
		int[] result = new int[Math.min(dfltStateNodes.size(), langStateNodes.size())];

		for (int i = 0; i < result.length; i++) {

			AndroidNode dfltNode = dfltStateNodes.get(i);

			int minIndex = i;
			double minValue = Integer.MAX_VALUE;

			for (int j = 0; j < langStateNodes.size(); j++) {
				AndroidNode langNode = langStateNodes.get(j);
				if (dfltNode.compare(langNode) < minValue) {
					minValue = dfltNode.compare(langNode);
					minIndex = j;
				}
			}
			result[i] = minIndex;
		}
		return result;
	}

	private void pairStates(JSONObject dfltLangJSONTrans) {

		// Pair states from graphs

		boolean sameStates = true;
		ArrayList<State> defltStates = defltLayoutGraph.getStates();
		ArrayList<State> langStates = destLangLayoutGraph.getStates();
		int index = 0;
		while (sameStates && index < defltStates.size() && index < langStates.size()) {
			State dfltTempState = defltStates.get(index);
			State langTempState = langStates.get(index);
			sameStates = dfltTempState.compareTo(langTempState);
			if (sameStates) {
				statePairing.put(index, index);
				index++;
			}
		}
		dfltLangJSONTrans.put("missingDfltStates", (defltStates.size() - index));
		System.out.println("compareLayoutGraph :: " + defltLanguage + "->" + destLanguage + " || There was "
				+ (defltStates.size() - index) + " states in " + defltLanguage + " Graph that were not paired");
		// Add notProcessed states from defltGraph to set
		for (int i = index; i < defltStates.size(); i++) {
			State dfltTempState = defltStates.get(i);
			defltStatesNotProcessed.add(dfltTempState.getId());
		}
		dfltLangJSONTrans.put("missingLangStates", (langStates.size() - index));
		System.out.println("compareLayoutGraph :: " + defltLanguage + "->" + destLanguage + " || There was "
				+ (langStates.size() - index) + " states in " + destLanguage + " Graph that were not paired");
		// Add notProcessed stated from langGraph to set
		for (int i = index; i < langStates.size(); i++) {
			State langTempState = langStates.get(i);
			langStatesNotProcessed.add(langTempState.getId());
		}
	}

}
