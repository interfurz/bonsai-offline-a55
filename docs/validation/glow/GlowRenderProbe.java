package de.bonsai.offline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GlowRenderProbe {
    private static final File OUTPUT=new File("build/glow-profile");
    private static final class Renderer {
        final View view;final Field phase;final Canvas canvas;final Bitmap bitmap;
        Renderer(View view,int height) throws Exception {
            this.view=view;view.layout(0,0,824,height);
            phase=view.getClass().getDeclaredField("phase");phase.setAccessible(true);
            bitmap=Bitmap.createBitmap(824,height,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);
        }
        void frame(float value) throws Exception {
            phase.setFloat(view,value);canvas.drawColor(Color.rgb(10,10,14),PorterDuff.Mode.SRC);
            if(view instanceof PulsingBorder)((PulsingBorder)view).onDraw(canvas);
            else ((BaselinePulsingBorder)view).onDraw(canvas);
        }
        double timed() throws Exception {
            long begin=System.nanoTime();
            for(int i=0;i<24;i++)frame(i/24f);
            return (System.nanoTime()-begin)/24e6;
        }
        void capture(String name) throws Exception {
            frame(.25f);
            try(FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        }
    }
    @Test public void compareSameSizedLightFrames() throws Exception {
        OUTPUT.mkdirs();JSONArray results=new JSONArray();
        android.content.Context context=RuntimeEnvironment.getApplication();DesignTokens tokens=new DesignTokens(context.getResources());
        for(String mode:new String[]{"BORDER","RAIL","AMBIENT"})for(String state:new String[]{"IDLE","GENERATING","DONE"}) {
            int height=mode.equals("AMBIENT")?1784:mode.equals("RAIL")?480:160;
            PulsingBorder current=new PulsingBorder(context,tokens,30,1,PulsingBorder.Mode.valueOf(mode));
            BaselinePulsingBorder baseline=new BaselinePulsingBorder(context,tokens,30,1,BaselinePulsingBorder.Mode.valueOf(mode));
            current.setState(PulsingBorder.State.valueOf(state));baseline.setState(BaselinePulsingBorder.State.valueOf(state));
            Renderer old=new Renderer(baseline,height),now=new Renderer(current,height);
            old.timed();now.timed();double[] before=new double[7],after=new double[7];
            for(int sample=0;sample<7;sample++) {
                if(sample%2==0){before[sample]=old.timed();after[sample]=now.timed();}
                else {after[sample]=now.timed();before[sample]=old.timed();}
            }
            JSONArray beforeSamples=new JSONArray(before),afterSamples=new JSONArray(after);
            Arrays.sort(before);Arrays.sort(after);
            results.put(new JSONObject().put("mode",mode).put("state",state).put("width_px",824).put("height_px",height)
                .put("baseline_ms_samples",beforeSamples).put("optimized_ms_samples",afterSamples)
                .put("baseline_median_ms",before[3]).put("optimized_median_ms",after[3]));
            old.capture(mode+"-"+state+"-before");now.capture(mode+"-"+state+"-after");
            old.bitmap.recycle();now.bitmap.recycle();
        }
        JSONObject report=new JSONObject().put("baseline","2dfe2c4").put("renderer","Robolectric 4.14.1 native Skia software Canvas, host CPU")
            .put("method","7 alternating paired samples of 24 phase steps, per renderer 24 warm-up frames; identical pixels and state; median wall time per frame")
            .put("limits","No Android GPU, display/compositor, model inference, device power or A55 measurement. Steady-state only, without crossfades or child views.")
            .put("results",results);
        Files.write(new File(OUTPUT,"render-cost.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
