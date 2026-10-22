package de.bonsai.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only access to the Python export. No network, model loading or corpus copy. */
final class KnowledgeSearch {
    static final int MAX_HITS=3, MAX_TEXT=600, MAX_HEADING=160, MAX_SOURCE=240, MAX_QUERY=256;
    static final int DIM=256, CANDIDATES=20, MAX_ORIGINAL_BYTES=32768;
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS);
    interface QueryEncoder {
        String model();
        float[] encode(String query) throws Exception;
    }
    private final File database,vectors;
    private final QueryEncoder encoder;
    KnowledgeSearch(File database,File vectors) { this(database,vectors,new SampleEncoder()); }
    KnowledgeSearch(File database,File vectors,QueryEncoder encoder) {
        this.database=database;this.vectors=vectors;this.encoder=encoder;
    }
    static KnowledgeSearch forStore(ChatStore store) throws IOException {
        File internal=new File(store.getReadableDatabase().getPath()).getParentFile().getParentFile();
        File directory=new File(internal,"files/rag");
        Properties config=new Properties();
        File settings=new File(directory,"config.properties");
        if(settings.isFile())try(InputStream in=new FileInputStream(settings)){config.load(in);}
        return new KnowledgeSearch(resolve(internal,directory,config.getProperty("database","knowledge.sqlite")),
            resolve(internal,directory,config.getProperty("vectors","vectors.i8")));
    }
    private static File resolve(File internal,File directory,String path) throws IOException {
        File file=(new File(path).isAbsolute()?new File(path):new File(directory,path)).getCanonicalFile();
        if(!file.getPath().startsWith(internal.getCanonicalPath()+File.separator))
            throw new IOException("RAG-Pfad muss im App-internen Speicher liegen.");
        return file;
    }
    JSONObject search(String query,BooleanSupplier cancelled) throws Exception {
        if(query==null || query.trim().isEmpty() || query.length()>MAX_QUERY)
            throw new IllegalArgumentException("suche erwartet query mit 1 bis 256 Zeichen.");
        if(!database.isFile())throw new IOException("Wissensdatenbank fehlt. Bitte knowledge.sqlite im App-internen RAG-Ordner bereitstellen.");
        check(cancelled);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            if(db.getVersion()!=1)throw new IOException("Unbekannte RAG-Datenbankversion.");
            List<Long> lexical=lexical(db,query);
            List<Long> semantic=new ArrayList<>();
            String warning="";
            if(vectors!=null && vectors.isFile()) {
                String model="";
                try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE key='embedding_model'",null)) {
                    if(c.moveToFirst())model=c.getString(0);
                }
                if(encoder!=null && encoder.model().equals(model))semantic=vectorRanks(encoder.encode(query),cancelled);
                else warning="Nur Textsuche: passender Query-Encoder für "+clip(model,100)+" fehlt.";
            }
            Map<Long,Double> scores=new HashMap<>();
            for(List<Long> ranking:java.util.Arrays.asList(lexical,semantic))for(int i=0;i<ranking.size();i++)
                scores.merge(ranking.get(i),1.0/(61+i),Double::sum);
            List<Long> ids=new ArrayList<>(scores.keySet());
            ids.sort(Comparator.<Long>comparingDouble(id->-scores.get(id)).thenComparingLong(id->id));
            JSONArray hits=new JSONArray();
            for(long id:ids.subList(0,Math.min(MAX_HITS,ids.size()))) {
                check(cancelled);
                try(Cursor c=db.rawQuery("SELECT article_id,title,heading,source,text_zlib FROM chunks WHERE id=?",new String[]{Long.toString(id)})) {
                    if(!c.moveToFirst())throw new IOException("Vektordatei passt nicht zur Datenbank: Chunk fehlt.");
                    hits.put(new JSONObject().put("chunk_id",id).put("article_id",clip(c.getString(0),64))
                        .put("title",clip(c.getString(1),MAX_HEADING)).put("heading",clip(c.getString(2),MAX_HEADING))
                        .put("source",clip(c.getString(3),MAX_SOURCE)).put("text",clip(inflate(c.getBlob(4)),MAX_TEXT)));
                }
            }
            JSONObject result=new JSONObject().put("hits",hits);
            if(!warning.isEmpty())result.put("warning",warning);
            return result;
        }
    }
    private static List<Long> lexical(SQLiteDatabase db,String query) {
        Matcher words=WORD.matcher(query.toLowerCase(Locale.ROOT));
        StringBuilder match=new StringBuilder();
        for(int i=0;i<32 && words.find();i++) {
            if(i>0)match.append(" OR ");
            match.append('"').append(words.group()).append('"');
        }
        if(match.length()==0)return new ArrayList<>();
        try {
            return readIds(db,"SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts),rowid LIMIT 20",match.toString());
        } catch(SQLiteException noFts5) {
            // Android SQLite may omit FTS5; the exporter includes this compatibility index.
            return readIds(db,"SELECT rowid FROM chunks_fts4 WHERE chunks_fts4 MATCH ? ORDER BY rowid LIMIT 20",match.toString());
        }
    }
    private static List<Long> readIds(SQLiteDatabase db,String sql,String query) {
        List<Long> ids=new ArrayList<>();
        try(Cursor c=db.rawQuery(sql,new String[]{query})) { while(c.moveToNext())ids.add(c.getLong(0)); }
        return ids;
    }
    private static final class Scored {
        final long id; final double score;
        Scored(long id,double score){this.id=id;this.score=score;}
    }
    List<Long> vectorRanks(float[] query,BooleanSupplier cancelled) throws IOException {
        if(query.length!=DIM)throw new IOException("Query-Encoder muss 256 Dimensionen liefern.");
        double max=0;
        for(float value:query) {
            if(!Float.isFinite(value))throw new IOException("Ungültiger Query-Vektor.");
            max=Math.max(max,Math.abs(value));
        }
        double norm=0;for(float value:query)norm+=(double)value*value;
        norm=Math.sqrt(norm);
        double queryScale=max==0?1:max/norm/127;
        int[] quantized=new int[DIM];
        for(int i=0;i<DIM;i++) {
            double value=norm==0?0:query[i]/norm/queryScale;
            quantized[i]=(int)Math.copySign(Math.floor(Math.abs(value)+.5),value);
        }
        Comparator<Scored> best=Comparator.<Scored>comparingDouble(s->s.score).reversed().thenComparingLong(s->s.id);
        PriorityQueue<Scored> heap=new PriorityQueue<>(CANDIDATES,best.reversed());
        try(InputStream stream=new java.io.BufferedInputStream(new FileInputStream(vectors))) {
            ByteBuffer header=read(stream,16);
            byte[] magic=new byte[8];header.get(magic);
            int dimensions=header.getInt(),count=header.getInt();
            if(!new String(magic,StandardCharsets.US_ASCII).equals("BRAGV001") || dimensions!=DIM || count<0
                || vectors.length()!=16L+(long)count*(12+DIM))throw new IOException("Ungültige INT8-Vektordatei.");
            byte[] bytes=new byte[12+DIM];
            for(int row=0;row<count;row++) {
                check(cancelled);
                readFully(stream,bytes);
                ByteBuffer record=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                long id=record.getLong();float scale=record.getFloat();
                if(id<=0 || !Float.isFinite(scale) || scale<=0)throw new IOException("Ungültiger INT8-Vektoreintrag.");
                int dot=0;for(int i=0;i<DIM;i++)dot+=quantized[i]*record.get();
                double score=dot*scale*queryScale;
                if(score>0) {
                    heap.add(new Scored(id,score));
                    if(heap.size()>CANDIDATES)heap.poll();
                }
            }
        }
        List<Scored> sorted=new ArrayList<>(heap);sorted.sort(best);
        List<Long> ids=new ArrayList<>();for(Scored item:sorted)ids.add(item.id);
        return ids;
    }
    private static ByteBuffer read(InputStream in,int length) throws IOException {
        byte[] bytes=new byte[length];readFully(in,bytes);return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void readFully(InputStream in,byte[] bytes) throws IOException {
        int offset=0;
        while(offset<bytes.length) {
            int read=in.read(bytes,offset,bytes.length-offset);
            if(read<0)throw new IOException("Abgeschnittene INT8-Vektordatei.");
            offset+=read;
        }
    }
    private static String inflate(byte[] bytes) throws IOException {
        try(InputStream in=new InflaterInputStream(new java.io.ByteArrayInputStream(bytes));
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int read;
            while((read=in.read(buffer))!=-1) {
                if(out.size()+read>MAX_ORIGINAL_BYTES)throw new IOException("RAG-Text überschreitet das Sicherheitslimit.");
                out.write(buffer,0,read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    static String clip(String text,int maximum) {
        if(text.length()<=maximum)return text;
        int end=maximum-1;
        if(end>0 && Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end)+"…";
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");
    }
    /** Must remain identical to rag.embeddings.HashEncoder; fixtures only. */
    static final class SampleEncoder implements QueryEncoder {
        public String model(){return "hash-v1";}
        public float[] encode(String query) {
            float[] vector=new float[DIM];Matcher words=WORD.matcher(query.toLowerCase(Locale.ROOT));
            while(words.find()) {
                int hash=0x811c9dc5;
                for(byte value:words.group().getBytes(StandardCharsets.UTF_8))hash=(hash^(value&255))*16777619;
                vector[hash&255]+=(hash&256)!=0?1:-1;
            }
            return vector;
        }
    }
}
