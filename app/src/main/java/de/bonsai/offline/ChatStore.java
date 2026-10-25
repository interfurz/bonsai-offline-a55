package de.bonsai.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class ChatStore extends SQLiteOpenHelper {
    final Context context;
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
    ChatStore(Context c) { super(c, "chats.sqlite", null, 4);context=c.getApplicationContext(); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);if(!db.isReadOnly())createVectors(db);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE, role TEXT NOT NULL, text TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX messages_chat ON messages(chat_id,id)");
        createMemory(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1 || newVersion>4) throw new IllegalStateException("Unbekannte Datenbankversion");
        if(oldVersion<2) db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
        if(oldVersion<3) createMemory(db);
        else if(oldVersion<4) db.execSQL("ALTER TABLE memory ADD COLUMN source TEXT NOT NULL DEFAULT 'Bestand vor Version 4'");
        createVectors(db);
    }
    private static void createMemory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memory(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, updated INTEGER NOT NULL, source TEXT NOT NULL DEFAULT 'Chat-Tool')");
        createVectors(db);
    }
    private static void createVectors(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS memory_vectors(key TEXT PRIMARY KEY NOT NULL REFERENCES memory(key) ON DELETE CASCADE, profile TEXT NOT NULL, vector BLOB NOT NULL)");}
    void remember(String key,String value) {
        remember(key,value,"Chat-Tool");
    }
    void remember(String key,String value,String source) {
        if(key==null || key.trim().isEmpty() || key.length()>128 || value==null || value.trim().isEmpty() || value.length()>4000)
            throw new IllegalArgumentException("Schlüssel: 1–128 Zeichen; Inhalt: 1–4000 Zeichen.");
        ContentValues v=new ContentValues();
        v.put("key",key); v.put("value",value); v.put("updated",System.currentTimeMillis());v.put("source",source);
        if(getWritableDatabase().insertWithOnConflict("memory",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)
            throw new IllegalStateException("Erinnerung konnte nicht gespeichert werden.");
    }
    static final class Memory {
        final String key,value,source;final long updated;
        Memory(String key,String value,long updated,String source){this.key=key;this.value=value;this.updated=updated;this.source=source;}
    }
    List<Memory> memories(String query) {
        List<Memory> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT key,value,updated,source FROM memory WHERE instr(lower(key || char(10) || value),lower(?))>0 ORDER BY updated DESC,key",new String[]{query})) {
            while(c.moveToNext())result.add(new Memory(c.getString(0),c.getString(1),c.getLong(2),c.getString(3)));
        }
        return result;
    }
    void forget(String key) {getWritableDatabase().delete("memory","key=?",new String[]{key});}
    String recall(String key) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM memory WHERE key=?",new String[]{key})) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    long createChat(String title) {
        ContentValues v=new ContentValues(); v.put("title", title); v.put("updated",System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("chats",null,v);
    }
    List<Chat> chats() {
        return chats(false);
    }
    List<Chat> chats(boolean archived) {
        List<Chat> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title FROM chats WHERE archived=? ORDER BY updated DESC LIMIT 100",new String[]{archived?"1":"0"})) {
            while(c.moveToNext()) result.add(new Chat(c.getLong(0),c.getString(1)));
        }
        return result;
    }
    List<Message> messages(long chat) {
        List<Message> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,role,text,detail FROM messages WHERE chat_id=? ORDER BY id",new String[]{Long.toString(chat)})) {
            while(c.moveToNext()) {
                String role=c.getString(1),text=c.getString(2);
                result.add(new Message(c.getLong(0),role,"assistant".equals(role)?ReasoningText.strip(text):text,c.getString(3)));
            }
        }
        // Repair older assistant messages lazily without altering user quotes or requiring a schema change.
        for(Message m:result)if("assistant".equals(m.role)) {
            ContentValues v=new ContentValues();v.put("text",m.text);
            getWritableDatabase().update("messages",v,"id=? AND text<>?",new String[]{Long.toString(m.id),m.text});
        }
        return result;
    }
    long add(long chat,String role,String text,String detail) {
        if("assistant".equals(role))text=ReasoningText.strip(text);
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
    void archiveChat(long chat,boolean archived) {
        ContentValues v=new ContentValues(); v.put("archived",archived?1:0);
        getWritableDatabase().update("chats",v,"id=?",new String[]{Long.toString(chat)});
    }
}
