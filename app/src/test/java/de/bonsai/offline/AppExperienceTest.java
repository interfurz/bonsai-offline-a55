package de.bonsai.offline;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppExperienceTest {
    private static TextView find(View v,String text) {
        if(v instanceof TextView && ((TextView)v).getText().toString().contains(text))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),text);if(t!=null)return t;}
        return null;
    }
    private static Object field(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static View described(View v,String description) {
        if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=described(((ViewGroup)v).getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
    private static AlertDialog actions(MainActivity activity,String title) throws Exception {
        View root=activity.getWindow().getDecorView();
        if(field(activity,"drawer")==null)described(root,"Chatverlauf öffnen").performClick();
        assertTrue(find((View)field(activity,"drawer"),title).performLongClick());
        return org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    }
    private static void choose(AlertDialog dialog,int index) {
        dialog.getListView().performItemClick(null,index,index);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @Test public void longPressMenuCancelsWithoutChangingChats() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            long id=app.store.createChat("Menütest");app.selectChat(id);
            AlertDialog menu=actions(a,"Menütest");
            assertEquals(3,menu.getListView().getAdapter().getCount());
            assertEquals("Löschen",menu.getListView().getAdapter().getItem(0));
            assertEquals("Archivieren",menu.getListView().getAdapter().getItem(1));
            assertEquals("Abbrechen",menu.getListView().getAdapter().getItem(2));
            screenshot(menu.getWindow().getDecorView(),"chat-actions");
            choose(menu,2);assertFalse(menu.isShowing());
            menu=actions(a,"Menütest");
            android.view.MotionEvent outside=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_OUTSIDE,-10,-10,0);
            try { assertTrue(menu.onTouchEvent(outside)); } finally { outside.recycle(); }
            assertFalse(menu.isShowing());assertEquals(id,app.currentChat);
            assertEquals(1,app.store.chats().size());assertTrue(app.store.chats(true).isEmpty());
        }
    }
    @Test public void archiveAndRestoreSelectedChatFromDrawer() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            long id=app.store.createChat("Archivtest");app.store.add(id,"assistant","Bleibt erhalten","");app.selectChat(id);
            choose(actions(a,"Archivtest"),1);
            assertEquals(0,app.currentChat);assertEquals(0,app.prefs.getLong("chat",-1));
            assertTrue(app.messages.isEmpty());assertTrue(app.store.chats().isEmpty());
            assertEquals("Bleibt erhalten",app.store.messages(id).get(0).text);
            View root=a.getWindow().getDecorView();assertNull(find(root,"Archivtest"));
            find(root,"Archivierte Chats").performClick();
            AlertDialog menu=actions(a,"Archivtest");
            assertEquals("Wiederherstellen",menu.getListView().getAdapter().getItem(1));choose(menu,1);
            assertEquals(id,app.store.chats().get(0).id);assertTrue(app.store.chats(true).isEmpty());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,((View)field(a,"page")).getImportantForAccessibility());
            a.onBackPressed();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertNull(field(a,"drawer"));
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,((View)field(a,"page")).getImportantForAccessibility());
        }
    }
    @Test public void deletingAnotherChatKeepsCurrentConversationAndRequiresConfirmation() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            long current=app.store.createChat("Aktiver Verlauf"),other=app.store.createChat("Anderer Verlauf");
            app.store.add(current,"assistant","Aktuelle Antwort","");app.store.add(other,"assistant","Andere Antwort","");app.selectChat(current);
            choose(actions(a,"Anderer Verlauf"),0);
            AlertDialog confirmation=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(2,app.store.chats().size());
            choose(actions(a,"Anderer Verlauf"),0);
            confirmation=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(current,app.currentChat);assertEquals("Aktuelle Antwort",app.messages.get(0).text);
            assertEquals(1,app.store.chats().size());assertTrue(app.store.messages(other).isEmpty());
        }
    }
    @Test public void chatChangesRespectBusyStateAndClearOnlyTheSelectedConversation() {
        BonsaiApp app=(BonsaiApp)org.robolectric.RuntimeEnvironment.getApplication();
        long current=app.store.createChat("Aktuell"),other=app.store.createChat("Anderer Chat");
        app.store.add(current,"assistant","Hallo","");app.selectChat(current);
        app.busy=true;app.deleteChat(current);app.archiveChat(current,true);
        assertEquals(2,app.store.chats().size());assertEquals(current,app.currentChat);
        app.busy=false;app.archiveChat(other,true);
        assertEquals(current,app.currentChat);assertEquals("Hallo",app.messages.get(0).text);
        app.deleteChat(current);
        assertEquals(0,app.currentChat);assertTrue(app.messages.isEmpty());assertEquals(0,app.prefs.getLong("chat",-1));
        assertTrue(app.store.messages(current).isEmpty());assertEquals(other,app.store.chats(true).get(0).id);
    }
    private static void screenshot(View root,String name) throws Exception {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        int w=root.getResources().getDisplayMetrics().widthPixels,h=root.getResources().getDisplayMetrics().heightPixels;root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        File folder=new File("build/ui-captures");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    @Test public void welcomeAndPerformanceAreNavigable() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();
            assertNotNull(find(root,"Gute Gedanken"));assertNotNull(find(root,"Modell importieren"));
            assertFalse(((View)field(a,"send")).isEnabled());screenshot(root,"welcome-light");
            android.widget.EditText input=(android.widget.EditText)field(a,"input");input.setText("Mein Entwurf");
            TextView[] tabs=(TextView[])field(a,"tabs");tabs[2].performClick();
            assertTrue(tabs[2].isSelected());assertNotNull(find(root,"Passend zu deinem Gerät"));
            assertTrue(find(root,"Modell öffnen").isEnabled());screenshot(root,"performance-light");
            find(root,"Modell öffnen").performClick();assertTrue(tabs[1].isSelected());
            assertNotNull(find(root,"Antworten anpassen"));screenshot(root,"model-light");
            a.onBackPressed();assertTrue(tabs[0].isSelected());assertEquals("Mein Entwurf",input.getText().toString());
        }
    }
    @Test @Config(qualifiers="w375dp-h812dp-night-mdpi")
    public void narrowDarkModeAndLargeTextKeepNavigationReachable() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();
            screenshot(root,"welcome-dark-375-large-text");
            TextView[] tabs=(TextView[])field(a,"tabs");
            for(int i=0;i<tabs.length;i++){
                tabs[i].performClick();screenshot(root,"large-text-tab-"+i);
                int[] location=new int[2];tabs[i].getLocationOnScreen(location);
                assertTrue("Tab must remain on screen",location[1]>=0&&location[1]+tabs[i].getHeight()<=root.getHeight());
                assertTrue(tabs[i].getHeight()>=48);
                assertFalse("Tab label must not be ellipsized",tabs[i].getLayout().getEllipsisCount(0)>0);
                assertEquals("Tab names stay on one line at 200% text",1,tabs[i].getLineCount());
            }
        } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f); }
    }
    @Test @Config(qualifiers="w1024dp-h900dp-mdpi")
    public void wideLayoutCapsReadingWidth() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();screenshot(root,"tablet-light");
            assertTrue(((View)field(a,"page")).getWidth()<=DesignTokens.CONTENT_WIDTH);
        }
    }
    @Test public void inputValidationAndStreamingPreserveDraftAndText() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;app.notifyUi();
            android.widget.EditText input=(android.widget.EditText)field(a,"input");input.setText(" ");assertFalse(((View)field(a,"send")).isEnabled());
            input.setText("a".repeat(32001));assertFalse(((View)field(a,"send")).isEnabled());assertNotNull(find(a.getWindow().getDecorView(),"Bitte kürze"));
            input.setText("Meine nächste Frage");assertTrue(((View)field(a,"send")).isEnabled());
            app.messages.add(new ChatStore.Message(1,"user","Erkläre Photosynthese",""));
            ChatStore.Message answer=new ChatStore.Message(0,"assistant",""," ");app.messages.add(answer);app.busy=true;app.notifyUi();
            TextView live=(TextView)field(a,"liveAnswer");assertEquals("Bonsai denkt nach …",live.getText().toString());
            answer.text="Pflanzen nutzen Licht.";app.notifyUi();
            assertSame("Streaming must reuse the text view",live,field(a,"liveAnswer"));assertEquals(answer.text,live.getText().toString());
            assertTrue(input.isEnabled());assertEquals("Meine nächste Frage",input.getText().toString());
            app.busy=false;app.error="Datei nicht lesbar.";app.notifyUi();assertNotNull(find(a.getWindow().getDecorView(),"Vorgang nicht abgeschlossen"));
            screenshot(a.getWindow().getDecorView(),"conversation-error");
        }
    }
    @Test public void regeneratedVariantPreservesOriginalAndCopiesOnlyEarlierTurns(){
        BonsaiApp app=(BonsaiApp)org.robolectric.RuntimeEnvironment.getApplication();ChatStore store=app.store;
        long original=store.createChat("Original");store.add(original,"user","Frage 1","");store.add(original,"assistant","Antwort 1","Messung");store.add(original,"user","Frage 2","");store.add(original,"assistant","Antwort 2","");
        long variant=store.forkBeforeLastTurn(original);assertNotEquals(original,variant);
        assertEquals(4,store.messages(original).size());assertEquals(2,store.messages(variant).size());
        assertEquals("Antwort 1",store.messages(variant).get(1).text);
        assertEquals("Messung",store.messages(variant).get(1).detail);
    }
    @Test public void bothPalettesMeetTextAndControlContrast(){
        android.content.res.Resources r=org.robolectric.RuntimeEnvironment.getApplication().getResources();
        int old=r.getConfiguration().uiMode;
        for(int mode:new int[]{android.content.res.Configuration.UI_MODE_NIGHT_NO,android.content.res.Configuration.UI_MODE_NIGHT_YES}){
            android.content.res.Configuration c=new android.content.res.Configuration(r.getConfiguration());c.uiMode=mode;r.updateConfiguration(c,r.getDisplayMetrics());DesignTokens t=new DesignTokens(r);
            for(int bg:new int[]{t.background,t.surface})for(int fg:new int[]{t.text,t.muted,t.primary,t.error,t.warning})assertTrue("Text contrast below AA",contrast(fg,bg)>=4.5);
            assertTrue(contrast(t.action,t.onPrimary)>=4.5);assertTrue(contrast(t.border,t.surface)>=3);
        }
        android.content.res.Configuration c=new android.content.res.Configuration(r.getConfiguration());c.uiMode=old;r.updateConfiguration(c,r.getDisplayMetrics());
    }
    private static double luminance(int color){double sum=0;int[] shifts={16,8,0};double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double v=((color>>shifts[i])&255)/255.0;sum+=weights[i]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));}return sum;}
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    @Test public void disabledSystemAnimationsNeverLeaveTranslatedContent() throws Exception {
        java.lang.reflect.Method durationScale=android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale",float.class);
        durationScale.setAccessible(true);durationScale.invoke(null,0f);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();TextView[] tabs=(TextView[])field(a,"tabs");tabs[1].performClick();
            View screen=((ViewGroup)field(a,"screenHost")).getChildAt(0);
            assertEquals(1f,screen.getAlpha(),0f);assertEquals(0f,screen.getTranslationX(),0f);assertEquals(0f,screen.getTranslationY(),0f);
        } finally {durationScale.invoke(null,1f);}
    }
    @Test @Config(qualifiers="w375dp-h812dp-night-mdpi")
    public void darkReadyStateAndHistoryCanBeOpened() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;app.notifyUi();
            assertNotNull(find(a.getWindow().getDecorView(),"Einfach verstehen"));screenshot(a.getWindow().getDecorView(),"ready-dark");
            java.lang.reflect.Method drawer=MainActivity.class.getDeclaredMethod("showDrawer");drawer.setAccessible(true);drawer.invoke(a);
            assertNotNull(find(a.getWindow().getDecorView(),"Noch keine Chats"));screenshot(a.getWindow().getDecorView(),"history-dark");
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,((View)field(a,"page")).getImportantForAccessibility());
            a.onBackPressed();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertNull(field(a,"drawer"));assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,((View)field(a,"page")).getImportantForAccessibility());
        }
    }
    @Test public void tunedProfilesAreInvalidatedBySettingsAndHaveUniqueCandidates() {
        BonsaiApp a=(BonsaiApp)org.robolectric.RuntimeEnvironment.getApplication();
        a.prefs.edit().putString("optimizedFor",a.profileIdentity()).commit();assertTrue(a.optimized());
        a.prefs.edit().putInt("threads",6).commit();assertFalse(a.optimized());
        String measuredIdentity=a.profileIdentity(false,2,4,128);
        a.prefs.edit().putBoolean("gpu",false).putInt("threads",2).putInt("batchThreads",4).putInt("microBatch",128).putString("optimizedFor",measuredIdentity).commit();
        assertTrue("A saved winner must be recognized after applying its settings",a.optimized());
        List<KernelOptimizer.Profile> cpu=KernelOptimizer.profiles(new KernelOptimizer.Profile(false,4,4,32),false);
        assertEquals(cpu.size(),cpu.stream().map(KernelOptimizer.Profile::key).distinct().count());
        assertTrue(cpu.stream().allMatch(p->!p.gpu && p.microBatch<=256 && p.threads<=8));
    }
    @Test public void divergentAndNonFiniteKernelResultsAreRejected() throws Exception {
        org.json.JSONObject ref=new org.json.JSONObject("{\"signature\":[1,2,3,4],\"top_tokens\":[1,2,3,4]}");
        assertTrue(BackendGuard.matches(ref,new org.json.JSONObject("{\"signature\":[1.001,2,3,4],\"top_tokens\":[1,2,3,4]}")));
        assertFalse(BackendGuard.matches(ref,new org.json.JSONObject("{\"signature\":[9,1,12,-4],\"top_tokens\":[99,98,97,96]}")));
        assertFalse(BackendGuard.matches(ref,new org.json.JSONObject("{\"signature\":[1],\"top_tokens\":[1]}")));
        assertFalse(BackendGuard.matches(ref,new org.json.JSONObject("{\"signature\":[\"NaN\",2,3,4],\"top_tokens\":[1,2,3,4]}")));
        assertFalse(BackendGuard.matches(new org.json.JSONObject("{\"signature\":[1],\"top_tokens\":[]}"),new org.json.JSONObject("{\"signature\":[1],\"top_tokens\":[]}")));
    }
    @Test public void kernelReferenceWithoutTopTokensIsRejected() throws Exception {
        org.json.JSONObject incomplete=new org.json.JSONObject("{\"signature\":[1,2,3,4],\"top_tokens\":[]}");
        org.json.JSONObject candidate=new org.json.JSONObject("{\"signature\":[1,2,3,4],\"top_tokens\":[99,98,97,96]}");
        assertFalse("An empty top-token reference cannot validate a kernel",BackendGuard.matches(incomplete,candidate));
    }
    @Test public void longerKernelProbesCanMatchTheReferencePrefix() throws Exception {
        org.json.JSONObject reference=new org.json.JSONObject("{\"signature\":[1,2,3,4],\"top_tokens\":[1,2]}");
        org.json.JSONObject longer=new org.json.JSONObject("{\"signature\":[1,2,3,4,5,6],\"top_tokens\":[1,2,3]}");
        assertTrue(BackendGuard.matches(reference,longer));
    }
    @Test public void oversizedMessageKeepsItsDraftWhenRejected() throws Exception {
        assertRejectedSendKeepsDraft("  " + "x".repeat(32001) + "  ", "Die Nachricht ist zu lang.");
    }
    @Test public void unavailableRuntimeKeepsItsDraftWhenRejected() throws Exception {
        assertRejectedSendKeepsDraft("  Bitte diesen Entwurf behalten.  ", "Native Runtime konnte nicht geladen werden");
    }
    private void assertRejectedSendKeepsDraft(String draft,String expectedError) throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.loaded=true; // Reach send validation without loading a model on the host.
            assertEquals(0L,((Long)field(app,"handle")).longValue());
            android.widget.EditText input=(android.widget.EditText)field(a,"input");
            input.setText(draft);
            View send=(View)field(a,"send");
            if(draft.length()>32000) {
                assertFalse("Oversized drafts cannot be submitted through the UI",send.isEnabled());
                assertEquals(View.VISIBLE,((View)field(a,"inputError")).getVisibility());
                assertFalse("Core validation must still reject oversized input",app.send(draft.trim()));
            } else {
                assertTrue(send.isEnabled());assertTrue(send.performClick());
                assertEquals("Rejected sends must not become a pending accepted prompt","",field(a,"pendingPrompt"));
            }
            assertTrue(app.error,app.error.contains(expectedError));
            assertEquals("A synchronously rejected send must preserve the exact draft",draft,input.getText().toString());
            assertFalse(app.busy);assertEquals(0,app.currentChat);assertTrue(app.messages.isEmpty());
            assertTrue(app.store.chats().isEmpty());
        }
    }
    @Test @Config(shadows=KernelTemperatureTest.NativeFixture.class)
    public void acceptedMessageClearsItsDraftAndPersistsTheTurn() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;
            android.widget.EditText input=(android.widget.EditText)field(a,"input");input.setText("  Hallo Bonsai  ");
            assertTrue(((View)field(a,"send")).performClick());
            assertEquals("",input.getText().toString());
            app.worker.shutdown();assertTrue(app.worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            List<ChatStore.Message> saved=app.store.messages(app.currentChat);
            assertEquals(2,saved.size());assertEquals("Hallo Bonsai",saved.get(0).text);
            assertEquals("Synthetic test reply",saved.get(1).text);assertEquals("",app.error);
        }
    }
    @Test @Config(shadows=BackgroundInferenceTest.Engine.class)
    public void stoppedActivityKeepsInferenceAndRestoresExactFailedDraftOnReturn() throws Exception {
        assertFailedBackgroundSendKeepsDraft(false);
    }
    @Test @Config(shadows=BackgroundInferenceTest.Engine.class)
    public void backgroundFailureDoesNotOverwriteTheNextDraft() throws Exception {
        assertFailedBackgroundSendKeepsDraft(true);
    }
    private void assertFailedBackgroundSendKeepsDraft(boolean writeNextDraft) throws Exception {
        BackgroundInferenceTest.Engine.started=new java.util.concurrent.CountDownLatch(1);
        BackgroundInferenceTest.Engine.finish=new java.util.concurrent.CountDownLatch(1);
        BackgroundInferenceTest.Engine.fail=true;BackgroundInferenceTest.Engine.stopped=false;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            android.widget.EditText input=(android.widget.EditText)field(a,"input");
            String draft="  Bitte genau diesen Entwurf behalten.  ",nextDraft="Meine nächste Frage";
            input.setText(draft);assertTrue(((View)field(a,"send")).performClick());
            assertEquals("",input.getText().toString());assertEquals(draft,field(a,"pendingPrompt"));
            assertTrue(BackgroundInferenceTest.Engine.started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            if(writeNextDraft)input.setText(nextDraft);
            controller.pause().stop();
            assertTrue(app.busy);assertFalse(app.cancelled);assertFalse(BackgroundInferenceTest.Engine.stopped);
            assertNull(app.listener);assertFalse(app.main.hasCallbacks((Runnable)field(a,"progressTick")));
            BackgroundInferenceTest.Engine.finish.countDown();
            app.worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(app.busy);assertEquals("Test inference failure",app.error);
            assertEquals(writeNextDraft?nextDraft:"",input.getText().toString());
            controller.start().resume().visible();
            assertEquals(writeNextDraft?nextDraft:draft,input.getText().toString());
            assertEquals("",field(a,"pendingPrompt"));assertTrue(app.store.messages(app.currentChat).isEmpty());
        } finally { BackgroundInferenceTest.Engine.finish.countDown(); }
    }
    @Test public void checksumApprovalSurvivesNativeModelPageRecreation() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            ((TextView[])field(a,"tabs"))[1].performClick();
            BonsaiApp.ModelApproval request=new BonsaiApp.ModelApproval(
                new ModelIntegrity.Mismatch(ModelIntegrity.Q2_NAME,4,"0".repeat(64),ModelIntegrity.Q2_SHA),false);
            app.modelApproval=request;app.error=request.mismatch.getMessage();app.notifyUi();
            AlertDialog first=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(first.isShowing());
            assertEquals("Trotzdem fortfahren",first.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            app.notifyUi();assertSame(first,org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            controller.recreate();MainActivity next=controller.get();
            AlertDialog reopened=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertFalse(first.isShowing());assertNotSame(first,reopened);assertTrue(reopened.isShowing());
            assertSame(request,app.modelApproval);assertTrue(((TextView[])field(next,"tabs"))[1].isSelected());
            reopened.cancel();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(app.modelApproval);assertEquals("",app.error);
        }
    }
    @Test @Config(shadows=BackgroundInferenceTest.Engine.class)
    public void nativeModelPageKeepsFlexibleImportAndCompatibilitySettings() throws Exception {
        BackgroundInferenceTest.Engine.loads=0;BackgroundInferenceTest.Engine.divergent=false;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            java.nio.file.Files.write(app.modelFile().toPath(),new byte[]{'G','G','U','F'});
            app.prefs.edit().putString("modelName","other-Q4_K_M.gguf").putBoolean("gpu",false)
                .putBoolean("cpuCompatibility",false).putString("optimizedFor","old-profile").putInt("batchThreads",8).putInt("microBatch",128)
                .putBoolean("notificationsAsked",true).apply();
            app.loaded=true;((TextView[])field(a,"tabs"))[1].performClick();View root=a.getWindow().getDecorView();
            find(root,"Beispielmodell").performClick();
            android.content.Intent download=org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
            assertEquals(android.content.Intent.ACTION_VIEW,download.getAction());assertEquals(ModelIntegrity.downloadUrl(),download.getDataString());
            find(root,"Anderes Modell importieren").performClick();
            AlertDialog picker=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(find(picker.getWindow().getDecorView(),"Wähle eine lokale GGUF-Datei"));
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.content.Intent select=org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,select.getAction());assertEquals("*/*",select.getType());
            android.widget.CheckBox compatible=(android.widget.CheckBox)find(root,"CPU-Kompatibilitätsmodus");compatible.setChecked(true);
            find(root,"Einstellungen speichern").performClick();
            assertTrue(app.prefs.getBoolean("cpuCompatibility",false));assertFalse(app.prefs.contains("optimizedFor"));
            assertFalse(app.prefs.contains("batchThreads"));assertEquals(1,app.microBatch());
            app.worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(app.error,app.loaded);assertTrue(app.cpuCompatibility);assertTrue(BackgroundInferenceTest.Engine.loads>0);
            ((TextView[])field(a,"tabs"))[2].performClick();
            assertNotNull(find(root,"jedem Temperaturstatus"));assertNotNull(find(root,"Hintergrund weiter"));
            assertTrue(((View)field(a,"performanceStart")).isEnabled());
        }
    }
    @Test public void conversationAndDraftSurviveRecreation() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.currentChat=app.store.createChat("Testchat");app.store.add(app.currentChat,"user","Hallo Bonsai","");app.store.add(app.currentChat,"assistant","Hallo!","15 Tokens/s");
            app.messages.clear();app.messages.addAll(app.store.messages(app.currentChat));app.notifyUi();
            android.widget.EditText input=(android.widget.EditText)field(a,"input");input.setText("Mein Entwurf");
            controller.recreate();MainActivity next=controller.get();
            assertEquals("Mein Entwurf",((android.widget.EditText)field(next,"input")).getText().toString());
            assertNotNull(find(next.getWindow().getDecorView(),"Hallo!"));screenshot(next.getWindow().getDecorView(),"conversation");
        }
    }
}
