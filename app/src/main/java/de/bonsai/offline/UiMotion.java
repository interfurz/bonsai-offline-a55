package de.bonsai.offline;

import android.animation.ValueAnimator;
import android.view.View;

/** Only three patterns: navigation, panel orientation, and press feedback. */
final class UiMotion {
    private UiMotion() {}
    static void reset(View view) {
        view.animate().cancel();
        view.setAlpha(1); view.setTranslationX(0); view.setTranslationY(0);
        view.setScaleX(1); view.setScaleY(1);
    }
    static void enter(View view,float x,float y) {
        reset(view);
        if(!ValueAnimator.areAnimatorsEnabled())return;
        view.setAlpha(0); view.setTranslationX(x); view.setTranslationY(y);
        view.animate().alpha(1).translationX(0).translationY(0)
            .setDuration(DesignTokens.MOTION_ENTER).setInterpolator(DesignTokens.EASE_IN_OUT).start();
    }
    static void exit(View view,float x,Runnable done) {
        reset(view);
        if(!ValueAnimator.areAnimatorsEnabled()){done.run();return;}
        view.animate().alpha(0).translationX(x).setDuration(DesignTokens.MOTION_EXIT)
            .setInterpolator(DesignTokens.EASE_OUT).withEndAction(done).start();
    }
    static void press(View view,boolean down) {
        view.animate().cancel();
        if(!ValueAnimator.areAnimatorsEnabled()){reset(view);return;}
        view.animate().scaleX(down?.96f:1f).scaleY(down?.96f:1f)
            .setDuration(DesignTokens.MOTION_PRESS).setInterpolator(DesignTokens.EASE_OUT).start();
    }
}
