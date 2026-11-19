package uniandes.tsdl.itdroid.helper;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource names that are never translated nor counted as missing, the framework ones packaged with
 * ITDroid plus the ones of translation.keepResources. {@link Translatability} applies them.
 */
public class NotTranslatableStringsDictionary {

    static final String FILE_NAME = "not-translatable-strings.xml";

    private final Set<String> names = new HashSet<>();

    public NotTranslatableStringsDictionary(Collection<String> extraNames) {
        try (InputStream in = NotTranslatableStringsDictionary.class.getResourceAsStream(FILE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(FILE_NAME + " is missing from the ITDroid jar");
            }
            Document document = new SAXBuilder().build(in);
            List<Element> strings = document.getRootElement().getChildren();
            for (Element e : strings) {
                if (e.getAttributeValue("name") != null) {
                    names.add(e.getAttributeValue("name"));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not read the packaged " + FILE_NAME, e);
        }
        names.addAll(extraNames);
    }

    public boolean translatable(String key) {
        return !names.contains(key);
    }
}
