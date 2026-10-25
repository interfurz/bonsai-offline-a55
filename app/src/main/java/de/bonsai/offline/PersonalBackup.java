package de.bonsai.offline;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Versioned logical backup. Imports append chats and never replace existing memories. */
final class PersonalBackup {
    static final int MAX_BYTES=16*1024*1024;
    static void write(ChatStore store,OutputStream output) throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        JSONObject root=new JSONObject().put("format","bonsai-personal-backup").put("version",1);
        JSONArray chats=new JSONArray(),memory=new JSONArray();
        try {
            int messageCount=0;
            long characters=0;
            try(Cursor c=db.rawQuery("SELECT id,title,updated,archived FROM chats ORDER BY id",null)) {
                while(c.moveToNext()) {
                    characters+=c.getString(1).length();
                    if(chats.length()>=10000)throw new IOException("Zu viele Chats für eine Sicherung.");
                    JSONObject chat=new JSONObject().put("title",c.getString(1)).put("updated",c.getLong(2)).put("archived",c.getInt(3)!=0);
                    JSONArray messages=new JSONArray();
                    try(Cursor m=db.rawQuery("SELECT role,text,detail FROM messages WHERE chat_id=? ORDER BY id",new String[]{c.getString(0)})) {
                        while(m.moveToNext()) {
                            characters+=(long)m.getString(0).length()+m.getString(1).length()+m.getString(2).length();
                            if(characters>MAX_BYTES)throw new IOException("Sicherung überschreitet 16 MiB.");
                            if(++messageCount>100000)throw new IOException("Zu viele Nachrichten für eine Sicherung.");
                            messages.put(new JSONObject().put("role",m.getString(0)).put("text",m.getString(1)).put("detail",m.getString(2)));
                        }
                    }
                    chats.put(chat.put("messages",messages));
                }
            }
            for(ChatStore.Memory m:store.memories("")) {
                characters+=(long)m.key.length()+m.value.length()+m.source.length();
                if(characters>MAX_BYTES)throw new IOException("Sicherung überschreitet 16 MiB.");
                if(memory.length()>=10000)throw new IOException("Zu viele Erinnerungen für eine Sicherung.");
                memory.put(new JSONObject().put("key",m.key).put("value",m.value).put("updated",m.updated).put("source",m.source));
            }
            root.put("chats",chats).put("memory",memory);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Sicherung überschreitet 16 MiB.");
        output.write(bytes);output.flush();
    }
    static int restore(ChatStore store,InputStream input) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1) {
            if(bytes.size()+n>MAX_BYTES)throw new IOException("Sicherung überschreitet 16 MiB.");
            bytes.write(buffer,0,n);
        }
        JSONObject root=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        if(!"bonsai-personal-backup".equals(root.getString("format")) || root.getInt("version")!=1)
            throw new IOException("Unbekanntes Sicherungsformat.");
        JSONArray chats=root.getJSONArray("chats"),memory=root.getJSONArray("memory");
        if(chats.length()>10000 || memory.length()>10000)throw new IOException("Zu viele Einträge.");
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            int count=0;
            for(int i=0;i<chats.length();i++) {
                JSONObject chat=chats.getJSONObject(i);String title=text(chat,"title",1000,false);
                long updated=date(chat);boolean archived=chat.getBoolean("archived");
                long id=store.createChat(title);JSONArray messages=chat.getJSONArray("messages");
                for(int j=0;j<messages.length();j++) {
                    if(++count>100000)throw new IOException("Zu viele Nachrichten.");
                    JSONObject message=messages.getJSONObject(j);String role=text(message,"role",20,false);
                    if(!role.equals("user") && !role.equals("assistant"))throw new IOException("Ungültige Nachrichtenrolle.");
                    store.add(id,role,text(message,"text",1000000,true),text(message,"detail",1000000,true));
                }
                ContentValues values=new ContentValues();values.put("updated",updated);values.put("archived",archived?1:0);
                db.update("chats",values,"id=?",new String[]{Long.toString(id)});
            }
            for(int i=0;i<memory.length();i++) {
                JSONObject item=memory.getJSONObject(i);String key=text(item,"key",128,false),value=text(item,"value",4000,false);
                long updated=date(item);String source=text(item,"source",200,false);
                if(store.recall(key)!=null)continue;
                ContentValues values=new ContentValues();values.put("key",key);values.put("value",value);values.put("updated",updated);values.put("source",source);
                db.insertOrThrow("memory",null,values);
            }
            db.setTransactionSuccessful();return chats.length();
        } finally {db.endTransaction();}
    }
    private static long date(JSONObject object) throws Exception {
        Object value=object.get("updated");
        if(!(value instanceof Number) || ((Number)value).longValue()<0)throw new IOException("Ungültiges Datum.");
        return ((Number)value).longValue();
    }
    private static String text(JSONObject object,String key,int max,boolean empty) throws Exception {
        Object value=object.get(key);
        if(!(value instanceof String) || ((String)value).length()>max || (!empty && ((String)value).trim().isEmpty()))
            throw new IOException("Ungültiges Feld: "+key);
        return (String)value;
    }
    private PersonalBackup() {}
}
