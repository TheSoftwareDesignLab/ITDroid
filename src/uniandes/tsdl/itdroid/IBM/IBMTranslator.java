package uniandes.tsdl.itdroid.IBM;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.ibm.watson.developer_cloud.language_translator.v3.LanguageTranslator;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslateOptions;
import com.ibm.watson.developer_cloud.language_translator.v3.model.Translation;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslationResult;
import com.ibm.watson.developer_cloud.service.security.IamOptions;

import io.github.cdimascio.dotenv.Dotenv;
import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.translator.TranslationInterface;

public class IBMTranslator implements TranslationInterface {

    private static final String OUTPUT_FOLDER = "./temp/res/values";

    private List<String> values;
    private List<String> names;
    private String propertiesDirectory;
    public IBMTranslator(String directory){
        values = new ArrayList<>();
        names = new ArrayList<>();
        propertiesDirectory = directory;
    }
    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        Dotenv dotenv = Dotenv.load();
        System.out.println(dotenv.get("GATEWAY"));
        //Initialize the dictionary to exclude automatically translated strings.
        NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(propertiesDirectory);
        //Read the default strings.xml file
        SAXBuilder builder = new SAXBuilder();
        File xmlFile = new File(xmlPath);
        Document document = builder.build(xmlFile);

        // Initialize set to manage strings that are already translated
        Set<String> translatedStrings = new HashSet<>();
        // Read the language specific strings.xml file
        SAXBuilder builder2 = new SAXBuilder();
        File xmlOutputFolder = new File(OUTPUT_FOLDER + "-" + outputLang + "/");
        File xmlOutputFile = new File(OUTPUT_FOLDER + "-" + outputLang + "/strings.xml");
        // Create the output directory if it doesn't exists.
        if(!xmlOutputFolder.exists()){
        	xmlOutputFolder.mkdirs();
            xmlOutputFile.createNewFile();
            BufferedWriter writer = new BufferedWriter(new FileWriter(xmlOutputFile));
            writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            writer.newLine();
            writer.write("<resources>");
            writer.newLine();
            writer.write("</resources>");
            writer.newLine();
            writer.close();
        }
        Document outputDocument = builder2.build(xmlOutputFile);
        Element outputRoot = outputDocument.getRootElement();
        //Get the strings that were previously translated by the developer.
        List<Element> strings2 = outputRoot.getChildren();
        for(int i = 0; i < strings2.size(); i++){
            translatedStrings.add(strings2.get(i).getAttributeValue("name"));
        }
        // Get the root element from the default strings xml file.
        Element root = document.getRootElement();
        List<Element> strings = root.getChildren();
        //Extract the strings that should be translated
        Element e;
        String attributeValue;
        String text;
        for(int i = 0; i < strings.size(); i++){
            e = strings.get(i);
            attributeValue = e.getAttributeValue("name");
            text = e.getText();
            //If the string has not been translated, add it to the list
            if(dictionary.translatable(attributeValue) && !(translatedStrings.contains(attributeValue) && !isOnlyNumbersAndSpecs(text))){
                text = replaceInjectedStrings1(text);
                text = replaceInjectedDigits1(text);
                values.add(text);
                names.add(attributeValue);
            }
        }
        //Call the IBM API to translate strings.
        IamOptions options = new IamOptions.Builder().apiKey(dotenv.get("API_KEY")).build();
        LanguageTranslator languageTranslator = new LanguageTranslator(
                "2018-05-01",
                options);
        System.out.println("model: " + inputLang + "-" + outputLang);
        languageTranslator.setEndPoint(dotenv.get("GATEWAY"));
        TranslateOptions translateOptions = new TranslateOptions.Builder().text(values).modelId(inputLang + '-' + outputLang).build();
        //Get the translation results.
        TranslationResult result = languageTranslator.translate(translateOptions)
                .execute();
        List<Translation> translations = result.getTranslations();
        //Add the translated strings to the specific language strings.xml file.
        Element newString;
        String text2;
        for (int i = 0; i < translations.size(); i++){
            newString = new Element("string");
            newString.setAttribute("name", names.get(i));
            text2 = replaceUnscapedCharacters(translations.get(i).getTranslationOutput());
            text2 = replaceInjectedStrings2(text2);
            text2 = replaceInjectedDigits2(text2);
            newString.setText(text2);
            outputRoot.addContent(newString);
        }
        //Save changes.
        XMLOutputter output = new XMLOutputter();
        output.setFormat(Format.getPrettyFormat());
        output.output(outputDocument, new OutputStreamWriter(new FileOutputStream(xmlOutputFile), "UTF8"));
    }

    /**
     * Checks if a string only contains numbers and special characters
     * @param string
     * @return
     */
    public boolean isOnlyNumbersAndSpecs(String string){
        return string.matches("[\\d-/@#$%^&_+=():sd\\s]+");
    }

    /**
     * Replaces " and ' that are unscaped.
     * @param text
     * @return
     */
    public static String replaceUnscapedCharacters(String text) {
        String modifier1 = text.replaceAll("(?<!\\\\)\"", "\\\\\"");
        String modifier2 = modifier1.replaceAll("(?<!\\\\)\'", "\\\\\'");
        return modifier2;
    }

    /**
     * Replaces the injected strings for non translatable strings
     * @param input
     * @return
     */
    public static String  replaceInjectedStrings1 (String input) {
        String repleaceable = input;
        Pattern pattern = Pattern.compile("%\\d\\$s");
        Matcher matcher = pattern.matcher(repleaceable);
        while(matcher.find()) {
            String injectedCharacter = matcher.group(0);
            char number = injectedCharacter.charAt(1);
            repleaceable = matcher.replaceFirst("xyz" + number);
            matcher = pattern.matcher(repleaceable);
        }

        return repleaceable;
    }

    /**
     * Replaces the non translatable string for the injected parameters values.
     * @param input
     * @return
     */
    public static String replaceInjectedStrings2 (String input) {
        String repleaceable = input;
        Pattern pattern = Pattern.compile("xyz\\d");
        Matcher matcher = pattern.matcher(repleaceable);
        while(matcher.find()) {
            String injectedCharacter = matcher.group(0);
            char number = injectedCharacter.charAt(3);
            repleaceable = matcher.replaceFirst("%" + number + "\\$s");
            matcher = pattern.matcher(repleaceable);
        }
        return repleaceable;

    }
    /**
     * Replaces the injected digits for non translatable strings
     * @param input
     * @return
     */
    public static String  replaceInjectedDigits1 (String input) {
        String repleaceable = input;
        Pattern pattern = Pattern.compile("%\\d\\$d");
        Matcher matcher = pattern.matcher(repleaceable);
        while(matcher.find()) {
            String injectedCharacter = matcher.group(0);
            char number = injectedCharacter.charAt(1);
            repleaceable = matcher.replaceFirst("jkl" + number);
            matcher = pattern.matcher(repleaceable);
        }

        return repleaceable;
    }
    /**
     * Replaces the non translatable string for the injected parameters values.
     * @param input
     * @return
     */
    public static String replaceInjectedDigits2 (String input) {
        String repleaceable = input;
        Pattern pattern = Pattern.compile("jkl\\d");
        Matcher matcher = pattern.matcher(repleaceable);
        while(matcher.find()) {
            String injectedCharacter = matcher.group(0);
            char number = injectedCharacter.charAt(3);
            repleaceable = matcher.replaceFirst("%" + number + "\\$d");
            matcher = pattern.matcher(repleaceable);
        }
        return repleaceable;

    }
}
