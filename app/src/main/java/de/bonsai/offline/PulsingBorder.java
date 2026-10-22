package de.bonsai.offline;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

/** Native, offline interpretation of Paper's PulsingBorder; no WebView or GL context.
 * Cached shaders follow the rounded perimeter. Drawing never changes child layout.
 */
final class PulsingBorder extends FrameLayout {
    enum State {
        IDLE(.45f,.12f,.2f,.08f,.18f,3,.35f,
            new int[]{0xff2b5cff,0xff7a3cff,0xff00c2ff}),
        GENERATING(1.5f,.42f,.5f,.5f,.55f,4,.55f,
            new int[]{0xff0dc1fd,0xffd915ef,0xccff3f2e,0xffffb800}),
        DONE(.3f,.1f,.25f,0,.12f,2,.5f,
            new int[]{0xff19d38a,0xff0dc1fd});

        final float speed,intensity,bloom,pulse,smoke,spotSize;
        final int spots;
        final int[] colors;
        State(float speed,float intensity,float bloom,float pulse,float smoke,int spots,
              float spotSize,int[] colors) {
            this.speed=speed;this.intensity=intensity;this.bloom=bloom;this.pulse=pulse;
            this.smoke=smoke;this.spots=spots;this.spotSize=spotSize;this.colors=colors;
        }
    }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds=new RectF(),inner=new RectF();
    private final Path perimeter=new Path(),interior=new Path();
    private final PathMeasure measure=new PathMeasure();
    private final Matrix rotation=new Matrix(),spotMatrix=new Matrix();
    private final float[] point=new float[2];
    private final SweepGradient[] borders=new SweepGradient[State.values().length];
    private final RadialGradient[][] halos=new RadialGradient[State.values().length][];
    private final DesignTokens tokens;
    private final PowerManager powerManager;
    private final float density,radius,strength;
    private State state=State.IDLE,previous=State.IDLE;
    private float phase,transition=1;
    private long lastFrame,nextPowerCheck;
    private boolean powerSave;
    private boolean active=true,scheduled;

    public PulsingBorder(Context context) {this(context,new DesignTokens(context.getResources()),30,1);}
    PulsingBorder(Context context,DesignTokens tokens,float radius,float strength) {
        super(context);this.tokens=tokens;this.radius=radius;this.strength=strength;
        density=getResources().getDisplayMetrics().density;
        powerManager=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        setWillNotDraw(false);setClipChildren(false);setClipToPadding(false);
        int inset=Math.round(9*density);setPadding(inset,inset,inset,inset);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for(State preset:State.values()) {
            halos[preset.ordinal()]=new RadialGradient[preset.colors.length];
            for(int i=0;i<preset.colors.length;i++)
                halos[preset.ordinal()][i]=new RadialGradient(0,0,1,
                    new int[]{preset.colors[i],preset.colors[i]&0x00ffffff},null,Shader.TileMode.CLAMP);
        }
    }

    State getState(){return state;}
    boolean isFrameScheduled(){return scheduled;}
    void setState(State next) {
        if(state==next)return;
        previous=state;state=next;transition=shouldRunFrames()&&motionEnabled()?0:1;invalidate();schedule();
    }
    void setActive(boolean enabled) {
        active=enabled;nextPowerCheck=0;
        if(!enabled)stop();else schedule();
    }
    private boolean motionEnabled() {
        long now=SystemClock.uptimeMillis();
        if(now>=nextPowerCheck){powerSave=powerManager!=null&&powerManager.isPowerSaveMode();nextPowerCheck=now+1000;}
        return ValueAnimator.areAnimatorsEnabled()&&!powerSave;
    }
    private boolean shouldRunFrames() {
        return active&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE&&hasWindowFocus();
    }
    private void schedule() {
        if(!scheduled&&shouldRunFrames()&&motionEnabled()) {
            scheduled=true;lastFrame=SystemClock.uptimeMillis();postDelayed(frame,33);
        }
    }
    private void stop(){removeCallbacks(frame);scheduled=false;lastFrame=0;}
    private final Runnable frame=new Runnable(){
        @Override public void run() {
            scheduled=false;
            if(!shouldRunFrames()||!motionEnabled()){invalidate();return;}
            long now=SystemClock.uptimeMillis();
            float elapsed=Math.min(.1f,(now-lastFrame)/1000f);lastFrame=now;
            phase=(phase+elapsed*state.speed*.08f)%1;
            transition=Math.min(1,transition+elapsed/.65f);
            invalidate();scheduled=true;postDelayed(this,33);
        }
    };
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();schedule();}
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)schedule();else stop();
    }
    @Override protected void onVisibilityChanged(View changed,int visibility) {
        super.onVisibilityChanged(changed,visibility);
        // View's constructor may dispatch this before our fields are initialized.
        if(frame!=null){if(isShown())schedule();else stop();}
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(focused)schedule();else stop();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        float inset=9*density,r=Math.min(radius*density,(h-2*inset)/2);
        bounds.set(inset,inset,w-inset,h-inset);inner.set(bounds);inner.inset(2*density,2*density);
        perimeter.reset();perimeter.addRoundRect(bounds,r,r,Path.Direction.CW);measure.setPath(perimeter,true);
        interior.reset();interior.addRoundRect(inner,Math.max(0,r-2*density),Math.max(0,r-2*density),Path.Direction.CW);
        for(State preset:State.values()) {
            int[] colors=new int[preset.colors.length+1];
            System.arraycopy(preset.colors,0,colors,0,preset.colors.length);colors[colors.length-1]=colors[0];
            borders[preset.ordinal()]=new SweepGradient(w/2f,h/2f,colors,null);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(bounds.isEmpty())return;
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(tokens.raised);
        canvas.drawPath(perimeter,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(tokens.subtleBorder);
        canvas.drawPath(perimeter,paint);
        boolean animate=motionEnabled();float mix=animate?transition:1;
        if(mix<1)drawLight(canvas,previous,1-mix);
        drawLight(canvas,state,mix);
        paint.setShader(null);paint.setAlpha(255);
    }
    private void drawLight(Canvas canvas,State preset,float opacity) {
        SweepGradient shader=borders[preset.ordinal()];if(shader==null)return;
        rotation.setRotate(phase*360,getWidth()/2f,getHeight()/2f);shader.setLocalMatrix(rotation);
        float wave=(float)(.5+.5*Math.sin(phase*Math.PI*12));
        float energy=(.3f+preset.intensity)*(1-preset.pulse*.3f+preset.pulse*wave)*opacity*strength;
        paint.setStyle(Paint.Style.STROKE);paint.setShader(shader);
        for(int layer=5;layer>=1;layer--) {
            paint.setStrokeWidth(density*(1+layer*2.4f));
            paint.setAlpha(Math.min(255,Math.round(energy*(5+12*preset.bloom)*(6-layer)/5)));
            canvas.drawPath(perimeter,paint);
        }
        paint.setStrokeWidth(1.25f*density);paint.setAlpha(Math.min(255,Math.round(energy*230)));
        canvas.drawPath(perimeter,paint);
        // Local soft spots form a moving, smoke-like halo, clipped away from text.
        canvas.save();canvas.clipOutPath(interior);paint.setStyle(Paint.Style.STROKE);
        for(int i=0;i<preset.spots;i++) {
            float distance=((phase+i/(float)preset.spots)%1)*measure.getLength();
            measure.getPosTan(distance,point,null);
            float size=density*(22+44*preset.spotSize)*(1+.12f*(float)Math.sin(phase*20+i));
            RadialGradient halo=halos[preset.ordinal()][i%preset.colors.length];
            spotMatrix.setScale(size,size);spotMatrix.postTranslate(point[0],point[1]);halo.setLocalMatrix(spotMatrix);
            paint.setShader(halo);
            for(int layer=3;layer>=1;layer--) {
                paint.setStrokeWidth(density*(1+layer*2));
                paint.setAlpha(Math.min(255,Math.round(energy*(30+85*preset.smoke)*(4-layer)/3)));
                canvas.drawPath(perimeter,paint);
            }
        }
        canvas.restore();
    }
}
