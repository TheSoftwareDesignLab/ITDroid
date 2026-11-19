package uniandes.tsdl.itdroid.model;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class State {

	private int id;

	private String activityName;

	private Document parsedXML;

	private String rawXML;

	private List<AndroidNode> stateNodes;

	private String screenShot;

	private Set<GraphEdgeType>[][] graph;

	/** Id of the source-language state this one reproduces (replayed explorations), 0 when unknown. */
	private int sourceStateId;

	/** Parses the raw XML dump of a screen into its nodes and the relations between them. */
	public State(int id, String activityName, String rawXML, String screenShot) throws ParserConfigurationException, SAXException, IOException {
		stateNodes = new ArrayList<AndroidNode>();

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

	@SuppressWarnings("unchecked")
	private void populateStateNodes() {
		NodeList allNodes = parsedXML.getElementsByTagName("node");
		Node currentNode;
		AndroidNode newAndroidNode;
		graph = (Set<GraphEdgeType>[][]) new Set[allNodes.getLength()][allNodes.getLength()];
		// The nodes come in document order, so a parent is always placed before its children.
		Map<Node, Integer> positions = new IdentityHashMap<>();
		for (int i = 0; i < allNodes.getLength(); i++) {
			currentNode = allNodes.item(i);
			newAndroidNode = new AndroidNode(currentNode);
			Integer parent = positions.get(currentNode.getParentNode());
			newAndroidNode.placeInTree(i, parent == null ? -1 : parent);
			if (parent != null) {
				stateNodes.get(parent).addChild(i);
			}
			positions.put(currentNode, i);
			stateNodes.add(newAndroidNode);
			processLastNode();
		}
	}

	/**
	 * Pixels two boxes may overlap and still be side by side or one above the other. The layout rounds
	 * every edge, so two elements that touch can share a pixel, and a mirrored layout rounds them again.
	 */
	public static final int SLACK = 2;

	/** How much two intervals share, negative when they are apart. */
	private static int overlap(int a1, int a2, int b1, int b2) {
		return Math.min(a2, b2) - Math.max(a1, b1);
	}

	/**
	 * True when two intervals cross by a sliver, at most {@link #SLACK} pixels and less than half of the
	 * shorter one, which is the rounding of the layout and not an overlap. An element cut by the edge of
	 * the screen down to a pixel or two is not a sliver of what it touches.
	 */
	private static boolean sliver(int a1, int a2, int b1, int b2) {
		int o = overlap(a1, a2, b1, b2);
		return o > 0 && o <= SLACK && 2 * o < Math.min(a2 - a1, b2 - b1);
	}

	/** True when the two boxes share part of their height, more than a sliver, so they are in one row. */
	public static boolean sameRow(AndroidNode a, AndroidNode b) {
		int a1 = a.getPoint1()[1], a2 = a.getPoint2()[1], b1 = b.getPoint1()[1], b2 = b.getPoint2()[1];
		return overlap(a1, a2, b1, b2) > 0 && !sliver(a1, a2, b1, b2);
	}

	/** True when both nodes hang from the same parent (two top-level nodes count as siblings). */
	public boolean nodesAreSiblings(int node1, int node2) {
		return stateNodes.get(node1).getParentPosition() == stateNodes.get(node2).getParentPosition();
	}

	private void processLastNode() {
		int lastItemIndex = stateNodes.size()-1;
		AndroidNode lastItem = stateNodes.get(lastItemIndex);
		for (int i = 0; i < stateNodes.size()-1; i++) {
			AndroidNode tempItem = stateNodes.get(i);
			graph = compareNodes(lastItem, lastItemIndex, tempItem, i, graph);
		}
		graph[lastItemIndex][lastItemIndex] = EnumSet.of(GraphEdgeType.DEFAULT);
	}

	private Set<GraphEdgeType>[][] compareNodes(AndroidNode firstNode, int firstNodeIndex, AndroidNode secondNode,
			int secondNodeIndex, Set<GraphEdgeType>[][] tempGraph) {
		int[] firstNodeCoor1 = firstNode.getPoint1();
		int[] firstNodeCoor2 = firstNode.getPoint2();
		int[] secondNodeCoor1 = secondNode.getPoint1();
		int[] secondNodeCoor2 = secondNode.getPoint2();
		int xCoord = 0;
		int yCoord = 1;

		tempGraph[secondNodeIndex][firstNodeIndex] = EnumSet.noneOf(GraphEdgeType.class);
		tempGraph[firstNodeIndex][secondNodeIndex] = EnumSet.noneOf(GraphEdgeType.class);

		// two boxes that cross by a sliver still count as one after the other
		boolean sliverY = sliver(firstNodeCoor1[yCoord], firstNodeCoor2[yCoord], secondNodeCoor1[yCoord], secondNodeCoor2[yCoord]);
		boolean sliverX = sliver(firstNodeCoor1[xCoord], firstNodeCoor2[xCoord], secondNodeCoor1[xCoord], secondNodeCoor2[xCoord]);
		if(firstNodeCoor1[yCoord]>=secondNodeCoor2[yCoord] || (sliverY && firstNodeCoor1[yCoord]>secondNodeCoor1[yCoord])) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.ABOVE);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.BELOW);
		}
		if(firstNodeCoor2[yCoord]<=secondNodeCoor1[yCoord] || (sliverY && firstNodeCoor1[yCoord]<secondNodeCoor1[yCoord])) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.BELOW);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.ABOVE);
		}
		if(firstNodeCoor1[xCoord]>=secondNodeCoor2[xCoord] || (sliverX && firstNodeCoor1[xCoord]>secondNodeCoor1[xCoord])) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.LEFT);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.RIGHT);
		}
		if(firstNodeCoor2[xCoord]<=secondNodeCoor1[xCoord] || (sliverX && firstNodeCoor1[xCoord]<secondNodeCoor1[xCoord])) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.RIGHT);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.LEFT);
		}
		if(firstNodeCoor2[xCoord]==secondNodeCoor2[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.RIGHT_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.RIGHT_ALIGNED);
		}
		if(firstNodeCoor1[xCoord]==secondNodeCoor1[xCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.LEFT_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.LEFT_ALIGNED);
		}
		if(firstNodeCoor1[yCoord]==secondNodeCoor1[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.TOP_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.TOP_ALIGNED);
		}
		if(firstNodeCoor2[yCoord]==secondNodeCoor2[yCoord]) {
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.BOTTOM_ALIGNED);
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.BOTTOM_ALIGNED);
		}
		if(firstNodeCoor1[xCoord]<=secondNodeCoor1[xCoord]
				&& firstNodeCoor1[yCoord]<=secondNodeCoor1[yCoord]
						&& firstNodeCoor2[xCoord]>=secondNodeCoor2[xCoord]
								&& firstNodeCoor2[yCoord]>=secondNodeCoor2[yCoord]) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.CONTAINS);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.CONTAINED);
		}
		if(firstNodeCoor1[xCoord]>=secondNodeCoor1[xCoord]
				&& firstNodeCoor1[yCoord]>=secondNodeCoor1[yCoord]
						&& firstNodeCoor2[xCoord]<=secondNodeCoor2[xCoord]
								&& firstNodeCoor2[yCoord]<=secondNodeCoor2[yCoord]) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.CONTAINED);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.CONTAINS);
		}
		// Two elements intersect when they share area, more than a sliver each way, and neither holds the
		// other, even when aligned, as two buttons of one row overlapped by a longer text are.
		boolean shareArea = firstNodeCoor1[xCoord] < secondNodeCoor2[xCoord] && secondNodeCoor1[xCoord] < firstNodeCoor2[xCoord]
				&& firstNodeCoor1[yCoord] < secondNodeCoor2[yCoord] && secondNodeCoor1[yCoord] < firstNodeCoor2[yCoord]
				&& !sliverX && !sliverY;
		boolean oneHoldsTheOther = tempGraph[firstNodeIndex][secondNodeIndex].contains(GraphEdgeType.CONTAINS)
				|| tempGraph[firstNodeIndex][secondNodeIndex].contains(GraphEdgeType.CONTAINED);
		if((shareArea && !oneHoldsTheOther) || (tempGraph[firstNodeIndex][secondNodeIndex].size()==0
				&& tempGraph[secondNodeIndex][firstNodeIndex].size()==0)) {
			tempGraph[firstNodeIndex][secondNodeIndex].add(GraphEdgeType.INTERSECTS);
			tempGraph[secondNodeIndex][firstNodeIndex].add(GraphEdgeType.INTERSECTS);
		}
		return tempGraph;
	}

	public int getId() {
		return id;
	}

	public int getSourceStateId() {
		return sourceStateId;
	}

	public void setSourceStateId(int sourceStateId) {
		this.sourceStateId = sourceStateId;
	}

	/**
	 * First check for a state reached by replaying the same taps in another language, which must be
	 * on the same activity. Whether it is the same screen is then decided by {@link NodePairing}.
	 */
	public boolean matchesReplayed(State other) {
		return activityName == null ? other.getActivityName() == null : activityName.equals(other.getActivityName());
	}

	public String getActivityName() {
		return activityName;
	}

	public String getRawXML() {
		return rawXML;
	}

	public List<AndroidNode> getStateNodes() {
		return stateNodes;
	}

	public String getScreenShot() {
		return screenShot;
	}

	public Set<GraphEdgeType>[][] getGraph() {
		return graph;
	}

	@Override
	public String toString() {
		return id + " - " + activityName + " (" + stateNodes.size() + " elements)";
	}

}
