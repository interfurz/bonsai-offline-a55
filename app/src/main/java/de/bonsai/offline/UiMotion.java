package de.bonsai.offline;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.Interpolator;
import android.os.PowerManager;
import android.content.Context;

/** Native state-change motion. Streaming tokens never trigger an animation. */
final class UiMotion {
    private UiMotion() {}
    static final Interpolator LIQUID_SPRING=t -> t==1f?1f:
        (float)(1-Math.exp(-7*t)*Math.cos(10*t));
    static boolean enabled(View view) {
        PowerManager power=(PowerManager)view.getContext().getSystemService(Context.POWER_SERVICE);
        return ValueAnimator.areAnimatorsEnabled()&&(power==null||!power.isPowerSaveMode());
    }
    static void reset(View view) {
        view.animate().cancel();
        view.animate().withEndAction(null);
        view.setAlpha(1); view.setTranslationX(0); view.setTranslationY(0);
        view.setScaleX(1); view.setScaleY(1);
    }
    static void enter(View view,float x,float y) {
        reset(view);
        if(!enabled(view))return;
        view.setAlpha(0); view.setTranslationX(x); view.setTranslationY(y);
        view.animate().alpha(1).translationX(0).translationY(0)
            .setDuration(380).setInterpolator(x==0?LIQUID_SPRING:DesignTokens.EASE_OUT).start();
    }
    static void exit(View view,float x,Runnable done) {
        reset(view);
        if(!enabled(view)){done.run();return;}
        view.animate().alpha(0).translationX(x).setDuration(DesignTokens.MOTION_EXIT)
            .setInterpolator(DesignTokens.EASE_OUT).withEndAction(done).start();
    }
    static void press(View view,boolean down) {
        view.animate().cancel();
        if(!enabled(view)){reset(view);return;}
        view.animate().scaleX(down?.97f:1f).scaleY(down?.94f:1f)
            .setDuration(down?110:380).setInterpolator(down?DesignTokens.EASE_OUT:LIQUID_SPRING).start();
    }
    // Observe only: the native ImageButton retains click and accessibility handling.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void bindPress(View view) {
        view.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)press(v,true);
            else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)press(v,false);
            return false;
        });
    }
}
