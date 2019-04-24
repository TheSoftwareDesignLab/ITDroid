package uniandes.tsdl.itdroid.helper;

import java.util.ArrayList;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.xml.sax.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XMLComparator {
	
	ArrayList<String> useful = new ArrayList<>();
	ArrayList<String> useless = new ArrayList<>();
	private int numLinesUseless = 29;
	private int numLineasFont = 12;
	/*
	 * Constructor Method
	 * @params
	 * xmls represents and array of absolute paths where the string.xml files are supposed to be.
	 * alpha represents the tolerance level of untranslated strings
	 */
	public XMLComparator(String[] xmls,Integer alpha, String directory){
		
		File inputFile = null;
		try {
			if(xmls != null) {
				inputFile = new File(xmls[0]);
				NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(directory);
				//Read the default strings.xml file
				SAXBuilder builder = new SAXBuilder();
				org.jdom2.Document document = builder.build(inputFile);
				// Initialize set to manage the default strings
				Set<String> originalStrings = new HashSet<>();
				//Get default strings
				Element root = document.getRootElement();
				List <Element> strings = root.getChildren();
				Element e;
				String stringName;
				for(int j = 0; j < strings.size(); j++){
					e = strings.get(j);
					stringName = e.getAttributeValue("name");
					if(dictionary.translatable(stringName)){
						originalStrings.add(stringName);
					}
				}
				File file2;
				SAXBuilder builder2 = new SAXBuilder();
				Document document2;
				List<Element> strings2;
				Element root2;
				Set<String> translatedStrings;
				for (int i =1; i < xmls.length; i++) {
					file2 = new File(xmls[i]);
					if(file2.exists()){
						document2 = builder2.build(file2);
						root2 = document2.getRootElement();
						strings2 = root2.getChildren();
						Element e2;
						String string2Name;
						translatedStrings = new HashSet<>();
						for(int j = 0; j < strings2.size(); j++){
							e2 = strings2.get(j);
							string2Name = e2.getAttributeValue("name");
							if(dictionary.translatable(string2Name)){
								translatedStrings.add(string2Name);
							}
						}

						Set <String> difference = new HashSet(originalStrings);
						difference.removeAll(translatedStrings);
						if(difference.size() > alpha){
							useless.add(xmls[i]);
						}
						else{
							useful.add(xmls[i]);
						}
					} else{
						useless.add(xmls[i]);
					}
				}
			}
			
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}


	}
	
	public ArrayList<String>  getUsefull(){
		return useful;
	}
	
	public ArrayList<String> getUseLess(){
		return useless;
	}
	
}
