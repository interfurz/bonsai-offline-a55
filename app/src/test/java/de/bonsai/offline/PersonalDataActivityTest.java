package de.bonsai.offline;

import android.app.AlertDialog;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class PersonalDataActivityTest {
    private static TextView find(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().equals(text))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static EditText edit(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=edit(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    @Test public void explicitMemoryEditAndConfirmedDeleteWorkWithoutAModel() {
        try(ActivityController<PersonalDataActivity> controller=Robolectric.buildActivity(PersonalDataActivity.class).setup()) {
            PersonalDataActivity activity=controller.get();BonsaiApp app=(BonsaiApp)activity.getApplication();
            assertTrue(app.store.memories("").isEmpty());assertFalse(app.loaded);
            find(activity.getWindow().getDecorView(),"Erinnerung hinzufügen").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            EditText key=edit(dialog.getWindow().getDecorView());assertNotNull(key);
            android.view.ViewGroup parent=(android.view.ViewGroup)key.getParent();
            EditText value=(EditText)parent.getChildAt(1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());
            key.setText("name");value.setText("Alex");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals("Alex",app.store.recall("name"));assertEquals("Manuell",app.store.memories("").get(0).source);
            find(activity.getWindow().getDecorView(),"Löschen: name").performClick();
            dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertEquals("Alex",app.store.recall("name"));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals("Alex",app.store.recall("name"));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            find(activity.getWindow().getDecorView(),"Löschen: name").performClick();
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(app.store.recall("name"));
        }
    }
}
