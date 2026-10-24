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

/** Read-only access to the Python export with optional local query inference. */
final class KnowledgeSearch {
    static final int MAX_HITS=3, MAX_TEXT=600, MAX_HEADING=160, MAX_SOURCE=240, MAX_QUERY=256;
    static final int DIM=256, CANDIDATES=20, MAX_ORIGINAL_BYTES=32768;
    // Android rejects UNICODE_CHARACTER_CLASS; explicit Unicode categories need no flag.
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    interface QueryEncoder {
        String model();
        float[] encode(String query) throws Exception;
        default float[] encode(String query,BooleanSupplier cancelled) throws Exception {return encode(query);}
    }
    private final File database,vectors,ivf;
    private final int nprobe,rrfK;
    private final QueryEncoder encoder;
    KnowledgeSearch(File database,File vectors) { this(database,vectors,new SampleEncoder()); }
    KnowledgeSearch(File database,File vectors,QueryEncoder encoder) {
        this(database,vectors,null,encoder,16,60);
    }
    KnowledgeSearch(File database,File vectors,File ivf,QueryEncoder encoder,int nprobe,int rrfK) {
        if(nprobe<1 || rrfK<0)throw new IllegalArgumentException("nprobe muss positiv und rrf_k nichtnegativ sein.");
        this.database=database;this.vectors=vectors;this.ivf=ivf;this.encoder=encoder;this.nprobe=nprobe;this.rrfK=rrfK;
    }
    static KnowledgeSearch forStore(ChatStore store) throws IOException {
        File internal=new File(store.getReadableDatabase().getPath()).getParentFile().getParentFile();
        File directory=new File(internal,"files/rag");
        Properties config=new Properties();
        File settings=new File(directory,"config.properties");
        if(settings.isFile())try(InputStream in=new FileInputStream(settings)){config.load(in);}
        File ivf=resolve(internal,directory,config.getProperty("ivf","knowledge.ivf"));
        if(!ivf.isFile() && config.containsKey("ivf"))throw new IOException("IVF-Index fehlt: "+ivf.getName());
        try {
            String encoderModel=config.getProperty("encoder_model",EmbeddingGemma2Encoder.MODEL);
            QueryEncoder queryEncoder;
            if(encoderModel.equals("hash-v1"))queryEncoder=new SampleEncoder();
            else if(encoderModel.equals(EmbeddingGemma2Encoder.MODEL))queryEncoder=new EmbeddingGemma2Encoder(
                resolve(internal,directory,config.getProperty("encoder_path","embeddinggemma-2-Q8_0.gguf")),
                Integer.parseInt(config.getProperty("encoder_threads","4")));
            else throw new IllegalArgumentException("Unbekanntes encoder_model: "+encoderModel);
            return new KnowledgeSearch(resolve(internal,directory,config.getProperty("database","knowledge.sqlite")),
                resolve(internal,directory,config.getProperty("vectors","vectors.i8")),ivf.isFile()?ivf:null,queryEncoder,
                Integer.parseInt(config.getProperty("nprobe","16")),Integer.parseInt(config.getProperty("rrf_k","60")));
        } catch(IllegalArgumentException e){throw new IOException("Ungültige RAG-Suchkonfiguration: "+e.getMessage(),e);}
    }
    private static File resolve(File internal,File directory,String path) throws IOException {
        if(!new File(path).isAbsolute() && (path.startsWith("/") || path.startsWith("\\")))
            throw new IOException("RAG-Pfad muss im App-internen Speicher liegen.");
        File file=(new File(path).isAbsolute()?new File(path):new File(directory,path)).getCanonicalFile();
        if(!file.getPath().startsWith(internal.getCanonicalPath()+File.separator))
            throw new IOException("RAG-Pfad muss im App-internen Speicher liegen.");
        return file;
    }
    static JSONObject searchInstalled(ChatStore store,String query,BooleanSupplier cancelled) throws Exception {
        return searchInstalled(store,query,"all",cancelled);
    }
    static JSONObject searchInstalled(ChatStore store,String query,String corpus,BooleanSupplier cancelled) throws Exception {
        if(!java.util.Arrays.asList("all","wikipedia","wikibooks","stackexchange").contains(corpus))throw new IllegalArgumentException("Unbekannter Textkorpus.");
        File files=KnowledgePackages.files(store);
        List<KnowledgePackages.Installed> packages=KnowledgePackages.active(files,"rag");
        boolean installedRag=false;
        for(KnowledgePackages.Installed item:KnowledgePackages.installed(files))if(item.type().equals("rag"))installedRag=true;
        if(packages.isEmpty() && !installedRag && (corpus.equals("all") || corpus.equals("wikipedia")))return forStore(store).search(query,cancelled);
        List<JSONObject> candidates=new ArrayList<>();JSONArray warnings=new JSONArray();
        EmbeddingGemma2Encoder baseEncoder=new EmbeddingGemma2Encoder(KnowledgePackages.encoder(files),4);
        QueryEncoder sharedEncoder=new QueryEncoder() {
            private float[] vector;
            public String model(){return EmbeddingGemma2Encoder.MODEL;}
            public float[] encode(String text) throws Exception{return encode(text,cancelled);}
            public float[] encode(String text,BooleanSupplier stop) throws Exception {if(vector==null)vector=baseEncoder.encode(text,stop);return vector;}
        };
        // All installed RAG packages have the same validated normalized embedding
        // profile. Merge semantic candidates globally before RRF; local first
        // ranks must not give alphabetically early packages permanent priority.
        for(KnowledgePackages.Installed item:packages) {
            if(!corpus.equals("all") && !corpus.equals(item.manifest.getJSONObject("source").getString("corpus")))continue;
            check(cancelled);
            KnowledgeSearch search=new KnowledgeSearch(new File(item.directory,"knowledge.sqlite"),null,new File(item.directory,"knowledge.ivf"),
                sharedEncoder,KnowledgePackages.nprobe(item.manifest),60);
            JSONObject result=search.search(query,cancelled,CANDIDATES*2,true);JSONArray hits=result.getJSONArray("hits");
            if(result.has("warning"))warnings.put(item.id()+": "+result.getString("warning"));
            for(int i=0;i<hits.length();i++)candidates.add(hits.getJSONObject(i).put("package_id",item.id()));
        }
        JSONArray hits=fusePackages(candidates);
        JSONObject result=new JSONObject().put("hits",hits);
        if(warnings.length()>0)result.put("warning",warnings.toString());
        if(packages.isEmpty())result.put("warning","Keine Textpakete aktiviert.");
        return result;
    }
    private static String identity(JSONObject hit){return hit.optString("package_id")+":"+hit.optLong("chunk_id");}
    static JSONArray fusePackages(List<JSONObject> candidates) throws Exception {
        List<JSONObject> lexical=new ArrayList<>(),semantic=new ArrayList<>();
        for(JSONObject hit:candidates){if(hit.optInt("_lexical_rank")>0)lexical.add(hit);if(hit.has("_semantic_score"))semantic.add(hit);}
        Comparator<JSONObject> stable=Comparator.comparing(KnowledgeSearch::identity);
        Comparator<JSONObject> cosine=Comparator.<JSONObject>comparingDouble(hit->-hit.optDouble("_semantic_score",-Double.MAX_VALUE)).thenComparing(stable);
        semantic.sort(cosine);
        // BM25 values use collection-specific IDF. Interleave local lexical
        // ranks, resolving equal ranks by the comparable normalized cosine.
        lexical.sort(Comparator.<JSONObject>comparingInt(hit->hit.optInt("_lexical_rank")).thenComparing(cosine));
        Map<String,Double> scores=new HashMap<>();
        for(List<JSONObject> ranking:java.util.Arrays.asList(lexical,semantic))for(int i=0;i<ranking.size();i++)scores.merge(identity(ranking.get(i)),1.0/(61+i),Double::sum);
        List<JSONObject> ordered=new ArrayList<>(candidates);
        ordered.sort(Comparator.<JSONObject>comparingDouble(hit->-scores.getOrDefault(identity(hit),0.)).thenComparing(cosine));
        JSONArray result=new JSONArray();
        for(int i=0;i<Math.min(MAX_HITS,ordered.size());i++) {
            JSONObject hit=new JSONObject(ordered.get(i).toString());hit.remove("_lexical_rank");hit.remove("_semantic_score");result.put(hit);
        }
        return result;
    }
    JSONObject search(String query,BooleanSupplier cancelled) throws Exception {
        return search(query,cancelled,MAX_HITS,false);
    }
    private JSONObject search(String query,BooleanSupplier cancelled,int maximumHits,boolean rankMetadata) throws Exception {
        if(query==null || query.trim().isEmpty() || query.length()>MAX_QUERY)
            throw new IllegalArgumentException("suche erwartet query mit 1 bis 256 Zeichen.");
        if(!database.isFile())throw new IOException("Wissensdatenbank fehlt. Bitte knowledge.sqlite im App-internen RAG-Ordner bereitstellen.");
        check(cancelled);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            if(db.getVersion()!=1)throw new IOException("Unbekannte RAG-Datenbankversion.");
            List<Long> lexical=lexical(db,query,cancelled);
            List<Long> semantic=new ArrayList<>();
            Map<Long,Double> semanticScores=new HashMap<>();
            String warning="";
            if(ivf!=null || (vectors!=null && vectors.isFile())) {
                String model="";
                try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE key='embedding_model'",null)) {
                    if(c.moveToFirst())model=c.getString(0);
                }
                // Existing fixture installations continue to work without configuration.
                QueryEncoder selected="hash-v1".equals(model) && encoder instanceof EmbeddingGemma2Encoder?new SampleEncoder():encoder;
                if(selected!=null && selected.model().equals(model)) {
                    boolean compatible=true;
                    if(EmbeddingGemma2Encoder.MODEL.equals(model)) {
                        try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE key='embedding_profile'",null)) {
                            compatible=c.moveToFirst() && EmbeddingGemma2Encoder.PROFILE.equals(c.getString(0));
                        }
                    }
                    float[] vector=null;
                    if(!compatible)warning="Nur Textsuche: EmbeddingGemma-2-Index mit aktuellem RAG-Export neu erstellen (embedding_profile).";
                    else try {vector=selected.encode(query,cancelled);}
                    catch(CancellationException e){throw e;}
                    catch(Exception | LinkageError e) {
                        check(cancelled);
                        warning="Nur Textsuche: Query-Encoder fehlgeschlagen: "+clip(String.valueOf(e.getMessage()),160);
                    }
                    check(cancelled);
                    if(vector!=null) {
                        if(ivf==null)semantic=vectorRanks(vector,cancelled);
                        else {
                            IvfIndex index=mappedIndex(ivf);IvfResult ranked=index.search(vector,nprobe,CANDIDATES,cancelled,true,false);semantic=ranked.ranking();
                            for(int i=0;i<ranked.ids.length;i++)semanticScores.put(ranked.ids[i],ranked.dots[i]*(double)index.scale*index.scale);
                        }
                    }
                }
                else warning="Nur Textsuche: passender Query-Encoder für "+clip(model,100)+" fehlt.";
            }
            Map<Long,Double> scores=new HashMap<>();
            for(List<Long> ranking:java.util.Arrays.asList(lexical,semantic))for(int i=0;i<ranking.size();i++)
                scores.merge(ranking.get(i),1.0/(rrfK+1.0+i),Double::sum);
            List<Long> ids=new ArrayList<>(scores.keySet());
            ids.sort(Comparator.<Long>comparingDouble(id->-scores.get(id)).thenComparingLong(id->id));
            JSONArray hits=new JSONArray();
            for(long id:ids.subList(0,Math.min(maximumHits,ids.size()))) {
                check(cancelled);
                try(Cursor c=db.rawQuery("SELECT article_id,title,heading,source,text_zlib FROM chunks WHERE id=?",new String[]{Long.toString(id)})) {
                    if(!c.moveToFirst())throw new IOException("Vektordatei passt nicht zur Datenbank: Chunk fehlt.");
                    JSONObject hit=new JSONObject().put("chunk_id",id).put("article_id",clip(c.getString(0),64))
                        .put("title",clip(c.getString(1),MAX_HEADING)).put("heading",clip(c.getString(2),MAX_HEADING))
                        .put("source",clip(c.getString(3),MAX_SOURCE)).put("text",clip(inflate(c.getBlob(4)),MAX_TEXT));
                    if(rankMetadata){hit.put("_lexical_rank",lexical.indexOf(id)+1);if(semanticScores.containsKey(id))hit.put("_semantic_score",semanticScores.get(id));}
                    try(Cursor exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='post_metadata'",null)) {
                        if(exists.moveToFirst())try(Cursor attribution=db.rawQuery("SELECT data_json FROM post_metadata WHERE article_id=? AND length(data_json)<=8192",new String[]{c.getString(0)})) {
                            if(attribution.moveToFirst())hit.put("attribution",new JSONObject(attribution.getString(0)));
                        }
                    }
                    hits.put(hit);
                }
            }
            JSONObject result=new JSONObject().put("hits",hits);
            if(!warning.isEmpty())result.put("warning",warning);
            return result;
        }
    }
    private static List<Long> lexical(SQLiteDatabase db,String query,BooleanSupplier cancelled) throws IOException {
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
            return Fts4Search.search(db,query,cancelled);
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
    /** One immutable mapped snapshot, shared by queries; vector bytes never enter the heap. */
    static final class IvfIndex {
        static final int HEADER_BYTES=128, MAP_BYTES=256*1024*1024;
        final int clusters;
        final long count;
        final float scale;
        private final ByteBuffer metadata;
        private final ByteBuffer[] pages;
        private final long centersOffset,idsOffset;
        private final long[] offsets;
        IvfIndex(File file) throws IOException {
            if(!file.isFile())throw new IOException("IVF-Index fehlt: "+file.getName());
            try(java.io.RandomAccessFile source=new java.io.RandomAccessFile(file,"r");
                java.nio.channels.FileChannel channel=source.getChannel()) {
                if(channel.size()<HEADER_BYTES)throw invalid("Header abgeschnitten");
                ByteBuffer header=channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic=new byte[8];header.get(magic);
                int version=header.getInt(),headerBytes=header.getInt(),dimension=header.getInt();
                clusters=header.getInt();count=header.getLong();scale=header.getFloat();int flags=header.getInt();
                long offsetsOffset=header.getLong();centersOffset=header.getLong();idsOffset=header.getLong();
                long vectorsOffset=header.getLong(),fileBytes=header.getLong();header.getLong(); // training seed
                for(int i=88;i<HEADER_BYTES;i++)if(header.get(i)!=0)throw invalid("Reservierte Headerbytes");
                if(!new String(magic,StandardCharsets.US_ASCII).equals("BRAGIVF1") || version!=1 || headerBytes!=HEADER_BYTES
                    || dimension!=DIM || clusters<1 || count<0 || count>Long.MAX_VALUE/(DIM+8L)
                    || !Float.isFinite(scale) || scale<=0 || flags!=1)throw invalid("Header/Version/Dimension/Skalierung");
                long expectedCenters=HEADER_BYTES+((long)clusters+1)*8;
                long expectedIds=expectedCenters+(long)clusters*DIM*4;
                long expectedVectors=((expectedIds+count*8+4095)/4096)*4096;
                if(offsetsOffset!=HEADER_BYTES || centersOffset!=expectedCenters || idsOffset!=expectedIds
                    || vectorsOffset!=expectedVectors || vectorsOffset>Integer.MAX_VALUE
                    || fileBytes!=vectorsOffset+count*DIM || fileBytes!=channel.size())throw invalid("Dateigröße/Offsets");
                metadata=channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,vectorsOffset).order(ByteOrder.LITTLE_ENDIAN);
                offsets=new long[clusters+1];
                for(int i=0;i<=clusters;i++) {
                    offsets[i]=metadata.getLong((int)(HEADER_BYTES+(long)i*8));
                    if(offsets[i]<0 || offsets[i]>count || (i>0 && offsets[i]<offsets[i-1]))throw invalid("Cluster-Offsets");
                }
                if(offsets[0]!=0 || offsets[clusters]!=count)throw invalid("Cluster-Grenzen");
                for(long at=centersOffset;at<idsOffset;at+=4)
                    if(!Float.isFinite(metadata.getFloat((int)at)))throw invalid("Zentroid enthält ungültige Werte");
                int pageCount=(int)((count*DIM+MAP_BYTES-1)/MAP_BYTES);
                pages=new ByteBuffer[pageCount];
                for(int i=0;i<pageCount;i++) {
                    long relative=(long)i*MAP_BYTES;
                    pages[i]=channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,vectorsOffset+relative,
                        Math.min(MAP_BYTES,count*DIM-relative));
                }
            } catch(IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new IOException("Ungültiger IVF-Index: "+e.getMessage(),e);
            }
        }
        private static IOException invalid(String detail){return new IOException("Ungültiger IVF-Index: "+detail);}
        IvfResult search(float[] input,int nprobe,int topK,BooleanSupplier cancelled,boolean positiveOnly,boolean fullScan) throws IOException {
            if(input==null || input.length!=DIM || nprobe<1 || topK<1 || topK>10000)throw invalid("Query/nprobe/Top-k");
            check(cancelled);
            double norm=0;
            for(float value:input) {
                if(!Float.isFinite(value))throw invalid("Query-Vektor");
                norm+=(double)value*value;
            }
            norm=Math.sqrt(norm);
            float[] query=new float[DIM];int[] quantized=new int[DIM];
            for(int d=0;d<DIM;d++) {
                query[d]=norm==0?0:(float)(input[d]/norm);
                double value=(double)query[d]/scale;
                quantized[d]=(int)Math.max(-127,Math.min(127,Math.copySign(Math.floor(Math.abs(value)+.5),value)));
            }
            int probes=fullScan?clusters:Math.min(nprobe,clusters);
            int[] chosen=new int[probes];double[] distances=new double[probes];
            java.util.Arrays.fill(distances,Double.POSITIVE_INFINITY);
            long start=System.nanoTime();
            if(fullScan)for(int i=0;i<clusters;i++)chosen[i]=i;
            else for(int cluster=0;cluster<clusters;cluster++) {
                if((cluster&63)==0)check(cancelled);
                double distance=0;int at=(int)(centersOffset+(long)cluster*DIM*4);
                for(int d=0;d<DIM;d++) {
                    double center=metadata.getFloat(at+d*4);
                    distance+=center*center-2*center*query[d];
                }
                if(distance>=distances[probes-1])continue; // equal distances keep the lower cluster ID
                int position=probes-1;
                while(position>0 && distance<distances[position-1]) {
                    chosen[position]=chosen[position-1];distances[position]=distances[position-1];position--;
                }
                chosen[position]=cluster;distances[position]=distance;
            }
            long centroidNanos=System.nanoTime()-start;start=System.nanoTime();
            IntHeap heap=new IntHeap(topK);long scanned=0;
            for(int cluster:chosen) {
                check(cancelled);
                long position=offsets[cluster],end=offsets[cluster+1];scanned+=end-position;
                while(position<end) {
                    long relative=position*DIM;
                    int page=(int)(relative/MAP_BYTES),at=(int)(relative%MAP_BYTES);
                    ByteBuffer bytes=pages[page];
                    long until=Math.min(end,((long)page+1)*MAP_BYTES/DIM);
                    for(;position<until;position++,at+=DIM) {
                        if((position&1023)==0)check(cancelled);
                        int dot=0;
                        for(int d=0;d<DIM;d++)dot+=quantized[d]*bytes.get(at+d);
                        if((!positiveOnly || dot>0) && heap.mayEnter(dot)) {
                            long id=metadata.getLong((int)(idsOffset+position*8));
                            if(id<=0)throw invalid("Chunk-ID");
                            heap.add(id,dot);
                        }
                    }
                }
            }
            long scanNanos=System.nanoTime()-start;
            long[] ids=new long[heap.size];int[] dots=new int[heap.size];
            for(int i=ids.length-1;i>=0;i--){ids[i]=heap.ids[0];dots[i]=heap.dots[0];heap.remove();}
            return new IvfResult(ids,dots,centroidNanos,scanNanos,scanned);
        }
    }
    static final class IvfResult {
        final long[] ids;final int[] dots;final long centroidNanos,scanNanos,scanned;
        IvfResult(long[] ids,int[] dots,long centroidNanos,long scanNanos,long scanned) {
            this.ids=ids;this.dots=dots;this.centroidNanos=centroidNanos;this.scanNanos=scanNanos;this.scanned=scanned;
        }
        List<Long> ranking(){List<Long> result=new ArrayList<>(ids.length);for(long id:ids)result.add(id);return result;}
    }
    /** Primitive min-heap: weakest score / largest ID at root; no per-vector allocation. */
    private static final class IntHeap {
        final long[] ids;final int[] dots;int size;
        IntHeap(int capacity){ids=new long[capacity];dots=new int[capacity];}
        boolean mayEnter(int dot){return size<ids.length || dot>=dots[0];}
        private boolean weaker(int a,int b){return dots[a]<dots[b] || (dots[a]==dots[b] && ids[a]>ids[b]);}
        private void swap(int a,int b){long id=ids[a];ids[a]=ids[b];ids[b]=id;int dot=dots[a];dots[a]=dots[b];dots[b]=dot;}
        void add(long id,int dot) {
            if(size==ids.length) {
                if(dot<dots[0] || (dot==dots[0] && id>=ids[0]))return;
                ids[0]=id;dots[0]=dot;sink();
            } else {
                int at=size++;ids[at]=id;dots[at]=dot;
                while(at>0 && weaker(at,(at-1)/2)){int parent=(at-1)/2;swap(at,parent);at=parent;}
            }
        }
        void remove(){--size;if(size>0){ids[0]=ids[size];dots[0]=dots[size];sink();}}
        private void sink(){int at=0;while(at*2+1<size){int child=at*2+1;if(child+1<size && weaker(child+1,child))child++;if(!weaker(child,at))break;swap(at,child);at=child;}}
    }
    private static final Map<String,IvfIndex> mappedIndexes=new java.util.LinkedHashMap<>(16,.75f,true);
    private static synchronized IvfIndex mappedIndex(File file) throws IOException {
        File canonical=file.getCanonicalFile();
        String key=canonical.getPath()+":"+file.length()+":"+file.lastModified();
        IvfIndex cached=mappedIndexes.get(key);
        if(cached==null) {
            cached=new IvfIndex(file);mappedIndexes.put(key,cached);
            if(mappedIndexes.size()>16)mappedIndexes.remove(mappedIndexes.keySet().iterator().next());
        }
        return cached;
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
