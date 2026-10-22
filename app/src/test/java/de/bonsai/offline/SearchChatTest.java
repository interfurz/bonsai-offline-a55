package de.bonsai.offline;

import android.os.Looper;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=SearchChatTest.Engine.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class SearchChatTest {
    @Implements(value=NativeEngine.class,isInAndroidSdk=false)
    public static class Engine {
        static int calls;
        @Implementation protected static void __staticInitializer__(){}
        @Implementation protected static long create(){return 1;}
        @Implementation protected static void resetStop(long handle){}
        @Implementation protected static void clearCache(long handle){}
        @Implementation protected static byte[] commitTurn(long handle,byte[][] roles,byte[][] texts){return "{}".getBytes(StandardCharsets.UTF_8);}
        @Implementation protected static byte[] generate(long handle,byte[][] roles,byte[][] texts,int tokens,int seed,NativeEngine.Stream stream,byte[] grammar,int budget) {
            String answer=++calls==1?"<tool>{\"name\":\"suche\",\"args\":{\"query\":\"Photosynthese\"}}</tool>":"Pflanzen nutzen Lichtenergie. [1]";
            stream.onBytes(answer.getBytes(StandardCharsets.UTF_8));
            return "{\"tokens_per_second\":2,\"output_tokens\":8,\"first_token_ms\":10,\"stopped\":false,\"limit_reached\":false}".getBytes(StandardCharsets.UTF_8);
        }
    }
    private static boolean hasText(android.view.View view,String needle) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(needle))return true;
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),needle))return true;
        }
        return false;
    }
    @Test public void sourcesRenderAndPersistWhileToolExchangeRemainsHidden() throws Exception {
        BonsaiApp app=(BonsaiApp)RuntimeEnvironment.getApplication();
        RagFixtures.install(app);app.loaded=true;Engine.calls=0;
        assertTrue(app.send("Was ist Photosynthese?"));
        app.worker.submit(()->{}).get(10,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",app.error);assertEquals(2,app.messages.size());
        String answer=app.messages.get(1).text;
        assertTrue(answer.contains("Quellen:"));assertTrue(answer.contains("fixture://bonsai/2"));
        assertFalse(answer.contains("<tool"));assertEquals(2,app.store.messages(app.currentChat).size());
        assertEquals(answer,app.store.messages(app.currentChat).get(1).text);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            android.view.View root=controller.get().getWindow().getDecorView();
            assertTrue(hasText(root,"fixture://bonsai/2"));assertFalse(hasText(root,"<tool>"));
        }
    }
}
