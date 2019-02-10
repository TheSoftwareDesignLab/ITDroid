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
			e.printStackTrace();
		}

		URL[] urls = {url};
		ClassLoader loader = new URLClassLoader(urls);
		bundle = ResourceBundle.getBundle(PROPERTY_FILE_NAME, Locale.getDefault(), loader);
	}


	public String[] getSelectedLanguagesAsArray() {
		
		Set<String> ids = bundle.keySet();
		String[] response = new String[ids.size()-1];
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
