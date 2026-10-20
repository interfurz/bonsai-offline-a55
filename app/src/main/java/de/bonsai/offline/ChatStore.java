package de.bonsai.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class ChatStore extends SQLiteOpenHelper {
    static final class Message {
        final long id;
        final String role;
        String text;
        String detail;
        Message(long id, String role, String text, String detail) {
            this.id=id; this.role=role; this.text=text; this.detail=detail;
        }
    }
    static final class Chat {
        final long id;
        final String title;
        Chat(long id, String title) { this.id=id; this.title=title; }
    }
    ChatStore(Context c) { super(c, "chats.sqlite", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE, role TEXT NOT NULL, text TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX messages_chat ON messages(chat_id,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        throw new IllegalStateException("Unbekannte Datenbankversion");
    }
    long createChat(String title) {
        ContentValues v=new ContentValues(); v.put("title", title); v.put("updated",System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("chats",null,v);
    }
    List<Chat> chats() {
        List<Chat> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title FROM chats ORDER BY updated DESC LIMIT 100",null)) {
            while(c.moveToNext()) result.add(new Chat(c.getLong(0),c.getString(1)));
        }
        return result;
    }
    List<Message> messages(long chat) {
        List<Message> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,role,text,detail FROM messages WHERE chat_id=? ORDER BY id",new String[]{Long.toString(chat)})) {
            while(c.moveToNext()) result.add(new Message(c.getLong(0),c.getString(1),c.getString(2),c.getString(3)));
        }
        return result;
    }
    long add(long chat,String role,String text,String detail) {
        SQLiteDatabase db=getWritableDatabase();
        ContentValues v=new ContentValues(); v.put("chat_id",chat); v.put("role",role); v.put("text",text); v.put("detail",detail);
        db.beginTransaction();
        try {
            long id=db.insertOrThrow("messages",null,v);
            ContentValues update=new ContentValues(); update.put("updated",System.currentTimeMillis());
            db.update("chats",update,"id=?",new String[]{Long.toString(chat)});
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }
    void removeMessage(long id) { getWritableDatabase().delete("messages","id=?",new String[]{Long.toString(id)}); }
    long forkBeforeLastTurn(long original) {
        List<Message> history=messages(original);
        if(history.size()<2 || !"user".equals(history.get(history.size()-2).role)
            || !"assistant".equals(history.get(history.size()-1).role))
            throw new IllegalStateException("Keine abgeschlossene Antwort vorhanden.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String prompt=history.get(history.size()-2).text.replace('\n',' ');
            long variant=createChat("Variante · "+prompt.substring(0,Math.min(prompt.length(),50)));
            for(int i=0;i<history.size()-2;i++) {
                Message m=history.get(i);add(variant,m.role,m.text,m.detail);
            }
            db.setTransactionSuccessful();return variant;
        } finally { db.endTransaction(); }
    }
    void recoverInterruptedTurns() {
        // Generation may have been killed by Android after persisting its user turn.
        getWritableDatabase().execSQL("DELETE FROM messages WHERE role='user' AND id IN (SELECT MAX(id) FROM messages GROUP BY chat_id)");
    }
    void deleteChat(long chat) { getWritableDatabase().delete("chats","id=?",new String[]{Long.toString(chat)}); }
}
