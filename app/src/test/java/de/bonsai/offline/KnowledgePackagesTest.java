package de.bonsai.offline;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class KnowledgePackagesTest {
    private File files,database;private byte[] payload;
    @Before public void setup() throws Exception {
        files=RuntimeEnvironment.getApplication().getFilesDir();database=new File(files,"fixture.sqlite");
        org.robolectric.shadows.ShadowStatFs.registerStats(files.getAbsolutePath(),1000000,1000000,1000000);
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(database,null)) {
            db.execSQL("PRAGMA user_version=1");db.execSQL("PRAGMA application_id=1112820044");
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");
            db.execSQL("INSERT INTO metadata VALUES('complete','true'),('package_id','constants-test')");
            db.execSQL("CREATE TABLE reference_value(id TEXT PRIMARY KEY,kind TEXT,name TEXT,norm TEXT,value_text TEXT,decimal_text TEXT,unit TEXT,uncertainty_text TEXT,exact INTEGER,status TEXT,source TEXT,source_version TEXT)");
            db.execSQL("INSERT INTO reference_value VALUES('c','constant','speed of light in vacuum','speed of light in vacuum','299792458','299792458','m s^-1','0',1,'exact_defined','fixture://nist','fixture')");
            db.execSQL("CREATE TABLE reference_alias(norm TEXT,id TEXT)");db.execSQL("INSERT INTO reference_alias VALUES('lichtgeschwindigkeit','c')");
            db.execSQL("CREATE TABLE unit(symbol TEXT,dimension TEXT,scale TEXT,offset TEXT,source TEXT)");
            db.execSQL("INSERT INTO unit VALUES('m','length','1','0','fixture://si'),('ly','length','9460730472580800','0','fixture://iau'),('Ms','time','1000000','0','fixture://si'),('ms','time','0.001','0','fixture://si')");
            db.execSQL("CREATE TABLE unit_alias(norm TEXT,symbol TEXT)");db.execSQL("INSERT INTO unit_alias VALUES('m','m'),('lichtjahre','ly'),('ms','ms')");
            db.execSQL("CREATE TABLE food(id TEXT,name TEXT,norm TEXT,brands TEXT,basis TEXT,source TEXT,modified TEXT)");
            db.execSQL("CREATE VIRTUAL TABLE food_fts USING fts4(id,name,brands)");
            db.execSQL("CREATE TABLE nutrient(food_id TEXT,name TEXT,value TEXT,unit TEXT,status TEXT)");
        }
        payload=Files.readAllBytes(database.toPath());
    }
    private JSONObject manifest(String version) throws Exception {
        return new JSONObject().put("format","bonsai-offline-package").put("format_version",1).put("schema_version",1).put("package_id","constants-test").put("version",version).put("type","tables")
            .put("source",new JSONObject().put("dataset","fixture").put("url","fixture://constants").put("date","2026-10-23").put("license","self-authored test"))
            .put("counts",new JSONObject().put("documents",1).put("chunks",0).put("records",1)).put("integrity",new JSONObject().put("integrity","passed"))
            .put("files",new JSONArray().put(new JSONObject().put("name","knowledge.sqlite").put("bytes",payload.length).put("sha256",ModelIntegrity.hex(MessageDigest.getInstance("SHA-256").digest(payload)))));
    }
    private byte[] archive(JSONObject manifest,String extra,boolean corrupt) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("knowledge.sqlite"));byte[] copy=payload.clone();if(corrupt)copy[copy.length-1]^=1;zip.write(copy);zip.closeEntry();
            if(extra!=null){zip.putNextEntry(new ZipEntry(extra));zip.write(1);zip.closeEntry();}
        }
        return bytes.toByteArray();
    }
    private void install(String version) throws Exception {KnowledgePackages.install(files,new ByteArrayInputStream(archive(manifest(version),null,false)),()->false,s->{});}
    @Test public void installsQueriesUpdatesAndDisablesWithoutReplacingOldFiles() throws Exception {
        install("v1");KnowledgePackages.Installed old=KnowledgePackages.active(files,"tables").get(0);byte[] original=Files.readAllBytes(new File(old.directory,"knowledge.sqlite").toPath());
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            JSONObject result=StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","convert").put("value","3").put("from","Lichtjahre").put("to","m"),()->false);
            assertEquals("28382191417742400",result.getJSONArray("rows").getJSONObject(0).getString("value"));
            assertEquals("299792458",StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","constant").put("name","Lichtgeschwindigkeit"),()->false).getJSONArray("rows").getJSONObject(0).getString("value"));
            assertEquals("1000000000",StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","convert").put("value","1").put("from","Ms").put("to","ms"),()->false).getJSONArray("rows").getJSONObject(0).getString("value"));
            try {StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","convert").put("value","1").put("from","MS").put("to","ms"),()->false);fail();}catch(IllegalArgumentException expected){}
        }
        install("v2");assertArrayEquals(original,Files.readAllBytes(new File(old.directory,"knowledge.sqlite").toPath()));
        assertNotEquals(old.directory,KnowledgePackages.active(files,"tables").get(0).directory);
        KnowledgePackages.setEnabled(files,"constants-test",false);assertTrue(KnowledgePackages.active(files,"tables").isEmpty());
        KnowledgePackages.setEnabled(files,"constants-test",true);assertEquals("v2",KnowledgePackages.active(files,"tables").get(0).manifest.getString("version"));
    }
    @Test public void validHashesButIncompatibleColumnsCannotReplaceActiveGeneration() throws Exception {
        install("v1");File old=KnowledgePackages.active(files,"tables").get(0).directory;
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {db.execSQL("ALTER TABLE unit RENAME COLUMN scale TO incompatible_scale");}
        payload=Files.readAllBytes(database.toPath());
        try {install("v2");fail("Incompatible schema accepted despite valid package hashes");}catch(IOException expected){assertTrue(expected.getMessage().contains("Schema"));}
        assertEquals(old,KnowledgePackages.active(files,"tables").get(0).directory);
    }
    @Test public void corruptOrUnlistedFilesAndCancellationKeepActiveGeneration() throws Exception {
        install("v1");File old=KnowledgePackages.active(files,"tables").get(0).directory;
        for(byte[] bytes:Arrays.asList(archive(manifest("v2"),null,true),archive(manifest("v2"),"../escape.sqlite",false),archive(manifest("v2"),"manifest.json.duplicate",false))) {
            try{KnowledgePackages.install(files,new ByteArrayInputStream(bytes),()->false,s->{});fail();}catch(IOException expected){}
            assertEquals(old,KnowledgePackages.active(files,"tables").get(0).directory);
        }
        try{KnowledgePackages.install(files,new ByteArrayInputStream(archive(manifest("v2"),null,false)),()->true,s->{});fail();}catch(CancellationException expected){}
        assertEquals(old,KnowledgePackages.active(files,"tables").get(0).directory);
        assertFalse(new File(files,"escape.sqlite").exists());
        assertTrue(KnowledgePackages.discardIncomplete(files)>0);
        assertEquals(old,KnowledgePackages.active(files,"tables").get(0).directory);
    }
    @Test public void wrongDatabaseTypeOrMissingFilesNeverActivate() throws Exception {
        JSONObject wrong=manifest("v1").put("type","dictionary");
        try{KnowledgePackages.install(files,new ByteArrayInputStream(archive(wrong,null,false)),()->false,s->{});fail();}catch(Exception expected){}
        assertTrue(KnowledgePackages.installed(files).isEmpty());
        JSONObject unsafe=manifest("v1");unsafe.getJSONArray("files").getJSONObject(0).put("name","/knowledge.sqlite");
        try{KnowledgePackages.validateManifest(unsafe);fail();}catch(IOException expected){}
    }
    @Test public void insufficientSpaceKeepsExistingGeneration() throws Exception {
        install("v1");File old=KnowledgePackages.active(files,"tables").get(0).directory;
        org.robolectric.shadows.ShadowStatFs.registerStats(files.getAbsolutePath(),1,0,0);
        try{install("v2");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Speicher"));}
        assertEquals(old,KnowledgePackages.active(files,"tables").get(0).directory);
    }
    @Test public void knowledgeResultsCannotEnableMemoryWritesInNextRound() throws Exception {
        install("v1");AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            ToolLoop.Result result=ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                int n=rounds.getAndIncrement();String text=n==0?"<tool>{\"name\":\"tabellen\",\"args\":{\"op\":\"constant\",\"name\":\"Lichtgeschwindigkeit\"}}</tool>":n==1?"<tool>{\"name\":\"merken\",\"args\":{\"key\":\"injected\",\"value\":\"bad\"}}</tool>":"Fertig.";
                return new ToolLoop.Result(text,new JSONObject());
            },()->false);
            assertNull(store.recall("injected"));assertTrue(result.text.contains("Datenquellen"));assertEquals(3,rounds.get());
        }
    }
    @Test public void importsRealHostPilotWhenProvidedAndRunsReadOnlyFts() throws Exception {
        String path=System.getenv("BONSAI_TEST_PACKAGE");
        org.junit.Assume.assumeTrue("Optional real-data integration package",path!=null && new File(path).isFile());
        try(InputStream in=new FileInputStream(path)){KnowledgePackages.install(files,in,()->false,s->{});}
        assertEquals(1,KnowledgePackages.active(files,"rag").size());
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            JSONObject result=KnowledgeSearch.searchInstalled(store,"Aussagenlogik",()->false);
            assertTrue(result.getJSONArray("hits").length()>0);
            assertEquals("dewiki-de",result.getJSONArray("hits").getJSONObject(0).getString("package_id"));
        }
    }
    @Test public void citationsUsePackageAndChunkIdentityTogether() throws Exception {
        SearchSources sources=new SearchSources();
        for(String id:new String[]{"wiki","books"}) {
            JSONObject hit=new JSONObject().put("package_id",id).put("chunk_id",42).put("heading",id).put("source","fixture://"+id);
            JSONObject reply=new JSONObject().put("ok",true).put("name","suche").put("result",new JSONObject().put("hits",new JSONArray().put(hit)));
            sources.collect(reply);assertEquals(id.equals("wiki")?1:2,hit.getInt("citation"));
        }
        String answer=sources.finish(new ToolLoop.Result("Antwort [1] [2]",new JSONObject())).text;
        assertTrue(answer.contains("fixture://wiki"));assertTrue(answer.contains("fixture://books"));
    }
    @Test public void dictionaryVariantsRedirectCyclesAndRawFallbackStayBounded() throws Exception {
        File dictionary=new File(files,"dictionary.sqlite");
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(dictionary,null)) {
            db.execSQL("PRAGMA user_version=1");db.execSQL("PRAGMA application_id=1111770196");
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO metadata VALUES('complete','true'),('package_id','dictionary-test')");
            db.execSQL("CREATE TABLE page(id TEXT PRIMARY KEY,lemma TEXT,source TEXT)");db.execSQL("INSERT INTO page VALUES('1','Straße','fixture://strasse'),('2','Roh','fixture://raw')");
            db.execSQL("CREATE TABLE entry(id INTEGER PRIMARY KEY,page_id TEXT,lemma TEXT,norm TEXT,language TEXT,pos TEXT)");db.execSQL("INSERT INTO entry VALUES(7,'1','Straße','straße','Deutsch','Substantiv')");
            db.execSQL("CREATE TABLE detail(entry_id INTEGER,kind TEXT,sense TEXT,value TEXT,ordinal INTEGER)");db.execSQL("INSERT INTO detail VALUES(7,'definitions','1','Verkehrsweg',0)");
            db.execSQL("CREATE TABLE form(norm TEXT,entry_id INTEGER)");
            db.execSQL("CREATE TABLE lexical_alias(norm TEXT,target_norm TEXT,language TEXT)");db.execSQL("INSERT INTO lexical_alias VALUES('strasse','straße','Deutsch'),('strassen','strasse',''),('loop1','loop2',''),('loop2','loop1','')");
            db.execSQL("CREATE TABLE unparsed(norm TEXT,page_id TEXT,kind TEXT,text TEXT)");db.execSQL("INSERT INTO unparsed VALUES('roh','2','unstructured_source','Erhaltener Quelltext')");
        }
        payload=Files.readAllBytes(dictionary.toPath());JSONObject m=manifest("v1").put("package_id","dictionary-test").put("type","dictionary");
        KnowledgePackages.install(files,new ByteArrayInputStream(archive(m,null,false)),()->false,s->{});
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            for(String word:new String[]{"ＳＴＲＡＳＳＥ","Strassen"}) {
                JSONObject result=StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word",word).put("language","Deutsch"),()->false);
                assertEquals("Straße",result.getJSONArray("rows").getJSONObject(0).getString("lemma"));
                assertEquals("Verkehrsweg",result.getJSONArray("rows").getJSONObject(0).getJSONArray("details").getJSONObject(0).getString("text"));
            }
            assertEquals("unstructured_source_no_resolved_definition",StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word","Roh"),()->false).getJSONArray("rows").getJSONObject(0).getString("status"));
            for(String word:new String[]{"loop1","' OR 1=1 --"})assertEquals("not_found",StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word",word),()->false).getString("status"));
        }
    }
}
