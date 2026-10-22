package de.bonsai.offline;

import android.app.Application;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ToolLoopTest {
    private static ToolLoop.Result result(String text) { return new ToolLoop.Result(text,new JSONObject()); }
    private static String decode(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static String call(String name,String args) { return "<tool>{\"name\":\""+name+"\",\"args\":"+args+"}</tool>"; }
    private ChatStore store() { return new ChatStore(RuntimeEnvironment.getApplication()); }
    @Test public void grammarModeUsesFinalOnlyGrammarOnThirdRoundAndUnwrapsAnswer() throws Exception {
        AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=store()) {
            ToolLoop.Result answer=ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                assertNotNull(grammar);
                assertTrue(decode(texts[0]).contains("GBNF ist aktiv"));
                if(rounds.incrementAndGet()<3) {
                    assertTrue(decode(grammar).startsWith("root ::= tool | final"));
                    return result(call("calc","{\"expr\":\"2+2\"}"));
                }
                assertTrue(decode(grammar).startsWith("root ::= final\n"));
                return result("<final>4 🪴\n2 < 4</final>");
            },()->false,true);
            assertEquals("4 🪴\n2 < 4",answer.text);
        }
    }
    @Test public void grammarModeRejectsIncompleteOrUnwrappedAnswers() throws Exception {
        try(ChatStore store=store()) {
            for(String text:new String[]{"Hallo", "<final>Hallo", "<final></final>", "<final>Hallo</final>leak"}) {
                try { ToolLoop.run(Collections.emptyList(),store,(r,t,g)->result(text),()->false,true); fail(text); }
                catch(IllegalStateException expected) { }
            }
            ToolLoop.Result cut=result("<final>Hallo</final>");cut.stats.put("limit_reached",true);
            try { ToolLoop.run(Collections.emptyList(),store,(r,t,g)->cut,()->false,true); fail(); }
            catch(IllegalStateException expected) { }
        }
    }
    @Test public void calculatorResultFeedsNextGenerationButOnlyFinalTextIsReturned() throws Exception {
        AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=store()) {
            ToolLoop.Result finalResult=ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                if(rounds.getAndIncrement()==0) {
                    assertTrue(decode(texts[0]).contains("<tool>"));
                    assertNull(grammar);
                    return result(call("calc","{\"expr\":\"2*(3+4)\"}"));
                }
                assertEquals("assistant",decode(roles[1]));
                assertEquals("user",decode(roles[2]));
                assertTrue(decode(texts[2]).contains("\"result\":14"));
                return result("Das Ergebnis ist 14.");
            },()->false);
            assertEquals("Das Ergebnis ist 14.",finalResult.text);
            assertEquals(2,rounds.get());
            assertTrue(store.chats().isEmpty());
        }
    }
    @Test public void memoryCanBeWrittenAndReadDuringSeparateTurns() throws Exception {
        try(ChatStore store=store()) {
            for(String tool:new String[]{"merken","abrufen"}) {
                AtomicInteger rounds=new AtomicInteger();
                ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                    if(rounds.getAndIncrement()==0) return result(call(tool,tool.equals("merken")?"{\"key\":\"name\",\"value\":\"Alex\"}":"{\"key\":\"name\"}"));
                    String reply=decode(texts[texts.length-1]);
                    assertTrue(reply.contains("\"ok\":true"));
                    if(tool.equals("abrufen")) assertTrue(reply.contains("Alex"));
                    return result("Fertig");
                },()->false);
            }
        }
    }
    @Test public void errorsAreReturnedToModelForCorrection() throws Exception {
        for(String request:new String[]{call("calc","{\"expr\":\"1/0\"}"),call("unknown","{}"),call("merken","{\"key\":\"a\",\"value\":7}"),"<tool>{broken}</tool>", "<tool>{'name':'merken','args':{'key':'a','value':'bad'}}</tool>", "<tool>{\"name\":\"calc\",\"name\":\"merken\",\"args\":{\"key\":\"a\",\"value\":\"bad\"}}</tool>"}) {
            AtomicInteger rounds=new AtomicInteger();
            try(ChatStore store=store()) {
                ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                    if(rounds.getAndIncrement()==0) return result(request);
                    assertTrue(decode(texts[texts.length-1]).contains("\"ok\":false"));
                    return result("Konnte nicht ausgeführt werden.");
                },()->false);
                assertNull(store.recall("a"));
            }
        }
    }
    @Test public void twoToolsCanFinishWithinThreeGenerations() throws Exception {
        AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=store()) {
            ToolLoop.Result finalResult=ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                switch(rounds.incrementAndGet()) {
                    case 1: return result(call("calc","{\"expr\":\"6*7\"}"));
                    case 2:
                        assertTrue(decode(texts[texts.length-1]).contains("42"));
                        return result(call("merken","{\"key\":\"answer\",\"value\":\"42\"}"));
                    default: return result("Das Ergebnis 42 ist gespeichert.");
                }
            },()->false);
            assertEquals(3,rounds.get());
            assertEquals("42",store.recall("answer"));
            assertEquals("Das Ergebnis 42 ist gespeichert.",finalResult.text);
        }
    }
    @Test public void thirdGenerationCannotExecuteAnotherTool() throws Exception {
        AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=store()) {
            try {
                ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                    int round=rounds.incrementAndGet();
                    if(round==3) {
                        assertTrue(decode(texts[texts.length-1]).contains("Antworte jetzt abschließend"));
                        return result(call("merken","{\"key\":\"blocked\",\"value\":\"no\"}"));
                    }
                    return result(call("calc","{\"expr\":\"2+2\"}"));
                },()->false);
                fail();
            } catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Tool-Limit")); }
            assertEquals(3,rounds.get());
            assertNull(store.recall("blocked"));
        }
    }
    @Test public void mixedTruncatedAndCancelledCallsCannotWriteMemory() throws Exception {
        String request=call("merken","{\"key\":\"blocked\",\"value\":\"no\"}");
        try(ChatStore store=store()) {
            for(String text:new String[]{"Vorwort "+request, request+request, "<tool>{", request.replace("<tool>","<TOOL>")}) {
                try { ToolLoop.run(Collections.emptyList(),store,(r,t,g)->result(text),()->false); fail(); }
                catch(IllegalStateException expected) { }
            }
            ToolLoop.Result truncated=result(request);
            truncated.stats.put("limit_reached",true);
            try { ToolLoop.run(Collections.emptyList(),store,(r,t,g)->truncated,()->false); fail(); }
            catch(IllegalStateException expected) { }
            AtomicInteger cancellationChecks=new AtomicInteger();
            try { ToolLoop.run(Collections.emptyList(),store,(r,t,g)->result(request),()->cancellationChecks.incrementAndGet()>1); fail(); }
            catch(CancellationException expected) { }
            assertNull(store.recall("blocked"));
        }
    }
}
