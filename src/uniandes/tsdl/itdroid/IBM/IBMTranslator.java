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
        for (int i = 0; i < translations.size(); i++){
            newString = new Element("string");
            newString.setAttribute("name", names.get(i));
            newString.setText(translations.get(i).getTranslationOutput());
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
}
