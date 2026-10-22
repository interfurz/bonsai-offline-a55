package de.bonsai.offline;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Ephemeral one-line activity; never contributes to the answer, TTFT or stored history. */
final class InferenceProgress implements NativeEngine.Stream {
    private static final int MAX_TAIL=240;
    private final BiConsumer<String,Double> update;
    private String tail="";
    InferenceProgress(BiConsumer<String,Double> update) { this.update=update; }
    @Override public void onBytes(byte[] bytes) {}
    @Override public void onThinking(byte[] bytes) {
        tail=(tail+new String(bytes,StandardCharsets.UTF_8)).replaceAll("\\s+"," ");
        if(tail.length()>MAX_TAIL) {
            int start=tail.length()-MAX_TAIL;if(Character.isLowSurrogate(tail.charAt(start)))start++;
            tail=tail.substring(start);
        }
        if(!tail.trim().isEmpty())update.accept(tail.trim(),-1.0);
    }
    @Override public void onPrefill(int done,int total,double speed) {
        if(total<=0)return;
        double fraction=Math.min(1,Math.max(0,(double)done/total));
        String rate=Double.isFinite(speed) && speed>0?String.format(Locale.GERMAN,"%.1f Tok/s",speed):"… Tok/s";
        update.accept(String.format(Locale.GERMAN,"Prompt-Cache %.0f %% · %s",fraction*100,rate),fraction);
    }
}
