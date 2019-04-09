package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class Helper {

	public static Helper instance = null;
	public static String currDirectory = "";
	public static List<String> actNames = new ArrayList<String>();
	public static String mainActivity = "";
	public static String packageName = "";
	public final static String MANIFEST = "AndroidManifest.xml";
	public final static String MAIN_ACTION = "android.intent.action.MAIN";
	public static final int MIN_VERSION = 2;
	public static final int MAX_VERSION = 27;
	public static final String MIN_SDK_VERSION = "android:minSdkVersion";
	public static final String TARGET_SDK_VERSION = "android:targetSdkVersion";
	public static final String MAX_SDK_VERSION = "android:maxSdkVersion";
	public static final String STRINGS = "strings.xml";
	public static final String COLORS = "colors.xml";

	public static Helper getInstance() {
		if (instance == null) {
			instance = new Helper();
		}
		return instance;
	}

	public static String getPackageName() {
		return packageName;
	}



	public static void setPackageName(String packageName) {
		Helper.packageName = packageName;
	}



	public String getCurrentDirectory() throws UnsupportedEncodingException {

		String dir = System.getProperty("user.dir");
		return dir;

	}

	public static boolean isWindows() {
		String os = System.getProperty("os.name").toLowerCase();
		if (os.indexOf("win") >= 0) {
			return true;
		}
		return false;
	}

	public static int levenshteinDistance(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        // i == 0
        int [] costs = new int [b.length() + 1];
        for (int j = 0; j < costs.length; j++)
            costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            // j == 0; nw = lev(i - 1, j)
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]), a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }
}
