package de.bonsai.offline;

import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONObject;
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

/** Control-flow tests only: synthetic native probes are NOT hardware measurements. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, shadows=KernelTemperatureTest.NativeFixture.class)
public class KernelTemperatureTest {
    private BonsaiApp app;

    @Before public void prepare() throws Exception {
        app=(BonsaiApp)RuntimeEnvironment.getApplication();
        app.prefs.edit().clear().commit();
        app.cpuReference=new JSONObject(NativeFixture.PROBE).put("identity",BackendGuard.identity(app));
        app.loaded=true;app.busy=true;app.optimizing=true;app.cancelled=false;
        NativeFixture.probes=0;NativeFixture.heatOnProbe=0;
        setThermal(PowerManager.THERMAL_STATUS_NONE);
    }
    private static void setThermal(int status) {
        PowerManager manager=(PowerManager)RuntimeEnvironment.getApplication().getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(manager).setCurrentThermalStatus(status);
    }
    private void runAndAssertCompleted(int before,int after) throws Exception {
        setThermal(before);
        KernelOptimizer.run(app,1,false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        JSONObject report=app.readReport("optimizer.json");
        assertNotNull("Optimizer rejected thermal status "+before+": "+app.error,report);
        assertTrue(report.getBoolean("completed"));
        assertEquals(before,report.getInt("thermal_before"));
        assertEquals(after,report.getInt("thermal_after"));
        assertTrue(app.optimized());assertTrue(app.loaded);
        assertFalse(app.busy);assertFalse(app.optimizing);
        assertFalse(app.prefs.getBoolean("optimizerRunning",true));
        assertEquals(KernelOptimizer.profiles(KernelOptimizer.current(app),false).size(),report.getJSONArray("trials").length());
        assertEquals("",app.error);
    }
    @Test public void everyAndroidThermalStatusAllowsOptimization() throws Exception {
        for(int status=PowerManager.THERMAL_STATUS_NONE;status<=PowerManager.THERMAL_STATUS_SHUTDOWN;status++) {
            prepare();
            new java.io.File(app.getFilesDir(),"optimizer.json").delete();
            runAndAssertCompleted(status,status);
        }
    }
    @Test public void heatingDuringCandidateMeasurementsDoesNotAbort() throws Exception {
        NativeFixture.heatOnProbe=1;
        runAndAssertCompleted(PowerManager.THERMAL_STATUS_NONE,PowerManager.THERMAL_STATUS_SEVERE);
    }
    @Test public void heatingDuringWinnerConfirmationDoesNotAbort() throws Exception {
        NativeFixture.heatOnProbe=KernelOptimizer.profiles(KernelOptimizer.current(app),false).size()*3+1;
        runAndAssertCompleted(PowerManager.THERMAL_STATUS_NONE,PowerManager.THERMAL_STATUS_SEVERE);
    }
    @Test public void explicitCancellationStillPreservesTheProfileAtHighThermalStatus() throws Exception {
        setThermal(PowerManager.THERMAL_STATUS_SEVERE);
        String original=app.profileIdentity();
        app.prefs.edit().putString("optimizedFor",original).commit();
        app.cancelled=true;
        KernelOptimizer.run(app,1,false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(app.readReport("optimizer-attempt.json").getBoolean("completed"));
        assertEquals(original,app.profileIdentity());assertTrue(app.optimized());
        assertTrue(app.loaded);assertFalse(app.busy);assertFalse(app.optimizing);
        assertFalse(app.prefs.getBoolean("optimizerRunning",true));
    }

    @Implements(value=NativeEngine.class,isInAndroidSdk=false)
    public static class NativeFixture {
        static final String PROBE="{\"signature\":[1,2,3,4],\"top_tokens\":[1,2,3,4],\"fingerprint\":\"test-fixture-only\",\"score_ms\":100,\"tokens_per_second\":10}";
        static int probes,heatOnProbe;
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static void clearCache(long handle) {}
        @Implementation protected static void commitTurn(long handle,byte[][] roles,byte[][] contents) {}
        @Implementation protected static long create() { return 1; }
        @Implementation protected static void resetStop(long handle) { }
        @Implementation protected static void referenceCpu(boolean enabled) { }
        @Implementation protected static void configure(long handle,int threads,int batchThreads,int microBatch) { }
        @Implementation protected static byte[] load(long handle,byte[] path,int context,int threads,boolean gpu) {
            return BonsaiApp.utf8("{\"backend\":\"CPU\"}");
        }
        @Implementation protected static byte[] probe(long handle,int steps) {
            if(++probes==heatOnProbe)setThermal(PowerManager.THERMAL_STATUS_SEVERE);
            return BonsaiApp.utf8(PROBE);
        }
        @Implementation protected static byte[] generate(long handle,byte[][] roles,byte[][] contents,int maxTokens,int seed,NativeEngine.Stream callback,byte[] grammar,int reasoningBudget) {
            callback.onBytes(BonsaiApp.utf8("Synthetic test reply"));
            return BonsaiApp.utf8("{\"tokens_per_second\":1,\"output_tokens\":1,\"first_token_ms\":1,\"stopped\":false,\"limit_reached\":false}");
        }
    }
}
