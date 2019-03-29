package uniandes.tsdl.itdroid.helper;

import java.util.ArrayList;
import org.w3c.dom.*;
import org.xml.sax.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
public class XMLComparator {
	
	ArrayList<String> useful = new ArrayList<>();
	ArrayList<String> useless = new ArrayList<>();
	private int numLinesUseless = 29;
	private int numLineasFont = 12;
	/*
	 * Constructor Method
	 * @params
	 * xmls represents and array of absolute paths where the string.xml files are supposed to be.
	 * alpha represents the tolerance level of untranslate words
	 */
	public XMLComparator(String[] xmls,Integer alpha){
		
		File inputFile = null;
		DocumentBuilderFactory dbFactory =  DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		Document doc;
		XPath xPath = null;
		String expression = "/resources/string";
		try {
			dBuilder =dbFactory.newDocumentBuilder();
			if(xmls != null) {
				inputFile = new File(xmls[0]);
				//doc = dBuilder.parse(inputFile);
				doc = dBuilder.parse(inputFile);
				doc.getDocumentElement().normalize();
				xPath = XPathFactory.newInstance().newXPath();
				
				NodeList baseLineNodeList = (NodeList) xPath.compile(expression).evaluate(
			            doc, XPathConstants.NODESET);
				int usefulValues = (baseLineNodeList.getLength()- numLinesUseless - numLineasFont);
				String file = null;
				int numNodes = 0;
				for (int i =1; i < xmls.length; i++) {
					
					file = xmls[i];
					inputFile = new File(file);
					if(inputFile.exists()) {
						//doc = dBuilder.parse(inputFile);
						doc = dBuilder.parse(inputFile);
						doc.getDocumentElement().normalize();
						xPath = XPathFactory.newInstance().newXPath();
						
						NodeList nodeList = (NodeList) xPath.compile(expression).evaluate(
					            doc, XPathConstants.NODESET);
						numNodes = (nodeList.getLength()-numLinesUseless);
						if(numNodes >= (usefulValues - alpha) && (usefulValues - alpha) >= 0) {
							useful.add(file);
						} else {useless.add(file);}
					} else {
						useless.add(file);
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
		}
		
		
	}
	
	public ArrayList<String>  getUsefull(){
		return useful;
	}
	
	public ArrayList<String> getUseLess(){
		return useless;
	}
	
}
