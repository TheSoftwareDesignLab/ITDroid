package uniandes.tsdl.itdroid.model;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.json.simple.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import uniandes.tsdl.itdroid.helper.Helper;

public class State {

	/**
	 * State ID
	 */
	private int id;

	/**
	 * Activity name
	 */
	private String activityName;

	/**
	 * Parsed XML
	 */
	private Document parsedXML;

	/**
	 * Raw XML
	 */
	private String rawXML;

	/**
	 * List of the state elements
	 */
	private List<AndroidNode> stateNodes;

	/**
	 * Array of outbound transitions
	 */
	private List<Transition> outboundTransitions = new ArrayList<Transition>();

	/**
	 * Array of inbound transitions
	 */
	private List<Transition> inboundTransitions = new ArrayList<Transition>();

	private String screenShot;

	private Set<GraphEdgeType>[][] graph;

	/**
	 * Creates a new state
	 * 
	 * @param hybrid            The application is hybrid
	 * @param contextualChanges Contextual changes must be invoked
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws SAXException
	 */
	public State(int id, String activityName, String rawXML, String screenShot)
			throws ParserConfigurationException, SAXException, IOException {
		stateNodes = new ArrayList<AndroidNode>();
		outboundTransitions = new ArrayList<Transition>();
		inboundTransitions = new ArrayList<Transition>();

		this.id = id;
		this.activityName = activityName;
		this.rawXML = rawXML;
		this.screenShot = screenShot;
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();
		InputSource is = new InputSource(new StringReader(rawXML));
		this.parsedXML = builder.parse(is);
		populateStateNodes();
	}

	private void populateStateNodes() {
		NodeList allNodes = parsedXML.getElementsByTagName("node");
		Node currentNode;
		AndroidNode newAndroidNode;
		graph = (Set<GraphEdgeType>[][]) new Set[allNodes.getLength()][allNodes.getLength()];
		for (int i = 0; i < allNodes.getLength(); i++) {
			currentNode = allNodes.item(i);
			newAndroidNode = new AndroidNode(this, currentNode);
			stateNodes.add(newAndroidNode);
			processLastNode();
		}
	}

	private void processLastNode() {
		int lastItemIndex = stateNodes.size() - 1;
		AndroidNode lastItem = stateNodes.get(lastItemIndex);
		for (int i = 0; i < stateNodes.size() - 1; i++) {
			AndroidNode tempItem = stateNodes.get(i);
			graph = compareNodes(lastItem, lastItemIndex, tempItem, i, graph);
		}
		graph[lastItemIndex][lastItemIndex] = new HashSet<GraphEdgeType>();
		graph[lastItemIndex][lastItemIndex].add(GraphEdgeType.DEFAULT);
	}

	private Set<GraphEdgeType>[][] compareNodes(AndroidNode firstNode, int firstNodeIndex, AndroidNode secondNode,
			int secondNodeIndex, Set<GraphEdgeType>[][] tempGraph) {
		int[] firstNodeCoor1 = firstNode.getPoint1();
		int[] firstNodeCoor2 = firstNode.getPoint2();
		int[] secondNodeCoor1 = secondNode.getPoint1();
		int[] secondNodeCoor2 = secondNode.getPoint2();
		int xCoord = 0;
		int yCoord = 1;

		tempGraph[secondNodeIndex][firstNodeIndex] = new HashSet<GraphEdgeType>();
		tempGraph[firstNodeIndex][secondNodeIndex] = new HashSet<GraphEdgeType>();

		if (firstNodeCoor1[yCoord] >= secondNodeCoor2[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.ABOVE);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.BELOW);
		}
		if (firstNodeCoor2[yCoord] <= secondNodeCoor1[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.BELOW);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.ABOVE);
		}
		if (firstNodeCoor1[xCoord] >= secondNodeCoor2[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.LEFT);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.RIGHT);
		}
		if (firstNodeCoor2[xCoord] <= secondNodeCoor1[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.RIGHT);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.LEFT);
		}
		if (firstNodeCoor2[xCoord] == secondNodeCoor2[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.RIGHT_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.RIGHT_ALIGNED);
		}
		if (firstNodeCoor1[xCoord] == secondNodeCoor1[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.LEFT_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.LEFT_ALIGNED);
		}
		if (firstNodeCoor1[yCoord] == secondNodeCoor1[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.TOP_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.TOP_ALIGNED);
		}
		if (firstNodeCoor2[yCoord] == secondNodeCoor2[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.BOTTOM_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.BOTTOM_ALIGNED);
		}
		if (firstNodeCoor1[xCoord] <= secondNodeCoor1[xCoord] && firstNodeCoor1[yCoord] <= secondNodeCoor1[yCoord]
				&& firstNodeCoor2[xCoord] >= secondNodeCoor2[xCoord]
				&& firstNodeCoor2[yCoord] >= secondNodeCoor2[yCoord]) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.CONTAINS);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.CONTAINED);
		}
		if (firstNodeCoor1[xCoord] >= secondNodeCoor1[xCoord] && firstNodeCoor1[yCoord] >= secondNodeCoor1[yCoord]
				&& firstNodeCoor2[xCoord] <= secondNodeCoor2[xCoord]
				&& firstNodeCoor2[yCoord] <= secondNodeCoor2[yCoord]) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.CONTAINED);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.CONTAINS);
		}
		if (tempGraph[firstNodeIndex][secondNodeIndex].size() == 0
				&& tempGraph[secondNodeIndex][firstNodeIndex].size() == 0) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.INTERSECTS);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.INTERSECTS);
		}
		return tempGraph;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getActivityName() {
		return activityName;
	}

	public void setActivityName(String activityName) {
		this.activityName = activityName;
	}

	public Document getParsedXML() {
		return parsedXML;
	}

	public void setParsedXML(Document parsedXML) {
		this.parsedXML = parsedXML;
		generatePossibleTransition();
	}

	public String getRawXML() {
		return rawXML;
	}

	public void setRawXML(String rawXML) {
		this.rawXML = rawXML;
	}

	public void addInboundTransition(Transition pTransition) {
		inboundTransitions.add(pTransition);
	}

	public void addOutboundTransition(Transition pTransition) {
		outboundTransitions.add(pTransition);
	}

	public List<AndroidNode> getStateNodes() {
		return stateNodes;
	}

	public List<Transition> getOutboundTransitions() {
		return outboundTransitions;
	}

	public void setOutboundTransitions(List<Transition> outboundTransitions) {
		this.outboundTransitions = outboundTransitions;
	}

	/**
	 * Checks if two nodes are siblings.
	 * 
	 * @param node1 First node.
	 * @param node2 Second node.
	 * @return True if they're siblings, false otherwise.
	 */
	public boolean nodesAreSiblings(int node1, int node2) {
		AndroidNode androidNode1 = stateNodes.get(node1);
		AndroidNode androidNode2 = stateNodes.get(node2);
		String[] node1PathArray = androidNode1.getxPath().split("/");
		String[] node2PathArray = androidNode2.getxPath().split("/");
		node1PathArray = androidNode1.getxPath().contains(":id")
				? node1PathArray = Arrays.copyOfRange(node1PathArray, 0, node1PathArray.length - 3)
				: Arrays.copyOfRange(node1PathArray, 0, node1PathArray.length - 1);
		node2PathArray = androidNode2.getxPath().contains(":id")
				? node2PathArray = Arrays.copyOfRange(node2PathArray, 0, node2PathArray.length - 3)
				: Arrays.copyOfRange(node2PathArray, 0, node2PathArray.length - 1);

		String node1PathComponent = "";
		String node2PathComponent = "";
		boolean areSiblings = true;

		int largerPath = node1PathArray.length > node2PathArray.length ? node1PathArray.length : node2PathArray.length;

		// Compares if the two nodes have the same path up to the parent node
		for (int i = 0; i < largerPath && areSiblings; i++) {
			try {
				node1PathComponent = node1PathArray[i];
				node2PathComponent = node2PathArray[i];
				areSiblings = node1PathComponent.equals(node2PathComponent);
			} catch (IndexOutOfBoundsException e) {
				areSiblings = false;
			}
		}

		return areSiblings;
	}

	/**
	 * Evaluates the XML view of the file and generate the possible transitions for
	 * the state
	 */
	public void generatePossibleTransition() {

		// GUI interactions
		NodeList allNodes = parsedXML.getElementsByTagName("node");
		Node currentNode;
		AndroidNode newAndroidNode;
		NamedNodeMap attributes;
		for (int i = 0; i < allNodes.getLength(); i++) {
			currentNode = allNodes.item(i);
			newAndroidNode = new AndroidNode(this, currentNode);
			stateNodes.add(newAndroidNode);
			if (newAndroidNode.isAButton() || newAndroidNode.isClickable() || (newAndroidNode.isEnabled())) {
				// possibleTransitions.push(new Transition(this,
				// TransitionType.GUI_CLICK_BUTTON, newAndroidNode));
			}

		}
	}

	public void setScreenShot(String screenShot) {
		this.screenShot = screenShot;
	}

	public String getScreenShot() {
		return screenShot;
	}

	public Set<GraphEdgeType>[][] getGraph() {
		return graph;
	}

	public AndroidNode getAndroidNode(String resourceID, String xpath, String text) {

		for (int i = 0; i < stateNodes.size(); i++) {
			AndroidNode temp = stateNodes.get(i);
			if (temp.getxPath().equals(xpath) && temp.getResourceID().equals(resourceID)
					&& temp.getText().equals(text)) {
				return temp;
			}
		}
		return null;

	}

	@Override
	public String toString() {
		String result = id + " - " + activityName + "\n";
		for (int j = 0; j < outboundTransitions.size(); j++) {
			result += outboundTransitions.get(j).toString() + "\n";
		}
		for (int j = 0; j < stateNodes.size(); j++) {
			result += stateNodes.get(j).toString() + "\n";
		}
		for (int i = 0; i < graph.length; i++) {
			for (int j = 0; j < graph[0].length; j++) {
				result += i + " -> " + j + " := ";
				Iterator<GraphEdgeType> iter = graph[i][j].iterator();
				while (iter.hasNext()) {
					result += iter.next() + ";";
				}
				result += "\n";
			}
		}
		return result;
	}

	public void writeFile(BufferedWriter bw) throws IOException {
		bw.write("State: " + id + " - " + activityName);
		bw.write("Transitions:");
		bw.newLine();
		for (int j = 0; j < outboundTransitions.size(); j++) {
			bw.write("\t" + outboundTransitions.get(j).toString());
			bw.newLine();
		}
		bw.write("Android Nodes:");
		bw.newLine();
		for (int j = 0; j < stateNodes.size(); j++) {
			bw.write("\t" + stateNodes.get(j).toString());
			bw.newLine();
		}
		bw.write("Graph Edges:");
		bw.newLine();
		for (int i = 0; i < graph.length; i++) {
			for (int j = 0; j < graph[0].length; j++) {
				bw.write("\t" + i + " -> " + j + " := ");
				Iterator<GraphEdgeType> iter = graph[i][j].iterator();
				while (iter.hasNext()) {
					bw.write(iter.next() + ";");
				}
				bw.newLine();
			}
		}
	}

	@SuppressWarnings("unchecked")
	public JSONObject getStateInfo() {
		JSONObject state = new JSONObject();
		state.put("id", id);
		state.put("activityName", activityName);
		JSONObject transitions = new JSONObject();
		JSONObject transition;
		Transition t;
		// Get transitions information
		for (int i = 0; i < outboundTransitions.size(); i++) {
			transition = new JSONObject();
			t = outboundTransitions.get(i);
			transition.put("origin", t.getOrigin().getId());
			transition.put("destination", t.getDestination().getId());
			transition.put("type", t.getType().toString());
			transitions.put(i, transition);
		}
		state.put("transitions", transitions);
		// Get nodes information
		JSONObject nodes = new JSONObject();
		JSONObject point1;
		JSONObject point2;
		JSONObject node;
		for (int i = 0; i < stateNodes.size(); i++) {
			node = new JSONObject();
			node.put("index", stateNodes.get(i).getIndex());
			node.put("xPath", stateNodes.get(i).getxPath());
			point1 = new JSONObject();
			point1.put("x", stateNodes.get(i).getPoint1()[0]);
			point1.put("y", stateNodes.get(i).getPoint1()[1]);
			point2 = new JSONObject();
			point2.put("x", stateNodes.get(i).getPoint2()[0]);
			point2.put("y", stateNodes.get(i).getPoint2()[1]);
			node.put("point1", point1);
			node.put("point2", point2);

			nodes.put(i, node);
		}
		state.put("nodes", nodes);
		// Get edges information
		JSONObject edges = new JSONObject();
		JSONObject edge;
		JSONObject relations;
		int totalEdges = 0;
		for (int i = 0; i < graph.length; i++) {
			for (int j = 0; j < graph[0].length; j++) {
				edge = new JSONObject();
				relations = new JSONObject();
				edge.put("origin", i);
				edge.put("destination", j);
				Iterator<GraphEdgeType> iter = graph[i][j].iterator();
				int index = 0;
				while (iter.hasNext()) {
					relations.put(index, iter.next().toString());
					index++;
				}
				edge.put("relations", relations);

				edges.put(totalEdges, edge);
				totalEdges++;
			}
		}
		state.put("edges", edges);
		return state;
	}

	public boolean compareTo(State langTempState) {

		if (!activityName.equals(langTempState.getActivityName())) {
			System.out.println(activityName);
			System.out.println(langTempState.getActivityName());
			return false;
		}
		int amntNodesDiff = Math.abs(stateNodes.size() - langTempState.getStateNodes().size());
		// System.out.println("compareStates :: AmountNodesDiff "+id+"
		// "+langTempState.getId()+" "+amntNodesDiff);
		if (amntNodesDiff > 1) {
			System.out.println(amntNodesDiff);
			return false;
		}
		// false, if the levenshtein distance is greater than 10% of rawXML length
		int acceptancePercentage = 10;
		int lvnshtnDist = Helper.levenshteinDistance(rawXML, langTempState.getRawXML());
		// System.out.println("compareStates :: LevenshteinDist "+id+"
		// "+langTempState.getId()+" "+lvnshtnDist+"
		// "+((lvnshtnDist*100)/rawXML.length()));
		if (lvnshtnDist >= ((rawXML.length() * acceptancePercentage) / 100)) {
			System.out.println(lvnshtnDist);
			System.out.println(((rawXML.length() * acceptancePercentage) / 100));
			return false;
		}
		return true;
	}

}
