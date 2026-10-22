package de.bonsai.offline;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Render the actual Android assets and screens, including native vector rasterization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconVisualTest {
    private static final String[] KINDS={"menu","edit","plus","send","stop","close","model","performance","history","archive","book","idea","copy","refresh","speed","count","time","info"};
    private static final String[] LABELS={"Menü","Neuer Chat","Plus","Senden","Stop","Schließen","Modell","Leistung","Chatverlauf","Archiv","Verstehen / Buch","Idee","Kopieren","Neu generieren","Geschwindigkeit","Tokenanzahl","Zeit","Information"};
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void idle(){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));}
    private static View described(View v,String text){
        if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=described(((ViewGroup)v).getChildAt(i),text);if(f!=null)return f;}return null;
    }
    private static TextView find(View v,String text){
        if(v instanceof TextView&&((TextView)v).getText().toString().equals(text))return(TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView f=find(((ViewGroup)v).getChildAt(i),text);if(f!=null)return f;}return null;
    }
    private static void write(Bitmap b,String name)throws Exception{
        File dir=new File("build/icon-captures");dir.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();
    }
    private static void capture(MainActivity a,String name)throws Exception{
        idle();View root=a.getWindow().getDecorView();int w=824,h=1784;
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);root.draw(c);
        Dialog popup=(Dialog)field(a,"sectionDialog");if(popup!=null){
            View decor=popup.getWindow().getDecorView();int pw=popup.getWindow().getAttributes().width,ph=popup.getWindow().getAttributes().height;
            decor.measure(View.MeasureSpec.makeMeasureSpec(pw,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ph,View.MeasureSpec.EXACTLY));decor.layout(0,0,pw,ph);
            c.drawColor(0x8c000000);c.save();c.translate((w-pw)/2f,(h-ph)/2f);decor.draw(c);c.restore();
        }write(b,name);
    }
    private static void assertTarget(View v){assertNotNull(v);float d=v.getResources().getDisplayMetrics().density;assertTrue(v.getWidth()/d>=48);assertTrue(v.getHeight()/d>=48);}
    private void surfaces(boolean dark)throws Exception{
        String theme=dark?"dark":"light";
        try(ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup().visible()){
            MainActivity a=ctl.get();BonsaiApp app=(BonsaiApp)a.getApplication();app.loaded=true;app.notifyUi();idle();capture(a,"suggestions-"+theme);
            long chat=app.store.createChat("Gedanken zum Wald");app.store.add(chat,"user","Wie speichert ein Wald Wasser?","");
            app.store.add(chat,"assistant","Wurzeln, Humus und Moose nehmen Regen auf und geben ihn langsam wieder ab.","11,6 Tokens/s · 40 Tokens · 1,8 s bis zum ersten Token");app.selectChat(chat);app.loaded=true;app.notifyUi();
            ((android.widget.EditText)field(a,"input")).setText("Erkläre mir mehr dazu");capture(a,"chat-actions-"+theme);
            View root=a.getWindow().getDecorView();assertTarget(described(root,"Nachricht senden"));assertTarget(described(root,"Antwort kopieren"));
            TextView detail=(TextView)field(a,"liveDetail");assertEquals("11,6 Tokens/s · 40 Tokens · 1,8 s bis zum ersten Token",detail.getContentDescription().toString());
            app.busy=true;app.notifyUi();capture(a,"chat-stop-"+theme);assertTarget(described(root,"Vorgang abbrechen"));
            app.busy=false;app.notifyUi();described(root,"Menü öffnen").performClick();capture(a,"sidebar-"+theme);
            assertTarget(described(root,"Menü schließen"));
            find((View)field(a,"drawer"),"Archivierte Chats").performClick();capture(a,"archive-"+theme);
            described(root,"Menü schließen").performClick();idle();
            for(String title:new String[]{"Modell","Leistung"}){
                described(root,"Menü öffnen").performClick();find((View)field(a,"drawer"),title).performClick();idle();
                capture(a,(title.equals("Modell")?"model-":"performance-")+theme);
                Dialog popup=(Dialog)field(a,"sectionDialog");assertTarget(described(popup.getWindow().getDecorView(),"Pop-up schließen"));
                described(popup.getWindow().getDecorView(),"Pop-up schließen").performClick();idle();
            }
        }
    }
    @Test public void nativeLightSurfaces()throws Exception{surfaces(false);}
    @Test @Config(qualifiers="w412dp-h892dp-night-xhdpi") public void nativeDarkSurfaces()throws Exception{surfaces(true);}
    @Test public void contactSheetsAndRuntimeTint()throws Exception{
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        for(boolean dark:new boolean[]{false,true}){
            Bitmap b=Bitmap.createBitmap(640,1064,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);
            int bg=dark?0xff141419:0xfffafafc,fg=dark?0xfff5f5f7:0xff1b1b20,muted=dark?0xffa6a6b0:0xff62626e;
            c.drawColor(bg);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(fg);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(28);c.drawText("BONSAI / ICONS",32,54,p);
            p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setColor(muted);p.setTextSize(13);c.drawText("18 Originalmotive · 24er-Raster · 1,75er-Strich",32,84,p);
            for(int i=0;i<3;i++){p.setTextSize(12);c.drawText(new int[]{16,24,28}[i]+" dp",360+i*96,124,p);}
            for(int row=0;row<KINDS.length;row++){
                int cy=156+row*44;p.setColor(fg);p.setTextSize(14);c.drawText(LABELS[row],32,cy+5,p);
                for(int col=0;col<3;col++){
                    int size=new int[]{16,24,28}[col];Drawable icon=BonsaiIcons.icon(context,KINDS[row],fg,size);int x=376+col*96-size/2;
                    icon.setBounds(x,cy-size/2,x+size,cy+size/2);icon.draw(c);
                }
            }
            p.setColor(muted);p.setTextSize(12);c.drawText("Native Android VectorDrawables · 1 dp = 1 px in dieser Datei",32,994,p);c.drawText("SVG-Master: currentColor · Zustände und Lichtsäume: Container",32,1016,p);
            write(b,"contact-"+(dark?"dark":"light"));
        }
        for(String kind:KINDS){
            Bitmap b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);Drawable icon=BonsaiIcons.icon(context,kind,0xff00aa55,48);icon.draw(new Canvas(b));
            int count=0;for(int y=0;y<48;y++)for(int x=0;x<48;x++){int px=b.getPixel(x,y);if(android.graphics.Color.alpha(px)>128){assertTrue(android.graphics.Color.green(px)>android.graphics.Color.red(px));count++;}}
            assertTrue("Icon renders with runtime tint: "+kind,count>12);icon.setAlpha(0);b.eraseColor(0);icon.draw(new Canvas(b));
            for(int y=0;y<48;y++)for(int x=0;x<48;x++)assertEquals(0,android.graphics.Color.alpha(b.getPixel(x,y)));b.recycle();
        }
    }
}
