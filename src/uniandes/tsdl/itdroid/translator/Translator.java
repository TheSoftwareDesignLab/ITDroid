package uniandes.tsdl.itdroid.translator;

/**
 * Strategy context for translation: holds the source file and the input/output languages and
 * delegates the actual work to a {@link TranslationInterface} implementation.
 */
public class Translator {

    private final String path;
    private final String inputLang;
    private final String outputLang;

    public Translator(String pPath, String pInLang, String pOutLang){
        this.path = pPath;
        this.inputLang = pInLang;
        this.outputLang = pOutLang;
    }

    public void translate(TranslationInterface translationStrategy) throws Exception{
        translationStrategy.translate(this.path, this.inputLang, this.outputLang);
    }
}
