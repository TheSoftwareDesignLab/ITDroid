package uniandes.tsdl.itdroid.helper;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.util.Hashtable;
import java.util.List;

public class NotTranslatableStringsDictionary {
    private Hashtable<String, String> diccionario;

    public NotTranslatableStringsDictionary(String pDirectory) throws Exception{
        diccionario = new Hashtable<>(70);
        SAXBuilder builder = new SAXBuilder();
        File xmlFile = new File(pDirectory + "/strings.xml");
        Document document = builder.build(xmlFile);

        Element root = document.getRootElement();
        List<Element> strings = root.getChildren();

        Element e;
        for(int i = 0; i < strings.size(); i++){
            e = strings.get(i);
            // Hashtable rejects null keys/values; skip elements without a name attribute and
            // substitute "" for missing text so a malformed entry cannot NPE the whole run.
            String name = e.getAttributeValue("name");
            String text = e.getText();
            if (name != null) {
                diccionario.put(name, text != null ? text : "");
            }
        }
    }

    public boolean translatable (String key){
        return !(diccionario.containsKey(key));
    }
}
