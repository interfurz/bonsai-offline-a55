package de.bonsai.offline;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalBackupTest {
    private static ByteArrayInputStream input(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
    @Test public void backupIncludesAllArchivesAndRestoresWithoutReplacingExistingData() throws Exception {
        Application context=RuntimeEnvironment.getApplication();
        String backup;
        try(ChatStore store=new ChatStore(context)) {
            for(int i=0;i<105;i++) {
                long id=store.createChat("Archiv "+i);store.add(id,"user","Frage 🪴","");store.add(id,"assistant","**Antwort**","Quelle");store.archiveChat(id,true);
            }
            store.remember("name","Alt","Manuell");store.remember("neu","Importierter Wert","Chat-Tool");
            ByteArrayOutputStream out=new ByteArrayOutputStream();PersonalBackup.write(store,out);backup=out.toString("UTF-8");
        }
        context.deleteDatabase("chats.sqlite");
        try(ChatStore store=new ChatStore(context)) {
            long existing=store.createChat("Bestehend");store.remember("name","Nicht ersetzen","Manuell");
            assertEquals(105,PersonalBackup.restore(store,input(backup)));
            assertEquals("Bestehend",store.chats().get(0).title);assertEquals(existing,store.chats().get(0).id);
            assertEquals("Nicht ersetzen",store.recall("name"));assertEquals("Importierter Wert",store.recall("neu"));
            try(android.database.Cursor count=store.getReadableDatabase().rawQuery("SELECT count(*) FROM chats WHERE archived=1",null)){count.moveToFirst();assertEquals(105,count.getInt(0));}
            long restored=store.chats(true).get(0).id;assertEquals("**Antwort**",store.messages(restored).get(1).text);
            assertEquals("Quelle",store.messages(restored).get(1).detail);
        }
    }
    @Test public void invalidImportRollsBackChatsAndMemoryTogether() throws Exception {
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            long id=store.createChat("Erhalten");store.remember("bestand","sicher");
            JSONObject root=new JSONObject().put("format","bonsai-personal-backup").put("version",1)
                .put("chats",new JSONArray().put(new JSONObject().put("title","Neu").put("updated",1).put("archived",false).put("messages",new JSONArray())))
                .put("memory",new JSONArray().put(new JSONObject().put("key","zuerst").put("value","gültig").put("updated",1).put("source","Manuell"))
                    .put(new JSONObject().put("key","ungültig").put("value",42).put("updated",1).put("source","Manuell")));
            try{PersonalBackup.restore(store,input(root.toString()));fail();}catch(IOException expected){}
            assertEquals(1,store.chats().size());assertEquals(id,store.chats().get(0).id);assertNull(store.recall("zuerst"));assertEquals("sicher",store.recall("bestand"));
            try{PersonalBackup.restore(store,input(root.put("version",999).toString()));fail();}catch(IOException expected){}
        }
    }
    @Test public void memoryMigrationPreservesDatesAndSupportsLiteralSearchAndDeletion() throws Exception {
        Application context=RuntimeEnvironment.getApplication();
        try(ChatStore store=new ChatStore(context)){store.remember("Muster%_","Hallo Welt");}
        try(SQLiteDatabase db=context.openOrCreateDatabase("chats.sqlite",0,null)) {
            db.execSQL("DROP TABLE memory_vectors");
            db.execSQL("ALTER TABLE memory RENAME TO memory_new");
            db.execSQL("CREATE TABLE memory(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL,updated INTEGER NOT NULL)");
            db.execSQL("INSERT INTO memory SELECT key,value,updated FROM memory_new");db.execSQL("DROP TABLE memory_new");db.setVersion(3);
        }
        try(ChatStore store=new ChatStore(context)) {
            ChatStore.Memory old=store.memories("%_").get(0);assertEquals("Bestand vor Version 4",old.source);assertTrue(old.updated>0);
            assertEquals(1,store.memories("welt").size());assertTrue(store.memories("' OR 1=1 --").isEmpty());
            store.remember(old.key,"Neu","Manuell");assertEquals("Manuell",store.memories("Neu").get(0).source);
            store.forget(old.key);assertNull(store.recall(old.key));assertTrue(store.memories("").isEmpty());
        }
    }
}
