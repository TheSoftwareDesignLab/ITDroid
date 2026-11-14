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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.xml.sax.SAXException;

public class LayoutGraph {


	public static String STATES = "states";
	public static String TRANSITIONS = "transitions";

	private String scriptPath;
	private ArrayList<State> states;
	private ArrayList<Transition> transitions;
	private String language;

	/** Loads the graph of one explored app version without writing the debug dumps. */
	public LayoutGraph(String language, String resultFolderPath) {
		this(language, resultFolderPath, false);
	}

	/**
	 * Loads {@code result.json} from the folder. With {@code writeDumps}, also writes the readable
	 * {@code graph.txt} and {@code graph.json} next to it: every relation of every pair of nodes, so
	 * they are large, and nothing in the pipeline reads them. Off by default, so that loading a
	 * graph never modifies the folder it reads (config key {@code debug.graphDumps}).
	 */
	public LayoutGraph(String language, String resultFolderPath, boolean writeDumps) {

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

			// States are keyed by their id. Iterate the keys actually present (in numeric order)
			// instead of assuming a contiguous 1..amountStates range: a replayed exploration keeps
			// the SOURCE ids, so a state the translated build never reached simply leaves a gap.
			List<Integer> ids = new ArrayList<>();
			for (Object key : statess.keySet()) {
				try {
					ids.add(Integer.parseInt(String.valueOf(key)));
				} catch (NumberFormatException e) {
					System.err.println("LayoutGraph :: non-numeric state key '"+key+"' in "+scriptPath+", skipping");
				}
			}
			Collections.sort(ids);
			Map<Integer, State> byId = new HashMap<>();
			for (int key : ids) {
				JSONObject currentState = (JSONObject) statess.get(String.valueOf(key));
				if (currentState == null) {
					continue;
				}
				Object idObj = currentState.get("id");
				int id = idObj != null ? Math.toIntExact((long) idObj) : key;
				State tempState = new State(
						id,
						(String) currentState.get("activityName"),
						(String) currentState.get("rawXML"),
						(String) currentState.get("screenShot"));
				Object sourceId = currentState.get("sourceStateId");
				if (sourceId != null) {
					tempState.setSourceStateId(Math.toIntExact((long) sourceId));
				}
				states.add(tempState);
				byId.put(id, tempState);
			}

			JSONObject transitionss = (JSONObject) obj.get(TRANSITIONS);
			if (transitionss == null) {
				transitionss = new JSONObject();
			}

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
				// States are looked up by id (not list position): skip a transition whose endpoints
				// are not in this graph instead of throwing.
				State origin = byId.get(originState);
				State dest = byId.get(destState);
				if (origin == null || dest == null) {
					System.err.println("LayoutGraph :: transition "+transitionKey+" references an unknown state (origin="+originState+", dest="+destState+"), skipping");
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
				Transition tempTransition = new Transition(origin, tType);
				tempTransition.setDestination(dest);
				if(currentTransition.containsKey("androidNode")) {
					JSONObject androidNode = (JSONObject) currentTransition.get("androidNode");
					String resourceID = (String) androidNode.get("resourceID");
					String xpath = (String) androidNode.get("xpath");
					String text = (String) androidNode.get("text");
					tempTransition.setOriginElement(origin.getAndroidNode(resourceID, xpath, text));
				}
				origin.addOutboundTransition(tempTransition);
				dest.addInboundTransition(tempTransition);
				transitions.add(tempTransition);
			}
			if (writeDumps) {
				writeDumps(resultFolderPath);
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

	/** Writes graph.txt and graph.json (states, nodes and every node-to-node relation) into the folder. */
	@SuppressWarnings("unchecked")
	private void writeDumps(String folder) throws IOException {
		// try-with-resources so the writer is always closed, even if writing throws part-way.
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(folder+File.separator+"graph.txt"))) {
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
			Files.write(Paths.get(folder+File.separator+"graph.json"), langReport.toJSONString().getBytes());
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

	public ArrayList<State> getStates() {
		return states;
	}

	/** The state at list position i (states are ordered by id). */
	public State getState(int i) {
		return states.get(i);
	}

	public ArrayList<Transition> getTransitions() {
		return transitions;
	}


}
