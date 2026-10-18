package de.bonsai.offline;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Small native canvas animation, stopped when hidden and respectful of reduced motion. */
final class OrbitView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring=new RectF();
    private ValueAnimator motion;
    private RadialGradient glow;
    private float phase;
    private long lastFrame;
    OrbitView(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    private void startMotion() {
        if(motion!=null || !isAttachedToWindow() || getWindowVisibility()!=VISIBLE || !ValueAnimator.areAnimatorsEnabled()) return;
        motion=ValueAnimator.ofFloat(0,360); motion.setDuration(16000); motion.setRepeatCount(ValueAnimator.INFINITE);
        motion.setInterpolator(new android.view.animation.LinearInterpolator());
        motion.addUpdateListener(a->{ long now=android.os.SystemClock.uptimeMillis(); if(now-lastFrame>=33) { phase=(float)a.getAnimatedValue(); lastFrame=now; invalidate(); } });
        motion.start();
    }
    private void stopMotion() { if(motion!=null) { motion.cancel(); motion=null; } }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startMotion(); }
    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if(v==VISIBLE)startMotion();else stopMotion(); }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        float x=w/2f,y=h/2f,r=Math.min(x,y)*.66f;
        if(r>0)glow=new RadialGradient(x-r*.2f,y-r*.25f,r*1.5f,new int[]{0xff55705a,0xff263e33,0x00101311},null,Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas c) {
        float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)*.66f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(glow);
        c.drawCircle(x,y,r*1.45f,paint); paint.setShader(null);
        paint.setColor(0xffa9c5a9); paint.setStrokeWidth(getResources().getDisplayMetrics().density*.8f); paint.setStyle(Paint.Style.STROKE);
        ring.set(x-r,y-r*.68f,x+r,y+r*.68f);
        c.save();c.rotate(-32+phase*.08f,x,y); c.drawOval(ring,paint);c.restore();
        ring.set(x-r*.65f,y-r,x+r*.65f,y+r);
        c.save();c.rotate(28-phase*.1f,x,y);c.drawOval(ring,paint);c.restore();
        c.save(); c.rotate(phase,x,y); paint.setColor(0xffe9c39a); paint.setStyle(Paint.Style.FILL);
        c.drawCircle(x+r*.86f,y,r*.045f,paint);c.restore();
        paint.setColor(0xffdbe9d5); c.drawCircle(x,y,r*.12f,paint);
    }
}
