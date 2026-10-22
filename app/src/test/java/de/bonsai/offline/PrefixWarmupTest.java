package de.bonsai.offline;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=PrefixWarmupTest.Engine.class)
public class PrefixWarmupTest {
    @Implements(value=NativeEngine.class,isInAndroidSdk=false)
    public static class Engine {
        static String system;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static long create() { return 1; }
        @Implementation protected static byte[] warmPrefix(long handle,byte[] text) {
            system=new String(text,StandardCharsets.UTF_8);return BonsaiApp.utf8("{}");
        }
    }
    @Test public void warmupUsesExactToolLoopSystemForEachGrammarModeAndCanBeDisabled() {
        BonsaiApp app=(BonsaiApp)RuntimeEnvironment.getApplication();
        for(boolean gbnf:new boolean[]{false,true}) {
            app.prefs.edit().putBoolean("gbnfTools",gbnf).putBoolean("prefixWarmup",true).commit();
            app.warmPrefixOnWorker();assertEquals(ToolLoop.systemPrompt(gbnf),Engine.system);
        }
        Engine.system=null;app.prefs.edit().putBoolean("prefixWarmup",false).commit();
        app.warmPrefixOnWorker();assertNull(Engine.system);
    }
}
