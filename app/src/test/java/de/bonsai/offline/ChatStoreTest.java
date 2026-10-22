package de.bonsai.offline;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ChatStoreTest {
    @Test public void archiveSurvivesReopeningAndCanBeRestored() {
        Application context=RuntimeEnvironment.getApplication();
        long id;
        try(ChatStore store=new ChatStore(context)) {
            id=store.createChat("Verlauf");
            store.add(id,"user","Hallo","");
            store.archiveChat(id,true);
            assertTrue(store.chats().isEmpty());
        }
        try(ChatStore store=new ChatStore(context)) {
            assertEquals(id,store.chats(true).get(0).id);
            assertEquals("Hallo",store.messages(id).get(0).text);
            store.archiveChat(id,false);
            assertTrue(store.chats(true).isEmpty());
            assertEquals(id,store.chats().get(0).id);
            store.deleteChat(id);
            assertTrue(store.chats().isEmpty());
            assertTrue(store.messages(id).isEmpty());
        }
    }
    @Test public void migrationKeepsExistingChatsAndMessages() {
        Application context=RuntimeEnvironment.getApplication();
        try(SQLiteDatabase db=context.openOrCreateDatabase("chats.sqlite",0,null)) {
            db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE, role TEXT NOT NULL, text TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')");
            db.execSQL("INSERT INTO chats VALUES(42,'Bisheriger Chat',100)");
            db.execSQL("INSERT INTO messages VALUES(1,42,'assistant','Gespeichert','')");
            db.setVersion(1);
        }
        try(ChatStore store=new ChatStore(context)) {
            assertEquals(42,store.chats().get(0).id);
            assertEquals("Gespeichert",store.messages(42).get(0).text);
            store.archiveChat(42,true);
            assertTrue(store.chats().isEmpty());
            assertEquals(42,store.chats(true).get(0).id);
        }
    }
    @Test public void versionTwoMigrationAndMemorySurviveReopenAndChatDeletion() {
        Application context=RuntimeEnvironment.getApplication();
        try(SQLiteDatabase db=context.openOrCreateDatabase("chats.sqlite",0,null)) {
            db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE, role TEXT NOT NULL, text TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')");
            db.execSQL("INSERT INTO chats VALUES(42,'Bisheriger Chat',100,1)");
            db.execSQL("INSERT INTO messages VALUES(1,42,'assistant','Gespeichert','')");
            db.setVersion(2);
        }
        String key="name' OR 1=1 --";
        try(ChatStore store=new ChatStore(context)) {
            assertEquals(42,store.chats(true).get(0).id);
            assertEquals("Gespeichert",store.messages(42).get(0).text);
            assertNull(store.recall("missing"));
            store.remember(key,"Alt");
            store.remember(key,"Alex 🪴");
            store.deleteChat(42);
        }
        try(ChatStore store=new ChatStore(context)) {
            assertEquals(3,store.getReadableDatabase().getVersion());
            assertEquals("Alex 🪴",store.recall(key));
            assertNull(store.recall("name"));
            assertTrue(store.chats(true).isEmpty());
        }
    }

}
