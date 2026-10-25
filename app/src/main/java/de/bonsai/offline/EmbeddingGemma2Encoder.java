package de.bonsai.offline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Text-only, local GGUF inference. The native call owns and releases its model/context. */
final class EmbeddingGemma2Encoder implements KnowledgeSearch.QueryEncoder {
    static final String MODEL="google/embeddinggemma-2";
    static final String PROFILE="embeddinggemma2-search-v1";
    static final String HF_REVISION="914f7f89142e33e77833254d9c9b90c3cef7303b";
    static final String QUERY_PREFIX="task: search result | query: ";
    private final File weights;
    private final int threads;
    EmbeddingGemma2Encoder(File weights,int threads) {
        if(threads<1 || threads>8)throw new IllegalArgumentException("encoder_threads muss zwischen 1 und 8 liegen.");
        this.weights=weights;this.threads=threads;
    }
    public String model(){return MODEL;}
    public float[] encode(String query) throws Exception {return encode(query,()->false);}
    @Override public float[] encode(String query,BooleanSupplier cancelled) throws Exception {
        return encodePrompt(QUERY_PREFIX+query,cancelled);
    }
    float[] encodeDocument(String title,String text,BooleanSupplier cancelled) throws Exception {
        return encodePrompt("title: "+title+" | text: "+text,cancelled);
    }
    private float[] encodePrompt(String prompt,BooleanSupplier cancelled) throws Exception {
        if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");
        if(!weights.isFile())throw new IOException("EmbeddingGemma-2-GGUF fehlt: "+weights.getName());
        float[] output=Native.encode(weights.getPath().getBytes(StandardCharsets.UTF_8),
            prompt.getBytes(StandardCharsets.UTF_8),threads,cancelled);
        if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");
        if(output==null || output.length!=768)throw new IOException("EmbeddingGemma 2 muss 768 Dimensionen liefern.");
        return reduce(output);
    }
    static float[] reduce(float[] full) throws IOException {
        if(full==null || full.length!=768)throw new IOException("Ungültige EmbeddingGemma-2-Dimension.");
        double norm=0;
        for(int i=0;i<full.length;i++) {
            if(!Float.isFinite(full[i]))throw new IOException("Ungültiger EmbeddingGemma-2-Vektor.");
            if(i<KnowledgeSearch.DIM)norm+=(double)full[i]*full[i];
        }
        if(norm<=0)throw new IOException("Leerer EmbeddingGemma-2-Vektor.");
        norm=Math.sqrt(norm);
        float[] reduced=new float[KnowledgeSearch.DIM];
        for(int i=0;i<reduced.length;i++)reduced[i]=(float)(full[i]/norm);
        return reduced;
    }
    // Kept lazy: FTS and hash fixtures never need to load the JNI library.
    static final class Native {
        static {System.loadLibrary("bonsai-jni");}
        static native float[] encode(byte[] path,byte[] prompt,int threads,BooleanSupplier cancelled);
    }
}
