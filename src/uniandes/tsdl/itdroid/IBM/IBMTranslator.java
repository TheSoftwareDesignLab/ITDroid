package uniandes.tsdl.itdroid.IBM;

import com.ibm.watson.developer_cloud.language_translator.v3.LanguageTranslator;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslateOptions;
import com.ibm.watson.developer_cloud.language_translator.v3.model.Translation;
import com.ibm.watson.developer_cloud.language_translator.v3.model.TranslationResult;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import uniandes.tsdl.itdroid.translator.TranslationInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class IBMTranslator implements TranslationInterface {

    private List<String> values;
    private List<String> names;
    public IBMTranslator(){
        values = new ArrayList<>();
        names = new ArrayList<>();
    }
    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        File xmlFile = new File(xmlPath);
        Document document = builder.build(xmlFile);

        Element root = document.getRootElement();
        List<Element> strings = root.getChildren();

        Element e;
        for(int i = 0; i < strings.size(); i++){
            e = strings.get(i);
            values.add(e.getText());
            names.add(e.getAttributeValue("name"));
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

        List<Translation> translations = result.getTranslations();
        List<String> newValues = new ArrayList();
        for (int i = 0; i < translations.size(); i++){
            newValues.add(translations.get(i).getTranslationOutput());
            System.out.println(translations.get(i).getTranslationOutput());
        }

    }
}
