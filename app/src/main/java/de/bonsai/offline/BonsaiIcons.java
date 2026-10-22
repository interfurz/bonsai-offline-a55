package de.bonsai.offline;

import android.content.Context;
import android.graphics.drawable.Drawable;

/** Resource lookup and runtime tint only. Geometry lives in res/drawable and SVG masters. */
final class BonsaiIcons {
    private BonsaiIcons() {}
    static int resource(String kind) {
        switch(kind) {
            case "menu": return R.drawable.ic_menu;
            case "edit": return R.drawable.ic_new_chat;
            case "plus": return R.drawable.ic_plus;
            case "send": return R.drawable.ic_send;
            case "stop": return R.drawable.ic_stop;
            case "close": return R.drawable.ic_close;
            case "model": return R.drawable.ic_model;
            case "performance": return R.drawable.ic_performance;
            case "history": return R.drawable.ic_history;
            case "archive": return R.drawable.ic_archive;
            case "book": return R.drawable.ic_book;
            case "idea": return R.drawable.ic_idea;
            case "copy": return R.drawable.ic_answer_copy;
            case "refresh": return R.drawable.ic_answer_retry;
            case "speed": return R.drawable.ic_answer_speed;
            case "count": return R.drawable.ic_answer_count;
            case "time": return R.drawable.ic_answer_time;
            case "info": return R.drawable.ic_answer_info;
            case "chat": return R.drawable.ic_history;
            default: return 0;
        }
    }
    static Drawable icon(Context context,String kind,int color,int size) {
        int resource=resource(kind);
        if(resource==0)throw new IllegalArgumentException("Unknown icon: "+kind);
        Drawable icon=context.getDrawable(resource).mutate();
        icon.setTint(color);icon.setBounds(0,0,size,size);
        return icon;
    }
}
