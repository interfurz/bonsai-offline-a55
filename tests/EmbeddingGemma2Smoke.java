package de.bonsai.offline;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production Java/JNI encoder with real local weights, on host or Android. */
public final class EmbeddingGemma2Smoke {
    private static float[] document(File model,String text) throws Exception {
        return EmbeddingGemma2Encoder.reduce(EmbeddingGemma2Encoder.Native.encode(
            model.getPath().getBytes(StandardCharsets.UTF_8),text.getBytes(StandardCharsets.UTF_8),2,()->false));
    }
    private static double dot(float[] a,float[] b) {
        double sum=0;for(int i=0;i<a.length;i++)sum+=(double)a[i]*b[i];return sum;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Pass a local EmbeddingGemma 2 GGUF path");
        File model=new File(args[0]);
        EmbeddingGemma2Encoder encoder=new EmbeddingGemma2Encoder(model,2);
        String query="Wie erzeugen Pflanzen mit Sonnenlicht Sauerstoff? 🌳";
        AtomicInteger polls=new AtomicInteger();
        long start=System.nanoTime();
        float[] vector=encoder.encode(query,()->{polls.incrementAndGet();return false;});
        System.out.println("Real JNI query: dimensions="+vector.length+", norm="+dot(vector,vector)+", host_ms="+(System.nanoTime()-start)/1e6+", cancellation_polls="+polls.get());
        if(vector.length!=256 || Math.abs(dot(vector,vector)-1)>1e-5)throw new AssertionError("Invalid output");
        float[] related=document(model,"title: Photosynthese | text: Pflanzen wandeln Lichtenergie in chemische Energie um. Dabei erzeugen sie aus Wasser und Kohlendioxid Zucker und Sauerstoff.");
        float[] unrelated=document(model,"title: Berlin | text: Berlin ist die Hauptstadt Deutschlands. Die Stadt liegt an der Spree und hat viele Museen.");
        double positive=dot(vector,related),negative=dot(vector,unrelated);
        System.out.println("German retrieval example: photosynthesis="+positive+", Berlin="+negative);
        if(positive<=negative)throw new AssertionError("Related document must rank higher");
        for(int stopAt:new int[]{1,Math.max(2,polls.get()-20)}) {
            AtomicInteger ticks=new AtomicInteger();
            try {encoder.encode(query,()->ticks.incrementAndGet()>=stopAt);throw new AssertionError("Cancellation ignored");}
            catch(CancellationException expected){System.out.println("Cancellation passed at poll "+ticks.get());}
        }
        float[] repeated=encoder.encode(query);
        if(dot(vector,repeated)<.99999)throw new AssertionError("Query changed after cancellation");
        System.out.println("EmbeddingGemma 2 production encoder smoke passed (host CPU; no A55 claim).");
    }
}
