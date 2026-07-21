package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

public class LanguageBundle {

	private static final String PROPERTY_FILE_NAME = "settings";
	private ResourceBundle bundle;


	public LanguageBundle(String propertyDir) {
		init(propertyDir);
	}

	
	public boolean isLanguageSelected(String id) {
		return bundle.containsKey(id);
	}

	public ResourceBundle getBundle() {
		return bundle;
	}
	
	public String printSelectedLanguages() {
		
		Set<String> ids = bundle.keySet();
		String selectedLanguages = "Selected Languages: "+(ids.size()-1)+"\n";

		for (String id : ids) {
			if(!id.equals("defaultLng")) {
				selectedLanguages += bundle.getString(id)+"\n";				
			}
		}
		selectedLanguages += "------------\n";
		
		return selectedLanguages;
	}



	private void init(String propertyDir) {
		File file = new File(propertyDir);
		URL url = null;

		try {
			url = file.toURI().toURL();
		} catch (MalformedURLException e) {
			// Fail fast: leaving url null here only defers the failure to a confusing NPE /
			// MissingResourceException when the bundle is later loaded.
			throw new RuntimeException("Could not resolve the settings directory to a URL: " + propertyDir, e);
		}

		URL[] urls = {url};
		ClassLoader loader = new URLClassLoader(urls);
		bundle = ResourceBundle.getBundle(PROPERTY_FILE_NAME, Locale.getDefault(), loader);
	}


	public String[] getSelectedLanguagesAsArray() {
		
		Set<String> ids = bundle.keySet();
		// Size by the actual number of non-"defaultLng" keys; the "defaultLng" key is optional, so
		// assuming ids.size()-1 would write past the end (AIOOBE) when it is absent.
		int count = 0;
		for (String id : ids) {
			if(!id.equals("defaultLng")) {
				count++;
			}
		}
		String[] response = new String[count];
		int i =0;
		for (String id : ids) {
			if(!id.equals("defaultLng")) {
				response[i] = id;
				i++;
			}
		}

		return response;
		
	}

}
