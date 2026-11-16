package uniandes.tsdl.itdroid.model;

public class Transition {

	public enum Type {
	GUI_CLICK_BUTTON,
    SWIPE,
    SCROLL,
    GUI_RANDOM,
    GUI_INPUT_TEXT,
    GUI_INPUT_NUMBER,
    CONTEXT_INTERNET_ON,
    CONTEXT_INTERNET_OFF,
    CONTEXT_LOCATION_ON,
    CONTEXT_LOCATION_OFF,
    ROTATE_LANDSCAPE,
    ROTATE_PORTRAIT,
    BUTTON_BACK,
    FIRST_INTERACTION;
	}

	
	private State origin;
	private State destination;
	private Type type;
	private AndroidNode originElement;

	public Transition(State origin, Type type) {
		this.origin = origin;
		this.type = type;

	}

	public State getOrigin() {
		return origin;
	}

	public State getDestination() {
		return destination;
	}

	public void setDestination(State destination) {
		this.destination = destination;
	}

	public Type getType() {
		return type;
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
