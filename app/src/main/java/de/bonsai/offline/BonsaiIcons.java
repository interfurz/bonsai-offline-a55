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
            case "chat": return R.drawable.ic_message;
            default: return 0;
        }
    }
    static int compactResource(int resource) {
        if(resource==R.drawable.ic_menu)return R.drawable.ic_menu_16;
        if(resource==R.drawable.ic_new_chat)return R.drawable.ic_new_chat_16;
        if(resource==R.drawable.ic_plus)return R.drawable.ic_plus_16;
        if(resource==R.drawable.ic_send)return R.drawable.ic_send_16;
        if(resource==R.drawable.ic_stop)return R.drawable.ic_stop_16;
        if(resource==R.drawable.ic_close)return R.drawable.ic_close_16;
        if(resource==R.drawable.ic_model)return R.drawable.ic_model_16;
        if(resource==R.drawable.ic_performance)return R.drawable.ic_performance_16;
        if(resource==R.drawable.ic_history)return R.drawable.ic_history_16;
        if(resource==R.drawable.ic_archive)return R.drawable.ic_archive_16;
        if(resource==R.drawable.ic_book)return R.drawable.ic_book_16;
        if(resource==R.drawable.ic_idea)return R.drawable.ic_idea_16;
        if(resource==R.drawable.ic_answer_copy)return R.drawable.ic_answer_copy_16;
        if(resource==R.drawable.ic_answer_retry)return R.drawable.ic_answer_retry_16;
        if(resource==R.drawable.ic_answer_speed)return R.drawable.ic_answer_speed_16;
        if(resource==R.drawable.ic_answer_count)return R.drawable.ic_answer_count_16;
        if(resource==R.drawable.ic_answer_time)return R.drawable.ic_answer_time_16;
        if(resource==R.drawable.ic_answer_info)return R.drawable.ic_answer_info_16;
        return resource;
    }
    static Drawable drawable(Context context,int resource,int color,int size) {
        float density=context.getResources().getDisplayMetrics().density;
        if(size<=Math.round(16*density))resource=compactResource(resource);
        Drawable icon=context.getDrawable(resource).mutate();
        icon.setTint(color);icon.setBounds(0,0,size,size);
        return icon;
    }
    static Drawable icon(Context context,String kind,int color,int size) {
        int resource=resource(kind);
        if(resource==0)throw new IllegalArgumentException("Unknown icon: "+kind);
        return drawable(context,resource,color,size);
    }
}
