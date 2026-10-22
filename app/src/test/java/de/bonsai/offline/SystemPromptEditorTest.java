package de.bonsai.offline;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SystemPromptEditorTest {
    private static TextView find(View root,String text) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(text))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            TextView found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private static EditText field(View root,String description) {
        if(root instanceof EditText && description.contentEquals(root.getContentDescription()))return (EditText)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            EditText found=field(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    private static void click(SystemPromptEditor editor,String label) { assertTrue(find(editor.getWindow().getDecorView(),label).performClick());Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void confirm(int button) {
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(button).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static Object member(Object target,String name) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static View described(View root,String description) {
        if(description.equals(root.getContentDescription()==null?null:root.getContentDescription().toString()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found=described(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    private static void capture(SystemPromptEditor editor) throws Exception {
        View root=editor.getWindow().getDecorView();int w=editor.getWindow().getAttributes().width,h=editor.getWindow().getAttributes().height;
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image));
        java.io.File directory=new java.io.File("build/ui-captures");directory.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,"system-prompt.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
        image.recycle();
    }
    @Test public void modelMenuOpensEditorAndActivityRecreationPreservesDraftButRelocksTools() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity=controller.get();described(activity.getWindow().getDecorView(),"Menü öffnen").performClick();
            find((View)member(activity,"drawer"),"Modell").performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            android.app.Dialog section=(android.app.Dialog)member(activity,"sectionDialog");find(section.getWindow().getDecorView(),"Systemprompt bearbeiten").performClick();
            SystemPromptEditor editor=(SystemPromptEditor)member(activity,"systemPromptEditor");assertTrue(editor.isShowing());capture(editor);
            field(editor.getWindow().getDecorView(),"Eigene Vorgaben").setText("Entwurf nach Drehung");
            click(editor,"Tool-Anweisungen entsperren");confirm(AlertDialog.BUTTON_POSITIVE);field(editor.getWindow().getDecorView(),"Tool-Anweisungen").setText("Tool-Entwurf nach Drehung");
            controller.recreate();editor=(SystemPromptEditor)member(controller.get(),"systemPromptEditor");assertTrue(editor.isShowing());
            assertEquals("Entwurf nach Drehung",field(editor.getWindow().getDecorView(),"Eigene Vorgaben").getText().toString());
            EditText tools=field(editor.getWindow().getDecorView(),"Tool-Anweisungen");assertEquals("Tool-Entwurf nach Drehung",tools.getText().toString());assertNull(tools.getKeyListener());
            assertFalse(SystemPrompt.customized(((BonsaiApp)controller.get().getApplication()).prefs));
            click(editor,"Abbrechen");assertNull(member(controller.get(),"systemPromptEditor"));
        }
    }
    @Test public void warningGatesToolEditingAndCancelNeverChangesPreferences() {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            BonsaiApp app=(BonsaiApp)activity.get().getApplication();
            SystemPromptEditor editor=new SystemPromptEditor(activity.get(),app,null);editor.show();View root=editor.getWindow().getDecorView();
            EditText tools=field(root,"Tool-Anweisungen");assertNull(tools.getKeyListener());
            assertFalse(find(root,"Tool-Anweisungen auf Standard setzen").isEnabled());
            click(editor,"Tool-Anweisungen entsperren");
            confirm(AlertDialog.BUTTON_NEGATIVE);assertNull(tools.getKeyListener());
            click(editor,"Tool-Anweisungen entsperren");
            confirm(AlertDialog.BUTTON_POSITIVE);
            assertNotNull(tools.getKeyListener());assertTrue(find(root,"Tool-Anweisungen auf Standard setzen").isEnabled());
            tools.setText("Tool-Entwurf");field(root,"Eigene Vorgaben").setText("Entwurf");click(editor,"Abbrechen");
            assertEquals(ToolLoop.SYSTEM_PROMPT,app.systemPrompt(false));assertFalse(SystemPrompt.customized(app.prefs));
        }
    }
    @Test public void personalPromptSavesWithoutUnlockAndReopeningLocksCustomizedTools() {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            BonsaiApp app=(BonsaiApp)activity.get().getApplication();SystemPromptEditor editor=new SystemPromptEditor(activity.get(),app,null);editor.show();
            field(editor.getWindow().getDecorView(),"Eigene Vorgaben").setText("Antworte knapp.");click(editor,"Prompt speichern");
            assertFalse(editor.isShowing());assertEquals("Antworte knapp.",SystemPrompt.instructions(app.prefs));assertEquals(SystemPrompt.DEFAULT_TOOLS,SystemPrompt.tools(app.prefs));
            editor=new SystemPromptEditor(activity.get(),app,null);editor.show();click(editor,"Tool-Anweisungen entsperren");
            confirm(AlertDialog.BUTTON_POSITIVE);
            field(editor.getWindow().getDecorView(),"Tool-Anweisungen").setText("Geänderte Tool-Regeln");click(editor,"Prompt speichern");
            editor=new SystemPromptEditor(activity.get(),app,null);editor.show();
            EditText tools=field(editor.getWindow().getDecorView(),"Tool-Anweisungen");assertEquals("Geänderte Tool-Regeln",tools.getText().toString());assertNull(tools.getKeyListener());
            editor.dismiss();
        }
    }
    @Test public void restoredDraftSurvivesButToolsNeedFreshWarningAndResetWorks() {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            BonsaiApp app=(BonsaiApp)activity.get().getApplication();Bundle draft=new Bundle();draft.putString("instructions","Entwurf");draft.putString("tools","Eigene Tools");
            SystemPromptEditor editor=new SystemPromptEditor(activity.get(),app,draft);editor.show();
            assertEquals("Entwurf",editor.draft().getString("instructions"));click(editor,"Prompt speichern");
            assertTrue(editor.isShowing());assertFalse(SystemPrompt.customized(app.prefs));
            confirm(AlertDialog.BUTTON_POSITIVE);
            click(editor,"Vorgaben auf Standard setzen");click(editor,"Tool-Anweisungen auf Standard setzen");click(editor,"Prompt speichern");
            assertFalse(editor.isShowing());assertFalse(SystemPrompt.customized(app.prefs));
        }
    }
}
