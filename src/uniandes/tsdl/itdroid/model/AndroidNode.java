package uniandes.tsdl.itdroid.model;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import uniandes.tsdl.itdroid.helper.Helper;

public class AndroidNode {

	public static String TRUE = "true";
	public static String FALSE = "false";

	private State state;
	private boolean interacted;
	private boolean clickable;
	private int[] centralPoint;
	private int[] point1;
	private int[] point2;
	private String pClass;
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
		xPath = domNode.getAttributes().getNamedItem("index").getNodeValue()+"_"+(!pClass.equals("")?classes[classes.length-1]:"")+(!resourceID.equals("")?"/"+resourceID:"");
		Node temp = domNode.getParentNode();
		while(!temp.getNodeName().equals("hierarchy")) {
			NamedNodeMap teemp = temp.getAttributes();
			String [] classess = teemp.getNamedItem("class").getNodeValue().split("\\.");
			String indexx = teemp.getNamedItem("index").getNodeValue();
			xPath=indexx+"_"+classess[classess.length-1]+"/"+xPath;
			temp = temp.getParentNode();
		}
	}

	public void loadAttributesFromDom(Node domNode) {
		NamedNodeMap attributes = domNode.getAttributes();
		name = domNode.getNodeName();
		String bounds;
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
					enabled = true;
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
			else {
				System.out.println("IMPORTANT: Property "+ attribute.getNodeName() + " is not included in RIP");
			}
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
	 * Calculates the bounds and central point of a node
	 * @param text Raw input
	 * Initializes point1, point2 and centralPoint
	 */
	public void loadBounds(String text) {
		String bounds = text.replace("][", "/").replace("[", "").replace("]", "");
		bounds += "/0";
		String[] coords = bounds.split("/");
		String coord1 = coords[0];
		String coord2 = coords[1];
		String[] points1 = coord1.split(",");
		String[] points2 = coord2.split(",");
		int x1 = Integer.parseInt(points1[0]);
		int x2 = Integer.parseInt(points2[0]);
		int y1 = Integer.parseInt(points1[1]);
		int y2 = Integer.parseInt(points2[1]);
		point1 = new int[] {x1,y1};
		point2 = new int[] {x2,y2};
		centralPoint = new int[] {(int)((x1+x2)/2), (int)((y1+y2)/2)};
	}

	public boolean isClickable() {
		return clickable;
	}
	
	public boolean isAButton() {
		
		switch(pClass) {
		case "android.widget.Button":
			return true;
		}
		
		return pClass.toLowerCase().contains("button");
	}

	public int getCentralX() {
		return centralPoint[0];
	}

	public int getCentralY() {
		return centralPoint[1];
	}
	
	public String getpClass() {
		return pClass;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public double compare(AndroidNode langNode) {
		return (Helper.levenshteinDistance(toString(), langNode.toString()))/(double)toString().length();
	}

}
