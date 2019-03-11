package uniandes.tsdl.itdroid.translator;

public class Translator {

    private String path;

    private String inputLang;

    private String outputLang;

    //Constructor
    public Translator(String pPath, String pInLang, String pOutLang){
        this.path = pPath;
        this.inputLang = pInLang;
        this.outputLang = pOutLang;
    }

    public void translate(TranslationInterface translationStrategy) throws Exception{
    	System.out.println(this.path+" - "+this.inputLang+" - "+this.outputLang);
        translationStrategy.translate(this.path, this.inputLang, this.outputLang);
    }

    //Setters and getters
    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public void setInputLang(String inputLang) {
        this.inputLang = inputLang;
    }

    public String getInputLang() {
        return inputLang;
    }

    public void setOutputLang(String outputLang) {
        this.outputLang = outputLang;
    }

    public String getOutputLang() {
        return outputLang;
    }
}
