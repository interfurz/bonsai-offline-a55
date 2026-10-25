package de.bonsai.offline;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=android.app.Application.class)
public class MemorySearchTest {
    private static float[] vector(int coordinate){float[] v=new float[256];v[coordinate]=1;return v;}
    private static class Encoder implements MemorySearch.Encoder {
        final AtomicInteger documents=new AtomicInteger();
        public float[] query(String text,BooleanSupplier c){return vector(0);}
        public float[] document(String key,String value,BooleanSupplier c){documents.incrementAndGet();return vector(value.contains("Bahn")?0:1);}
    }
    @Test public void semanticCandidatesPersistAndEditsAndDeletesInvalidateVectors() throws Exception {
        Encoder encoder=new Encoder();
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            store.remember("pendeln","Ich fahre Bahn");store.remember("essen","Ich mag Äpfel");
            org.json.JSONObject result=MemorySearch.search(store,"Arbeitsweg",true,encoder,()->false);
            assertEquals("pendeln",result.getJSONArray("hits").getJSONObject(0).getString("key"));assertEquals(2,encoder.documents.get());
        }
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            assertEquals(1,MemorySearch.search(store,"Arbeitsweg",true,encoder,()->false).getJSONArray("hits").length());assertEquals(2,encoder.documents.get());
            store.remember("pendeln","Ich gehe zu Fuß");
            assertEquals(0,MemorySearch.search(store,"Arbeitsweg",true,encoder,()->false).getJSONArray("hits").length());assertEquals(3,encoder.documents.get());
            store.forget("pendeln");store.forget("essen");
            try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM memory_vectors",null)){c.moveToFirst();assertEquals(0,c.getInt(0));}
            assertEquals(0,MemorySearch.search(store,"Arbeitsweg",true,encoder,()->false).getJSONArray("hits").length());
        }
    }
    @Test public void optedOutSearchDoesNotLoadEncoderAndErrorsKeepLexicalResults() throws Exception {
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            store.remember("name","Alex");
            Encoder unavailable=new Encoder(){@Override public float[] query(String text,BooleanSupplier c){throw new IllegalStateException("no model");}};
            assertEquals(1,MemorySearch.search(store,"Alex",false,unavailable,()->false).getJSONArray("hits").length());
            org.json.JSONObject result=MemorySearch.search(store,"Alex",true,unavailable,()->false);assertEquals(1,result.getJSONArray("hits").length());assertTrue(result.getString("warning").startsWith("Nur Textsuche"));
            assertEquals(0,MemorySearch.search(store,"unbekannt",false,unavailable,()->false).getJSONArray("hits").length());
        }
    }
    @Test public void cancellationBeforeCommitDoesNotLeaveDerivedData() throws Exception {
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            store.remember("test","Bahn");AtomicBoolean cancelled=new AtomicBoolean();
            Encoder encoder=new Encoder(){@Override public float[] document(String k,String v,BooleanSupplier c){cancelled.set(true);return vector(0);}};
            try{MemorySearch.search(store,"Weg",true,encoder,cancelled::get);fail();}catch(CancellationException expected){}
            try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM memory_vectors",null)){c.moveToFirst();assertEquals(0,c.getInt(0));}
        }
    }
}
