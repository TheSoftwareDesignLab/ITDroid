package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.xml.sax.SAXException;

public class LayoutGraph {


	public static Object AMOUNT_TRANSITIONS = "amountTransitions";
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

			int amountStates = Math.toIntExact((long) obj.get(AMOUNT_STATES));
			int amountTransitions = Math.toIntExact((long) obj.get(AMOUNT_TRANSITIONS));

			JSONObject statess = (JSONObject) obj.get(STATES);

			for (int i = 0; i < amountStates; i++) {
				JSONObject currentState = (JSONObject) statess.get((i+1)+"");
				State tempState = new State(
						Math.toIntExact((long) currentState.get("id")),
						(String) currentState.get("activityName"),
						(String) currentState.get("rawXML"),
						(String) currentState.get("screenShot"));
				states.add(tempState);
			}

			JSONObject transitionss = (JSONObject) obj.get(TRANSITIONS);

			for (int i = 1; i < amountTransitions; i++) {
				JSONObject currentTransition = (JSONObject) transitionss.get(i+"");
				int originState = Math.toIntExact((long) currentTransition.get("stState"));
				TransitionType tType = TransitionType.valueOf((String)currentTransition.get("tranType"));
				int destState = Math.toIntExact((long) currentTransition.get("dsState"));
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
			BufferedWriter bw = new BufferedWriter(new FileWriter(resultFolderPath+File.separator+"graph.txt"));
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
			bw.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
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
