package de.bonsai.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.os.StatFs;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.*;

/** Immutable generations. Registry replacement is the only activation operation. */
final class KnowledgePackages {
    static final String ENCODER_SHA="2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135";
    static final long ENCODER_BYTES=309855456L;
    private static final Object IMPORT_LOCK=new Object(), REGISTRY_LOCK=new Object();
    private static final Set<String> NAMES=new HashSet<>(Arrays.asList("knowledge.sqlite","knowledge.ivf","vectors.i8","sources.json","LICENSES.txt"));
    private static final long RESERVE=128L*1024*1024;
    interface Progress {void update(String text);}
    static final class Installed {
        final JSONObject manifest; final File directory; final boolean enabled;
        Installed(JSONObject manifest,File directory,boolean enabled){this.manifest=manifest;this.directory=directory;this.enabled=enabled;}
        String id(){return manifest.optString("package_id");}
        String type(){return manifest.optString("type");}
    }
    static File files(ChatStore store) throws IOException {
        return new File(new File(store.getReadableDatabase().getPath()).getCanonicalFile().getParentFile().getParentFile(),"files");
    }
    private static File root(File files) throws IOException {
        File root=new File(files,"knowledge");
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("Wissensverzeichnis nicht verfügbar.");
        return root;
    }
    private static JSONObject registry(File files) throws Exception {
        AtomicFile file=new AtomicFile(new File(root(files),"active.json"));
        if(!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists())return new JSONObject();
        try(InputStream in=file.openRead()){return new JSONObject(readSmall(in,262144));}
    }
    private static void writeRegistry(File files,JSONObject value) throws Exception {
        File directory=root(files),target=new File(directory,"active.json");
        File staged=new File(directory,"active-"+UUID.randomUUID()+".new");
        try(FileOutputStream out=new FileOutputStream(staged)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        // Require a same-filesystem atomic rename. A provider/filesystem that
        // cannot provide it must fail rather than remove the old registry first.
        java.nio.file.Files.move(staged.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    static List<Installed> installed(File files) throws Exception {
        synchronized(REGISTRY_LOCK) {
            JSONObject registry=registry(files);List<Installed> result=new ArrayList<>();
            for(Iterator<String> keys=registry.keys();keys.hasNext();) {
                String id=keys.next();JSONObject record=registry.getJSONObject(id);
                String generation=record.getString("generation");
                if(!generation.matches("[a-f0-9-]{36}"))throw new IOException("Ungültige Paketgeneration.");
                File directory=new File(root(files),generation);
                JSONObject manifest;
                try(InputStream in=new FileInputStream(new File(directory,"manifest.json"))){manifest=new JSONObject(readSmall(in,262144));}
                validateManifest(manifest);
                if(!id.equals(manifest.getString("package_id")))throw new IOException("Paketidentität stimmt nicht.");
                result.add(new Installed(manifest,directory,record.getBoolean("enabled")));
            }
            return result;
        }
    }
    static List<Installed> active(File files,String type) throws Exception {
        List<Installed> result=new ArrayList<>();
        for(Installed item:installed(files))if(item.enabled && item.type().equals(type))result.add(item);
        return result;
    }
    static void setEnabled(File files,String id,boolean enabled) throws Exception {
        synchronized(REGISTRY_LOCK) {
            JSONObject value=registry(files);value.getJSONObject(id).put("enabled",enabled);writeRegistry(files,value);
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Import abgebrochen; aktives Paket bleibt erhalten.");
    }
    private static String readSmall(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(output.size()+n>limit)throw new IOException("Paketmanifest zu groß.");output.write(buffer,0,n);}
        return output.toString(StandardCharsets.UTF_8.name());
    }
    static void validateManifest(JSONObject m) throws Exception {
        if(!"bonsai-offline-package".equals(m.getString("format")) || m.getInt("format_version")!=1 || m.getInt("schema_version")!=1)throw new IOException("Unbekanntes Paketformat.");
        if(!m.getString("package_id").matches("[a-z][a-z0-9.-]{1,63}") || !m.getString("version").matches("[a-zA-Z0-9._-]{1,80}"))throw new IOException("Ungültige Paketkennung/Version.");
        String type=m.getString("type");
        if(!Arrays.asList("rag","wikidata","dictionary","tables").contains(type))throw new IOException("Unbekannter Datenbanktyp.");
        JSONObject source=m.getJSONObject("source");
        java.time.LocalDate.parse(source.getString("date"));
        for(String key:Arrays.asList("dataset","url","license"))if(source.getString(key).isEmpty())throw new IOException("Quellennachweis fehlt.");
        if(!"passed".equals(m.getJSONObject("integrity").getString("integrity")))throw new IOException("Paket wurde nicht validiert.");
        JSONObject counts=m.getJSONObject("counts");
        for(String key:Arrays.asList("documents","chunks","records"))if(counts.getLong(key)<0)throw new IOException("Ungültige Datensatzanzahl.");
        Set<String> names=new HashSet<>();JSONArray files=m.getJSONArray("files");
        if(files.length()<1 || files.length()>NAMES.size())throw new IOException("Ungültige Dateiliste.");
        for(int i=0;i<files.length();i++) {
            JSONObject f=files.getJSONObject(i);String name=f.getString("name");
            if(!NAMES.contains(name) || !names.add(name) || f.getLong("bytes")<0 || !f.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Ungültiger Dateivertrag.");
        }
        if(!names.contains("knowledge.sqlite"))throw new IOException("SQLite fehlt.");
        if(type.equals("rag")) {
            JSONObject e=m.getJSONObject("embedding");
            if(!EmbeddingGemma2Encoder.MODEL.equals(e.getString("model")) || !EmbeddingGemma2Encoder.PROFILE.equals(e.getString("profile")) || e.getInt("dimension")!=256 || !e.getString("revision").matches("[a-f0-9]{40}") || !names.contains("knowledge.ivf"))throw new IOException("Inkompatibles Embeddingprofil.");
            if(!ENCODER_SHA.equals(e.getString("query_gguf_sha256")))throw new IOException("Unbekannte Query-Modellgeneration.");
            if(!EmbeddingGemma2Encoder.HF_REVISION.equals(e.getString("revision")) || !Arrays.asList("wikipedia","wikibooks","stackexchange").contains(source.getString("corpus")))throw new IOException("Ungeprüfte Modellrevision oder Korpuszuordnung.");
            JSONObject lineage=m.getJSONObject("lineage"),identity=lineage.getJSONObject("embedding_identity");
            if(lineage.getLong("count")!=counts.getLong("chunks") || !lineage.getString("reference_sha256").matches("[a-f0-9]{64}"))throw new IOException("IVF-Herkunftsnachweis fehlt.");
            for(String key:Arrays.asList("model","revision","profile","database_sha256"))if(!identity.getString(key).equals(e.getString(key)))throw new IOException("DB-/Modell-/IVF-Herkunft stimmt nicht.");
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(f.getString("name").equals("knowledge.ivf") && !f.getString("sha256").equals(lineage.getString("index_sha256")))throw new IOException("IVF-Hash stimmt nicht zum Herkunftsnachweis.");}
        } else if(names.contains("knowledge.ivf") || names.contains("vectors.i8"))throw new IOException("Strukturierte Fakten benötigen keinen Vektorindex.");
    }
    static JSONObject install(File files,InputStream input,BooleanSupplier cancelled,Progress progress) throws Exception {
        synchronized(IMPORT_LOCK) {
            String generation=UUID.randomUUID().toString();File stage=new File(root(files),generation);
            if(!stage.mkdir())throw new IOException("Importverzeichnis konnte nicht erstellt werden.");
            if(!new File(stage,".importing").createNewFile())throw new IOException("Importmarkierung fehlt.");
            // Failed/interrupted generations are retained; no previous generation
            // is deleted or modified, and mmap readers keep their immutable files.
            JSONObject manifest;
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input,1024*1024))) {
                ZipEntry first=zip.getNextEntry();
                if(first==null || !first.getName().equals("manifest.json") || first.isDirectory())throw new IOException("manifest.json muss erster ZIP-Eintrag sein.");
                String raw=readSmall(zip,262144);manifest=new JSONObject(raw);validateManifest(manifest);
                Map<String,JSONObject> expected=new HashMap<>();long total=0;
                JSONArray list=manifest.getJSONArray("files");
                for(int i=0;i<list.length();i++){JSONObject f=list.getJSONObject(i);expected.put(f.getString("name"),f);total=Math.addExact(total,f.getLong("bytes"));}
                long available=new StatFs(stage.getPath()).getAvailableBytes();
                long idScratch=manifest.getString("type").equals("rag")?Math.multiplyExact(manifest.getJSONObject("counts").getLong("chunks"),40):0;
                if(available<Math.addExact(Math.addExact(total,idScratch),RESERVE))throw new IOException("Nicht genug interner Speicher für Paket und ID-Prüfung.");
                long copied=0;Set<String> seen=new HashSet<>();byte[] buffer=new byte[1024*1024];ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    check(cancelled);String name=entry.getName();JSONObject f=expected.get(name);
                    if(f==null || !seen.add(name) || entry.isDirectory())throw new IOException("Unerwarteter oder doppelter ZIP-Eintrag: "+name);
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");long written=0;
                    try(FileOutputStream out=new FileOutputStream(new File(stage,name))) {
                        int n;
                        while((n=zip.read(buffer))!=-1) {
                            check(cancelled);written=Math.addExact(written,n);
                            if(written>f.getLong("bytes"))throw new IOException("Paketdatei größer als deklariert.");
                            out.write(buffer,0,n);hash.update(buffer,0,n);copied+=n;
                            progress.update("Kopieren: "+copied/1048576+" / "+total/1048576+" MiB");
                        }
                        out.getFD().sync();
                    }
                    if(written!=f.getLong("bytes") || !ModelIntegrity.hex(hash.digest()).equals(f.getString("sha256")))throw new IOException("Größe oder SHA256 stimmt nicht: "+name);
                }
                if(seen.size()!=expected.size())throw new IOException("Paketdateien fehlen.");
                progress.update("SQLite und Chunk-IDs prüfen …");validateDatabase(stage,manifest,cancelled);
                check(cancelled);
                try(FileOutputStream out=new FileOutputStream(new File(stage,"manifest.json"))){out.write(raw.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            }
            // A validated mapping can keep a Windows directory rename locked.
            // Generation paths are final from creation; ONLY active.json exposes
            // them to readers. Unregistered generations cannot be queried.
            check(cancelled);
            synchronized(REGISTRY_LOCK) {
                JSONObject value=registry(files);
                if(!value.has(manifest.getString("package_id")) && value.length()>=16)throw new IOException("Höchstens 16 Pakete installieren.");
                value.put(manifest.getString("package_id"),new JSONObject().put("generation",generation).put("enabled",true));
                writeRegistry(files,value);
            }
            new File(stage,".importing").delete(); // Registry is authoritative after commit.
            return manifest;
        }
    }
    private static void validateDatabase(File stage,JSONObject manifest,BooleanSupplier cancelled) throws Exception {
        String type=manifest.getString("type");File file=new File(stage,"knowledge.sqlite");
        // FTS4's xIntegrity uses a write-classified control statement even though
        // it validates existing data. This is an unpublished staging generation;
        // all runtime readers still use OPEN_READONLY. Verify bytes again below.
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            db.execSQL("PRAGMA cache_size=-4096");db.execSQL("PRAGMA temp_store=FILE");
            if(db.getVersion()!=1)throw new IOException("SQLite-Schemaversion passt nicht.");
            CancellationSignal signal=new CancellationSignal();
            Thread watcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(cancelled.getAsBoolean()){signal.cancel();return;}Thread.sleep(50);}}catch(InterruptedException ignored){}},"package-integrity-cancel");
            watcher.setDaemon(true);watcher.start();
            try(Cursor c=db.rawQuery("PRAGMA integrity_check",null,signal)) {
                if(!c.moveToFirst())throw new IOException("SQLite-Integritätsprüfung ohne Ergebnis.");
                String integrity=c.getString(0);
                if(!"ok".equals(integrity) || c.moveToNext())throw new IOException("SQLite-Integritätsprüfung fehlgeschlagen: "+(integrity==null?"NULL":integrity.substring(0,Math.min(200,integrity.length()))));
            } finally {watcher.interrupt();}
            String table=type.equals("rag")?"chunks":type.equals("wikidata")?"entity":type.equals("dictionary")?"entry":"reference_value";
            long expected=manifest.getJSONObject("counts").getLong(type.equals("rag")?"chunks":"records");
            try(Cursor c=db.rawQuery("SELECT count(*) FROM "+table,null)){if(!c.moveToFirst() || c.getLong(0)!=expected)throw new IOException("Datensatzanzahl stimmt nicht.");}
            if(!manifest.getString("package_id").equals(scalar(db,"SELECT value FROM metadata WHERE key='package_id'")))throw new IOException("DB/Paketidentität stimmt nicht.");
            if(type.equals("rag")) {
                JSONObject embedding=manifest.getJSONObject("embedding");
                if(!embedding.getString("profile").equals(scalar(db,"SELECT value FROM metadata WHERE key='embedding_profile'")) || !embedding.getString("revision").equals(scalar(db,"SELECT value FROM metadata WHERE key='embedding_revision'")))throw new IOException("DB/Embeddingprofil stimmt nicht.");
                if(!Arrays.asList("complete","limited").contains(scalar(db,"SELECT value FROM metadata WHERE key='ingest_status'")))throw new IOException("Unvollständiger Import.");
                for(String fts:Arrays.asList("chunks_fts4","chunks_fts")) {
                    // FTS4 is required for system SQLite without FTS5. Validate its
                    // exact ID set on every device, and FTS5 whenever available.
                    try {if(hasRow(db,"SELECT id FROM chunks EXCEPT SELECT rowid FROM "+fts+" LIMIT 1") || hasRow(db,"SELECT rowid FROM "+fts+" EXCEPT SELECT id FROM chunks LIMIT 1"))throw new IOException("FTS/Chunk-IDs passen nicht.");}
                    catch(android.database.sqlite.SQLiteException e){if(fts.equals("chunks_fts4"))throw new IOException("Android-FTS4 fehlt.",e);}
                }
                validateIndex(stage,db,expected,cancelled);
            } else if(type.equals("wikidata")) {
                if(!"true".equals(scalar(db,"SELECT value FROM metadata WHERE key='complete'")) || !"1113017159".equals(scalar(db,"PRAGMA application_id")))throw new IOException("Wikidata-Import unvollständig oder unbekannt.");
            } else {
                String expectedId=type.equals("dictionary")?"1111770196":"1112820044";
                if(!"true".equals(scalar(db,"SELECT value FROM metadata WHERE key='complete'")) || !expectedId.equals(scalar(db,"PRAGMA application_id")))throw new IOException("Strukturierter Import unvollständig oder unbekannt.");
            }
        }
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1){check(cancelled);hash.update(buffer,0,n);}}
        JSONArray files=manifest.getJSONArray("files");
        for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("name").equals("knowledge.sqlite") && !ModelIntegrity.hex(hash.digest()).equals(files.getJSONObject(i).getString("sha256")))throw new IOException("SQLite wurde bei der Staging-Prüfung verändert; Aktivierung abgelehnt.");
    }
    private static String scalar(SQLiteDatabase db,String sql) throws IOException {
        try(Cursor c=db.rawQuery(sql,null)){if(!c.moveToFirst())throw new IOException("DB-Metadaten fehlen.");return c.getString(0);}
    }
    private static boolean hasRow(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){return c.moveToFirst();}}
    private static void validateIndex(File stage,SQLiteDatabase corpus,long count,BooleanSupplier cancelled) throws Exception {
        File ivf=new File(stage,"knowledge.ivf");
        // Production reader validates all offsets, sizes, finite centers and
        // format flags; ID membership is checked with a disk B-tree below.
        KnowledgeSearch.IvfIndex checked=new KnowledgeSearch.IvfIndex(ivf);
        if(checked.count!=count)throw new IOException("IVF-Anzahl stimmt nicht.");
        File scratch=new File(stage,"validation.sqlite");
        try(SQLiteDatabase seen=SQLiteDatabase.openOrCreateDatabase(scratch,null); RandomAccessFile in=new RandomAccessFile(ivf,"r")) {
            seen.execSQL("PRAGMA cache_size=-4096");seen.execSQL("CREATE TABLE seen(id INTEGER PRIMARY KEY)");
            byte[] header=new byte[128];in.readFully(header);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long idsOffset=h.getLong(56);in.seek(idsOffset);byte[] block=new byte[8*8192];
            try(SQLiteStatement insert=seen.compileStatement("INSERT INTO seen VALUES(?)")) {
                for(long start=0;start<count;start+=8192) {
                    check(cancelled);int rows=(int)Math.min(8192,count-start);in.readFully(block,0,rows*8);
                    ByteBuffer ids=ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);seen.beginTransaction();
                    try {for(int i=0;i<rows;i++){long id=ids.getLong();if(id<=0)throw new IOException("Ungültige Chunk-ID.");insert.bindLong(1,id);insert.executeInsert();}seen.setTransactionSuccessful();}
                    finally {seen.endTransaction();}
                }
            }
            // Compare sorted cursors from separate connections; no ATTACH or
            // model-controlled SQL/path, no complete Java collection of IDs.
            try(Cursor a=corpus.rawQuery("SELECT id FROM chunks ORDER BY id",null);Cursor b=seen.rawQuery("SELECT id FROM seen ORDER BY id",null)) {
                while(a.moveToNext()){check(cancelled);if(!b.moveToNext() || a.getLong(0)!=b.getLong(0))throw new IOException("DB-/IVF-IDs passen nicht.");}
                if(b.moveToNext())throw new IOException("IVF enthält zusätzliche IDs.");
            }
        }
        if(!scratch.delete())throw new IOException("Temporäre ID-Prüfung konnte nicht abgeschlossen werden.");
    }
    static File encoder(File files) throws IOException {
        File file=new File(root(files),"encoders/"+ENCODER_SHA+".gguf");
        return file;
    }
    static long discardIncomplete(File files) throws Exception {
        synchronized(IMPORT_LOCK) {
            Set<String> retained=new HashSet<>();
            synchronized(REGISTRY_LOCK){JSONObject values=registry(files);for(Iterator<String> keys=values.keys();keys.hasNext();)retained.add(values.getJSONObject(keys.next()).getString("generation"));}
            File directory=root(files).getCanonicalFile();long removed=0;
            File[] children=directory.listFiles();if(children==null)return 0;
            for(File candidate:children) {
                String name=candidate.getName();
                if(retained.contains(name) || !candidate.isDirectory() || !(name.matches("staging-[a-f0-9-]{36}") || (name.matches("[a-f0-9-]{36}") && new File(candidate,".importing").isFile())))continue;
                File canonical=candidate.getCanonicalFile();
                if(!directory.equals(canonical.getParentFile()))throw new IOException("Ungültiger Staging-Pfad.");
                File[] pending=canonical.listFiles();if(pending==null)throw new IOException("Staging-Verzeichnis nicht lesbar.");
                for(File file:pending)if(file.isDirectory() || !canonical.equals(file.getCanonicalFile().getParentFile()))throw new IOException("Unerwarteter Staging-Inhalt; keine Dateien entfernt.");
                for(File file:pending){long size=file.length();if(!file.delete())throw new IOException("Unvollständiger Import wird noch verwendet.");removed+=size;}
                if(!canonical.delete())throw new IOException("Staging-Ordner konnte nicht entfernt werden.");
            }
            return removed;
        }
    }
    static void importEncoder(File files,InputStream input,BooleanSupplier cancelled,Progress progress) throws Exception {
        synchronized(IMPORT_LOCK) {
            File directory=new File(root(files),"encoders");if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Modellordner fehlt.");
            File target=new File(directory,ENCODER_SHA+".gguf");
            if(target.exists())throw new IOException("Dieses Query-Modell ist bereits importiert.");
            if(new StatFs(directory.getPath()).getAvailableBytes()<ENCODER_BYTES+RESERVE)throw new IOException("Nicht genug Speicher für Query-Modell.");
            File staged=new File(directory,UUID.randomUUID()+".part");MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream source=input;FileOutputStream out=new FileOutputStream(staged)) {
                byte[] buffer=new byte[1024*1024];int n;
                while((n=source.read(buffer))!=-1){check(cancelled);size+=n;if(size>ENCODER_BYTES)throw new IOException("Falsche Modellgröße.");out.write(buffer,0,n);hash.update(buffer,0,n);progress.update("Query-Modell: "+size/1048576+" MiB");}out.getFD().sync();
            }
            if(size!=ENCODER_BYTES || !ENCODER_SHA.equals(ModelIntegrity.hex(hash.digest())))throw new IOException("Falsche EmbeddingGemma-2-Modellgeneration.");
            check(cancelled);if(!staged.renameTo(target))throw new IOException("Query-Modell konnte nicht aktiviert werden.");
        }
    }
    private KnowledgePackages(){}
}
