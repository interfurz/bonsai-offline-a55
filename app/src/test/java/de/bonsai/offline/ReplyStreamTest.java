package de.bonsai.offline;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReplyStreamTest {
    @Test public void toolPrefixesNeverStreamAndFinalEnvelopesStayHidden() {
        List<String> visible=new ArrayList<>();
        ReplyStream tool=new ReplyStream(false,visible::add);
        for(char c:"<tool>{\"name\":\"calc\",\"args\":{\"expr\":\"2+2\"}}</tool>".toCharArray())tool.onBytes(new byte[]{(byte)c});
        assertTrue(visible.isEmpty());
        ReplyStream finalText=new ReplyStream(true,visible::add);
        for(char c:"<final>Hallo Welt</final>".toCharArray())finalText.onBytes(new byte[]{(byte)c});
        assertTrue(visible.contains("Hallo"));assertEquals("Hallo Welt",visible.get(visible.size()-1));
        for(String value:visible)assertFalse(value.contains("<"));
    }
    @Test public void normalTextStreamsBeforeCompletionAndReasoningIsRemoved() {
        List<String> visible=new ArrayList<>();ReplyStream stream=new ReplyStream(false,visible::add);
        stream.onBytes("<think>secret</think>Hallo".getBytes(StandardCharsets.UTF_8));
        assertEquals("Hallo",visible.get(0));
        stream.onBytes(" Welt".getBytes(StandardCharsets.UTF_8));assertEquals("Hallo Welt",stream.text());
        assertEquals("Ja",ReasoningText.strip("<think>secret</think>Ja<think>unfinished"));
    }    @Test public void splitEmojiNeverProducesReplacementCharacters() {
        List<String> visible=new ArrayList<>();ReplyStream stream=new ReplyStream(false,visible::add);
        for(byte b:"Hallo 🪴".getBytes(StandardCharsets.UTF_8))stream.onBytes(new byte[]{b});
        assertEquals("Hallo 🪴",stream.text());
        for(String value:visible)assertFalse(value.contains("\ufffd"));
    }

}
