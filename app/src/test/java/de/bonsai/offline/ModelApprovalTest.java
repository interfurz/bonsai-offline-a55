package de.bonsai.offline;

import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowStatFs;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=BackgroundInferenceTest.Engine.class)
public class ModelApprovalTest {
    private BonsaiApp app;
    private static final byte[] GGUF={'G','G','U','F'};
    private static final byte[] OLD="previous model".getBytes(StandardCharsets.UTF_8);

    @Before public void prepare() throws Exception {
        BackgroundInferenceTest.Engine.started=new CountDownLatch(1);
        BackgroundInferenceTest.Engine.finish=new CountDownLatch(0);
        BackgroundInferenceTest.Engine.loads=0;BackgroundInferenceTest.Engine.unloads=0;
        BackgroundInferenceTest.Engine.divergent=false;
        app=(BonsaiApp)RuntimeEnvironment.getApplication();
        app.prefs.edit().putBoolean("notificationsAsked",true).putString("modelName","previous.gguf").apply();
        app.loaded=true;Files.write(app.modelFile().toPath(),OLD);
        ShadowStatFs.registerStats(app.getFilesDir().getAbsolutePath(),1000000,1000000,1000000);
    }
    private void awaitCompletion() throws Exception {
        app.worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(app.busy);
    }
    private File partial() { return new File(app.getFilesDir(),"model.gguf.part"); }
    private void importFile(String name,byte[] data) throws Exception {
        Uri uri=Uri.parse("content://approval-test/model");
        ContentProvider provider=new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) {
                MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
                cursor.addRow(new Object[]{name,data.length});return cursor;
            }
            @Override public String getType(Uri u) { return "application/octet-stream"; }
            @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
            @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
            @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
        };
        ProviderInfo info=new ProviderInfo();info.authority="approval-test";provider.attachInfo(app,info);
        ShadowContentResolver.registerProviderInternal("approval-test",provider);
        Shadows.shadowOf(app.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(data));
        app.importModel(uri);awaitCompletion();
    }
    @Test public void popupContinuesStagedImportAndRemembersExactFile() throws Exception {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup()) {
            importFile(ModelIntegrity.Q2_NAME,GGUF);
            assertArrayEquals(OLD,Files.readAllBytes(app.modelFile().toPath()));
            assertEquals(0,BackgroundInferenceTest.Engine.loads);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Trotzdem fortfahren",dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitCompletion();
            assertTrue(app.error,app.loaded);assertNull(app.modelApproval);assertFalse(partial().exists());
            assertArrayEquals(GGUF,Files.readAllBytes(app.modelFile().toPath()));
            assertFalse(app.prefs.getString("approvedModelIntegrity","").isEmpty());
            app.loadModel();awaitCompletion();
            assertTrue(app.loaded);assertNull(app.modelApproval);assertEquals("",app.error);
        }
    }
    @Test public void cancelKeepsPreviousModelAndRemovesStagedCopy() throws Exception {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup()) {
            importFile(ModelIntegrity.Q2_NAME,GGUF);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(app.modelApproval);assertFalse(partial().exists());assertTrue(app.loaded);
            assertArrayEquals(OLD,Files.readAllBytes(app.modelFile().toPath()));
            assertEquals(0,BackgroundInferenceTest.Engine.unloads);
            assertEquals("",app.prefs.getString("approvedModelIntegrity",""));
        }
    }
    @Test public void loadingExistingFileCanBeApprovedButChangedBytesAskAgain() throws Exception {
        Files.write(app.modelFile().toPath(),GGUF);
        app.prefs.edit().putString("modelName",ModelIntegrity.Q2_NAME).apply();
        app.loadModel();awaitCompletion();assertNotNull(app.modelApproval);
        String oldSha=app.modelApproval.mismatch.sha;
        app.acceptModel(app.modelApproval);awaitCompletion();assertTrue(app.loaded);
        Files.write(app.modelFile().toPath(),new byte[]{'G','G','U','F',1});
        app.loadModel();awaitCompletion();
        assertNotNull(app.modelApproval);assertFalse(app.loaded);
        assertNotEquals(oldSha,app.modelApproval.mismatch.sha);
    }
    @Test public void pendingDecisionSurvivesActivityRecreation() throws Exception {
        ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).setup();
        importFile(ModelIntegrity.Q2_NAME,GGUF);
        BonsaiApp.ModelApproval pending=app.modelApproval;
        first.pause().stop().destroy();
        assertSame(pending,app.modelApproval);assertTrue(partial().exists());
        try(ActivityController<MainActivity> reopened=Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            ShadowAlertDialog.getLatestAlertDialog().cancel();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(app.modelApproval);assertFalse(partial().exists());
        }
    }
    @Test public void otherGgufNamesReachEngineWithoutBonsaiChecksumRequirement() throws Exception {
        importFile("another-model-Q4_K_M.gguf",GGUF);
        assertNull(app.modelApproval);assertTrue(app.error,app.loaded);
        assertEquals("another-model-Q4_K_M.gguf",app.modelName());
        assertTrue(BackgroundInferenceTest.Engine.loads>0);
    }
    @Test public void nonGgufFileDoesNotOfferChecksumOverride() throws Exception {
        importFile("another-model.gguf",new byte[]{0,1,2,3});
        assertNull(app.modelApproval);assertFalse(partial().exists());
        assertEquals(0,BackgroundInferenceTest.Engine.loads);
        assertTrue(app.error.contains("kein GGUF"));
        assertArrayEquals(OLD,Files.readAllBytes(app.modelFile().toPath()));
    }
}
