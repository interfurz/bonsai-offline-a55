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
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static long create() { return 1; }
        @Implementation protected static void resetStop(long handle) { stopped=false; }
        @Implementation protected static void stop(long handle) { stopped=true; finish.countDown(); }
        @Implementation protected static void referenceCpu(boolean enabled) { referenceMode=enabled; }
        @Implementation protected static void configure(long handle, int threads, int batchThreads, int microBatch) {}
        @Implementation protected static byte[] load(long handle, byte[] path, int context, int threads, boolean gpu) {
            return ("{\"backend\":\""+(gpu?"Vulkan":"CPU")+"\"}").getBytes(StandardCharsets.UTF_8);
        }
        @Implementation protected static byte[] probe(long handle, int steps) {
            return ((!referenceMode && divergent)?"{\"signature\":[100,200],\"top_tokens\":[9,9]}":"{\"signature\":[1,2],\"top_tokens\":[1,2]}").getBytes(StandardCharsets.UTF_8);
        }
        @Implementation protected static byte[] generate(long handle, byte[][] roles, byte[][] contents, int maxTokens, int seed, NativeEngine.Stream stream) throws Exception {
            started.countDown();
            if(!finish.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test worker timed out");
            if(fail) throw new IllegalStateException("Test inference failure");
            stream.onBytes("Hallo aus dem Hintergrund".getBytes(StandardCharsets.UTF_8));
            return ("{\"tokens_per_second\":2,\"output_tokens\":4,\"first_token_ms\":10,\"stopped\":"+stopped+",\"limit_reached\":false}").getBytes(StandardCharsets.UTF_8);
        }
    }
    private BonsaiApp app;
    @Before public void prepare() {
        Engine.started=new CountDownLatch(1); Engine.finish=new CountDownLatch(1); Engine.stopped=false; Engine.fail=false; Engine.divergent=false; Engine.referenceMode=false;
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
    @Test public void divergentOptimizedCpuFallsBackToIndependentScalarKernels() throws Exception {
        app.prefs.edit().putBoolean("cpuCompatibility",false).apply(); Engine.divergent=true;
        assertEquals("CPU · kompatibel",BackendGuard.load(app,1));
        assertTrue(app.cpuCompatibility); assertTrue(Engine.referenceMode);
        assertTrue(app.prefs.getBoolean("cpuCompatibility",false));
        assertEquals(1,app.microBatch());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(app.backendNotice.contains("unabhängige Rechenprüfung"));
    }
    @Test public void compatibleOptimizedCpuRemainsAvailable() throws Exception {
        app.prefs.edit().putBoolean("cpuCompatibility",false).apply();
        assertEquals("CPU",BackendGuard.load(app,1));
        assertFalse(app.cpuCompatibility); assertFalse(Engine.referenceMode);
    }
}
