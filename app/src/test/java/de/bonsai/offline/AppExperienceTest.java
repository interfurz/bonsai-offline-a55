package de.bonsai.offline;

import android.app.Dialog;
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
        assertTrue(find(root,title).performLongClick());
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
        int w=824,h=1784;root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        File folder=new File("build/ui-captures");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    @Test public void welcomeAndPerformanceAreNavigable() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();
            assertNotNull(find(root,"Raum für deine"));assertNotNull(find(root,"Dein Modell verbinden"));
            screenshot(root,"welcome");
            assertTrue(find(root,"Leistung").performClick());
            Dialog sheet=(Dialog)field(a,"performanceDialog");assertTrue(sheet.isShowing());
            assertNotNull(find(sheet.getWindow().getDecorView(),"KERNEL OPTIMIZER"));
            assertFalse(find(sheet.getWindow().getDecorView(),"Dieses Backend optimieren").isEnabled());
            screenshot(sheet.getWindow().getDecorView(),"performance");sheet.dismiss();
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
            assertTrue(((View)field(a,"send")).performClick());
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
