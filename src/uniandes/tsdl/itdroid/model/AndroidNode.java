package uniandes.tsdl.itdroid.model;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import uniandes.tsdl.itdroid.helper.Helper;

public class AndroidNode {

	public static String TRUE = "true";
	public static String FALSE = "false";

	private State state;
	private boolean clickable;
	private int[] point1 = {0,0};
	private int[] point2 = {0,0};
	private String pClass = "";
	private boolean enabled;
	private String resourceID="";
	private String text="";
	private String name="";
	private String xPath="";
	private String index="";
	
	@Override
	public String toString() {
		String result = name+xPath+resourceID;
		
		return result;
	}

	public String getIndex(){
		return index;
	}

	public AndroidNode(State state, Node domNode) {
		this.state = state;
		loadAttributesFromDom(domNode);
		String[] classes = pClass.split("\\.");
		NamedNodeMap domAttrs = domNode.getAttributes();
		Node ownIndex = domAttrs != null ? domAttrs.getNamedItem("index") : null;
		String ownIndexValue = ownIndex != null ? ownIndex.getNodeValue() : "";
		xPath = ownIndexValue+"_"+(!pClass.equals("")?classes[classes.length-1]:"")+(!resourceID.equals("")?"/"+resourceID:"");
		Node temp = domNode.getParentNode();
		while(temp != null && !temp.getNodeName().equals("hierarchy")) {
			NamedNodeMap teemp = temp.getAttributes();
			if(teemp == null) {
				temp = temp.getParentNode();
				continue;
			}
			Node classItem = teemp.getNamedItem("class");
			Node indexItem = teemp.getNamedItem("index");
			String [] classess = classItem != null ? classItem.getNodeValue().split("\\.") : new String[]{""};
			String indexx = indexItem != null ? indexItem.getNodeValue() : "";
			xPath=indexx+"_"+classess[classess.length-1]+"/"+xPath;
			temp = temp.getParentNode();
		}
	}

	public void loadAttributesFromDom(Node domNode) {
		NamedNodeMap attributes = domNode.getAttributes();
		name = domNode.getNodeName();
		String attributeValue;
		AndroidNodeProperty androidNodeProperty;
		for (int j = 0; j < attributes.getLength(); j++) {
			Node attribute = attributes.item(j);
			attributeValue = attribute.getNodeValue();
			 androidNodeProperty = AndroidNodeProperty.fromName(attribute.getNodeName());
			if (androidNodeProperty != null) {
				switch (androidNodeProperty) {
				case CLICKABLE:
					clickable = attributeValue.equals(TRUE);
					break;
				case BOUNDS:
					loadBounds(attributeValue);
					break;
				case CLASS:
					pClass = attributeValue;
					break;
				case ENABLED:
					enabled = attributeValue.equals(TRUE);
					break;
				case RESOURCE_ID:
					resourceID = attributeValue;
					break;
				case TEXT:
					text = attributeValue;
					break;
				case INDEX:
					index = attributeValue;
					break;
				default:
					break;
				}
			}
			// Attributes not in AndroidNodeProperty (e.g. NAF, password, scrollable) are simply
			// not part of the UI model and are ignored.
		}
	}
	
	
	
	public String getxPath() {
		return xPath;
	}

	public String getResourceID() {
		return resourceID;
	}

	public String getText() {
		return text;
	}

	public String getName() {
		return name;
	}
	
	

	public int[] getPoint1() {
		return point1;
	}

	public int[] getPoint2() {
		return point2;
	}

	/**
	 * Calculates the bounding box of a node.
	 * @param text Raw input
	 * Initializes point1 and point2
	 */
	public void loadBounds(String text) {
		if (text == null) {
			// keep the default {0,0} points
			return;
		}
		try {
			String bounds = text.replace("][", "/").replace("[", "").replace("]", "");
			bounds += "/0";
			String[] coords = bounds.split("/");
			if (coords.length < 2) {
				System.err.println("AndroidNode.loadBounds :: malformed bounds '"+text+"', using default {0,0}");
				return;
			}
			String[] points1 = coords[0].split(",");
			String[] points2 = coords[1].split(",");
			if (points1.length < 2 || points2.length < 2) {
				System.err.println("AndroidNode.loadBounds :: malformed bounds '"+text+"', using default {0,0}");
				return;
			}
			int x1 = Integer.parseInt(points1[0]);
			int x2 = Integer.parseInt(points2[0]);
			int y1 = Integer.parseInt(points1[1]);
			int y2 = Integer.parseInt(points2[1]);
			point1 = new int[] {x1,y1};
			point2 = new int[] {x2,y2};
		} catch (NumberFormatException e) {
			System.err.println("AndroidNode.loadBounds :: non-numeric bounds '"+text+"', using default {0,0}");
			point1 = new int[] {0,0};
			point2 = new int[] {0,0};
		}
	}

	public boolean isClickable() {
		return clickable;
	}

	public String getpClass() {
		return pClass;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public double compare(AndroidNode langNode) {
		String self = toString();
		// Guard against division by zero when this node has no name/xPath/resourceID: an empty
		// signature is identical (0.0) to another empty one and maximally different (1.0) otherwise.
		if (self.isEmpty()) {
			return langNode.toString().isEmpty() ? 0.0 : 1.0;
		}
		return Helper.levenshteinDistance(self, langNode.toString()) / (double) self.length();
	}

}
