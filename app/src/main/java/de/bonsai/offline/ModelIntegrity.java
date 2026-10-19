package de.bonsai.offline;

/** Checks the exact official Bonsai 2 files tested with this runtime, entirely offline. */
final class ModelIntegrity {
    static final String REVISION="8d6557168168f91ea6697a4a49ed2ef622b53adb";
    static final String Q2_NAME="Ternary-Bonsai-2-4B-Q2_0.gguf";
    static final String Q2_SHA="64a5183b44921bdb5ac4a6f5f92007821d6ebe5c66997d3c147fd214ab9433d4";
    static final String PQ2_NAME="Ternary-Bonsai-2-4B-PQ2_0.gguf";
    static final String PQ2_SHA="d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409";

    static String expectedSha(String name) {
        if(name==null) return null;
        name=name.replaceFirst("(?i) \\([0-9]+\\)(?=\\.gguf$)","");
        if(Q2_NAME.equalsIgnoreCase(name)) return Q2_SHA;
        if(PQ2_NAME.equalsIgnoreCase(name)) return PQ2_SHA;
        return null;
    }
    static void verify(String name,long bytes,String sha) {
        String expected=expectedSha(name);
        if(expected==null) return;
        long expectedBytes=Q2_SHA.equals(expected)?1212103520L:1146465120L;
        if(bytes!=expectedBytes || !expected.equalsIgnoreCase(sha))
            throw new IllegalStateException("Modellprüfung fehlgeschlagen: "+name+
                "\nDie Datei entspricht nicht der geprüften offiziellen Fassung. Gleiche Dateigröße genügt nicht."+
                "\nErwartete SHA256: "+expected+"\nImportierte SHA256: "+sha+
                "\nBitte die geprüfte Datei erneut herunterladen und importieren.");
    }
    static String downloadUrl() {
        return "https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/resolve/"+REVISION+"/"+Q2_NAME+"?download=true";
    }
    static String hex(byte[] digest) {
        StringBuilder out=new StringBuilder();
        for(byte value:digest) out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }
}
