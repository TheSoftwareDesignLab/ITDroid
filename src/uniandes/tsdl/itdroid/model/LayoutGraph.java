package uniandes.tsdl.itdroid.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.xml.sax.SAXException;

/** The screens of one explored app version, loaded from its result.json, and how many transitions joined them. */
public class LayoutGraph {

	public static final String STATES = "states";
	public static final String TRANSITIONS = "transitions";

	/** The folder of result.json, which the screenshot paths are relative to. */
	private final String folder;
	private final ArrayList<State> states = new ArrayList<>();
	private int transitions;

	/** Loads result.json from the folder, failing when it is missing or malformed. */
	public LayoutGraph(String resultFolderPath) {
		folder = resultFolderPath;
		String resultFile = resultFolderPath + File.separator + "result.json";
		try (FileReader reader = new FileReader(resultFile)) {
			JSONObject obj = (JSONObject) new JSONParser().parse(reader);

			JSONObject stateObjects = (JSONObject) obj.get(STATES);
			if (stateObjects == null) {
				stateObjects = new JSONObject();
			}
			// The ids present, in order, as a replayed exploration keeps the source ids and a state the
			// translated build never reached leaves a gap.
			List<Integer> ids = new ArrayList<>();
			for (Object key : stateObjects.keySet()) {
				try {
					ids.add(Integer.parseInt(String.valueOf(key)));
				} catch (NumberFormatException e) {
					System.err.println("LayoutGraph :: non-numeric state key '" + key + "' in " + resultFile + ", skipping");
				}
			}
			Collections.sort(ids);
			Set<Integer> known = new HashSet<>();
			for (int key : ids) {
				JSONObject current = (JSONObject) stateObjects.get(String.valueOf(key));
				if (current == null) {
					continue;
				}
				Object idObj = current.get("id");
				int id = idObj != null ? Math.toIntExact((long) idObj) : key;
				State state = new State(id, (String) current.get("activityName"), (String) current.get("rawXML"),
						(String) current.get("screenShot"));
				Object sourceId = current.get("sourceStateId");
				if (sourceId != null) {
					state.setSourceStateId(Math.toIntExact((long) sourceId));
				}
				states.add(state);
				known.add(id);
			}

			// Transitions are only counted, those between two screens of this graph.
			JSONObject transitionObjects = (JSONObject) obj.get(TRANSITIONS);
			if (transitionObjects != null) {
				for (Object value : transitionObjects.values()) {
					JSONObject t = (JSONObject) value;
					Object from = t == null ? null : t.get("stState");
					Object to = t == null ? null : t.get("dsState");
					if (from instanceof Long && to instanceof Long
							&& known.contains(Math.toIntExact((long) from)) && known.contains(Math.toIntExact((long) to))) {
						transitions++;
					}
				}
			}
		} catch (FileNotFoundException e) {
			// Fail fast rather than compare a truncated graph.
			System.err.println("LayoutGraph :: result.json not found at " + resultFile);
			throw new RuntimeException(e);
		} catch (IOException | ParseException | ParserConfigurationException | SAXException e) {
			System.err.println("LayoutGraph :: could not load " + resultFile + ": " + e);
			throw new RuntimeException(e);
		}
	}

	public ArrayList<State> getStates() {
		return states;
	}

	/** The state at list position i (states are ordered by id). */
	public State getState(int i) {
		return states.get(i);
	}

	/** The folder of result.json, which the screenshot paths are relative to. */
	public String getFolder() {
		return folder;
	}

	/** Transitions recorded between two screens of this graph. */
	public int getTransitionCount() {
		return transitions;
	}
}
