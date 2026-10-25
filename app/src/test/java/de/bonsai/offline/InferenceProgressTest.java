package de.bonsai.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class InferenceProgressTest {
    @Test public void thoughtsStayOnOneBoundedLineWithoutContributingToAnswer() {
        String[] line={""};double[] fraction={0};
        InferenceProgress progress=new InferenceProgress((text,value)->{line[0]=text;fraction[0]=value;});
        progress.onThinking(BonsaiApp.utf8("Zuerst\nprüfe ich\t die Zahlen 🪴."));
        assertEquals("Zuerst prüfe ich die Zahlen 🪴.",line[0]);assertEquals(-1,fraction[0],0);
        progress.onThinking(BonsaiApp.utf8("a".repeat(240)+"🪴"));assertTrue(line[0].length()<=240);assertTrue(line[0].endsWith("🪴"));
        progress.onBytes(BonsaiApp.utf8("Antwort"));assertFalse(line[0].contains("Antwort"));
    }
    @Test public void prefillUsesRealFractionAndMeasuredDecodeRate() {
        String[] line={""};double[] fraction={0};
        InferenceProgress progress=new InferenceProgress((text,value)->{line[0]=text;fraction[0]=value;});
        progress.onPrefill(256,1024,31.25);assertEquals("Prompt-Cache 25 % · 31,3 Tok/s",line[0]);assertEquals(.25,fraction[0],0);
        progress.onPrefill(0,1024,0);assertEquals("Prompt-Cache 0 % · … Tok/s",line[0]);
        progress.onPrefill(1024,1024,Double.NaN);assertEquals(1,fraction[0],0);assertFalse(line[0].contains("NaN"));
    }
    @Test public void previewPreservesFormattingAndSplitUtf8WhileKeepingASeparateStatusTail() {
        String[] line={""},preview={""};
        InferenceProgress progress=new InferenceProgress((text,value)->line[0]=text,text->preview[0]=text);
        byte[] bytes=BonsaiApp.utf8("Prüfen 🪴\nDann rechnen.");
        for(byte value:bytes)progress.onThinking(new byte[]{value});
        assertEquals("Prüfen 🪴\nDann rechnen.",preview[0]);
        assertEquals("Prüfen 🪴 Dann rechnen.",line[0]);
        progress.onPrefill(1,2,10);assertEquals("Prüfen 🪴\nDann rechnen.",preview[0]);
        progress.onBytes(BonsaiApp.utf8("Antwort"));assertFalse(preview[0].contains("Antwort"));
        progress.onThinking(BonsaiApp.utf8("🪴".repeat(5000)));
        assertTrue(preview[0].length()<=8000);assertFalse(Character.isLowSurrogate(preview[0].charAt(0)));
    }
}
