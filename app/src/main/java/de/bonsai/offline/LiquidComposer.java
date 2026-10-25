package de.bonsai.offline;

import android.content.Context;
import android.view.MotionEvent;

/** A soft, interruptible touch response around the existing composer controls.
 * Dispatch observes touches without consuming text selection, scrolling or clicks.
 */
final class LiquidComposer extends PulsingBorder {
    private boolean editing, touching;

    public LiquidComposer(Context context) {
        this(context,new DesignTokens(context.getResources()));
    }

    LiquidComposer(Context context, DesignTokens tokens) {
        super(context,tokens,20,1f);
    }

    void setEditing(boolean value) {
        if(editing==value)return;
        editing=value;
        settle();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching=true;
                settle();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching=false;
                settle();
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void settle() {
        animate().cancel();
        if(!UiMotion.enabled(this)){UiMotion.reset(this);return;}
        // The surface yields under the finger, then gently overshoots into place.
        animate().scaleX(touching?.992f:1f).scaleY(touching?.965f:1f)
            .translationY(editing?-2*getResources().getDisplayMetrics().density:0)
            .setDuration(touching?110:460)
            .setInterpolator(touching?DesignTokens.EASE_OUT:UiMotion.LIQUID_SPRING)
            .start();
    }

    @Override protected void onDetachedFromWindow() {
        touching=false;
        UiMotion.reset(this);
        super.onDetachedFromWindow();
    }
}
