package uniandes.tsdl.itdroid.IBM;

import com.ibm.watson.developer_cloud.language_translator.v3.LanguageTranslator;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslateOptions;
import com.ibm.watson.developer_cloud.language_translator.v3.model.Translation;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslationResult;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.translator.TranslationInterface;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class IBMTranslator implements TranslationInterface {

    private static final String OUTPUT_FOLDER = "./temp/res/values";

    private List<String> values;
    private List<String> names;
    public IBMTranslator(){
        values = new ArrayList<>();
        names = new ArrayList<>();
    }
    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary();
        SAXBuilder builder = new SAXBuilder();
        File xmlFile = new File(xmlPath);
        Document document = builder.build(xmlFile);

        Element root = document.getRootElement();
        List<Element> strings = root.getChildren();

        Element e;
        String attributeValue;
        for(int i = 0; i < strings.size(); i++){
            e = strings.get(i);
            attributeValue = e.getAttributeValue("name");
            if(dictionary.translatable(attributeValue)){
                values.add(e.getText());
                names.add(attributeValue);
            }
        }

        IamOptions options = new IamOptions.Builder().apiKey("taeNhB6MVcU4pn6TMPj7bCiwGSGL_w4hPCmwuwX24r3u").build();
        LanguageTranslator languageTranslator = new LanguageTranslator(
                "2018-05-01",
                options);
        System.out.println("model: " + inputLang + "-" + outputLang);
        languageTranslator.setEndPoint("https://gateway.watsonplatform.net/language-translator/api");
        TranslateOptions translateOptions = new TranslateOptions.Builder().text(values).modelId(inputLang + '-' + outputLang).build();
        TranslationResult result = languageTranslator.translate(translateOptions)
                .execute();

        SAXBuilder builder2 = new SAXBuilder();
        File xmlOutputFile = new File(OUTPUT_FOLDER + "-" + outputLang + "/strings.xml");
        Document outputDocument = builder2.build(xmlOutputFile);
        root = outputDocument.getRootElement();

        List<Translation> translations = result.getTranslations();
        Element newString;
        for (int i = 0; i < translations.size(); i++){
            newString = new Element("string");
            newString.setAttribute("name", names.get(i));
            newString.setText(translations.get(i).getTranslationOutput());
            root.addContent(newString);
        }

        XMLOutputter output = new XMLOutputter();
        output.setFormat(Format.getPrettyFormat());
        output.output(outputDocument, new FileWriter(xmlOutputFile));
    }
}
