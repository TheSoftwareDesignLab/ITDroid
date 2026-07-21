package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.xml.sax.SAXException;

public class LayoutGraph {


	public static String STATES = "states";
	public static String TRANSITIONS = "transitions";
	public static String AMOUNT_STATES = "amountStates";

	private String scriptPath;
	private ArrayList<State> states;
	private ArrayList<Transition> transitions;
	private String language;
	
	public LayoutGraph(String language, String resultFolderPath) {

		scriptPath = resultFolderPath+File.separator+"result.json";
		this.language = language;
		states = new ArrayList<State>();
		transitions = new ArrayList<Transition>();

		//JSON parser object to parse read file
		JSONParser jsonParser = new JSONParser();

		try (FileReader reader = new FileReader(scriptPath))
		{
			//Read JSON file
			JSONObject obj = (JSONObject) jsonParser.parse(reader);

			JSONObject statess = (JSONObject) obj.get(STATES);
			if (statess == null) {
				statess = new JSONObject();
			}

			// Guard the amountStates cast: fall back to the number of state entries actually present
			// when the key is absent/null instead of NPEing on the unboxing.
			Object amountStatesObj = obj.get(AMOUNT_STATES);
			int amountStates = amountStatesObj != null ? Math.toIntExact((long) amountStatesObj) : statess.size();

			for (int i = 0; i < amountStates; i++) {
				JSONObject currentState = (JSONObject) statess.get((i+1)+"");
				// amountStates may exceed the number of entries (or keys may not be exactly 1..N):
				// skip a missing state instead of NPEing on currentState.get(...).
				if (currentState == null) {
					System.err.println("LayoutGraph :: state "+(i+1)+" missing in "+scriptPath+", skipping");
					continue;
				}
				Object idObj = currentState.get("id");
				if (idObj == null) {
					System.err.println("LayoutGraph :: state "+(i+1)+" has no id in "+scriptPath+", skipping");
					continue;
				}
				State tempState = new State(
						Math.toIntExact((long) idObj),
						(String) currentState.get("activityName"),
						(String) currentState.get("rawXML"),
						(String) currentState.get("screenShot"));
				states.add(tempState);
			}

			JSONObject transitionss = (JSONObject) obj.get(TRANSITIONS);

			// Iterate over the keys actually present so no transition is dropped, regardless
			// of whether the explorer output keys them 0- or 1-based (the previous loop started at
			// index 1 and stopped before the last key, losing one transition).
			for (Object transitionKey : transitionss.keySet()) {
				JSONObject currentTransition = (JSONObject) transitionss.get(transitionKey);
				if (currentTransition == null) {
					continue;
				}
				Object stStateObj = currentTransition.get("stState");
				Object dsStateObj = currentTransition.get("dsState");
				if (stStateObj == null || dsStateObj == null) {
					System.err.println("LayoutGraph :: transition "+transitionKey+" missing stState/dsState, skipping");
					continue;
				}
				int originState = Math.toIntExact((long) stStateObj);
				int destState = Math.toIntExact((long) dsStateObj);
				// originState/destState are 1-based ids used directly as list indices; skip the
				// transition when they fall outside [1, states.size()] instead of throwing.
				if (originState-1 < 0 || originState-1 >= states.size()
						|| destState-1 < 0 || destState-1 >= states.size()) {
					System.err.println("LayoutGraph :: transition "+transitionKey+" references out-of-range state (origin="+originState+", dest="+destState+", states="+states.size()+"), skipping");
					continue;
				}
				// An unknown or null transition type must not abort the whole graph load: warn and skip.
				TransitionType tType;
				try {
					tType = TransitionType.valueOf((String)currentTransition.get("tranType"));
				} catch (IllegalArgumentException | NullPointerException e) {
					System.err.println("LayoutGraph :: transition "+transitionKey+" has unknown/missing type '"+currentTransition.get("tranType")+"', skipping");
					continue;
				}
				Transition tempTransition = new Transition(states.get(originState-1), tType);
				tempTransition.setDestination(states.get(destState-1));
				if(currentTransition.containsKey("androidNode")) {
					JSONObject androidNode = (JSONObject) currentTransition.get("androidNode");
					String resourceID = (String) androidNode.get("resourceID");
					String xpath = (String) androidNode.get("xpath");
					String text = (String) androidNode.get("text");
					tempTransition.setOriginElement(states.get(originState-1).getAndroidNode(resourceID, xpath, text));
				}
				states.get(originState-1).addOutboundTransition(tempTransition);
				states.get(destState-1).addInboundTransition(tempTransition);
				transitions.add(tempTransition);
			}
			// try-with-resources so the writer is always closed, even if writing throws part-way.
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(resultFolderPath+File.separator+"graph.txt"))) {
				bw.write("-------------------------");
				bw.newLine();
				bw.write("Language Result for: "+language);
				bw.newLine();
				bw.write("-------------------------");
				bw.newLine();
				bw.write("States: ");
				bw.newLine();
				for (int i = 0; i < states.size(); i++) {
					states.get(i).writeFile(bw);
				}
				JSONObject langReport = new JSONObject();
				langReport.put("language", language);
				JSONObject jsonStates = new JSONObject();
				for(int i = 0; i < states.size(); i++){
					jsonStates.put(i, states.get(i).getStateInfo());
				}
				langReport.put("states", jsonStates);
				Files.write(Paths.get(resultFolderPath+File.separator+"graph.json"), langReport.toJSONString().getBytes());
			}
		} catch (FileNotFoundException e) {
			// Fail fast on a genuine load failure rather than leaving a truncated graph that would
			// silently corrupt the downstream comparison.
			System.err.println("LayoutGraph :: result.json not found at "+scriptPath);
			throw new RuntimeException(e);
		} catch (IOException e) {
			System.err.println("LayoutGraph :: I/O error loading graph from "+scriptPath);
			throw new RuntimeException(e);
		} catch (ParseException e) {
			System.err.println("LayoutGraph :: malformed JSON in "+scriptPath);
			throw new RuntimeException(e);
		} catch (ParserConfigurationException e) {
			System.err.println("LayoutGraph :: XML parser configuration error while building a state");
			throw new RuntimeException(e);
		} catch (SAXException e) {
			System.err.println("LayoutGraph :: malformed state XML in "+scriptPath);
			throw new RuntimeException(e);
		}

	}

	@Override
	public String toString() {
		String result = "-------------------------\n";
		result += "Language Result for: "+language+"\n";
		result += "-------------------------\n";
		result += "States: \n";
		for (int i = 0; i < states.size(); i++) {
			result += states.get(i).toString()+"\n";
		}
		return result;
	}

	public String getScriptPath() {
		return scriptPath;
	}

	public void setScriptPath(String scriptPath) {
		this.scriptPath = scriptPath;
	}

	public ArrayList<State> getStates() {
		return states;
	}
	
	public State getState(int i) {
		return states.get(i);
	}

	public void setStates(ArrayList<State> states) {
		this.states = states;
	}

	public ArrayList<Transition> getTransitions() {
		return transitions;
	}

	public void setTransitions(ArrayList<Transition> transitions) {
		this.transitions = transitions;
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}
	
	
}
