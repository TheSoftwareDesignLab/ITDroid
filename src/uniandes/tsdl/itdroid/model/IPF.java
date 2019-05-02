package uniandes.tsdl.itdroid.model;

public class IPF {
	
	private String language;
	private State state;
	private int stateId;
	private AndroidNode node;
	private int nodePos;
	private String id;
	
	
	public IPF(String language, State state, AndroidNode node, int nodePos) {
		super();
		this.language = language;
		this.state = state;
		this.stateId = state.getId();
		this.node = node;
		this.nodePos = nodePos;
		id = language+";"+state.getId()+";"+nodePos;
	}
	
	public IPF(String ipf) {
		super();
		String[] params = ipf.split(";");
		this.language = params[0];
		this.stateId = Integer.parseInt(params[1]);
		this.nodePos = Integer.parseInt(params[2]);;
		this.id = ipf;
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
	
	public int getStateId() {
		return stateId;
	}

	@Override
	public String toString() {
		return "Lang: "+language+"; StateId: "+state.getId()+"; NodeId: "+nodePos+"; NodeXPath: "+node.getxPath();
	}

}
