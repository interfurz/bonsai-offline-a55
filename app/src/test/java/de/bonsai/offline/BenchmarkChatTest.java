package de.bonsai.offline;

import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Synthetic control flow only; native token identity is covered by CacheSmoke. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=BenchmarkChatTest.Engine.class)
public class BenchmarkChatTest {
    @Implements(value=NativeEngine.class,isInAndroidSdk=false)
    public static class Engine {
        static int clears,calls,commits,warmups;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static long create() {return 1;}
        @Implementation protected static void resetStop(long h) {}
        @Implementation protected static void clearCache(long h) {clears++;}
        @Implementation protected static byte[] warmPrefix(long h,byte[] system) {warmups++;return BonsaiApp.utf8("{warmed_tokens:100}");}
        @Implementation protected static byte[] commitTurn(long h,byte[][] roles,byte[][] texts) {
            commits++;
            assertEquals(commits*2+1,roles.length);
            for(byte[] text:texts) assertFalse(new String(text,StandardCharsets.UTF_8).contains("hidden"));
            return BonsaiApp.utf8("{cached_tokens:150,kv_positions:150}");
        }
        @Implementation protected static byte[] generate(long h,byte[][] roles,byte[][] texts,int limit,int seed,NativeEngine.Stream stream,byte[] grammar,int budget) {
            calls++;assertEquals(42,seed);assertEquals(128,limit);assertEquals(600,budget);
            assertEquals(calls*2,roles.length);
            stream.onBytes(BonsaiApp.utf8("<think>hidden</think>Antwort."));
            return BonsaiApp.utf8("{tokens_per_second:10,output_tokens:10,reasoning_tokens:2,generation_ms:1000,prefill_ms:3,reused_prompt_tokens:"+(100*calls)+",decoded_prompt_tokens:10,cached_tokens:150,kv_positions:150,stopped:false,limit_reached:false}");
        }
    }
    @Test public void fiveTurnsShareHistoryClearOnceAndPersistComparableJsonWithoutChangingUserChat() throws Exception {
        BonsaiApp app=(BonsaiApp)RuntimeEnvironment.getApplication();
        Engine.clears=Engine.calls=Engine.commits=Engine.warmups=0;
        app.loaded=true;app.prefs.edit().putBoolean("prefixWarmup",true).putBoolean("gbnfTools",false).commit();
        Files.write(app.modelFile().toPath(),BonsaiApp.utf8("synthetic model fixture"));
        app.currentChat=app.store.createChat("Existing");
        app.store.add(app.currentChat,"user","Existing question","");long chat=app.currentChat;
        app.runChatBenchmark();app.worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",app.error);assertFalse(app.busy);
        assertEquals(1,Engine.clears);assertEquals(5,Engine.calls);assertEquals(5,Engine.commits);assertEquals(1,Engine.warmups);
        assertEquals(chat,app.currentChat);assertEquals(1,app.store.messages(chat).size());
        JSONObject report=new JSONObject(new String(Files.readAllBytes(new File(app.getFilesDir(),"benchmark.json").toPath()),StandardCharsets.UTF_8));
        assertTrue(report.getBoolean("completed"));assertEquals("bonsai-chat-5-v1",report.getString("benchmark"));
        JSONArray turns=report.getJSONArray("turns");assertEquals(5,turns.length());
        for(int i=0;i<5;i++) {
            JSONObject turn=turns.getJSONObject(i);assertEquals(i+1,turn.getInt("turn"));
            assertTrue(turn.has("ttft_ms"));assertTrue(turn.has("thermal_status_before"));assertTrue(turn.has("battery_celsius_after"));
            assertEquals(2,turn.getInt("reasoning_tokens"));assertEquals(10,turn.getInt("decoded_prompt_tokens"));
            assertEquals("Antwort.",turn.getString("answer"));assertEquals(1,turn.getJSONArray("rounds").length());
        }
    }
    @Test public void resultWindowUsesExistingJsonExportRequest() throws Exception {
        BonsaiApp app=(BonsaiApp)RuntimeEnvironment.getApplication();
        app.benchmark=new JSONObject("{benchmark:'bonsai-chat-5-v1',completed:true,turns:[]}");
        org.robolectric.android.controller.ActivityController<MainActivity> controller=org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            BenchmarkControls.results(app);
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowActivity.IntentForResult request=Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
            assertEquals(11,request.requestCode);
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT,request.intent.getAction());
            assertEquals("application/json",request.intent.getType());
        } finally {controller.pause().stop().destroy();}
    }

}
