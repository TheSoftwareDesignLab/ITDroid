package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LayoutGraphComparision {

	public String defltLanguage;
	public LayoutGraph defltLayoutGraph;
	public String destLanguage;
	public LayoutGraph destLangLayoutGraph;
	public HashMap<Integer, Integer> statePairing = new HashMap<Integer, Integer>();
	public ArrayList<IPF> ipfs = new ArrayList<IPF>();
	public Set<Integer> defltStatesNotProcessed = new HashSet<Integer>();
	public Set<Integer> langStatesNotProcessed = new HashSet<Integer>();

	public LayoutGraphComparision(String deftLanguage, LayoutGraph defltGraph, String lang, String rawLang, LayoutGraph langGraph, String resultFolderPath, String outputFolder) throws IOException {

		defltLanguage = deftLanguage;
		defltLayoutGraph = defltGraph;
		destLanguage = lang;
		destLangLayoutGraph = langGraph;

		//Generate the pairing from the states
		pairStates();

		//Compare the states looking for IPFs
		Set<Integer> foundStates = statePairing.keySet();
		Iterator<Integer> fSI = foundStates.iterator();
		while(fSI.hasNext()) {
			int defltState = fSI.next(); 
			ArrayList<IPF> stateIPFs = compareStates(defltLayoutGraph.getState(defltState), destLangLayoutGraph.getState(defltState));
			ipfs.addAll(stateIPFs);
		}
		
		if(ipfs.size()>0) {
			
			Map<String, Long> result = ipfs.stream().collect(Collectors.groupingBy(w -> w.getID(), Collectors.counting() ));
			Set<IPF> uniqueIPFs = new HashSet<IPF>(ipfs);
			ArrayList<IPF> uniqueIPFsList = new ArrayList<IPF>(uniqueIPFs);
			IPFComparator comp = new IPFComparator(result);
			uniqueIPFsList.sort(comp);
			
			System.out.println("There are "+uniqueIPFsList.size()+" Internationalization Presentation Failures for "+destLanguage+" app version.");
			BufferedWriter bw = new BufferedWriter(new FileWriter(outputFolder+File.separator+"ipfs.csv", true));
//			bw.write("state;nodePos;ipfScore");
//			bw.newLine();
			for (int i = 0; i < uniqueIPFsList.size(); i++) {
				IPF tempIPF = uniqueIPFsList.get(i);
				bw.write(destLanguage+";"+tempIPF.getState().getId()+";"+tempIPF.getNodePos()+";"+result.get(tempIPF.getID()));
				bw.newLine();
			}
			bw.close();
			
		} else {
			System.out.println("There was not Internationalization Presentation Failures for "+destLanguage+" app version.");
		}
		
		
	}

	private ArrayList<IPF> compareStates(State dfltState, State langState) {

		Set<GraphEdgeType>[][] dfltGraph = dfltState.getGraph();
		Set<GraphEdgeType>[][] langGraph = langState.getGraph();
		ArrayList<IPF> ipfss = new ArrayList<IPF>();
		
		for (int i = 0; i < dfltGraph.length ; i++) {
			for (int j = i; j < dfltGraph[0].length; j++) {

				Set<GraphEdgeType> lostRelationsAB = new HashSet<GraphEdgeType>(dfltGraph[i][j]);
				lostRelationsAB.removeAll(langGraph[i][j]);

				Set<GraphEdgeType> lostRelationsBA = new HashSet<GraphEdgeType>(dfltGraph[j][i]);
				lostRelationsBA.removeAll(langGraph[j][i]);
				lostRelationsBA.addAll(lostRelationsAB);

				Set<GraphEdgeType> addedRelationsAB = new HashSet<GraphEdgeType>(langGraph[i][j]);
				addedRelationsAB.removeAll(dfltGraph[i][j]);

				Set<GraphEdgeType> addedRelationsBA = new HashSet<GraphEdgeType>(langGraph[j][i]);
				addedRelationsBA.removeAll(dfltGraph[j][i]);
				addedRelationsBA.addAll(addedRelationsAB);
				
				if((dfltState.getStateNodes().get(i).getpClass().contains("TextView") && !langState.getStateNodes().get(j).getpClass().contains("TextView"))
						|| (!dfltState.getStateNodes().get(i).getpClass().contains("TextView") && langState.getStateNodes().get(j).getpClass().contains("TextView"))
						|| (dfltState.getStateNodes().get(i).getpClass().contains("TextView") && langState.getStateNodes().get(j).getpClass().contains("TextView"))) {
					lostRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
					addedRelationsBA.removeAll(GraphEdgeType.getAligmentTypes());
				}
				
				if((lostRelationsBA.size()+addedRelationsBA.size())>0) {
					AndroidNode iLangNode = langState.getStateNodes().get(i);
					AndroidNode jLangNode = langState.getStateNodes().get(j);
					ipfss.add(new IPF(destLanguage, langState, iLangNode, i));						
					ipfss.add(new IPF(destLanguage, langState, jLangNode, j));						
					
				}
			}
		}
		
		
		return ipfss;
	}

	private void pairStates() {

		//Pair states from graphs

		boolean sameStates = true;
		ArrayList<State> defltStates = defltLayoutGraph.getStates();
		ArrayList<State> langStates = destLangLayoutGraph.getStates();
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
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || There was "+(defltStates.size()-index)+" states in "+defltLanguage+" Graph that were not paired");
		//Add notProcessed states from defltGraph to set
		for (int i = index; i < defltStates.size(); i++) {
			State dfltTempState = defltStates.get(i);
			defltStatesNotProcessed.add(dfltTempState.getId());
		}
		System.out.println("compareLayoutGraph :: "+defltLanguage+"->"+destLanguage+" || There was "+(langStates.size()-index)+" states in "+destLanguage+" Graph that were not paired");
		//Add notProcessed stated from langGraph to set 
		for (int i = index; i < langStates.size(); i++) {
			State langTempState = langStates.get(i);
			langStatesNotProcessed.add(langTempState.getId());
		}
	}



}
