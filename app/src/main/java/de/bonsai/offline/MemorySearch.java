package de.bonsai.offline;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import org.json.*;

/** Small opt-in derived index. A deletion cascades to vectors; edits invalidate them. */
final class MemorySearch {
    static final String PROFILE=EmbeddingGemma2Encoder.PROFILE+":"+KnowledgePackages.ENCODER_SHA+":memory-doc-v1";
    interface Encoder {
        float[] query(String text,BooleanSupplier cancelled) throws Exception;
        float[] document(String key,String value,BooleanSupplier cancelled) throws Exception;
    }
    static JSONObject search(ChatStore store,String query,BooleanSupplier cancelled) throws Exception {
        boolean semantic=store.context.getSharedPreferences("settings",0).getBoolean("semanticMemory",false);
        EmbeddingGemma2Encoder encoder=new EmbeddingGemma2Encoder(KnowledgePackages.encoder(KnowledgePackages.files(store)),2);
        return search(store,query,semantic,new Encoder(){
            public float[] query(String text,BooleanSupplier c) throws Exception{return encoder.encode(text,c);}
            public float[] document(String key,String value,BooleanSupplier c) throws Exception{return encoder.encodeDocument(key,value,c);}
        },cancelled);
    }
    static JSONObject search(ChatStore store,String query,boolean semantic,Encoder encoder,BooleanSupplier cancelled) throws Exception {
        if(query==null || query.trim().isEmpty() || query.length()>256)throw new IllegalArgumentException("Suchtext: 1–256 Zeichen.");
        check(cancelled);
        Map<String,Double> scores=new HashMap<>();Map<String,ChatStore.Memory> entries=new HashMap<>();
        List<ChatStore.Memory> all=store.memories("");
        String[] terms=query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for(ChatStore.Memory m:all){entries.put(m.key,m);String text=(m.key+" "+m.value).toLowerCase(Locale.ROOT);double score=0;for(String term:terms)if(!term.isEmpty()&&text.contains(term))score+=1; if(score>0)scores.put(m.key,score/(terms.length+1));}
        String warning="";int indexed=0;
        if(semantic && !all.isEmpty()) {
            try {
                float[] q=normalized(encoder.query(query,cancelled));int built=0;
                for(ChatStore.Memory memory:all.subList(0,Math.min(200,all.size()))) {
                    check(cancelled);float[] vector=load(store,memory.key);
                    if(vector==null && built<8) {
                        vector=normalized(encoder.document(memory.key,memory.value,cancelled));built++;check(cancelled);
                        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
                        try {
                            // Recheck the snapshot: never recreate a deleted/changed memory.
                            if(memory.value.equals(store.recall(memory.key))) {
                                ContentValues row=new ContentValues();row.put("key",memory.key);row.put("profile",PROFILE);row.put("vector",bytes(vector));db.insertWithOnConflict("memory_vectors",null,row,SQLiteDatabase.CONFLICT_REPLACE);
                            }else vector=null;
                            db.setTransactionSuccessful();
                        }finally{db.endTransaction();}
                    }
                    if(vector!=null){indexed++;double dot=0;for(int i=0;i<q.length;i++)dot+=q[i]*vector[i];if(dot>=.3)scores.merge(memory.key,dot,Double::sum);}
                }
                if(indexed<all.size())warning="Teilindex: "+indexed+" von "+all.size()+" Erinnerungen berücksichtigt; pro Suche maximal 8 neue Vektoren, insgesamt 200 aktuelle Einträge. Textsuche berücksichtigt alle Einträge.";
            }catch(CancellationException e){throw e;}
            catch(Exception | LinkageError e){warning="Nur Textsuche: lokales Embedding-Modell nicht verfügbar oder Indexfehler. "+e.getClass().getSimpleName();}
        }
        check(cancelled);List<String> keys=new ArrayList<>(scores.keySet());keys.sort(Comparator.<String>comparingDouble(k->-scores.get(k)).thenComparing(k->k));
        JSONArray hits=new JSONArray();
        for(String key:keys.subList(0,Math.min(5,keys.size()))){ChatStore.Memory m=entries.get(key);if(!m.value.equals(store.recall(key)))continue;hits.put(new JSONObject().put("key",key).put("value",m.value).put("source",m.source).put("updated",m.updated));}
        return new JSONObject().put("hits",hits).put("semantic_enabled",semantic).put("indexed",indexed).put("warning",warning).put("note","Ähnlichkeit ist kein Wahrheitsnachweis; Treffer sind tatsächlich gespeicherte Texte.");
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");}
    private static float[] normalized(float[] vector){if(vector==null || vector.length!=256)throw new IllegalArgumentException("Vektordimension");double norm=0;for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Vektorwert");norm+=(double)f*f;}if(norm<1e-12)throw new IllegalArgumentException("Leerer Vektor");float[] result=vector.clone();for(int i=0;i<result.length;i++)result[i]/=Math.sqrt(norm);return result;}
    private static byte[] bytes(float[] vector){ByteBuffer out=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);for(float f:vector)out.putFloat(f);return out.array();}
    private static float[] load(ChatStore store,String key){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT vector FROM memory_vectors WHERE key=? AND profile=?",new String[]{key,PROFILE})){if(!c.moveToFirst())return null;byte[] bytes=c.getBlob(0);if(bytes.length!=1024)return null;ByteBuffer in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] vector=new float[256];for(int i=0;i<256;i++)vector[i]=in.getFloat();try{return normalized(vector);}catch(IllegalArgumentException invalid){return null;}}}
    private MemorySearch(){}
}
