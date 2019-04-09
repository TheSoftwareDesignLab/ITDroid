package uniandes.tsdl.itdroid.model;

public class IPF {
	
	private String language;
	private State state;
	private AndroidNode node;
	private int nodePos;
	private String id;
	
	
	public IPF(String language, State state, AndroidNode node, int nodePos) {
		super();
		this.language = language;
		this.state = state;
		this.node = node;
		this.nodePos = nodePos;
		id = language+"|"+state.getId()+"|"+nodePos;
	}


	public String getLanguage() {
		return language;
	}


	public State getState() {
		return state;
	}


	public int getNodePos() {
		return nodePos;
	}
	
	public String getID() {
		return id;
	}
	
	@Override
	public String toString() {
		return "Lang: "+language+"; StateId: "+state.getId()+"; NodeId: "+nodePos+"; NodeXPath: "+node.getxPath();
	}

}
