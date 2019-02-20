package uniandes.tsdl.itdroid.translator;

public class IBMTranslator implements TranslationInterface {
    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) {
        System.out.println("Translating with IBM");
    }
}
