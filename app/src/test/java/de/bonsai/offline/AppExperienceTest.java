package de.bonsai.offline;


import android.app.AlertDialog;
import android.app.Dialog;
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
        if(field(activity,"drawer")==null)described(root,"Menü öffnen").performClick();
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
    private static void idleUi(){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private static View openSection(MainActivity activity,String label) throws Exception {
        Dialog current=(Dialog)field(activity,"sectionDialog");if(current!=null){current.dismiss();idleUi();}
        View root=activity.getWindow().getDecorView();
        if(field(activity,"drawer")==null)described(root,"Menü öffnen").performClick();
        find((View)field(activity,"drawer"),label).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        Dialog dialog=(Dialog)field(activity,"sectionDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());
        assertNull(field(activity,"drawer"));assertEquals(label,dialog.getWindow().getAttributes().getTitle().toString());
        assertSame("Chat stays mounted behind the popup",field(activity,"chatPage"),((ViewGroup)field(activity,"screenHost")).getChildAt(0));
        return dialog.getWindow().getDecorView();
    }
    private static void screenshot(View root,String name) throws Exception { screenshot(root,null,name); }
    private static void screenshot(MainActivity activity,String name) throws Exception {
        screenshot(activity.getWindow().getDecorView(),(Dialog)field(activity,"sectionDialog"),name);
    }
    private static void screenshot(View root,Dialog popup,String name) throws Exception {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        int w=root.getResources().getDisplayMetrics().widthPixels,h=root.getResources().getDisplayMetrics().heightPixels;root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);root.draw(canvas);
        if(popup!=null){
            // Robolectric has no system compositor; draw each native window at its configured size.
            android.view.Window window=popup.getWindow();View decor=window.getDecorView();
            int pw=window.getAttributes().width,ph=window.getAttributes().height;
            decor.measure(View.MeasureSpec.makeMeasureSpec(pw,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ph,View.MeasureSpec.EXACTLY));decor.layout(0,0,pw,ph);
            canvas.drawColor(0x8c000000);canvas.save();canvas.translate((w-pw)/2f,(h-ph)/2f);decor.draw(canvas);canvas.restore();
        }
        File folder=new File("build/ui-captures");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    private void captureAnswerFooter(String capture) throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity=controller.get();BonsaiApp app=(BonsaiApp)activity.getApplication();
            app.loaded=true;app.messages.clear();
            app.messages.add(new ChatStore.Message(1,"user","Wie speichert ein Wald Wasser?",""));
            String answer="Ein Wald speichert Wasser im Boden. Wurzeln, Humus und Moose nehmen Regen auf und geben ihn langsam wieder ab.";
            String metrics="11,6 Tokens/s · 40 Tokens · 1,8 s bis zum ersten Token";
            app.messages.add(new ChatStore.Message(2,"assistant",answer,metrics));app.notifyUi();idleUi();
            View root=activity.getWindow().getDecorView();
            View copy=described(root,"Antwort kopieren"),retry=described(root,"Neu generieren – Variante als eigenen Chat speichern");
            assertTrue(copy instanceof android.widget.ImageButton);assertNotNull(retry);assertTrue(retry.isEnabled());
            copy.performClick();
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            assertEquals(answer,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            TextView detail=(TextView)field(activity,"liveDetail");assertEquals(metrics,detail.getContentDescription().toString());
            assertEquals(3,((android.text.Spanned)detail.getText()).getSpans(0,detail.length(),android.text.style.ImageSpan.class).length);
            assertNull(find(root,"Eine neue Variante wird als eigener Chat gespeichert."));
            screenshot(root,capture);
            app.loaded=false;app.notifyUi();assertFalse(described(root,"Neu generieren – Variante als eigenen Chat speichern").isEnabled());
        }
    }
    @Test public void answerFooterIconsAndCopyWorkInLightTheme() throws Exception { captureAnswerFooter("answer-icons-light"); }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void answerFooterIconsAndCopyWorkInDarkTheme() throws Exception { captureAnswerFooter("answer-icons-dark"); }
    @Test @Config(qualifiers="w360dp-h800dp-night-xhdpi")
    public void answerFooterWrapsAtLargeTextSize() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f);captureAnswerFooter("answer-icons-large-text");
    }
    @Test public void welcomeAndPerformanceAreNavigable() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();
            assertEquals(0,field(a,"activeScreen"));assertNull(field(a,"sectionDialog"));assertNull(described(root,"Hauptnavigation"));
            assertNotNull(find(root,"Gute Gedanken"));assertNotNull(find(root,"Modell importieren"));
            assertFalse(((View)field(a,"send")).isEnabled());screenshot(root,"welcome-light");
            android.widget.EditText input=(android.widget.EditText)field(a,"input");input.setText("Mein Entwurf");
            View popup=openSection(a,"Leistung");Dialog dialog=(Dialog)field(a,"sectionDialog");
            assertEquals(2,field(a,"activeScreen"));assertNotNull(find(popup,"Passend zu deinem Gerät"));
            assertTrue(find(popup,"Modell öffnen").isEnabled());screenshot(a,"performance-light");
            find(popup,"Modell öffnen").performClick();assertEquals(1,field(a,"activeScreen"));
            assertSame("Switch popup content without stacking windows",dialog,field(a,"sectionDialog"));
            assertNotNull(find(popup,"Antworten anpassen"));screenshot(a,"model-light");
            described(popup,"Pop-up schließen").performClick();idleUi();assertNull(field(a,"sectionDialog"));assertEquals(0,field(a,"activeScreen"));
            assertEquals("Mein Entwurf",input.getText().toString());assertNotNull(find(root,"Gute Gedanken"));
        }
    }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void thinkingAndPrefillUseOneLineThenFinalAnswerWrapsNormally() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity=controller.get();BonsaiApp app=(BonsaiApp)activity.getApplication();
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();app.loaded=true;app.busy=true;app.status="Antwort wird vorbereitet …";
            app.messages.clear();app.messages.add(new ChatStore.Message(1,"user","Was geht?",""));
            ChatStore.Message answer=new ChatStore.Message(2,"assistant","","");app.messages.add(answer);
            app.generationLine="Ich überlege, wie ich die Frage passend beantworten kann.";app.notifyUi();idleUi();
            TextView body=(TextView)field(activity,"liveAnswer");assertEquals(app.generationLine,body.getText().toString());assertEquals(1,body.getMaxLines());
            screenshot(activity,"thinking-line-dark");
            app.generationLine="Prompt-Cache 25 % · 31,3 Tok/s";app.prefillFraction=.25;app.notifyUi();idleUi();
            assertEquals(app.generationLine,body.getText().toString());assertEquals(1,body.getMaxLines());
            android.widget.ProgressBar progress=(android.widget.ProgressBar)field(activity,"statusProgress");assertFalse(progress.isIndeterminate());assertEquals(250,progress.getProgress());
            screenshot(activity,"prefill-line-dark");
            answer.text="Hallo!\nWie kann ich dir helfen?";app.notifyUi();idleUi();
            assertEquals(answer.text,body.getText().toString());assertTrue(body.getMaxLines()>1);assertNull(body.getEllipsize());
            app.busy=false;app.notifyUi();idleUi();
        }
    }
    @Test @Config(qualifiers="w375dp-h812dp-night-mdpi")
    public void narrowDarkModeAndLargeTextKeepNavigationReachable() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();
            screenshot(root,"large-text-chat");
            described(root,"Menü öffnen").performClick();screenshot(root,"large-text-menu");
            for(String label:new String[]{"Modell","Leistung"}){
                View popup=openSection(a,label);screenshot(a,"large-text-"+label);
                View close=described(popup,"Pop-up schließen");assertNotNull(close);assertTrue(close.getHeight()>=48);
                android.graphics.Rect bounds=new android.graphics.Rect();close.getDrawingRect(bounds);((ViewGroup)popup).offsetDescendantRectToMyCoords(close,bounds);
                assertTrue("Popup close stays reachable",bounds.top>=0&&bounds.bottom<=popup.getHeight());
                TextView action=(TextView)(label.equals("Modell")?find(popup,"Modell importieren"):field(a,"performanceStart"));
                action.getDrawingRect(bounds);((ViewGroup)popup).offsetDescendantRectToMyCoords(action,bounds);
                assertTrue("Primary action stays reachable",bounds.top>=0&&bounds.bottom<=popup.getHeight());
                a.onBackPressed();idleUi();assertNull(field(a,"sectionDialog"));
            }
        } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f); }
    }
    @Test @Config(qualifiers="w1024dp-h900dp-mdpi")
    public void wideLayoutCapsReadingWidth() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();View root=a.getWindow().getDecorView();screenshot(root,"tablet-light");
            assertTrue(((View)field(a,"page")).getWidth()<=DesignTokens.CONTENT_WIDTH);
            openSection(a,"Modell");screenshot(a,"tablet-model-popup");
            assertEquals(DesignTokens.CONTENT_WIDTH,((Dialog)field(a,"sectionDialog")).getWindow().getAttributes().width);
        }
    }
    @Test @Config(qualifiers="w375dp-h812dp-night-mdpi")
    public void illustratedSuggestionsFillDraftAndKeepComposerReachable() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;app.notifyUi();
            View root=a.getWindow().getDecorView();screenshot(root,"ready-dark-prompts");
            TextView suggestion=find(root,"Einfach verstehen");assertNotNull(suggestion);
            assertTrue(suggestion.performClick());
            android.widget.EditText input=(android.widget.EditText)field(a,"input");
            assertEquals("Erkläre mir ein schwieriges Thema einfach: ",input.getText().toString());
            assertTrue(((View)field(a,"send")).isEnabled());
            find(root,"Eine Idee entwickeln").performClick();
            assertEquals("Hilf mir, eine Idee weiterzuentwickeln: ",input.getText().toString());
            View composer=(View)field(a,"composerGroup");int[] location=new int[2];composer.getLocationOnScreen(location);
            assertTrue("Composer stays on screen",location[1]>=0&&location[1]+composer.getHeight()<=root.getHeight());
            assertNull(described(root,"Hauptnavigation"));
        }
    }
    @Test public void popupRecreationAndDismissalPreserveConversationAndDraft() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.messages.add(new ChatStore.Message(1,"assistant","Meine gespeicherte Antwort",""));app.notifyUi();
            ((android.widget.EditText)field(a,"input")).setText("Mein ungesendeter Entwurf");
            openSection(a,"Modell");Dialog before=(Dialog)field(a,"sectionDialog");controller.recreate();a=controller.get();
            Dialog reopened=(Dialog)field(a,"sectionDialog");assertFalse(before.isShowing());assertTrue(reopened.isShowing());assertEquals(1,field(a,"activeScreen"));
            assertEquals("Mein ungesendeter Entwurf",((android.widget.EditText)field(a,"input")).getText().toString());
            assertNotNull(find(a.getWindow().getDecorView(),"Meine gespeicherte Antwort"));
            reopened.onBackPressed();idleUi();assertNull(field(a,"sectionDialog"));assertEquals(0,field(a,"activeScreen"));
            openSection(a,"Leistung");Dialog performance=(Dialog)field(a,"sectionDialog");
            android.view.MotionEvent outside=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_OUTSIDE,-10,-10,0);
            try{assertTrue(performance.onTouchEvent(outside));}finally{outside.recycle();}
            idleUi();assertNull(field(a,"sectionDialog"));assertEquals("Mein ungesendeter Entwurf",((android.widget.EditText)field(a,"input")).getText().toString());
        }
    }
    @Test @Config(shadows=BackgroundInferenceTest.Engine.class)
    public void popupShowsLiveBusyFeedbackAndCancel() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            openSection(a,"Modell");Dialog popup=(Dialog)field(a,"sectionDialog");
            app.busy=true;app.status="Modell wird geladen";app.notifyUi();
            assertSame(popup,field(a,"sectionDialog"));assertEquals(View.VISIBLE,((View)field(a,"popupStatusCard")).getVisibility());
            assertEquals("Modell wird geladen",((TextView)field(a,"popupState")).getText().toString());
            ((View)field(a,"popupAction")).performClick();assertTrue(app.cancelled);
            app.busy=false;app.error="Datei nicht lesbar";app.notifyUi();
            assertNotNull(find(popup.getWindow().getDecorView(),"Vorgang nicht abgeschlossen"));
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
            TextView live=(TextView)field(a,"liveAnswer");assertEquals("Antwort wird vorbereitet …",live.getText().toString());
            answer.text="Pflanzen nutzen Licht.";app.notifyUi();
            assertSame("Streaming must reuse the text view",live,field(a,"liveAnswer"));assertEquals(answer.text,live.getText().toString());
            assertTrue(input.isEnabled());assertEquals("Meine nächste Frage",input.getText().toString());
            app.busy=false;app.error="Datei nicht lesbar.";app.notifyUi();assertNotNull(find(a.getWindow().getDecorView(),"Vorgang nicht abgeschlossen"));
            screenshot(a.getWindow().getDecorView(),"conversation-error");
        }
    }
    @Test public void streamingUpdatesOnlyChangedAnswerWithoutRebindingOtherControls() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.loaded=true;app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            app.messages.add(new ChatStore.Message(1,"user","Frage",""));
            ChatStore.Message answer=new ChatStore.Message(2,"assistant","Hallo","");app.messages.add(answer);
            app.busy=true;app.status="Antwort wird geschrieben …";app.notifyUi();
            TextView body=(TextView)field(a,"liveAnswer"),subtitle=(TextView)field(a,"subtitle");
            int[] answerWrites={0},headerWrites={0};
            body.addTextChangedListener(countWrites(answerWrites));subtitle.addTextChangedListener(countWrites(headerWrites));
            CharSequence original=body.getText();
            for(int i=0;i<20;i++)app.notifyStreamUi();
            assertSame("Unchanged text must keep its buffer and selection",original,body.getText());
            assertEquals(0,answerWrites[0]);
            for(int i=0;i<20;i++){answer.text+=" Wort";app.notifyStreamUi();}
            assertEquals(answer.text,body.getText().toString());assertEquals(20,answerWrites[0]);
            assertEquals("Streaming must not rebind the header",0,headerWrites[0]);
            controller.pause().stop();assertNull(app.listener);assertNull(app.streamListener);
            answer.text+=" im Hintergrund";app.notifyStreamUi();
            assertEquals("Stopped activities must not receive streamed text",20,answerWrites[0]);
            controller.start().resume().visible();
            assertEquals(answer.text,((TextView)field(a,"liveAnswer")).getText().toString());
        }
    }
    private static android.text.TextWatcher countWrites(int[] count) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int length,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {}
            public void afterTextChanged(android.text.Editable text) {count[0]++;}
        };
    }
    @Test public void decorativeFramesKeepMovingDuringInferenceAndStopWithActivity() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            controller.windowFocusChanged(true);
            PulsingBorder composer=(PulsingBorder)field(a,"composerAura"),ambient=(PulsingBorder)field(a,"ambientBackdrop");
            assertTrue(composer.isFrameScheduled());assertTrue(ambient.isFrameScheduled());
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();app.busy=true;app.notifyUi();
            assertEquals(PulsingBorder.State.GENERATING,composer.getState());
            assertTrue("The work light must stay animated",composer.isFrameScheduled());
            assertTrue(ambient.isFrameScheduled());
            float phase=(float)field(composer,"phase"),ambientPhase=(float)field(ambient,"phase");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertTrue("Work colours continue moving",(float)field(composer,"phase")>phase);
            assertTrue("Ambient pools continue moving",(float)field(ambient,"phase")>ambientPhase);
            int[] frames=countLightFrames(composer,ambient);
            assertTrue("Steady work border has a 24 fps ceiling",frames[0]>0&&frames[0]<=24);
            assertTrue("Slow ambient motion has a 12 fps ceiling",frames[1]>0&&frames[1]<=12);
            assertTrue("Ambient surfaces redraw less often than the border",frames[1]<frames[0]);
            controller.windowFocusChanged(false);controller.windowFocusChanged(true);
            assertTrue(composer.isFrameScheduled());
            controller.pause().stop();assertFalse(composer.isFrameScheduled());assertFalse(ambient.isFrameScheduled());
            phase=(float)field(composer,"phase");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("A stopped activity must not spend frames",phase,(float)field(composer,"phase"),0);
            controller.start().resume().visible().windowFocusChanged(true);
            assertTrue(composer.isFrameScheduled());assertTrue(ambient.isFrameScheduled());
            app.busy=false;app.notifyUi();assertTrue(composer.isFrameScheduled());assertTrue(ambient.isFrameScheduled());
        }
    }
    private static int[] countLightFrames(PulsingBorder first,PulsingBorder second) throws Exception {
        PulsingBorder[] lights={first,second};long[] previous={(long)field(first,"lastFrame"),(long)field(second,"lastFrame")};
        int[] frames={0,0};
        for(int step=0;step<100;step++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));
            for(int i=0;i<lights.length;i++) {
                long now=(long)field(lights[i],"lastFrame");
                if(now!=previous[i]){frames[i]++;previous[i]=now;}
            }
        }
        return frames;
    }
    @Test public void workLightBoundsDrawingPassesAndClipsTransparentHaloAreas() {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        DesignTokens tokens=new DesignTokens(context.getResources());
        for(PulsingBorder.Mode mode:PulsingBorder.Mode.values()) {
            PulsingBorder light=new PulsingBorder(context,tokens,30,1,mode);
            light.layout(0,0,824,mode==PulsingBorder.Mode.AMBIENT?1784:160);
            light.setState(PulsingBorder.State.GENERATING);
            Bitmap bitmap=Bitmap.createBitmap(light.getWidth(),light.getHeight(),Bitmap.Config.ARGB_8888);
            DrawingBudgetCanvas canvas=new DrawingBudgetCanvas(bitmap);
            light.onDraw(canvas);
            if(mode==PulsingBorder.Mode.AMBIENT)assertEquals(2,canvas.circles);
            else {
                assertTrue("Steady work glow must stay below the old 18 effect strokes",canvas.paths<=14);
                assertEquals("Each spotlight excludes its transparent surroundings",4,canvas.haloClips);
            }
            bitmap.recycle();
        }
    }
    private static final class DrawingBudgetCanvas extends Canvas {
        int paths,circles,haloClips;
        DrawingBudgetCanvas(Bitmap bitmap){super(bitmap);}
        @Override public void drawPath(android.graphics.Path path,android.graphics.Paint paint){paths++;super.drawPath(path,paint);}
        @Override public void drawCircle(float x,float y,float radius,android.graphics.Paint paint){circles++;super.drawCircle(x,y,radius,paint);}
        @Override public boolean clipRect(float left,float top,float right,float bottom){haloClips++;return super.clipRect(left,top,right,bottom);}
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
            for(int bg:new int[]{t.background,t.surface,t.raised})for(int fg:new int[]{t.text,t.muted,t.primary,t.error,t.warning})assertTrue("Text contrast below AA",contrast(fg,bg)>=4.5);
            assertTrue("Badge text contrast below AA",contrast(t.primary,t.accentSurface)>=4.5);
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
            MainActivity a=controller.get();openSection(a,"Modell");
            View screen=(View)field(a,"popupHost");
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
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void nativeGlowTracksWorkCompletionErrorsAndCancellation() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();app.loaded=true;app.notifyUi();
            PulsingBorder glow=(PulsingBorder)field(a,"composerAura");
            assertEquals(PulsingBorder.State.IDLE,glow.getState());
            assertNotNull(described(a.getWindow().getDecorView(),"Modell und Einstellungen öffnen"));
            app.messages.add(new ChatStore.Message(1,"user","Wie entsteht eine gute Idee?",""));
            ChatStore.Message answer=new ChatStore.Message(2,"assistant","","");app.messages.add(answer);
            app.busy=true;app.status="Bonsai denkt nach …";app.notifyUi();
            assertEquals(PulsingBorder.State.GENERATING,glow.getState());
            TextView live=(TextView)field(a,"liveAnswer");
            answer.text="Eine gute Idee beginnt mit einer guten Frage.";app.notifyUi();
            assertSame(live,field(a,"liveAnswer"));screenshot(a,"glow-generating-dark");
            app.busy=false;app.notifyUi();
            assertEquals(PulsingBorder.State.DONE,glow.getState());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            assertEquals(PulsingBorder.State.IDLE,glow.getState());
            app.busy=true;app.notifyUi();app.busy=false;app.error="Testfehler";app.notifyUi();
            assertEquals("Errors never show a success glow",PulsingBorder.State.IDLE,glow.getState());
            app.error="";app.busy=true;app.cancelled=false;app.notifyUi();
            app.cancelled=true;app.busy=false;app.notifyUi();
            assertEquals("Cancellation never shows a success glow",PulsingBorder.State.IDLE,glow.getState());
        }
    }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void completedGlowCanBeCapturedInItsOwnNativeWindow() throws Exception {
        // Native offscreen captures of successive states may reuse stale text RenderNodes.
        // Capture completion in a fresh Activity, independently of the generating image.
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();app.loaded=true;
            app.messages.add(new ChatStore.Message(1,"user","Wie entsteht eine gute Idee?",""));
            app.messages.add(new ChatStore.Message(2,"assistant","Eine gute Idee beginnt mit einer guten Frage.",""));
            app.busy=true;app.notifyUi();app.busy=false;app.notifyUi();
            assertEquals(PulsingBorder.State.DONE,((PulsingBorder)field(a,"composerAura")).getState());
            screenshot(a,"glow-done-dark");
        }
    }
    @Test public void nativeGlowStopsWhenHiddenOrMotionIsDisabled() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();PulsingBorder glow=(PulsingBorder)field(a,"composerAura");
            controller.windowFocusChanged(true);glow.setActive(true);
            assertTrue(glow.isFrameScheduled());
            glow.setVisibility(View.GONE);assertFalse(glow.isFrameScheduled());
            glow.setVisibility(View.VISIBLE);assertTrue(glow.isFrameScheduled());
            described(a.getWindow().getDecorView(),"Menü öffnen").performClick();
            assertFalse("Underlying chat does not animate behind the drawer",glow.isFrameScheduled());
            assertFalse(((PulsingBorder)field(a,"welcomeAura")).isFrameScheduled());
            a.onBackPressed();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertTrue(glow.isFrameScheduled());
            controller.pause().stop();assertFalse(glow.isFrameScheduled());
            controller.start().resume().visible().windowFocusChanged(true);
            assertTrue(glow.isFrameScheduled());
            java.lang.reflect.Method durationScale=android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale",float.class);
            durationScale.setAccessible(true);durationScale.invoke(null,0f);
            try {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
                assertFalse(glow.isFrameScheduled());
                glow.setState(PulsingBorder.State.GENERATING);
                screenshot(a,"glow-reduced-motion");assertFalse(glow.isFrameScheduled());
            } finally {durationScale.invoke(null,1f);}
            org.robolectric.shadows.ShadowPowerManager power=org.robolectric.Shadows.shadowOf((android.os.PowerManager)a.getSystemService(android.content.Context.POWER_SERVICE));
            power.setIsPowerSaveMode(true);glow.setActive(true);assertFalse(glow.isFrameScheduled());
            power.setIsPowerSaveMode(false);glow.setActive(true);assertTrue(glow.isFrameScheduled());
        }
    }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void appWideLightingTracksWorkAndReleasesReplacedPopupSurfaces() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.loaded=true;app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            app.messages.add(new ChatStore.Message(1,"user","Wie entsteht eine gute Idee?",""));
            app.messages.add(new ChatStore.Message(2,"assistant","Eine gute Idee beginnt mit einer guten Frage.",""));
            app.busy=true;app.status="Modell wird vorbereitet …";app.notifyUi();controller.windowFocusChanged(true);
            PulsingBorder backdrop=(PulsingBorder)field(a,"ambientBackdrop");
            PulsingBorder answer=(PulsingBorder)field(a,"answerAura");
            assertEquals(PulsingBorder.Mode.AMBIENT,backdrop.getMode());
            assertEquals(PulsingBorder.Mode.RAIL,answer.getMode());
            assertEquals(PulsingBorder.State.GENERATING,backdrop.getState());
            assertEquals(PulsingBorder.State.GENERATING,answer.getState());
            assertTrue(backdrop.isFrameScheduled());assertTrue(answer.isFrameScheduled());
            View modelPopup=openSection(a,"Modell");
            focusWindow(modelPopup);
            PulsingBorder model=(PulsingBorder)field(a,"modelAura");
            assertEquals(PulsingBorder.State.GENERATING,model.getState());
            assertTrue("New pop-up surfaces keep the animated work state",model.isFrameScheduled());
            assertEquals(PulsingBorder.State.GENERATING,((PulsingBorder)field(a,"primaryAura")).getState());
            assertFalse("The covered app backdrop pauses",backdrop.isFrameScheduled());
            assertFalse("The covered answer pauses",answer.isFrameScheduled());
            assertNotNull(find(modelPopup,"Antworten anpassen"));screenshot(a,"app-light-model-dark");
            focusWindow(openSection(a,"Leistung"));
            assertFalse("Removed model surfaces leave the shared frame clock",model.isFrameScheduled());
            PulsingBorder performance=(PulsingBorder)field(a,"performanceAura");
            assertEquals(PulsingBorder.State.GENERATING,performance.getState());
            assertTrue(performance.isFrameScheduled());
            app.busy=false;app.notifyUi();
            assertEquals(PulsingBorder.State.DONE,performance.getState());
            assertEquals(PulsingBorder.State.DONE,((PulsingBorder)field(a,"primaryAura")).getState());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            assertEquals(PulsingBorder.State.IDLE,performance.getState());
            assertEquals("Removed surfaces no longer receive state updates",PulsingBorder.State.GENERATING,model.getState());
            controller.pause().stop();assertFalse(performance.isFrameScheduled());
        }
    }
    private static void focusWindow(View decor) {
        Object root=org.robolectric.util.ReflectionHelpers.callInstanceMethod(decor,"getViewRootImpl");
        org.robolectric.shadows.ShadowViewRootImpl shadow=org.robolectric.shadow.api.Shadow.extract(root);
        shadow.callWindowFocusChanged(true);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void selectedChatLightKeepsNavigationAndStopsAfterDrawerDismissal() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.loaded=true;long chat=app.store.createChat("Gedanken sammeln");
            app.store.add(chat,"user","Eine neue Idee","");app.store.add(chat,"assistant","Lass uns gemeinsam anfangen.","");app.selectChat(chat);
            described(a.getWindow().getDecorView(),"Menü öffnen").performClick();
            PulsingBorder selected=(PulsingBorder)field(a,"selectedChatAura");assertNotNull(selected);
            assertNotNull(find(selected,"Gedanken sammeln"));
            assertEquals(PulsingBorder.State.IDLE,selected.getState());screenshot(a,"app-light-sidebar-dark");
            assertEquals(chat,app.currentChat);
            a.onBackPressed();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertFalse(selected.isFrameScheduled());assertNull(field(a,"selectedChatAura"));
            assertEquals(chat,app.currentChat);
        }
    }
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi")
    public void performanceLightingShowsAnActiveMeasurementWithoutChangingItsControls() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity a=controller.get();BonsaiApp app=(BonsaiApp)a.getApplication();
            app.loaded=true;app.busy=true;app.optimizing=true;app.optimizingFraction=.42f;
            app.status="Optimierung läuft …";app.optimizingProgress="Profil wird gemessen";
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();app.notifyUi();
            View popup=openSection(a,"Leistung");
            assertNotNull(find(popup,"Profil wird gemessen"));assertTrue(find(popup,"Optimierung abbrechen").isEnabled());
            assertEquals(PulsingBorder.State.GENERATING,((PulsingBorder)field(a,"performanceAura")).getState());
            screenshot(a,"app-light-performance-dark");
            app.busy=false;app.optimizing=false;
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
            openSection(a,"Modell");
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
            assertSame(request,app.modelApproval);assertEquals(1,field(next,"activeScreen"));assertTrue(((Dialog)field(next,"sectionDialog")).isShowing());
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
            app.loaded=true;View root=openSection(a,"Modell");
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
            root=openSection(a,"Leistung");
            assertNotNull(find(root,"jedem Temperaturstatus"));assertNotNull(find(root,"Hintergrund weiter"));
            assertTrue(((View)field(a,"performanceStart")).isEnabled());
        }
    }
    @Test @Config(shadows=BackgroundInferenceTest.Engine.class)
    public void grammarSettingPersistsWithoutReloadAndIsDisabledWhileBusy() throws Exception {
        BackgroundInferenceTest.Engine.loads=0;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity=controller.get();BonsaiApp app=(BonsaiApp)activity.getApplication();
            java.nio.file.Files.write(app.modelFile().toPath(),new byte[]{'G','G','U','F'});
            app.loaded=true;app.prefs.edit().putString("optimizedFor","keep-profile").apply();
            View root=openSection(activity,"Modell");
            android.widget.CheckBox grammar=(android.widget.CheckBox)find(root,"GBNF für Tool-Aufrufe");
            assertFalse(grammar.isChecked());assertFalse(app.gbnfTools());
            grammar.setChecked(true);find(root,"Einstellungen speichern").performClick();
            assertTrue(app.gbnfTools());assertEquals(0,BackgroundInferenceTest.Engine.loads);
            assertEquals("keep-profile",app.prefs.getString("optimizedFor",""));
            root=openSection(activity,"Modell");
            assertTrue(((android.widget.CheckBox)find(root,"GBNF für Tool-Aufrufe")).isChecked());
            app.busy=true;root=openSection(activity,"Modell");
            assertFalse(find(root,"GBNF für Tool-Aufrufe").isEnabled());
            app.busy=false;
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
