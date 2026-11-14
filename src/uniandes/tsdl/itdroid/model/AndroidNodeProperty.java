package uniandes.tsdl.itdroid.model;

public enum AndroidNodeProperty {
	//Not accessibility friendy
	NAF ("NAF"),
	//Position of the element in the device screen (eg, [0,336][1080,1794])
	BOUNDS ("bounds"),
	PACKAGE ("package"),
	TEXT ("text"),
	CHECKABLE ("checkable"),
	CHECKED ("checked"),
	SELECTED ("selected"),
	CLICKABLE ("clickable"),
	CONTENT_DESCRIPTION ("content-desc"),
	ENABLED ("enabled"),
	FOCUSABLE ("focusable"),
	FOCUSED ("focused"),
	INDEX ("index"),
	PASSWORD ("password"),
	RESOURCE_ID ("resource-id"),
	SCROLLABLE ("scrollable"),
	//Class of the element: widget or view (eg, android.view.ViewGroup, android.widget.ImageButton)
	CLASS ("class"),
	LONG_CLICKABLE ("long-clickable");
	
	private final String name;
	
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
	

	
	AndroidNodeProperty(String name){
		this.name = name;
	}
}
