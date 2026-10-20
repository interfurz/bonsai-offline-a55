package de.bonsai.offline;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Shared native design system. Distances are dp, typography is sp. */
final class DesignTokens {
    static final int SPACE_XS=4, SPACE_SM=8, SPACE_MD=16, SPACE_LG=24, SPACE_XL=32;
    static final int TYPE_META=12, TYPE_LABEL=14, TYPE_BODY=16, TYPE_TITLE=24, TYPE_HERO=32;
    static final int RADIUS_SMALL=12, RADIUS_CARD=24, TOUCH_TARGET=48, CONTENT_WIDTH=720;
    static final long MOTION_PRESS=150, MOTION_ENTER=250, MOTION_EXIT=180, LOADING_DELAY=180;
    static final Interpolator EASE_OUT=new PathInterpolator(.16f,1f,.3f,1f);
    static final Interpolator EASE_IN_OUT=new PathInterpolator(.65f,0f,.35f,1f);
    final int background, surface, text, muted, primary, action, onPrimary, success, warning, error, border;
    final boolean dark;

    DesignTokens(Resources resources) {
        dark=(resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        background=dark?0xff1c151e:0xfffaf6f8;
        surface=dark?0xff2d2231:0xfff1e8ef;
        text=dark?0xfff6edf4:0xff30202e;
        muted=dark?0xffcbb7c8:0xff6d5467;
        primary=dark?0xffe6a7c1:0xff7b2947;
        action=dark?0xff8f3153:0xff7b2947;
        onPrimary=0xfffff4f8;
        success=primary;
        warning=dark?0xffe4c080:0xff755015;
        error=dark?0xffffb4c0:0xffa3273e;
        border=dark?0xff9f819b:0xff907389;
    }
}
