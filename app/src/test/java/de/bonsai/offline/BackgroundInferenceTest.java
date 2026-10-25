package de.bonsai.offline;

import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, shadows=BackgroundInferenceTest.Engine.class)
public class BackgroundInferenceTest {
    @Implements(value=NativeEngine.class, isInAndroidSdk=false)
    public static class Engine {
        static CountDownLatch started, finish;
        static volatile boolean stopped, fail;
        static volatile boolean referenceMode, divergent;
        static int loads,unloads,referenceProbes,candidateProbes;
        static boolean cancelReference;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void clearCache(long handle) {}
        @Implementation protected static byte[] commitTurn(long handle,byte[][] roles,byte[][] contents) {return BonsaiApp.utf8("{}");}
        @Implementation protected static long create() { return 1; }
        @Implementation protected static void resetStop(long handle) { stopped=false; }
        @Implementation protected static void stop(long handle) { stopped=true; finish.countDown(); }
        @Implementation protected static void referenceCpu(boolean enabled) { referenceMode=enabled; }
        @Implementation protected static void unload(long handle) { unloads++; }
        @Implementation protected static void configure(long handle, int threads, int batchThreads, int microBatch) {}
        @Implementation protected static byte[] load(long handle, byte[] path, int context, int threads, boolean gpu) {
            loads++;
            return ("{\"backend\":\""+(gpu?"Vulkan":"CPU")+"\"}").getBytes(StandardCharsets.UTF_8);
        }
        @Implementation protected static byte[] probe(long handle, int steps) {
            if(referenceMode)referenceProbes++;else candidateProbes++;
            if(cancelReference && referenceMode) {
                ((BonsaiApp)RuntimeEnvironment.getApplication()).cancelled=true;
                throw new IllegalStateException("Abgebrochen.");
            }
            return ((!referenceMode && divergent)?"{\"signature\":[100,200],\"top_tokens\":[9,9]}":"{\"signature\":[1,2],\"top_tokens\":[1,2]}").getBytes(StandardCharsets.UTF_8);
        }
        @Implementation protected static byte[] generate(long handle, byte[][] roles, byte[][] contents, int maxTokens, int seed, NativeEngine.Stream stream,byte[] grammar,int reasoningBudget) throws Exception {
            started.countDown();
            if(!finish.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test worker timed out");
            if(fail) throw new IllegalStateException("Test inference failure");
            stream.onBytes("Hallo aus dem Hintergrund".getBytes(StandardCharsets.UTF_8));
            return ("{\"tokens_per_second\":2,\"output_tokens\":4,\"first_token_ms\":10,\"prefill_ms\":9,\"stopped\":"+stopped+",\"limit_reached\":false}").getBytes(StandardCharsets.UTF_8);
        }
    }
    private BonsaiApp app;
    @Test public void cancelledReasoningCannotReappearFromLateCallbacks() throws Exception {
        assertTrue(app.begin("Antwort wird vorbereitet …"));
        var method=BonsaiApp.class.getDeclaredMethod("generationProgress");method.setAccessible(true);
        NativeEngine.Stream progress=(NativeEngine.Stream)method.invoke(app);
        progress.onThinking(BonsaiApp.utf8("Ich prüfe die Frage."));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(app.reasoningPreview.isEmpty());
        app.cancel();progress.onThinking(BonsaiApp.utf8("Veralteter Inhalt"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("",app.reasoningPreview);app.complete("");
        assertTrue(app.begin("Neue Antwort"));
        progress.onThinking(BonsaiApp.utf8("Inhalt aus dem vorigen Durchlauf"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("",app.reasoningPreview);app.complete("");
    }
    @Before public void prepare() {
        Engine.started=new CountDownLatch(1); Engine.finish=new CountDownLatch(1); Engine.stopped=false; Engine.fail=false; Engine.divergent=false; Engine.referenceMode=false;
        Engine.loads=0; Engine.unloads=0;Engine.referenceProbes=0;Engine.candidateProbes=0;Engine.cancelReference=false;
        app=(BonsaiApp)RuntimeEnvironment.getApplication();
        app.prefs.edit().putBoolean("notificationsAsked",true).apply();
        app.loaded=true;
    }
    private static PowerManager.WakeLock lock(InferenceService service) throws Exception {
        Field f=InferenceService.class.getDeclaredField("wakeLock"); f.setAccessible(true); return (PowerManager.WakeLock)f.get(service);
    }
    private void awaitCompletion() throws Exception {
        app.worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(app.busy);
    }
    @Test public void answerContinuesAfterActivityDestructionAndIsPersisted() throws Exception {
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();
        app.send("Hallo"); assertTrue(Engine.started.await(5,TimeUnit.SECONDS));
        ServiceController<InferenceService> service=Robolectric.buildService(InferenceService.class).create().startCommand(0,1);
        assertTrue(lock(service.get()).isHeld());
        activity.pause().stop().destroy();
        assertFalse(app.cancelled); assertTrue(app.busy); assertNull(app.listener);
        Engine.finish.countDown(); awaitCompletion();
        assertFalse(lock(service.get()).isHeld());
        assertEquals("Hallo aus dem Hintergrund",app.store.messages(app.currentChat).get(1).text);
        try(ActivityController<MainActivity> reopened=Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(2,((BonsaiApp)reopened.get().getApplication()).messages.size());
        }
        service.destroy();
    }
    @Test public void notificationCancelsAndKeepsServiceAliveUntilWorkerCompletes() throws Exception {
        app.send("Hallo"); assertTrue(Engine.started.await(5,TimeUnit.SECONDS));
        ServiceController<InferenceService> service=Robolectric.buildService(InferenceService.class,
            new Intent(app,InferenceService.class).setAction(InferenceService.CANCEL)).create().startCommand(0,1);
        assertTrue(app.cancelled); assertTrue(Engine.stopped);
        awaitCompletion();
        assertFalse(lock(service.get()).isHeld()); service.destroy();
    }
    @Test public void nativeFailureReleasesWakeLockAndRemovesUnansweredTurn() throws Exception {
        Engine.fail=true;
        app.send("Hallo"); assertTrue(Engine.started.await(5,TimeUnit.SECONDS));
        ServiceController<InferenceService> service=Robolectric.buildService(InferenceService.class).create().startCommand(0,1);
        Engine.finish.countDown(); awaitCompletion();
        assertFalse(lock(service.get()).isHeld());
        assertEquals("Test inference failure",app.error);
        assertTrue(app.store.messages(app.currentChat).isEmpty()); service.destroy();
    }
    @Test public void delayedStartAfterTaskCompletionDoesNotKeepCpuAwake() throws Exception {
        app.busy=false;
        ServiceController<InferenceService> service=Robolectric.buildService(InferenceService.class).create().startCommand(0,1);
        assertFalse(lock(service.get()).isHeld()); service.destroy();
    }
    @Test public void loadingDoesNotRunDifferentialInferenceOrChangeTheSelectedProfile() throws Exception {
        app.prefs.edit().putBoolean("cpuCompatibility",false).apply(); Engine.divergent=true;
        assertEquals("CPU",BackendGuard.load(app,1));
        assertFalse(app.cpuCompatibility); assertFalse(Engine.referenceMode);
        assertFalse(app.prefs.getBoolean("cpuCompatibility",false));
        assertEquals(512,app.microBatch());assertEquals(1,Engine.loads);
        assertEquals(0,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
        assertNull(app.cpuReference);assertNull(app.readReport("cpu-reference.json"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",app.backendNotice);
    }
    @Test public void prefillUpgradeReplacesOldCpuProfileOnlyOnce() {
        app.prefs.edit().remove("cpuPrefill512").putInt("microBatch",32).putString("optimizedFor","old-profile").apply();
        BonsaiApp.migratePrefillSettings(app.prefs);
        assertEquals(512,app.microBatch());assertFalse(app.prefs.contains("optimizedFor"));
        app.prefs.edit().putInt("microBatch",128).putString("optimizedFor","new-profile").apply();
        BonsaiApp.migratePrefillSettings(app.prefs);
        assertEquals(128,app.microBatch());assertEquals("new-profile",app.prefs.getString("optimizedFor",""));
    }
    @Test public void prefillUpgradePreservesCompatibilityAndGpuProfiles() {
        for(String mode:new String[]{"cpuCompatibility","gpu"}) {
            app.prefs.edit().clear().putBoolean(mode,true).putInt("microBatch",1).apply();
            BonsaiApp.migratePrefillSettings(app.prefs);
            assertEquals(1,app.microBatch());
        }
    }
    @Test public void compatibleOptimizedCpuRemainsAvailable() throws Exception {
        app.prefs.edit().putBoolean("cpuCompatibility",false).apply();
        assertEquals("CPU",BackendGuard.load(app,1));
        assertFalse(app.cpuCompatibility); assertFalse(Engine.referenceMode);
        assertEquals(0,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
    }
    @Test public void explicitCompatibilityModeLoadsOnceWithoutProbes() throws Exception {
        app.prefs.edit().putBoolean("cpuCompatibility",true).apply();
        assertEquals("CPU · kompatibel",BackendGuard.load(app,1));
        assertTrue(app.cpuCompatibility);assertTrue(Engine.referenceMode);
        assertEquals(1,Engine.loads);assertEquals(0,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
    }
    @Test public void selectedBackendLoadsDirectlyWithoutTemporaryReferenceContext() throws Exception {
        app.prefs.edit().putBoolean("gpu",true).putBoolean("cpuCompatibility",false).apply();
        assertEquals(BuildConfig.VULKAN_BUILD?"Vulkan":"CPU",BackendGuard.load(app,1));
        assertEquals(1,Engine.loads);assertEquals(0,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
    }
    @Test public void optimizerReferenceIsCreatedOnDemandAndReusedFromDisk() throws Exception {
        assertTrue(BackendGuard.prepareReference(app,1));
        assertFalse(app.cpuCompatibility);
        assertEquals(1,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
        app.cpuReference=null;
        assertFalse(BackendGuard.prepareReference(app,1));
        assertEquals(1,Engine.loads);assertEquals(1,Engine.referenceProbes);
        BackendGuard.load(app,1);
        assertEquals(2,Engine.loads);assertEquals(1,Engine.referenceProbes);assertEquals(0,Engine.candidateProbes);
        assertFalse(Engine.referenceMode);
    }
    @Test public void changedContextInvalidatesOptimizerReference() throws Exception {
        BackendGuard.prepareReference(app,1);
        app.prefs.edit().putInt("context",2048).apply();
        assertTrue(BackendGuard.prepareReference(app,1));
        assertEquals(2,Engine.referenceProbes);
    }
    @Test public void cancelledOptimizerReferenceIsNotCachedAndRestoresCompatibilityFlag() throws Exception {
        Engine.cancelReference=true;
        try { BackendGuard.prepareReference(app,1);fail("Cancellation must propagate"); }
        catch(IllegalStateException expected) {assertEquals("Abgebrochen.",expected.getMessage());}
        assertNull(app.cpuReference);assertNull(app.readReport("cpu-reference.json"));
        assertFalse(app.cpuCompatibility);assertFalse(app.prefs.getBoolean("cpuCompatibility",false));
    }
    @Test public void benchmarkIncludesFileChecksumAndActualAnswers() throws Exception {
        java.nio.file.Files.write(app.modelFile().toPath(),new byte[]{'G','G','U','F'});
        Engine.finish.countDown();
        app.runBenchmark(); awaitCompletion();
        org.json.JSONObject report=app.lastBenchmark();
        assertTrue(report.getBoolean("completed"));
        assertEquals(2,report.getInt("schema"));
        assertEquals("b83633aa785344791618f2fddf131b010ea04912a60430760b070bad293f65bd",report.getString("model_sha256"));
        assertEquals(3,report.getJSONArray("runs").length());
        for(int i=0;i<3;i++) assertEquals("Hallo aus dem Hintergrund",report.getJSONArray("runs").getJSONObject(i).getString("answer"));
    }
    @Test public void failedIntegrityCheckKeepsPreviousModelDuringImport() throws Exception {
        byte[] old="previous model".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(app.modelFile().toPath(),old);
        app.prefs.edit().putString("modelName","previous.gguf").apply();
        android.net.Uri uri=android.net.Uri.parse("content://integrity-test/model");
        android.content.ContentProvider provider=new android.content.ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public android.database.Cursor query(android.net.Uri u,String[] projection,String selection,String[] args,String order) {
                android.database.MatrixCursor cursor=new android.database.MatrixCursor(new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE});
                cursor.addRow(new Object[]{ModelIntegrity.Q2_NAME,4}); return cursor;
            }
            @Override public String getType(android.net.Uri u) { return "application/octet-stream"; }
            @Override public android.net.Uri insert(android.net.Uri u,android.content.ContentValues v) { throw new UnsupportedOperationException(); }
            @Override public int delete(android.net.Uri u,String selection,String[] args) { throw new UnsupportedOperationException(); }
            @Override public int update(android.net.Uri u,android.content.ContentValues v,String selection,String[] args) { throw new UnsupportedOperationException(); }
        };
        android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="integrity-test";
        provider.attachInfo(app,info);
        org.robolectric.shadows.ShadowStatFs.registerStats(app.getFilesDir().getAbsolutePath(),1000000,1000000,1000000);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("integrity-test",provider);
        Shadows.shadowOf(app.getContentResolver()).registerInputStream(uri,new java.io.ByteArrayInputStream(new byte[]{'G','G','U','F'}));
        app.importModel(uri); awaitCompletion();
        assertNotNull(app.modelApproval);
        assertArrayEquals(old,java.nio.file.Files.readAllBytes(app.modelFile().toPath()));
        assertEquals("previous.gguf",app.modelName()); assertTrue(app.loaded);
        assertEquals(0,Engine.unloads); assertEquals(0,Engine.loads);
        assertTrue(new java.io.File(app.getFilesDir(),"model.gguf.part").exists());
        app.declineModel(app.modelApproval);
        assertFalse(new java.io.File(app.getFilesDir(),"model.gguf.part").exists());
    }
    @Test public void previouslyImportedMismatchedModelCannotBeLoaded() throws Exception {
        java.nio.file.Files.write(app.modelFile().toPath(),new byte[]{'G','G','U','F'});
        app.prefs.edit().putString("modelName",ModelIntegrity.Q2_NAME).apply();
        app.loadModel(); awaitCompletion();
        assertNotNull(app.modelApproval);
        assertFalse(app.loaded); assertEquals(0,Engine.loads); assertEquals(1,Engine.unloads);
    }
}
