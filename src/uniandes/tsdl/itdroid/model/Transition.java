package uniandes.tsdl.itdroid.model;

public class Transition {
	
	private State origin;
	private State destination;
	private TransitionType type;
	private AndroidNode originElement;
	
	/**
	 * Creates a transition with a known origin state and a type
	 * @param origin
	 * @param type
	 */
	public Transition(State origin, TransitionType type) {
		this.origin = origin;
		this.type = type;
		
	}
	
	/**
	 * Creates a transition with a known origin, a known element and a type
	 * @param origin
	 * @param originElement
	 * @param type
	 */
	public Transition(State origin, TransitionType type, AndroidNode originElement) {
		this.origin = origin;
		this.type = type;
		this.originElement = originElement;
		
	}

	public State getOrigin() {
		return origin;
	}

	public void setOrigin(State origin) {
		this.origin = origin;
	}

	public State getDestination() {
		return destination;
	}

	public void setDestination(State destination) {
		this.destination = destination;
	}

	public TransitionType getType() {
		return type;
	}

	public void setType(TransitionType type) {
		this.type = type;
	}

	public AndroidNode getOriginNode() {
		return originElement;
	}

	public void setOriginElement(AndroidNode originElement) {
		this.originElement = originElement;
	}
	
	public String toString() {
		return origin.getId()+" -> "+destination.getId()+" := "+type;
	}
	
	
	

}
