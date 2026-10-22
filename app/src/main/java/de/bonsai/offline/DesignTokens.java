package de.bonsai.offline;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Shared native design system. Distances are dp, typography is sp. */
final class DesignTokens {
    static final int SPACE_XS=4, SPACE_SM=8, SPACE_MD=16, SPACE_LG=24, SPACE_XL=32;
    static final int TYPE_META=12, TYPE_LABEL=14, TYPE_BODY=16, TYPE_SUBTITLE=20, TYPE_TITLE=24, TYPE_HERO=32;
    static final int RADIUS_SMALL=12, RADIUS_CARD=24, TOUCH_TARGET=48, CONTENT_WIDTH=720;
    static final long MOTION_PRESS=150, MOTION_ENTER=250, MOTION_EXIT=180, LOADING_DELAY=180;
    static final Interpolator EASE_OUT=new PathInterpolator(.16f,1f,.3f,1f);
    static final Interpolator EASE_IN_OUT=new PathInterpolator(.65f,0f,.35f,1f);
    final int background, surface, text, muted, primary, action, onPrimary, success, warning, error, border;
    final int raised, accentSurface, subtleBorder;
    final boolean dark;

    DesignTokens(Resources resources) {
        dark=(resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        background=dark?0xff050506:0xfffafafa;
        surface=dark?0xff161618:0xfff0f0f3;
        raised=dark?0xff202023:0xffffffff;
        accentSurface=dark?0xff252035:0xffeee8ff;
        subtleBorder=dark?0xff343438:0xffdedee5;
        text=dark?0xfff5f5f7:0xff1b1b20;
        muted=dark?0xffa6a6b0:0xff62626e;
        primary=dark?0xffc4adff:0xff6540b5;
        action=0xff7545d6;
        onPrimary=0xffffffff;
        success=dark?0xff64dcb2:0xff187451;
        warning=dark?0xffe7c383:0xff79530e;
        error=dark?0xffffb3ba:0xffae243a;
        border=dark?0xff73737e:0xff81818c;
    }
}
