package de.bonsai.offline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.function.Consumer;

/** Streams final text; protocol prefixes and tool rounds stay internal. */
final class ReplyStream implements NativeEngine.Stream {
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private final boolean grammar;
    private final Consumer<String> visible;
    ReplyStream(boolean grammar,Consumer<String> visible) { this.grammar=grammar;this.visible=visible; }
    String text() {
        byte[] bytes=output.toByteArray();CharBuffer decoded=CharBuffer.allocate(bytes.length);
        if(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes),decoded,false).isError())
            throw new IllegalArgumentException("Ungültiges UTF-8 im Antwortstream.");
        decoded.flip();return ReasoningText.strip(decoded.toString());
    }
    @Override public void onBytes(byte[] chunk) {
        output.write(chunk,0,chunk.length);
        String text=text();int start=0;
        while(start<text.length() && Character.isWhitespace(text.charAt(start)))start++;
        String prefix=text.substring(start);
        if(prefix.isEmpty() || "<tool>".startsWith(prefix) || "<final>".startsWith(prefix) || prefix.startsWith("<tool>"))return;
        if(grammar) {
            if(!prefix.startsWith("<final>"))return;
            String content=prefix.substring(7),close="</final>";
            int end=content.indexOf(close);
            if(end>=0)content=content.substring(0,end);
            else for(int n=Math.min(close.length()-1,content.length());n>0;n--)
                if(content.endsWith(close.substring(0,n))) { content=content.substring(0,content.length()-n);break; }
            visible.accept(content);
        } else visible.accept(text);
    }
}
