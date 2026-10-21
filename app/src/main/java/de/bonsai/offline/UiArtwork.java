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
    static Drawable icon(String kind, int color, int size) {
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
                case "chat":
                    c.drawRoundRect(3,3,21,17,4,4,p);
                    path.moveTo(6,17);path.lineTo(6,21);path.lineTo(11,17);c.drawPath(path,p);
                    c.drawLine(8,8,16,8,p);c.drawLine(8,12,13,12,p);break;
                case "model":
                    c.drawRoundRect(5,5,19,19,3,3,p);c.drawRoundRect(9,9,15,15,1,1,p);
                    for(int i=8;i<=16;i+=8){c.drawLine(i,2,i,5,p);c.drawLine(i,19,i,22,p);c.drawLine(2,i,5,i,p);c.drawLine(19,i,22,i,p);}break;
                case "performance":
                    c.drawLine(4,20,4,13,p);c.drawLine(12,20,12,8,p);c.drawLine(20,20,20,3,p);
                    path.moveTo(3,8);path.lineTo(10,3);path.lineTo(15,5);c.drawPath(path,p);break;
                case "idea":
                    c.drawCircle(12,9,6,p);c.drawLine(8,14,9,18,p);c.drawLine(16,14,15,18,p);
                    c.drawLine(9,18,15,18,p);c.drawLine(10,21,14,21,p);break;
                case "book":
                    path.moveTo(12,5);path.quadTo(7,2,3,4);path.lineTo(3,19);path.quadTo(7,17,12,20);
                    path.quadTo(17,17,21,19);path.lineTo(21,4);path.quadTo(17,2,12,5);path.lineTo(12,20);c.drawPath(path,p);break;
                case "arrow":
                    c.drawLine(4,12,20,12,p);c.drawLine(15,7,20,12,p);c.drawLine(15,17,20,12,p);break;
                case "copy":
                    c.drawRoundRect(8,8,21,21,2,2,p);path.moveTo(16,4);path.lineTo(4,4);path.lineTo(4,16);c.drawPath(path,p);break;
                case "refresh":
                    c.drawArc(4,4,20,20,40,280,false,p);path.moveTo(20,3);path.lineTo(20,9);path.lineTo(14,9);c.drawPath(path,p);break;
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
