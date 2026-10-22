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
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static long create() { return 1; }
        @Implementation protected static void resetStop(long handle) {}
        @Implementation protected static void stop(long handle) { finish.countDown(); }
        @Implementation protected static byte[] generate(long handle,byte[][] roles,byte[][] texts,int maxTokens,int seed,NativeEngine.Stream stream) throws Exception {
            if(++calls==1) {
                stream.onBytes((malformed?"Vorwort ":"").getBytes(StandardCharsets.UTF_8));
                stream.onBytes("<tool>{\"name\":\"calc\",\"args\":{\"expr\":\"6*7\"}}</tool>".getBytes(StandardCharsets.UTF_8));
            } else {
                secondRound.countDown();
                if(!finish.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                byte[] answer="Das Ergebnis ist 42. 🪴".getBytes(StandardCharsets.UTF_8);
                for(byte b:answer) stream.onBytes(new byte[]{b});
            }
            return "{\"tokens_per_second\":2,\"output_tokens\":8,\"first_token_ms\":10,\"stopped\":false,\"limit_reached\":false}".getBytes(StandardCharsets.UTF_8);
        }
    }
    private BonsaiApp app;
    @Before public void prepare() {
        Engine.calls=0; Engine.malformed=false;
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
