package de.bonsai.offline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Local vector artwork: crisp at every density, with no image or network dependency. */
final class UiArtwork {
    static Drawable icon(Context context, String kind, int color, int size) {
        if(BonsaiIcons.resource(kind)!=0)return BonsaiIcons.icon(context,kind,color,size);
        Drawable d = new Glyph(kind, color);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static final class Glyph extends Drawable {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Glyph(String kind, int color) { this.kind=kind; p.setColor(color); }
        @Override public void draw(Canvas c) {
            c.save(); c.translate(getBounds().left,getBounds().top);
            c.scale(getBounds().width()/24f,getBounds().height()/24f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            switch(kind) {
                case "arrow":
                    c.drawLine(4,12,20,12,p);c.drawLine(15,7,20,12,p);c.drawLine(15,17,20,12,p);break;
                case "shield":
                    path.moveTo(12,2);path.lineTo(20,5);path.lineTo(20,12);path.quadTo(20,18,12,22);
                    path.quadTo(4,18,4,12);path.lineTo(4,5);path.close();c.drawPath(path,p);
                    path.reset();path.moveTo(8,12);path.lineTo(11,15);path.lineTo(16,9);c.drawPath(path,p);break;
                default:
                    path.moveTo(5,19);path.cubicTo(16,20,19,9,20,3);path.cubicTo(8,3,3,7,5,19);
                    path.moveTo(5,21);path.lineTo(15,10);c.drawPath(path,p);
            }
            c.restore();
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha);invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter);invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class BonsaiScene extends View {
        private final DesignTokens t;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path branch=new Path();
        public BonsaiScene(Context context) { this(context,new DesignTokens(context.getResources())); }
        BonsaiScene(Context context,DesignTokens tokens) {
            super(context);t=tokens;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);c.save();
            float scale=Math.min(getWidth()/300f,getHeight()/144f);
            c.translate(getWidth()/2f-150*scale,getHeight()/2f-72*scale);c.scale(scale,scale);
            p.setStyle(Paint.Style.FILL);p.setColor(t.surface);c.drawCircle(150,70,62,p);
            p.setStyle(Paint.Style.STROKE);p.setColor(t.subtleBorder);p.setStrokeWidth(1);
            c.drawCircle(150,70,70,p);c.drawLine(37,126,263,126,p);
            c.drawCircle(67,38,3,p);c.drawCircle(237,93,4,p);
            c.drawLine(228,25,228,35,p);c.drawLine(223,30,233,30,p);
            p.setColor(t.primary);p.setStrokeWidth(5);p.setStrokeCap(Paint.Cap.ROUND);
            branch.reset();branch.moveTo(151,110);branch.cubicTo(134,92,160,78,146,55);
            branch.moveTo(148,89);branch.cubicTo(136,79,122,83,113,68);
            branch.moveTo(151,77);branch.cubicTo(164,67,180,72,186,56);
            c.drawPath(branch,p);
            p.setStyle(Paint.Style.FILL);p.setColor(t.accentSurface);
            c.drawOval(90,48,141,73,p);c.drawOval(156,39,211,64,p);c.drawOval(119,24,171,53,p);
            p.setColor(t.primary);
            c.drawOval(94,45,140,64,p);c.drawOval(161,36,207,55,p);c.drawOval(123,22,169,43,p);
            p.setColor(t.action);branch.reset();branch.moveTo(122,108);branch.lineTo(179,108);
            branch.lineTo(170,124);branch.lineTo(131,124);branch.close();c.drawPath(branch,p);
            p.setColor(t.primary);c.drawRoundRect(119,105,182,110,2,2,p);
            c.restore();
        }
    }
}
