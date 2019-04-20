package uniandes.tsdl.itdroid.helper;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.TokenSource;
import org.antlr.runtime.tree.CommonTree;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

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
			
			if(brother != null && brother.getChildIndex()-t.getChildIndex()>2) {
				return false;
			}
			
			return true;
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
		folderPath = folderPath+File.separator+"smali";
		Collection<File> files = FileUtils.listFiles(new File(folderPath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		int possibleIPFS = 0;
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
					possibleIPFS+=keyStrings.size();
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

}
