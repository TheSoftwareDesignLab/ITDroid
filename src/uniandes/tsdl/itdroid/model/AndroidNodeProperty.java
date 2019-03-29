package uniandes.tsdl.itdroid.model;

public enum AndroidNodeProperty {
	//Not accessibility friendy
	NAF ("NAF", "boolean"),
	//Position of the element in the device screen (eg, [0,336][1080,1794])
	BOUNDS ("bounds", "pointsTuple"),
	PACKAGE ("package", "text"),
	TEXT ("text", "text"),
	CHECKABLE ("checkable", "boolean"),
	CHECKED ("checked", "boolean"),
	SELECTED ("selected", "boolean"),
	CLICKABLE ("clickable", "boolean"),
	CONTENT_DESCRIPTION ("content-desc", "text"),
	ENABLED ("enabled", "boolean"),
	FOCUSABLE ("focusable", "boolean"),
	FOCUSED ("focused", "boolean"),
	INDEX ("index", "text"),
	PASSWORD ("password", "boolean"),
	RESOURCE_ID ("resource-id", "text"),
	SCROLLABLE ("scrollable", "boolean"),
	//Class of the element: widget or view (eg, android.view.ViewGroup, android.widget.ImageButton)
	CLASS ("class", "text"),
	LONG_CLICKABLE ("long-clickable", "boolean");
	
	private final String name;
	private final String type;
	
	/**
	 * Find an AndroidNodeProperty by its name
	 * @param name
	 * @return AndroidNodeProperty or null if name is not listed
	 */
	public static AndroidNodeProperty fromName(String name) {
		for(AndroidNodeProperty anp : values()) {
			if(anp.name.equals(name)) {
				return anp;
			}
		}
		return null;
	}
	
	public String getName() {
		return name;
	}
	

	
	AndroidNodeProperty(String name, String type){
		this.name = name;
		this.type = type;
	}
}
