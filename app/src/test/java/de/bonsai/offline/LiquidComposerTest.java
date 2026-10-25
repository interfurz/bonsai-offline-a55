package de.bonsai.offline;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class LiquidComposerTest {
    private static void touch(View view,int action) {
        MotionEvent event=MotionEvent.obtain(0,20,action,50,50,0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static void idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(800));
    }
    @Test public void liquidTouchPreservesChildClicksAndCancellation() {
        try(var controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity=controller.get();
            LiquidComposer composer=new LiquidComposer(activity,new DesignTokens(activity.getResources()));
            Button action=new Button(activity);int[] clicks={0};
            action.setOnClickListener(v->clicks[0]++);composer.addView(action);
            activity.setContentView(composer);
            composer.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.EXACTLY));
            composer.layout(0,0,300,160);
            touch(composer,MotionEvent.ACTION_DOWN);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(Duration.ofMillis(80));
            assertTrue("Touch visibly deforms the surface",composer.getScaleY()<1f);
            touch(composer,MotionEvent.ACTION_UP);idle();
            assertEquals(1,clicks[0]);
            touch(composer,MotionEvent.ACTION_DOWN);touch(composer,MotionEvent.ACTION_CANCEL);idle();
            assertEquals(1,clicks[0]);
            assertEquals(1f,composer.getScaleX(),.001f);
            assertEquals(1f,composer.getScaleY(),.001f);
        }
    }
    @Test public void disabledAnimationsResetFocusAndPressImmediately() throws Exception {
        var scale=ValueAnimator.class.getDeclaredMethod("setDurationScale",float.class);
        scale.setAccessible(true);scale.invoke(null,0f);
        try(var controller=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=controller.get();
            LiquidComposer composer=new LiquidComposer(activity,new DesignTokens(activity.getResources()));
            composer.setScaleX(.9f);composer.setScaleY(.9f);composer.setTranslationY(-4);
            composer.setEditing(true);touch(composer,MotionEvent.ACTION_DOWN);
            assertEquals(1f,composer.getScaleX(),0f);assertEquals(1f,composer.getScaleY(),0f);
            assertEquals(0f,composer.getTranslationY(),0f);
            touch(composer,MotionEvent.ACTION_CANCEL);composer.setEditing(false);
            assertEquals(0f,composer.getTranslationY(),0f);
        } finally {scale.invoke(null,1f);}
    }
}
