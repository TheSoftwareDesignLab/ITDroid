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

    // LIST OF CODES FOR RTL LANGUAGES
    // ar = Arabic
    // arc = Aramaic
    // ckb = Kurdish (Sorani)
    // dv = Divehi
    // fa = Persian
    // ha = Hausa
    // he = Hebrew
    // khw = Khowar
    // ks = Kashimiri
    // ps = Pashto
    // ur = Urdu
    // uz_AF = Uzbeki Afghanistan
    // yi = Yidish
    public static final String[] RTL_LANGUAGES = { "ar", "arc", "ckb", "dv", "fa", "ha", "he", "khw", "ks", "ps", "ur",
            "uz_AF", "yi" };

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

    public static int levenshteinDistance(String left, String right) {
        left = left.toLowerCase();
        right = right.toLowerCase();
        // i == 0
        int n = left.length(); // length of left
        int m = right.length(); // length of right

        if (n == 0) {
            return m;
        } else if (m == 0) {
            return n;
        }

        if (n > m) {
            // swap the input strings to consume less memory
            final String tmp = left;
            left = right;
            right = tmp;
            n = m;
            m = right.length();
        }

        int[] p = new int[n + 1];

        // indexes into strings left and right
        int i; // iterates through left
        int j; // iterates through right
        int upper_left;
        int upper;

        char rightJ; // jth character of right
        int cost; // cost

        for (i = 0; i <= n; i++) {
            p[i] = i;
        }

        for (j = 1; j <= m; j++) {
            upper_left = p[0];
            rightJ = right.charAt(j - 1);
            p[0] = j;

            for (i = 1; i <= n; i++) {
                upper = p[i];
                cost = left.charAt(i - 1) == rightJ ? 0 : 1;
                // minimum of cell to the left+1, to the top+1, diagonally left and up +cost
                p[i] = Math.min(Math.min(p[i - 1] + 1, p[i] + 1), upper_left + cost);
                upper_left = upper;
            }
        }

        return p[n];
    }

    /**
     * Indicates whether a language is RTL.
     * 
     * @param code ISO code for the language
     * @return True if the language is RTL, false otherwise.
     */
    public static boolean languageIsRTL(String code) {
        for (String languageCode : RTL_LANGUAGES) {
            if (languageCode.equals(code)) {
                return true;
            }
        }

        return false;
    }
}
