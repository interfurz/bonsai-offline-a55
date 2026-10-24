package de.bonsai.offline;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import java.util.concurrent.CancellationException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class Fts4SearchTest {
    @Test public void rareSubjectRanksAboveUnrelatedLowIdsAndUnicodeQueryWorks() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE VIRTUAL TABLE chunks_fts4 USING fts4(title,heading,text,tokenize=unicode61)");
            for(int id=1;id<=30;id++)db.execSQL("INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(?,?,?,?)",new Object[]{id,"Unrelated","Intro","was ist die und wenn Menschen"});
            db.execSQL("INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(100,'Antenne','Polarisation','Wenn die Polarisation nicht übereinstimmt sinkt die Signalstärke')");
            assertEquals(Long.valueOf(100),Fts4Search.search(db,"Was ist die Polarisation einer Antenne?",()->false).get(0));
            assertEquals(Long.valueOf(100),Fts4Search.search(db,"ＡＮＴＥＮＮＥ",()->false).get(0));
            assertTrue(Fts4Search.search(db,"zzzunbekannt",()->false).isEmpty());
            assertTrue(Fts4Search.search(db,"was ist die",()->false).isEmpty());
            try {Fts4Search.search(db,"Antenne",()->true);fail();}catch(CancellationException expected){}
        }
    }
}
