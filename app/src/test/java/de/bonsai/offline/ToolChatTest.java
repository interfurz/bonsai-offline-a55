package de.bonsai.offline;

import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=ToolChatTest.Engine.class)
public class ToolChatTest {
    @Implements(value=NativeEngine.class,isInAndroidSdk=false)
    public static class Engine {
        static CountDownLatch secondRound,finish;
        static int calls;
        static boolean malformed;
        static byte[] lastGrammar;
        static int clears,lastBudget,firstReasoning;
        static byte[][] committedRoles,committedTexts;
        static boolean pauseMid;
        static CountDownLatch partial,resume;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void clearCache(long handle) { clears++; }
        @Implementation protected static void commitTurn(long handle,byte[][] roles,byte[][] contents) { committedRoles=roles;committedTexts=contents; }
        @Implementation protected static long create() { return 1; }
        @Implementation protected static void resetStop(long handle) {}
        @Implementation protected static void stop(long handle) { finish.countDown(); }
        @Implementation protected static byte[] generate(long handle,byte[][] roles,byte[][] texts,int maxTokens,int seed,NativeEngine.Stream stream,byte[] grammar,int reasoningBudget) throws Exception {
            lastGrammar=grammar;lastBudget=reasoningBudget;
            if(++calls==1) {
                stream.onBytes((malformed?"Vorwort ":"").getBytes(StandardCharsets.UTF_8));
                stream.onBytes("<tool>{\"name\":\"calc\",\"args\":{\"expr\":\"6*7\"}}</tool>".getBytes(StandardCharsets.UTF_8));
            } else {
                secondRound.countDown();
                if(!finish.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                if(pauseMid) {
                    stream.onBytes("Das Ergebnis ".getBytes(StandardCharsets.UTF_8));partial.countDown();
                    if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test timed out");
                }
                byte[] answer=(pauseMid?"ist 42.":grammar==null?"Das Ergebnis ist 42. 🪴":"<final>Das Ergebnis ist 42. 🪴</final>").getBytes(StandardCharsets.UTF_8);
                for(byte b:answer) stream.onBytes(new byte[]{b});
            }
            return ("{\"tokens_per_second\":2,\"output_tokens\":8,\"first_token_ms\":10,\"stopped\":false,\"limit_reached\":false,\"reasoning_tokens\":"+(calls==1?firstReasoning:0)+"}").getBytes(StandardCharsets.UTF_8);
        }
    }
    private BonsaiApp app;
    @Before public void prepare() {
        Engine.calls=0; Engine.malformed=false;Engine.clears=0;Engine.firstReasoning=0;Engine.pauseMid=false;
        Engine.partial=new CountDownLatch(1);Engine.resume=new CountDownLatch(1);
        Engine.secondRound=new CountDownLatch(1); Engine.finish=new CountDownLatch(1);
        app=(BonsaiApp)RuntimeEnvironment.getApplication();app.loaded=true;
    }
    private void complete() throws Exception {
        app.worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(app.busy);
    }
    @Test public void intermediateToolRoundNeverAppearsInUiOrStoredHistory() throws Exception {
        assertTrue(app.send("Berechne 6*7"));
        try {
            assertTrue(Engine.secondRound.await(5,TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(app.busy);
            assertEquals("",app.messages.get(1).text);
            assertEquals(1,app.store.messages(app.currentChat).size());
        } finally { Engine.finish.countDown(); }
        complete();
        assertEquals("",app.error);
        assertEquals(2,app.messages.size());
        assertEquals("Das Ergebnis ist 42. 🪴",app.messages.get(1).text);
        assertEquals(app.messages.get(1).text,app.store.messages(app.currentChat).get(1).text);
    }
    @Test public void enabledGrammarReachesNativeAndFinalEnvelopeIsNeverStored() throws Exception {
        app.prefs.edit().putBoolean("gbnfTools",true).apply();
        Engine.finish.countDown();
        app.send("Berechne 6*7");complete();
        assertNotNull(Engine.lastGrammar);
        assertTrue(new String(Engine.lastGrammar,StandardCharsets.UTF_8).startsWith("root ::= tool | final"));
        assertEquals("",app.error);
        assertEquals("Das Ergebnis ist 42. 🪴",app.store.messages(app.currentChat).get(1).text);
        app.prefs.edit().putBoolean("gbnfTools",false).apply();
        app.send("Hallo");complete();
        assertNull(Engine.lastGrammar);
        assertEquals(4,app.store.messages(app.currentChat).size());
    }
    @Test public void finalAnswerStreamsBeforeCompletionAndCommitOmitsToolRounds() throws Exception {
        Engine.pauseMid=true;Engine.firstReasoning=500;Engine.finish.countDown();
        app.send("Berechne 6*7");
        try {
            assertTrue(Engine.partial.await(5,TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(app.busy);assertEquals("Das Ergebnis ",app.messages.get(1).text);
        } finally { Engine.resume.countDown(); }
        complete();
        assertEquals(100,Engine.lastBudget);
        assertEquals(3,Engine.committedRoles.length);
        assertEquals("assistant",new String(Engine.committedRoles[2],StandardCharsets.UTF_8));
        assertEquals("Das Ergebnis ist 42.",new String(Engine.committedTexts[2],StandardCharsets.UTF_8));
    }
    @Test public void newAndSelectedChatsClearNativeCacheOnWorker() throws Exception {
        app.newChat();app.worker.submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(1,Engine.clears);
        app.selectChat(app.store.createChat("Other"));app.worker.submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(2,Engine.clears);
    }
    @Test public void cancellationDiscardsBufferedOutputAndUnansweredUserTurn() throws Exception {
        app.send("Berechne 6*7");
        assertTrue(Engine.secondRound.await(5,TimeUnit.SECONDS));
        app.cancel();complete();
        assertEquals(2,Engine.calls);
        assertTrue(app.messages.isEmpty());
        assertTrue(app.store.messages(app.currentChat).isEmpty());
    }
    @Test public void malformedRequestIsReportedWithoutPersistingToolMarkup() throws Exception {
        Engine.malformed=true;
        app.send("Berechne 6*7");complete();
        assertTrue(app.error.contains("Tool-Format"));
        assertTrue(app.messages.isEmpty());
        assertTrue(app.store.messages(app.currentChat).isEmpty());
        assertEquals(1,Engine.calls);
    }
}
