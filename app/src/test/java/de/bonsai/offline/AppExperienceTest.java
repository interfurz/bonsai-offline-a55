package de.bonsai.offline;

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
