package uniandes.tsdl.itdroid.model;

import java.util.Objects;

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
		this.nodePos = Integer.parseInt(params[2]);
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
		// Use the always-populated stateId and guard node: IPFs built from the String constructor
		// leave state/node null, so dereferencing state.getId()/node.getxPath() would NPE.
		String nodeXPath = node != null ? node.getxPath() : "";
		return "Lang: "+language+"; StateId: "+stateId+"; NodeId: "+nodePos+"; NodeXPath: "+nodeXPath;
	}

	// An IPF is uniquely identified by (language, stateId, nodePos) — the same fields that make up
	// getID(). Both constructors always populate these (the state/node object refs may be null when
	// built from the String form), so equals/hashCode must rely only on them. Required because IPFs
	// are deduplicated through a HashSet whose size becomes the reported amIPFs count.
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		IPF other = (IPF) obj;
		return stateId == other.stateId
				&& nodePos == other.nodePos
				&& Objects.equals(language, other.language);
	}

	@Override
	public int hashCode() {
		return Objects.hash(language, stateId, nodePos);
	}

}
