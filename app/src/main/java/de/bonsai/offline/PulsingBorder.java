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
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;

/** Native, offline interpretation of Paper's PulsingBorder; no WebView or GL context.
 * Cached shaders follow the rounded perimeter. Drawing never changes child layout.
 */
final class PulsingBorder extends FrameLayout {
    private static final float[] BLOOM_WIDTHS={13f,8.2f,3.4f};
    private static final float[] BLOOM_WEIGHTS={.3f,.9f,1.8f};
    enum Mode { BORDER, AMBIENT, RAIL }
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
    private final Mode mode;
    private State state=State.IDLE,previous=State.IDLE;
    private float phase,transition=1,perimeterLength;
    private long lastFrame,nextPowerCheck;
    private boolean powerSave;
    private boolean active=true,scheduled;

    public PulsingBorder(Context context) {this(context,new DesignTokens(context.getResources()),30,1);}
    PulsingBorder(Context context,DesignTokens tokens,float radius,float strength) {
        this(context,tokens,radius,strength,Mode.BORDER);
    }
    PulsingBorder(Context context,DesignTokens tokens,float radius,float strength,Mode mode) {
        super(context);this.tokens=tokens;this.radius=radius;this.strength=strength;this.mode=mode;
        density=getResources().getDisplayMetrics().density;
        powerManager=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        setWillNotDraw(false);setClipChildren(false);setClipToPadding(false);
        int inset=Math.round(9*density);
        if(mode==Mode.AMBIENT)setPadding(0,0,0,0);
        else if(mode==Mode.RAIL)setPadding(Math.round(12*density),0,0,0);
        else setPadding(inset,inset,inset,inset);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for(State preset:State.values()) {
            halos[preset.ordinal()]=new RadialGradient[preset.colors.length];
            for(int i=0;i<preset.colors.length;i++)
                halos[preset.ordinal()][i]=new RadialGradient(0,0,1,
                    new int[]{preset.colors[i],preset.colors[i]&0x00ffffff},null,Shader.TileMode.CLAMP);
        }
    }

    State getState(){return state;}
    Mode getMode(){return mode;}
    boolean isFrameScheduled(){return scheduled;}
    void setState(State next) {
        if(state==next)return;
        previous=state;state=next;transition=shouldRunFrames()&&motionEnabled()?0:1;
        if(!shouldRunFrames())stop();
        invalidate();if(scheduled)Frames.reschedule();else schedule();
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
    private int frameIntervalMillis() {
        if(mode==Mode.AMBIENT)return 84;
        if(transition<1 || state==State.DONE)return 34;
        return state==State.GENERATING?42:50;
    }
    private void schedule() {
        if(!scheduled&&shouldRunFrames()&&motionEnabled()) {
            scheduled=true;lastFrame=SystemClock.uptimeMillis();Frames.add(this);
        }
    }
    private void stop(){Frames.remove(this);scheduled=false;lastFrame=0;}
    private void advance(long now) {
        float elapsed=Math.min(.1f,(now-lastFrame)/1000f);lastFrame=now;
        phase=(phase+elapsed*state.speed*.08f)%1;
        transition=Math.min(1,transition+elapsed/.65f);invalidate();
    }
    /** One frame callback for every visible light, instead of one timer per surface. */
    private static final class Frames {
        private static final ArrayList<PulsingBorder> lights=new ArrayList<>();
        private static boolean pending;
        private static final Choreographer.FrameCallback tick=new Choreographer.FrameCallback(){
            @Override public void doFrame(long frameTimeNanos){
                pending=false;long now=SystemClock.uptimeMillis();
                for(int i=lights.size()-1;i>=0;i--){
                    PulsingBorder light=lights.get(i);
                    if(!light.shouldRunFrames()||!light.motionEnabled()){
                        lights.remove(i);light.scheduled=false;light.lastFrame=0;light.invalidate();
                    }else if(now-light.lastFrame>=light.frameIntervalMillis())light.advance(now);
                }
                post();
            }
        };
        static void add(PulsingBorder light){lights.add(light);reschedule();}
        static void remove(PulsingBorder light){
            if(lights.remove(light))reschedule();
        }
        static void reschedule(){if(pending){Choreographer.getInstance().removeFrameCallback(tick);pending=false;}post();}
        private static void post(){
            if(pending||lights.isEmpty())return;
            long now=SystemClock.uptimeMillis(),delay=Long.MAX_VALUE;
            for(int i=0;i<lights.size();i++){
                PulsingBorder light=lights.get(i);delay=Math.min(delay,Math.max(0,light.lastFrame+light.frameIntervalMillis()-now));
            }
            pending=true;Choreographer.getInstance().postFrameCallbackDelayed(tick,delay);
        }
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();schedule();}
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)schedule();else stop();
    }
    @Override protected void onVisibilityChanged(View changed,int visibility) {
        super.onVisibilityChanged(changed,visibility);
        // View's constructor may dispatch this before our fields are initialized.
        if(paint!=null){if(isShown())schedule();else stop();}
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(focused)schedule();else stop();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if(mode==Mode.AMBIENT){bounds.set(0,0,w,h);return;}
        float inset=9*density,r=Math.min(radius*density,(h-2*inset)/2);
        bounds.set(inset,inset,w-inset,h-inset);inner.set(bounds);inner.inset(2*density,2*density);
        perimeter.reset();perimeter.addRoundRect(bounds,r,r,Path.Direction.CW);measure.setPath(perimeter,true);
        interior.reset();interior.addRoundRect(inner,Math.max(0,r-2*density),Math.max(0,r-2*density),Path.Direction.CW);
        if(mode==Mode.RAIL){
            bounds.set(0,0,w,h);interior.reset();perimeter.reset();
            paint.setStrokeCap(Paint.Cap.ROUND);
            perimeter.moveTo(6*density,12*density);perimeter.lineTo(6*density,Math.max(12*density,h-12*density));
            measure.setPath(perimeter,false);
        }
        perimeterLength=measure.getLength();
        for(State preset:State.values()) {
            int[] colors=new int[preset.colors.length+1];
            System.arraycopy(preset.colors,0,colors,0,preset.colors.length);colors[colors.length-1]=colors[0];
            borders[preset.ordinal()]=new SweepGradient(w/2f,h/2f,colors,null);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(bounds.isEmpty())return;
        if(mode==Mode.BORDER){
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(tokens.raised);
            canvas.drawPath(perimeter,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(tokens.subtleBorder);
            canvas.drawPath(perimeter,paint);
        }
        boolean animate=shouldRunFrames()&&motionEnabled();float mix=animate?transition:1;
        if(mode==Mode.AMBIENT){
            if(mix<1)drawAmbient(canvas,previous,1-mix);
            drawAmbient(canvas,state,mix);
        }else{
            if(mix<1)drawLight(canvas,previous,1-mix);
            drawLight(canvas,state,mix);
        }
        paint.setShader(null);paint.setAlpha(255);
    }
    private void drawAmbient(Canvas canvas,State preset,float opacity){
        float w=getWidth(),h=getHeight(),drift=(float)Math.sin(phase*Math.PI*2);
        float size=Math.min(w*.9f,h*.6f);
        paint.setStyle(Paint.Style.FILL);
        // Edge pools leave the central reading area quiet; no full-screen colour wash.
        for(int i=0;i<2;i++){
            float x=i==0?w*(.92f+.08f*drift):w*(.02f-.07f*drift);
            float y=i==0?h*(.08f+.035f*drift):h*(.88f-.045f*drift);
            RadialGradient halo=halos[preset.ordinal()][i%preset.colors.length];
            spotMatrix.setScale(size,size);spotMatrix.postTranslate(x,y);halo.setLocalMatrix(spotMatrix);
            paint.setShader(halo);
            float level=tokens.dark?28+36*preset.intensity:16+24*preset.intensity;
            paint.setAlpha(Math.min(255,Math.round(level*strength*opacity)));
            canvas.drawCircle(x,y,size,paint);
        }
    }
    private void drawLight(Canvas canvas,State preset,float opacity) {
        SweepGradient shader=borders[preset.ordinal()];if(shader==null)return;
        rotation.setRotate(phase*360,getWidth()/2f,getHeight()/2f);shader.setLocalMatrix(rotation);
        float wave=(float)(.5+.5*Math.sin(phase*Math.PI*12));
        float energy=(.3f+preset.intensity)*(1-preset.pulse*.3f+preset.pulse*wave)*opacity*strength;
        paint.setStyle(Paint.Style.STROKE);paint.setShader(shader);
        for(int layer=0;layer<BLOOM_WIDTHS.length;layer++) {
            paint.setStrokeWidth(density*BLOOM_WIDTHS[layer]);
            paint.setAlpha(Math.min(255,Math.round(energy*(5+12*preset.bloom)*BLOOM_WEIGHTS[layer])));
            canvas.drawPath(perimeter,paint);
        }
        paint.setStrokeWidth(1.25f*density);paint.setAlpha(Math.min(255,Math.round(energy*230)));
        canvas.drawPath(perimeter,paint);
        // Local soft spots form a moving, smoke-like halo, clipped away from text.
        canvas.save();canvas.clipOutPath(interior);paint.setStyle(Paint.Style.STROKE);
        for(int i=0;i<preset.spots;i++) {
            float distance=((phase+i/(float)preset.spots)%1)*perimeterLength;
            measure.getPosTan(distance,point,null);
            float size=density*(22+44*preset.spotSize)*(1+.12f*(float)Math.sin(phase*20+i));
            RadialGradient halo=halos[preset.ordinal()][i%preset.colors.length];
            spotMatrix.setScale(size,size);spotMatrix.postTranslate(point[0],point[1]);halo.setLocalMatrix(spotMatrix);
            paint.setShader(halo);
            // The radial shader is transparent outside this square; skip those fragments.
            canvas.save();canvas.clipRect(point[0]-size,point[1]-size,point[0]+size,point[1]+size);
            for(int layer=2;layer>=1;layer--) {
                paint.setStrokeWidth(density*(layer==2?7:3));
                paint.setAlpha(Math.min(255,Math.round(energy*(30+85*preset.smoke)*(layer==2?.5f:1.5f))));
                canvas.drawPath(perimeter,paint);
            }
            canvas.restore();
        }
        canvas.restore();
    }
}
