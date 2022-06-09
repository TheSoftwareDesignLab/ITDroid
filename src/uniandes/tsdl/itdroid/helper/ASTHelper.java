package uniandes.tsdl.itdroid.helper;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.text.Normalizer.Form;

import com.google.gson.Gson;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.TokenSource;
import org.antlr.runtime.tree.CommonTree;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.json.simple.JSONObject;

import kotlin.text.Typography;
import okhttp3.internal.http.BridgeInterceptor;
import uniandes.tsdl.antlr.smaliParser;
import uniandes.tsdl.jflex.smaliFlexLexer;
import uniandes.tsdl.smali.LexerErrorInterface;

public class ASTHelper {

	public static CommonTree getAST(String sourcePath) {

		FileInputStream fis = null;
		File smaliFile = new File(sourcePath);
		CommonTree t = null;
		try {
			fis = new FileInputStream(smaliFile);
			InputStreamReader reader = new InputStreamReader(fis, "UTF-8");

			LexerErrorInterface lexer = new smaliFlexLexer(reader);
			((smaliFlexLexer)lexer).setSourceFile(smaliFile);
			// System.out.println(((smaliFlexLexer)lexer).nextToken().getText());
			CommonTokenStream tokens = new CommonTokenStream((TokenSource)lexer);
			tokens.getTokens();
			smaliParser parser = new smaliParser(tokens);
			// parser.setVerboseErrors(options.verboseErrors);
			// parser.setAllowOdex(options.allowOdexOpcodes);
			// parser.setApiLevel(options.apiLevel);

			smaliParser.smali_file_return result = parser.smali_file();
			t = result.getTree();
			return t;
		} catch (Exception e){
			e.printStackTrace();
		}
		return t;
	}

	public static CommonTree getFirstUncleNamedOfType(int type, String name, CommonTree t) {
		CommonTree parent = (CommonTree) t.getParent();
		List<CommonTree> uncles = (List<CommonTree>)((CommonTree)parent.getParent()).getChildren();
		for (int i = parent.getChildIndex()+1; i < uncles.size(); i++) {
			CommonTree tempUncle = (CommonTree) uncles.get(i);
			if(tempUncle.getType()==type && tempUncle.getChild(0).toStringTree().equals(name)) {
				return tempUncle;
			}
		}
		return null;
	}

	public static CommonTree getFirstBackUncleNamedOfType(int type, String name, CommonTree t) {
		CommonTree parent = (CommonTree) t.getParent();
		List<CommonTree> uncles = (List<CommonTree>)((CommonTree)parent.getParent()).getChildren();
		for (int i = parent.getChildIndex(); i > -1; i--) {
			CommonTree tempUncle = (CommonTree) uncles.get(i);
			if(tempUncle.getType()==type && tempUncle.getChild(0).toStringTree().equals(name)) {
				return tempUncle;
			}
		}
		return null;
	}

	public static CommonTree getFirstBrotherNamedOfType(int type, String name, CommonTree t) {
		CommonTree parent = (CommonTree) t.getParent();
		List<CommonTree> brothers = (List<CommonTree>)parent.getChildren();
		for (int i = t.getChildIndex()+1; i < brothers.size(); i++) {
			CommonTree tempBrother = (CommonTree) brothers.get(i);
			if(tempBrother.getType()==type && tempBrother.getChild(0).toStringTree().equals(name)) {
				return tempBrother;
			}
		}
		return null;
	}

	public static CommonTree hasIPutAndIGet(CommonTree t) {
		CommonTree iput = getFirstUncleNamedOfType(smaliParser.I_STATEMENT_FORMAT22c_FIELD, "iput-object", t);
		if(iput!=null && iput.getLine()-t.getLine()<7)
		{
			List<CommonTree> cousins = (List<CommonTree>)iput.getChildren();
			String varName = cousins.get(4).toStringTree();
			CommonTree iget = getFirstBrotherNamedOfType(smaliParser.I_STATEMENT_FORMAT22c_FIELD, "iget-object", iput);
			while(iget!=null)
			{
				List<CommonTree> cousinss = (List<CommonTree>)iget.getChildren();
				if(cousinss.get(4).toStringTree().equals(varName)){
					return iget;
				} else {
					iget = getFirstBrotherNamedOfType(smaliParser.I_STATEMENT_FORMAT22c_FIELD, "iget-object", iget);
				}
			}
		}
		return null;
	}

	public static boolean isValidLocation(CommonTree t){
		
		if(t.getType()==smaliParser.I_STATEMENT_FORMAT21c_STRING) {
			
			if(!t.getFirstChildWithType(smaliParser.INSTRUCTION_FORMAT21c_STRING).getText().equals("const-string")) {
				return false;
			}
			
			CommonTree brother = ASTHelper.getFirstBrotherNamedOfType(smaliParser.I_STATEMENT_FORMAT35c_METHOD, "invoke-virtual", t);
			
			if(brother != null) {
				for (int i = t.getChildIndex()+1; i < brother.getChildIndex(); i++) {
					CommonTree tempChild = (CommonTree) t.getParent().getChild(i);
					if (tempChild.getType()==smaliParser.I_STATEMENT_FORMAT21c_STRING) {
						if(tempChild.getChildren().get(1)==t.getChildren().get(1)) {
							return false;
						}
					}
				}
				return true;
			}
		}	
		
		return false;
	}

	private static boolean isNullOutputStream(CommonTree t) {
		String apis = "#Ljava/io/OutputStream;"
				+ "#Ljava/io/ByteArrayOutputStream;"
				+ "#Ljava/io/FileOutputStream;"
				+ "#Ljava/io/FilterOutputStream;"
				+ "#Ljava/io/ObjectOutputStream;"
				+ "#Ljava/io/PipedOutputStream;"
				+ "#Ljava/io/BufferedOutputStream;"
				+ "#Ljava/io/PrintStream;"
				+ "#Ljava/io/DataOutputStream;";
		if(apis.contains(t.getChild(2).toStringTree())
				&& t.getChild(3).toStringTree().equals("close")) {
			return true;
		}
		return false;
	}

	private static boolean isNullInputStream(CommonTree t) {
		String apis = "#Ljava/nio/channels/FileChannel;"
				+ "#Ljava/io/InputStream;"
				+ "#Ljava/io/BufferedInputStream;"
				+ "#Ljava/io/ByteArrayInputStream;"
				+ "#Ljava/io/DataInputStream;"
				+ "#Ljava/io/FilterInputStream;"
				+ "#Ljava/io/ObjectInputStream;"
				+ "#Ljava/io/PipedInputStream;"
				+ "#Ljava/io/SequenceInputStream;"
				+ "#Ljava/io/StringBufferInputStream;";
		if(apis.contains("#"+t.getChild(2).toStringTree()+"#")
				&& t.getChild(3).toStringTree().equals("close")) {
			return true;
		}
		return false;
	}

	private static boolean isNullBackendServiceReturn(CommonTree t) {
		CommonTree tree = (CommonTree) t.getFirstChildWithType(smaliParser.I_METHOD_PROTOTYPE);
		CommonTree treee = (CommonTree) tree.getFirstChildWithType(smaliParser.I_METHOD_RETURN_TYPE);
		String classs = treee.getChild(0).toString();
		return classs.equals("Lorg/apache/http/HttpResponse;");
	}

	private static boolean isOnCreateMethod(CommonTree t) {
		boolean resp = t.getChild(0).toString().equals("onCreate");
		if(resp) {
			CommonTree mProt = (CommonTree) t.getFirstChildWithType(smaliParser.I_METHOD_PROTOTYPE);
			resp = (mProt.getChildCount() == 2);
			if(resp) {
				resp = mProt.getChild(1).toString().equals("Landroid/os/Bundle;");
			}
		}
		return resp;
	}

	private static boolean isOnClickMethod(CommonTree t) {
		boolean resp = t.getChild(0).toString().equals("onClick");
		if(resp) {
			CommonTree mProt = (CommonTree) t.getFirstChildWithType(smaliParser.I_METHOD_PROTOTYPE);
			resp = (mProt.getChildCount() == 2);
			if(resp) {
				resp = mProt.getChild(1).toString().equals("Landroid/view/View;");
			}
		}
		return resp;
	}


	public static int findHardCodedStrings(String folderPath, String extrasFolder, String packageName, String outputPath) throws IOException {
		BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath+File.separator+"hcs.txt"));
		String origPath = folderPath;
		folderPath = folderPath+File.separator+"smali";
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		int possibleIPFS = replaceHCS(files, packageName, origPath, outputPath);
	
		for (File file : files) {
			if(file.getName().endsWith(".smali") && file.getCanonicalPath().contains(packageName.replace(".", Helper.isWindows()?"\\":"/")) && !file.getName().contains("EmmaInstrumentation") && !file.getName().contains("FinishListener") && !file.getName().contains("InstrumentedActivity") && !file.getName().contains("SMSInstrumentedReceiver")){
				String fileName = file.getName().replaceAll(".smali", "");
				HashMap<String, List<String>> list = processFile(file.getAbsolutePath(), folderPath, extrasFolder);
				bw.write(fileName);
				bw.newLine();
				Set<String> keys = list.keySet();
				Iterator<String> keysIter = keys.iterator();
				while(keysIter.hasNext()) {
					String method = keysIter.next();
					bw.write("\t"+method);
					bw.newLine();
					List<String> keyStrings = list.get(method);
					//possibleIPFS+=keyStrings.size();
					for (Iterator<String> iterator = keyStrings.iterator(); iterator.hasNext();) {
						String hardcodedString = iterator.next();
						bw.write("\t\t"+hardcodedString);
						bw.newLine();
					}
				}
			}
		}
		System.out.println("There are "+possibleIPFS+" hardcoded strings in your app. These strings are are shown in hcs.txt file stored in output folder.");
		bw.close();
		return possibleIPFS;
	}
	
	private static  HashMap<String, List<String>> processFile(String filePath, String projectPath, String extrasFolder){

		HashMap<String, List<String>> stringLocations = new HashMap<>();

		try {

			//Getting AST from file
			CommonTree cu = ASTHelper.getAST(filePath);
			TreeVisitorInstance ttv = new TreeVisitorInstance(filePath);
			ttv.visit(cu, null);

			HashSet<CommonTree> calls = ttv.getCalls();

			Iterator<CommonTree> a = calls.iterator();
			while(a.hasNext()){
				CommonTree b = a.next();
				String text = b.getFirstChildWithType(smaliParser.STRING_LITERAL).getText();
				String method = getParentOfType(smaliParser.I_METHOD, b).getFirstChildWithType(smaliParser.SIMPLE_NAME).getText();
				if(stringLocations.get(method) == null) {
					List<String> templist = new ArrayList<>();
					stringLocations.put(method, templist);
				}
				stringLocations.get(method).add(text);
			}			
		} catch(Exception e){
			e.printStackTrace();
		}
		return stringLocations;
	}

	private static CommonTree getParentOfType(int iMethod, CommonTree b) {
		CommonTree parent = (CommonTree) b.getParent();
		while(parent.getType()!=iMethod) {
			parent = (CommonTree) parent.getParent();
		}
		return parent;
	}

/////////// Methods used for the extraction of hard-coded strings (HCSs) ///////////

	/**
	*Method that starts the process of replacing the HCS in the Smali files.
	*It assigns an id for each HCS in the smali code and a name 
	*to associate with in the strings.xml file
	 * @param files all the smali files from the decompiled APK.
	 * @param packageName name of the package where the smali files are 
	 * @param folderPath path where the files are.
	 * @param outputPath path for the JSONs that are going to be added to the web report
	 * @throws IOException
	 * @returns The total of HCSs found and reported
	 */
	private static int replaceHCS(Collection<File> files, String packageName, String folderPath, String outputPath ) throws IOException{
		HashMap<String, List<String>> strTransl = new HashMap<>();//Map that holds the info of each HCS that is going to be replaced
		HashMap<String, List<String>> uniqueIds = new HashMap<>();//Map to keep a record of unique ids for the same string (if they are repeated)
		HashMap<String, List<Integer>> localReg = new HashMap<>();//
		HashMap<String,String[]> layoutStrs = new HashMap<>();//Map that holds the info of the HCS found in the layouts(XML files) 
		int reportedHCS = 0;
		int i = 500;//number of the unique id 	
		String identifier = getStringIdentifier(folderPath);//saves the identifier that the apk is currently using for string resources
		for (File file : files) {
			if(file.getName().endsWith(".smali") && file.getCanonicalPath().contains(packageName.replace(".", Helper.isWindows()?"\\":"/")) && !file.getName().contains("EmmaInstrumentation") && !file.getName().contains("FinishListener") && !file.getName().contains("InstrumentedActivity") && !file.getName().contains("SMSInstrumentedReceiver")){
				//Create the AST for the current file
				CommonTree fileTree = (CommonTree) ASTHelper.getAST(file.getAbsolutePath());
				TreeVisitorInstance ttv = new TreeVisitorInstance(file.getAbsolutePath());
				ttv.visit(fileTree, null);	
				HashSet<CommonTree> calls = ttv.getCalls();
				Iterator<CommonTree> iter = calls.iterator();
				//Iterate through all the possible HCS found
				while(iter.hasNext()){
					List<String> infoNewLines = new ArrayList<String>();
					CommonTree next = iter.next();

					//Get the tree with the information about local variables for the method of the current HCS
					CommonTree locals = getLocals(next);
					List<Integer> localValues = new ArrayList<Integer>();
					int numLocals = Integer.parseInt(locals.getChild(0).toString());//number of local vars declared
					int lineLocals = locals.getLine();//Line in the smali file where the local var declaration is
					int colLocals = locals.getChild(0).getCharPositionInLine();//Column to replace the number of locals
					localValues.add(numLocals);
					localValues.add(colLocals);
					localReg.put(file.getAbsolutePath().toString() + lineLocals, localValues);

					//Hardcoded Line replacement
					int line = next.getChild(2).getLine();//number of the line where the HCS is
					int charPos = next.getChild(2).getCharPositionInLine();//Column where the HCS starts
					infoNewLines.add(String.valueOf(charPos));
					i++;
					//Create the ids, checking that the strings with the same value have the same id
					if(!uniqueIds.containsKey(next.getChild(2).getText())){
						//If the string is new, the id is added to the unique map and to the map that holds the info of the HCS
						List<String> uniqueValues = new ArrayList<String>();
						String stringId = setStringId(i, identifier);
						infoNewLines.add(stringId);
						uniqueValues.add(stringId);
						String nameId = "extractedString" + i; //The name assigned to link with the id of the HCS
						infoNewLines.add(nameId);
						uniqueValues.add(nameId);
						uniqueIds.put(next.getChild(2).getText(), uniqueValues);
					}
					else{
						//If the string with the same value was already found, then only assign it the id already given
						infoNewLines.add(uniqueIds.get(next.getChild(2).getText()).get(0));
						infoNewLines.add(uniqueIds.get(next.getChild(2).getText()).get(1));
					}
					String strContent = next.getChild(2).getText();//What the HCS holds

					infoNewLines.add(strContent);
					infoNewLines.add(file.getName());//Name of the file where the HCS was found
					infoNewLines.add(String.valueOf(next.getParent().getParent().getLine()));//Number of the line which declares the method that uses the hardcoded string					
					infoNewLines.add(String.valueOf(numLocals));//number of local registers in the method
					infoNewLines.add(next.getParent().getParent().getChild(0).toString());//Name of the method where the HCS was found
					infoNewLines.add(file.getAbsolutePath().toString());
					//Checks wheter the string should be reported and extracted
					//If not, the string stays the same in the code
					if(isExtractable(strContent))
						strTransl.put(file.getAbsolutePath().toString() + String.valueOf(line), infoNewLines);
				//End harcoded line replacement proccess			
				}
			}
		}
		layoutStrs = repairLayoutHCS(folderPath,i, identifier); //extracts all the HCS found in the layout
		int notExtracted = writeSmaliFiles(strTransl, localReg, layoutStrs, folderPath, packageName, files, outputPath);//Writes the smali files with the new ids for the HCS
		reportedHCS = notExtracted + strTransl.size() + layoutStrs.size();

		return reportedHCS;
	}

	/**
	*Method that inspects all the Layout(XML) files to check for possible HCSs
	*An HCS in the layout is found if an XML attribute that displays text has a non strings.xml reference/value
	*Returns the map with the info of the HCS replacement to add it to the web report
	 * @param folderPath Path where the decompiled code is found
	 * @param strIndex last number given to the string id in the extraction of HCSs in the smali files. Important to continue from that number
	 * @param identifier identifier used by string resources in the APK
	 * @return Map with all the extracted HCSs and their respective info(id given, name and content)
	 */
	public static HashMap<String,String[]> repairLayoutHCS(String folderPath, int strIndex, String identifier){
		HashMap<String,String[]> layoutStrs = new HashMap<>();
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		int i = strIndex;//index of the last number assigned in the extraction of HCSs in smali files. Needed for consistency assigning ids
		for (File file : files) {
			List<String> newLines =  new ArrayList<String>();
			//only check the XML files in the layout folder 
			if(file.getAbsolutePath().contains("layout") && file.getName().contains(".xml")) {
				List<String> oldLines = readLines(file.getAbsolutePath());
				for (String current : oldLines ) {
					List<String> strToReplace = new ArrayList<>();
					String[] params =  current.split("android:");//splits to get all the XML attributes separated
					for (String tuple : params) {
						String txtKey = tuple.split("=")[0];//separates by key(attribute) = "value"

						//if the XML attribute shows any text in the GUI, it needs to be checked
						if(txtKey.equals("text") || txtKey.equals("hint") || txtKey.equals("contentDescription")){
							String txtValue = tuple.split("\"")[1];
							//if the value is not a reference found in strings.xml, then its a HCS and has to be extracted
							if(!txtValue.contains("@string/") && !txtValue.startsWith("@")){
								txtValue = "\""+ txtValue + "\"";
								strToReplace.add(txtValue.replace(" />\"", ""));//the last atrribute had the closing tag of the current layout, so it is removed for that param.
							}
						}
					}
					//Check if there's at least one parameter to replace in that layout
					if(strToReplace.size() > 0){
						for (String actToReplace : strToReplace){
							String[] references = new String[4];//Holds all the info about each layout HCS to put it in the web report
							i++;
							String stringId = setStringId(i, identifier);//creates an id to go in the code string references
							String replacement = file.getName().replace(".xml", "") + i;//Name linked to the id
							references[0] = stringId;
							//Check if the HCS found has an id assigned, meaning that is repeated
							if(layoutStrs.containsKey(actToReplace)){//If it does, give it the name previously set
								replacement = layoutStrs.get(actToReplace)[1];
								references[1] = replacement;
							}
							else{//If it's new, the name is going to be created
								references[1] = replacement;
							}

							references[2] = actToReplace;//content of the HCS
							references[3] = file.getName();//Name of the XML file
							layoutStrs.put(actToReplace, references);//sets the info of the current HCS found

							//Escape all the unicode replace chars that could cause issues parsing the XML files
							Pattern patternReplace = Pattern.compile("[^\\x00-\\x7F]");
							Matcher matcherAct = patternReplace.matcher(actToReplace);	
							actToReplace = matcherAct.replaceAll("");
							Matcher matcherCurrent = patternReplace.matcher(current);	
							current = matcherCurrent.replaceAll("");

							String newStr = current.replace(actToReplace, "\"@string/"+replacement+"\"");//reference to the new string
							newLines.add(newStr);	
						}
					}
					else{//if the line doesn't contain an HCS, leave it the same.
						newLines.add(current);
					}
				}
			writeLines(file.getAbsolutePath(), newLines);//Write all the file with the modifications	
			}
		}
		return layoutStrs;
	}

	/**
	 * Method that replaces all the HCSs in the smali files with the ids that were given in the replaceHCS method.
	 * After replacing all the HCSs, it creates the JSON files with the info of the changes to be shown in the web report.
	 * In this method the Strings that could not be replaced are filtered out, so they can be labeled as not extracted.
	 * @param hardCoded Map with the harcoded strings to replace
	 * @param locals Map with the info of the local variables for the methods where the HCSs are
	 * @param layoutStrs Map with the info of the ids of the replaced HCSs found in the layout
	 * @param folderPath path of the apk files
	 * @param packageName name of the package where the smali files are
	 * @param files all the smali files to check
	 * @param outputPath path where the JSONs for the web report are
	 * @return the number of HCSs that could not be extracted
	 */
	private static int writeSmaliFiles(HashMap<String, List<String>> hardCoded, HashMap<String, List<Integer>> locals,HashMap<String, String[]> layoutStrs, String folderPath, String packageName, Collection<File> files, String outputPath) throws IOException{
		HashMap<String, List<String>> notReplaced = new HashMap<>();//Contains all the strings that were not extracted for at least one reason.
		HashMap<String, List<String>> hcFinal =  hardCoded;//Is going to contain the strings that got extracted with no problem.
		int numbNotExtracted = 0;
		for (File file : files) {
			List<String> newLines = new ArrayList<String>();
			if(file.getName().endsWith(".smali") && file.getCanonicalPath().contains(packageName.replace(".", Helper.isWindows()?"\\":"/")) && !file.getName().contains("EmmaInstrumentation") && !file.getName().contains("FinishListener") && !file.getName().contains("InstrumentedActivity") && !file.getName().contains("SMSInstrumentedReceiver")){
				List<String> oldLines = readLines(file.getAbsolutePath());
				Boolean context = hasContext(oldLines);//Check if the class extends from context/activity
				for (int i = 0; i < oldLines.size(); i++) {
					String currentLine = oldLines.get(i);
					String originalLine = currentLine;
					String key = file.getAbsolutePath() + String.valueOf(i+1);
					int iniChar = 0;
					int finChar = 0;
					currentLine = currentLine.replace("\\", "");
					currentLine = currentLine.replaceAll("[^\\u0000-\\u00FF]", "");
					StringBuffer buf = new StringBuffer(currentLine);//buffer to replace in the exact column of the line
					//Process for the current line that is a HCS that has to be extracted	
					if(hardCoded.containsKey(key) && hardCoded.get(key) != null){
						//Check that it is the correct file
						if(file.getAbsolutePath().equals(hardCoded.get(key).get(8))){				
							//Get all the info to correctly replace
							String id = hardCoded.get(key).get(1);
							iniChar = Integer.parseInt(hardCoded.get(key).get(0));
							finChar = iniChar + hardCoded.get(key).get(3).length();
							buf.replace(iniChar, finChar, " ");
							buf.replace(iniChar,buf.length(), id);
							buf.replace(4, 16, "const");//replace the const-string for a const
							String register = currentLine.split(",")[0].replace("const-string", "").replace(" ", "");//gets the local register 							
							int numLocals = Integer.parseInt(hardCoded.get(key).get(6));

							//Get all the info of the method that the HCSs is in to later send it to the web report
							int indexMethod = Integer.parseInt(hardCoded.get(key).get(5))-1;
							int methodLineNum = indexMethod;
							String newPath = file.getAbsolutePath().split("\\\\smali\\\\")[1];
							newPath = newPath.replace(".smali", "");
							hcFinal.get(key).add(newPath);

							int paramCount = getNumParameters(methodLineNum, oldLines);//Get the number of params declared in a method
							Boolean pDirect = usesParamValue(methodLineNum, oldLines);//Check if the method that holds the HCS uses a param directly in an invoke line
							//Can only access string resources in a non-static method, because the static method cannot get a reference of the context (.this)
							if(!oldLines.get(indexMethod).contains("static") && !oldLines.get(indexMethod).contains("final")){
								//If the method where the HCS is found has 14 or more parameters + local vars, there's no way of adding more local vars needed to access the string reference.
								//Also, if there's an invoke-direct command that uses a param value, there's no way of adding more local vars
								//because the parameters would get values bigger than 16 and therefore can't be used in a invoke-direct call
								if(pDirect && (numLocals + paramCount) > 12  ){
									//as it couldn't be extracted, leave it untouched and report it as such
									newLines.add(originalLine);
									if(hcFinal.containsKey(key)){
										notReplaced.put(key, hcFinal.get(key));
										hcFinal.remove(key);
									}
								}
								//If new local vars can be created because the number of local vars after adding them is <= 17
								else{	
									//Check if the class where the HCS was found extends from context/activity
									if(context){
										//call getresources and assign it to an unused local variable
										newLines.add("    invoke-virtual/range {v"+ (numLocals)+" .. v" + (numLocals) + "}, L"+ packageName.replace(".", "/") + "/" + file.getName().replace(".smali", "") + ";->getResources()Landroid/content/res/Resources;");
										newLines.add("");
										newLines.add("    move-result-object v" + (numLocals+1));
										//assign the new referenced string to another unused local variable
										newLines.add("");
										newLines.add(buf.toString().replace(register, "v"+String.valueOf(numLocals+2)));
										//call get strings to get the valoue of the referenced string with the id from the previous line
										newLines.add("");
										newLines.add("    invoke-virtual/range {v"+ (numLocals+1)+" .. v" + (numLocals+2) + "}, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;");
										//asign the value of the referenced string to the original local var that held the HCS
										newLines.add("");
										newLines.add("    move-result-object "+(register));
									}
									//If it doesn't extend from either context/activity, there's no way to access resoucers without the possibility of introducing performance bugs.
									else{
										//as it couldn't be extracted, leave it untouched and report it as such
										newLines.add(originalLine);
										if(hcFinal.containsKey(key)){
											notReplaced.put(key, hcFinal.get(key));
											hcFinal.remove(key);
										}
									}
								}					
							}
							else{
								//as it couldn't be extracted, leave it untouched and report it as such
								newLines.add(originalLine);
								if(hcFinal.containsKey(key)){
									notReplaced.put(key, hcFinal.get(key));
									hcFinal.remove(key);
								}
							}
						}		
					}
					//The current line contains the number of local variables declared by a method
					else if(locals.containsKey(key) && locals.get(key) != null){
						String numLocals =String.valueOf(locals.get(key).get(0));
						int indexMethod = i-1;
						int paramCount = getNumParameters(indexMethod, oldLines);//Get the number of params declared in a method
						Boolean pDirect = usesParamValue(indexMethod, oldLines);//Check if the method that holds the HCS uses a param directly in an invoke line
						//Same check of parameters made before, if the total of local vars + params is bigger than 15 after adding new ones, then it cannot be modified
						if(pDirect && (locals.get(key).get(0) + paramCount) > 12  ){
							newLines.add(originalLine);
						}
						//if its possible to extract the HCS, then add more local vars to be able to do it
						else{
							numLocals =String.valueOf(locals.get(key).get(0)+3);
							iniChar = locals.get(key).get(1);
							finChar = iniChar + numLocals.length();
							buf.replace(iniChar, finChar, numLocals);
							//Still have to check if the resources can be accessed, otherwise there's no point in adding more vars
							if(context){
								newLines.add(buf.toString());
								//the p0 holds the .this reference, so a copy of the value is made to later be able to access the resources
								newLines.add("    move-object/from16 v" + (Integer.parseInt(numLocals)-3) + ", p0");	
							}
							else{//leave the local vars number as it is
								newLines.add(originalLine);
							}
						}
					}
					else{//if the line does not contain a HCS, leave it as it is
						newLines.add(originalLine);
					}
				}
				writeLines(file.getAbsolutePath(), newLines);//Write the smali file after doing all the modifications
			}
		}
		writeStringFiles(hcFinal, layoutStrs, packageName, folderPath);	//Write the ids and names of the strings in the XML references files 
		writeReportJSON(hcFinal, notReplaced, layoutStrs, outputPath); //Write the JSONs, that the web report uses, with the info about the changes made regarding HCSs 
		numbNotExtracted = notReplaced.size();
		return numbNotExtracted;
	}

	/**
	 * Method that writes the JSON files of extracted HCSs from the smali file, 
	 * extracted HCSs from the layouts, and reported but not extracted HCSs
	 *
	 * @param translated Map with all the extracted HCSs and their respective info
	 * @param untouched Map of the possible HCSs that were not extracted but are reported
	 * @param layout Map of the HCSs found in the layout and their respective info
	 * @param outputPath Location where the files are written
	 */
	private static void writeReportJSON(HashMap<String, List<String>> translated, HashMap<String, List<String>> untouched, HashMap<String, String[]> layout, String outputPath){
		String strContent = "";
		String className = "";
		String strId = "";
		String methodName = "";

		//Create JSON file for extracted HCSs
		List<JSONObject> listTranslated = new ArrayList<>();
		for (List<String> currTransl: translated.values()) {
			strContent = escapeXML(currTransl.get(3));//the HCS content
			strContent = strContent.replace("\\pd ", "\\n"); //fix the string that should contain \n and instead get the escaped version used for translation
			className = currTransl.get(9);
			if(className.contains("$"))//get only the name of the smali file without all that comes after a $	
				className = className.split("$")[0];
			methodName = currTransl.get(7);//method where the HCS was found
			strId = currTransl.get(2);//name given to the string resource.
			JSONObject currJson = new JSONObject();
			try {
				currJson.put("stringContent", strContent);
				currJson.put("className", className);
				currJson.put("stringID", strId);
				currJson.put("methodName", methodName);
				listTranslated.add(currJson);
			} catch (Exception e) {
				e.printStackTrace();
			}			
		}
		List<String> jsonTranslated = new ArrayList<>();
		jsonTranslated.add(new Gson().toJson(listTranslated));
		writeLines(outputPath + "/hcsTranslated.json", jsonTranslated);

		//Create JSON file for reported but not extracted HCSs
		List<JSONObject> notReplaced = new ArrayList<>();
		for (List<String> currStr: untouched.values()) {
			strContent = escapeXML(currStr.get(3));
			strContent = strContent.replace("\\pd ", "\\n");
			className = currStr.get(9);
			JSONObject currJson = new JSONObject();
			try {
				currJson.put("stringContent", strContent);
				currJson.put("className", className);
				notReplaced.add(currJson);
			} catch (Exception e) {
				e.printStackTrace();
			}			
		}
		List<String> jsonNotReplaced = new ArrayList<>();
		jsonNotReplaced.add(new Gson().toJson(notReplaced));
		writeLines(outputPath + "/hcsNotReplaced.json", jsonNotReplaced);

		//Create JSON file for the HCSs extracted from the layout
		List<JSONObject> layoutList = new ArrayList<>();
		for (String[] currStr: layout.values()) {
			strContent = escapeXML(currStr[2]);
			className = currStr[3].replace(".xml", "");
			strId = currStr[1];
			JSONObject currJson = new JSONObject();
			try {
				currJson.put("stringContent", strContent);
				currJson.put("className", className);
				currJson.put("stringID", strId);
				if(isExtractable(strContent)){
					currJson.put("resource", "strings.xml");
				}	
				else{
					currJson.put("resource", "nontranslatablestrings.xml");
				}
				layoutList.add(currJson);
			} catch (Exception e) {
				e.printStackTrace();
			}			
		}
		List<String> jsonLayout = new ArrayList<>();
		jsonLayout.add(new Gson().toJson(layoutList));
		writeLines(outputPath + "/hcsLayout.json", jsonLayout);		
	}

	/**
	 * Method that writes the XML files where the strings references should be to work properly. 
	 * Also writes in the R$String smali file to add the references so the java code can access them properly.
	 * @param hardCoded Map with all the info (ids, names, content) of the HCSs extracted from the smali files
	 * @param layoutStrs Map with all the info (ids, names, content) of the HCSs extracted from the XML layout files.
	 * @param packageName Name of the package where the decompiled files are
	 * @param folderPath Path of the decompiled files
	 */
	private static void writeStringFiles(HashMap<String, List<String>> hardCoded,HashMap<String, String[]> layoutStrs, String packageName, String folderPath) throws IOException{
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		List<String> newLines =  new ArrayList<String>();
		List<String> unique =  new ArrayList<String>();//Array to keep track of repeated strings to not add them more than once
		for (File file : files) {
			List<String> oldLines = readLines(file.getAbsolutePath());
			//Add the extracted HCSs new reference info to the resource strings file
			if(file.getAbsolutePath().equals(folderPath+File.separator+"res"+File.separator+"values"+File.separator+"strings.xml")){
				newLines = new ArrayList<String>();
				oldLines = readLines(file.getAbsolutePath());
				unique =  new ArrayList<String>();
				//Write every line of the file as it was, but dont use the resource close tag because all the new lines are gonna be written before it
				for (String currentLine : oldLines) {		
					currentLine = currentLine.replaceAll( "[^\\p{ASCII}]", "" );			
					if(!currentLine.equals("</resources>")){
						newLines.add(currentLine);
					}
					else{//Skips writing the </resources> line
						newLines.stream().skip(oldLines.size()-1);
					}		
				}
				//Now write all of the new string references but before putting the close resource tag
				for (List<String> value : hardCoded.values()) {
					String nameId = value.get(2);
					String name = escapeXML(value.get(3));
					if(name.contains("\\\\\'")){//escapes the "'" char, needs to be done after the escape in the previous line or it wont work
						name = name.replace("\\\\\'", "\\\'");
					}
					if(isExtractable(value.get(3))){//Check if the string should be extracted and reported, if not it does not do either
						if(!unique.contains(value.get(2))){//The string resource has to be only once in the references or it will encounter a conflict
							String line = "    <string name=\""+ nameId +"\" formatted=\"false\">"+ name+"</string>";
							unique.add(value.get(2));
							newLines.add(line);
						}
					}
				}
				//Do the same process as before but now with the references for the HCSs found in the layout files
				for (String[] value : layoutStrs.values()) {
					String nameId = value[1];
					String name = value[2].replace("\"", "");
					name = escapeXML(name);
					if(name.contains("\\\\\'")){
						name = name.replace("\\\\\'", "\\\'");
					}
					String line = "    <string name=\""+ nameId +"\" formatted=\"false\">"+ name+"</string>";
					newLines.add(line);
				}
				//Finally add the closing tag for the resource files
				newLines.add("</resources>");
				writeLines(file.getAbsolutePath(), newLines);	
			}
			//Add all the extracted HCS new reference info(resourceId, ) in the public XML
			else if(file.getAbsolutePath().equals(folderPath+File.separator+"res"+File.separator+"values"+File.separator+"public.xml")){
				unique =  new ArrayList<String>();
				newLines = new ArrayList<String>();
				oldLines = readLines(file.getAbsolutePath());
				for (String currentLine : oldLines) {//Write everything until before the closing tag
					if(!currentLine.equals("</resources>")){
						newLines.add(currentLine);
					}
					else{
						newLines.stream().skip(oldLines.size()-1);
					}		
				}
				//Write the reference info of the new string resources found in smali code
				for (List<String> value : hardCoded.values()) {
					String name = value.get(2);
					String id = value.get(1);
					if(!unique.contains(value.get(2))){
						String line = "    <public type=\"string\" name=\""+name+"\" id=\""+id+"\" />";
						newLines.add(line);
						unique.add(value.get(2));
					}
				}
				//Write the reference info of the new string resources found in smali code
				for (String[] value : layoutStrs.values()) {
					String id = value[0];
					String name = value[1];
					String line = "    <public type=\"string\" name=\""+name+"\" id=\""+id+"\" />";
					newLines.add(line);
				}
				//after adding all references, add the closing tag
				newLines.add("</resources>");
				writeLines(file.getAbsolutePath(), newLines);	
			}
			//Add all the extracted HCS new reference info(resourceId, ) in the R$string smali file
			else if(file.getAbsolutePath().contains("R$string.smali")){
				newLines = new ArrayList<String>();
				oldLines = readLines(file.getAbsolutePath());
				unique =  new ArrayList<String>();
				for (String currentLine : oldLines) {
					newLines.add(currentLine);
					//Checks the like where after it should start writing
					if(currentLine.equals("# static fields")){
						//First write the references of the HCSs found in the smali files
						for (List<String> value : hardCoded.values()) {
							String name = value.get(2);
							String id = value.get(1);
							//Check if the string should be reported and if the reference has not been written before
							if(isExtractable(value.get(3)) && !unique.contains(value.get(2))){
								String line = ".field public static final "+ name + ":I = "+id;
								newLines.add(line);
								newLines.add("");
								unique.add(value.get(2));
							}
						}
						//Then write the references of the HCSs found in layout files
						for (String[] value : layoutStrs.values()) {
							String name = value[1];
							String id = value[0];
							String line = ".field public static final "+ name + ":I = "+id;
							newLines.add(line);
							newLines.add("");							
						}
					}
				}
				writeLines(file.getAbsolutePath(), newLines);	
			}
		}					
	}

	/**
	 * Method that replaces back all the escaped chars in the XML strings file (english one) after the translation is done.
	 * @param folderPath folder where the string resources are found
	 */
	public static void setOriginalStrings(String folderPath){
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);

		for (File file : files) {
			List<String> newLines =  new ArrayList<String>();
			//Only edit the strings.xml english file.
			if(file.getName().equals("strings.xml") && !file.getAbsolutePath().contains("values-")) {
				List<String> oldLines = readLines(file.getAbsolutePath());
				for (String current : oldLines ) {
					if(current.contains("\\pd ")){//put back the \n char 
						newLines.add(current.replace("\\pd ", "\\n"));
					}
					else{
						newLines.add(current);
					}
				}
			writeLines(file.getAbsolutePath(), newLines);	
			}	
		}
	}

/////////// Support Methods used for the extraction of hard-coded strings (HCSs) ///////////

	/*
	* Support method that checks the AST of a smali file method, 
	* in order to get the information of the local variables declared in it.
	* Returns the AST with the info of the local variables for the method given by parameter
 	*/
	 public static CommonTree getLocals(CommonTree tree){
		CommonTree locals = null;
		CommonTree treeMethod = (CommonTree) tree.getParent().getParent();
		List<CommonTree> methodChildren = (List<CommonTree>) treeMethod.getChildren();
		for (CommonTree child : methodChildren) {
			if(child.getType() == smaliParser.I_LOCALS){
				locals = child;
				break;
			}			
		}
		return locals;
	}
	/*
	* Support method that sets the id that is going to be used to reference an extracted string
	* Returns the id created with the identifier of the string resources used by that APK.
	*/
	private static String setStringId(int index, String identifier){
		String stringId = "";
		//Checks the current number of the index to correctly create the id
		//always starts with 0x7f0 then followed by the identifier(given when created the APK) and then the index
		if(identifier.length()==1){
			identifier = "0"+identifier;
		}
		if( index>=0 && index < 10)
			stringId = "0x7f"+identifier+"000"+index;
		else if(index>= 10 && index < 100)
			stringId = "0x7f"+identifier+"00"+index;
		else if(index>= 100 && index < 1000)
			stringId = "0x7f"+identifier+"0"+index;
		else if(index>= 1000 && index < 10000)
			stringId = "0x7f"+identifier+index;
		return stringId;	
	}

	/*
	* Support method that gets the identifier used for the string resources.
	* Returns the identifier of the string resources used by that APK.
	*/
	public static String getStringIdentifier(String folderPath){
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		String identifier = "";
		Boolean checked = false;
		for (File file : files) {
			//only the file that holds the strings ids has to be checked
			if(file.getName().contains("public.xml")){
				List<String> fileLines = readLines(file.getAbsolutePath());
				for (int i = 0; i < fileLines.size() && !checked; i++){
					String currentLine = fileLines.get(i);
					//The identifier is extracted from the first string reference, so it stops there.
					if(currentLine.contains("type=\"string\"")){
						String identLine = currentLine.split("id")[1].replace("=\"","");
						String identValue = identLine.substring(4, 6);
						if(identValue.startsWith("0")){
							identifier = identValue.substring(1);
							checked = true;
						}
						else{
							identifier = identValue;
							checked = true;
						}				
					}
				}				
			}
		}
		return identifier;
	}

	/**
	 * Support method that checks if the current file extends Context or Activity.
	 * This is needed in order to access the string resources.
	 * @param oldLines list of file lines
	 **/
	private static boolean hasContext(List<String> oldLines){
		Boolean declaration = true;
		Boolean context = false;
		int index = 0;
		while(declaration){
			String line = oldLines.get(index);
			//The super line indicates what is the class is extending from.
			if(line.contains(".super")){
				if(line.contains("Activity") || line.contains("Context") ){
					context = true;
				}
				declaration = false;//stops when it finds the line that declares if the class extends from something
			}
			index++;
		}
		return context;
	}

	/**
	 * Support method that gets the number of parameters in a method in a smali file.
	 * This is needed to check if the sum of local variables and parameters is greater
	 * than the number of local vars that dalvik sets for memory access(16).
	 * @param methodLineNum number of the line where a method starts
	 * @param oldLines File lines to check
	 */
	private static int getNumParameters(int methodLineNum, List<String> oldLines){
		int paramCount = 1;// every non static parameter has atleast one param
		Boolean methodEnd = false;

		while(!methodEnd){
			methodLineNum++;
			String lineBetween = oldLines.get(methodLineNum);
			//checks for a parameter declaration
			if(lineBetween.contains(".param")){
				paramCount++;
			}
			//only has to check that method, so it goes no further
			else if(lineBetween.contains(".end method")){
				methodEnd = true;
			}
		}
		return paramCount;
	}

	/**
	 * Support method that checks if any param value of a method in a smali file is used in an invocation or moving the value with iget
	 * @param methodLineNum number of the line where a method starts
	 * @param oldLines File lines to check
	 * @return
	 */
	private static Boolean usesParamValue(int methodLineNum, List<String> oldLines){
		Boolean methodEnd = false;
		Boolean pDirect = false;
		String igetRegex = "    iget-[a-z]+\\s(v|p)\\d+(\\,\\s(p)\\d+)*\\,\\s.*";
		String invokeRegex = "    invoke-[a-z]+\\s\\{(v|p)\\d+(\\,\\s(v|p)\\d+)*\\}\\,\\s.*";
		while(!methodEnd){
			methodLineNum++;
			String lineBetween = oldLines.get(methodLineNum);
			if(lineBetween.matches(invokeRegex)){
				//Checks if an invoke method gets a parameter value
				if(lineBetween.split("\\s*[\\{\\}]\\s*")[1].contains("p"))
					pDirect = true;
			}
			else if(lineBetween.matches(igetRegex)){
				pDirect = true;
			}
			//only has to check that method, so it goes no further
			else if(lineBetween.contains(".end method")){
				methodEnd = true;
			}
		}
		return pDirect;
	}

	/**
	 * Support method that escapes all the necessary chars so the XML file can be written correctly
	 * @param str String to be escaped
	 */
	private static String escapeXML(String str){
		Pattern patternReplace = Pattern.compile("[^\\x00-\\x7F]");
		Matcher matcherReplace = patternReplace.matcher(str);	
		str = matcherReplace.replaceAll("");//Remove all the replace unicode chars to avoid parsing errors
		str = Normalizer.normalize(str, Normalizer.Form.NFD);
		str = str.replaceAll( "[^\\p{ASCII}]", "" );
		//One string could have all cases, so it has to check all
		if(str.contains("&"))
			str = str.replace("&", "&amp;");
		if(str.contains("<"))
			str = str.replace("<", "&lt;");
		if(str.contains(">"))
			str = str.replace(">", "&gt;");
		if(str.contains("\n"))
			str = str.replace("\n", "\\pd ");
		if(str.contains("\""))
			str = str.replace("\"", "");
		if(str.contains("'"))
			str = str.replace("'", "\\'");
		if(str.startsWith("?"))
			str = str.replaceFirst("\\?", "\\\\?");
   
		   return str;
	}

	/**
	 * Support method that checks for string patterns that indicate 
	 * that the string should not be reported as a HCS.
	 * @param str string to be checked
	 */
	private static Boolean isExtractable(String str){
		Boolean isTransla = true;
		//All the patterns of strings that should not be reported
		String numPattern = "^-?\\d+\\.?\\d*$";
		String packageName = "^([A-Za-z]{1}[A-Za-z\\d_]*\\.)+[A-Za-z][A-Za-z\\d_]*/*[A-Za-z]*$";
		String url = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])";
		String email = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
		String camelCase = "[a-zA-Z]+[A-Z0-9][a-z0-9]+[A-Za-z0-9]*[()]*";
		String path = "([A-Za-z])+/([A-Za-z])+";
		String hyphenatedWord = "[A-Za-z\\d]+(-[A-Za-z\\d]+)+";
		String atString = "^@+$";
		String specialChar = "[^\\u0000-\\u00FF]";
		String camelDotVariation = "([a-zA-Z]+[()]*\\.)+[a-zA-Z]+[()]*";
		Pattern patternReplace = Pattern.compile("[^\\x00-\\x7F]");//Check for non unicode chars
		Matcher matcherReplace = patternReplace.matcher(str);		
		
		if(matcherReplace.find()){
			isTransla = false;
		}
		str = str.replace("\"", "");//escapes the " char in order to get accurate matches
		if(str.matches(numPattern))
			isTransla = false;
		else if(str.matches(packageName))
			isTransla = false;
		else if(str.matches(url))
			isTransla = false;
		else if(str.matches(email))
			isTransla = false;
		else if(str.matches(camelCase) && str.length()>1)
			isTransla = false;
		else if(str.matches("@"))
			isTransla = false;
		else if(str.contains("android.intent"))
			isTransla = false;
		else if(str.matches(path))
			isTransla = false;
		else if(str.matches(hyphenatedWord))
			isTransla = false;
		else if(str.matches(atString))
			isTransla = false;
		else if(str.matches(specialChar))
			isTransla = false;
		else if(str.matches(camelDotVariation))
			isTransla = false;

		return isTransla;
	}

	/**
	 * Support method that reads all the lines of a file and puts them in a string list
	 * in order to make the editing process easier
	 * @param filePath path where the file is found
	 */
	private static List<String> readLines(String filePath){
		List<String> lines = new ArrayList<String>();
		try {
			BufferedReader br = new BufferedReader(new FileReader(filePath));
			String line;
			while((line = br.readLine())!=null){
				lines.add(line);
			}
			br.close();
		} catch(FileNotFoundException e){
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return lines;
	}

	/**
	 * Support method that reads all the lines of a file and puts them in a string list
	 * in order to make the editing process easier
	 * @param filePath path where the file is found
	 */
	public static boolean writeLines(String filePath, List<String> lines){		
		try{
			BufferedWriter bw = new BufferedWriter(new FileWriter(filePath));	
			for (String newLine : lines) {
				bw.write(newLine);
				bw.newLine();
				bw.flush();
			}		
			bw.close(); 
		}  catch (FileNotFoundException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}		
		return true;
	}


/////////// Methods used for the repairment of missed mirroring in the layouts for Right-to-Left (RTL) languages ///////////

	/**
	 * Method that checks the target SDK version declared for an Android app. It does it by running the command  "aapt dump badging"
	 * with the name of the app.
	 * @param apkName name of the app being tested
	 * @return False if the app's target SDK version is < 17 or if the target is not declared; True otherwise.
	 * @throws InterruptedException
	 */
	public static Boolean checkTargetSDK(String apkName) throws InterruptedException{
	Boolean validTarget = false;
	int targetSDK = 0;
			try {
				String contents = "" ;
				Process p = Runtime.getRuntime().exec(new String[]{"aapt", "dump", "badging", Paths.get(".\\", apkName + ".apk").toAbsolutePath().toString()});
				InputStream inputStream =  p.getInputStream(); //Gets the log that the command generates with the info about the app.

				//Read the log line by line looking for the target SDK declaration
				BufferedReader in = new BufferedReader(
						new InputStreamReader( inputStream ) );
					//Check for the declaration until the target is found
				while ( ( contents = in.readLine() ) != null && !validTarget )
					{
						if(contents.contains("targetSdkVersion:")){
							contents = contents.split(":")[1];
							targetSDK = Integer.parseInt(contents.replace("'", ""));
							if(targetSDK >= 17){
								validTarget = true;
							}
						}
					}
				in.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			return validTarget;
		}

	/**
	 * Method that rewrites the XML Layout files, looking to replace some XML attributes in order to support RTL languages:
	 * Replaces attributes that mention left or right, with start and end, respectively.
	 * @param hardCoded Map with all the info (ids, names, content) of the HCSs extracted from the smali files
	 * @param layoutStrs Map with all the info (ids, names, content) of the HCSs extracted from the XML layout files.
	 * @param packageName Name of the package where the decompiled files are
	 * @param folderPath Path of the decompiled files
	 */
	public static void supportRTL( String packageName, String folderPath) throws IOException{
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		List<String> newLines =  new ArrayList<String>();
		for (File file : files) {
			List<String> oldLines = readLines(file.getAbsolutePath());
			newLines =  new ArrayList<String>();
			//Only check the files included in the layout folder, or else some visual bugs could be introduced if replaced where it should not be.
			if(file.getAbsolutePath().contains(folderPath+File.separator+"res"+File.separator+"layout")){
				//Write every line of the file as it was, but dont use the resource close tag because all the new lines are gonna be written before it
				for (String currentLine : oldLines) {	
					//Check if any of the attributes makes reference to the word Left.
					//Ending with "=" helps to make sure it only checks attributes and not values.			
					Pattern patternLeft = Pattern.compile("Left=");
					Matcher matcherLeft = patternLeft.matcher(currentLine);
					String newLine = currentLine;
					//Some XML elements already have the attribute with Start, but also declare the same with left
					//So to avoid duplicated attributes, it checks if the attribute to fix already exists
					Pattern patternStart = Pattern.compile("android:\\w+Start=");
					Matcher matcherStart = patternStart.matcher(currentLine);
					if(matcherLeft.find() && !matcherStart.find()) {
						newLine = matcherLeft.replaceAll("Start=");
					}

					//Check if any of the attributes makes reference to the word Right.
					//Ending with "=" helps to make sure it only checks attributes and not values.	
					Pattern patternRight = Pattern.compile("Right=");
					Matcher matcherRight = patternRight.matcher(currentLine);
					//Same verification to avoid repeated attributes done in left/start, but now done with right/end
					Pattern patternEnd = Pattern.compile("android:(\\w)+End=");
					Matcher matcherEnd = patternEnd.matcher(currentLine);
					if(matcherRight.find() && !matcherEnd.find()) {
						newLine = matcherRight.replaceAll("End=");

					}
					newLines.add(newLine);
				}
				writeLines(file.getAbsolutePath(), newLines);
			}
			//Look for the manifest file to add the attribute that enables RTL support
			else if(file.getAbsolutePath().equals(folderPath+File.separator+"AndroidManifest.xml")){
				newLines =  new ArrayList<String>();
				String newLine = "";
				for (String currentLine : oldLines) {
					//The attribute cannot be repeated, so first check if it already exists.
					//Also, the attribute should be declared in the application element.					
					if(currentLine.contains("<application") && !currentLine.contains("android:supportsRtl=\"true\"")){
						newLine= currentLine.split(">")[0];
						newLine= newLine+" android:supportsRtl=\"true\">";
						newLines.add(newLine);
					}
					else{
						newLines.add(currentLine);
					}
				}
				writeLines(file.getAbsolutePath(), newLines);
			}
		}
		createLayoutsRTL(packageName, folderPath);					
	}
	/**
	 * Method that copies the layout folder and all of its files in order to create a version that forces mirroring
	 * that is only needed for RTL languages. Currently it handles only arabic.
	 * @param packageName name of the apk package
	 * @param folderPath path where the layout files are stored.
	 */
	public static void createLayoutsRTL(String packageName, String folderPath){
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		List<String> newLines =  new ArrayList<String>();
		for (File file : files) {
			newLines =  new ArrayList<String>();
			List<String> oldLines = readLines(file.getAbsolutePath());
			Boolean firstLayout = false;
			if(file.getAbsolutePath().contains(folderPath+File.separator+"res"+File.separator+"layout")){
				for (String currentLine : oldLines) {
					String newLine = currentLine; 
					//Only the parent layout in the file needs to have the forced RTL attribute, so this variable allows to not check all the file
					//it has to be a layout element and also has to be the opening tag
					if(!currentLine.contains("</") && currentLine.contains("Layout") && !firstLayout ){
						//Make sure not to add the attribute twice in case it already is declared
						if(!currentLine.contains("android:layoutDirection")){
							newLine = currentLine.split(">")[0];
							//Check if the element tag closes in the line the change is going to happen
							//If so, then it has to be added back
							if(currentLine.endsWith(">")){
								//The element can also be a one liner, so it could be closed with /> at the end
								//This is other case that must be added back.
								if(currentLine.endsWith("/>")){
									newLine = currentLine.split("/>")[0];
									newLine= newLine+" android:layoutDirection=\"rtl\"/>";
								}
								else{
									newLine= newLine+" android:layoutDirection=\"rtl\">";
								}
							}
							else{
								//Add it without closing the tag, as the element declaration is continued in the following line(s)
								newLine= newLine+" android:layoutDirection=\"rtl\"";
							}
							newLines.add(newLine);
							firstLayout = true;
						}
						else{
							newLines.add(currentLine);
						}
					}
					else{
						newLines.add(currentLine);
					}
				}
				//Finally create the folder for the arab layout and place all the files modified in this method there.
				new File(folderPath + File.separator + "res" + File.separator + "layout-ar").mkdirs();
				String newFile = folderPath + File.separator + "res" + File.separator + "layout-ar" + File.separator + file.getName();
				writeLines(newFile, newLines);
			}
		}
	}
}