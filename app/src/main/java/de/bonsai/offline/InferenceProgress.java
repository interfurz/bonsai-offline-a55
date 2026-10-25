package de.bonsai.offline;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;

/** Bounded progress and reasoning preview, separate from answers, TTFT and history. */
final class InferenceProgress implements NativeEngine.Stream {
    private static final int MAX_TAIL=240;
    private final BiConsumer<String,Double> update;
    private final Consumer<String> preview;
    private final StringBuilder reasoning=new StringBuilder();
    private byte[] pending=new byte[0];
    private String tail="";
    InferenceProgress(BiConsumer<String,Double> update) { this(update,null); }
    InferenceProgress(BiConsumer<String,Double> update,Consumer<String> preview) { this.update=update;this.preview=preview; }
    @Override public void onBytes(byte[] bytes) {}
    @Override public void onThinking(byte[] bytes) {
        byte[] input=Arrays.copyOf(pending,pending.length+bytes.length);
        System.arraycopy(bytes,0,input,pending.length,bytes.length);
        ByteBuffer encoded=ByteBuffer.wrap(input);CharBuffer decoded=CharBuffer.allocate(input.length);
        if(StandardCharsets.UTF_8.newDecoder().decode(encoded,decoded,false).isError())
            throw new IllegalArgumentException("Ungültiges UTF-8 im Reasoningstream.");
        pending=new byte[encoded.remaining()];encoded.get(pending);decoded.flip();
        String chunk=decoded.toString();
        reasoning.append(chunk);
        if(reasoning.length()>8000) {
            int start=reasoning.length()-8000;
            if(Character.isLowSurrogate(reasoning.charAt(start)))start++;
            reasoning.delete(0,start);
        }
        if(preview!=null&&!chunk.isEmpty())preview.accept(reasoning.toString());
        tail=(tail+chunk).replaceAll("\\s+"," ");
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
