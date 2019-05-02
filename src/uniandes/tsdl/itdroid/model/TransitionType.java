package uniandes.tsdl.itdroid.model;

import java.util.HashSet;
import java.util.Set;

public enum TransitionType {
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
	
	public static Set<TransitionType> getUserTypeTransitions(){
		Set<TransitionType> userTypes = new HashSet<TransitionType>();
		userTypes.add(GUI_CLICK_BUTTON);
		return userTypes;
	}
	
	public static Set<TransitionType> getScrollTransitions(){
		Set<TransitionType> userTypes = new HashSet<TransitionType>();
		userTypes.add(SCROLL);
		userTypes.add(SWIPE);
		return userTypes;
	}
}