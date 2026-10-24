package de.bonsai.offline;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class KnowledgeSearchTest {
    private File directory,database,vectors;
    @Before public void prepare() throws Exception {
        directory=RagFixtures.install(RuntimeEnvironment.getApplication());
        database=new File(directory,"knowledge.sqlite");vectors=new File(directory,"vectors.i8");
    }
    private KnowledgeSearch search(){return new KnowledgeSearch(database,vectors);}
    private static String decode(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
    private static ToolLoop.Result result(String text){return new ToolLoop.Result(text,new JSONObject());}
    private static String call(String args){return "<tool>{\"name\":\"suche\",\"args\":"+args+"}</tool>";}
    private static JSONObject reply(byte[][] texts) throws Exception {
        String text=decode(texts[texts.length-1]);
        return new JSONObject(text.substring(text.indexOf('>')+1,text.indexOf("</tool_result>")));
    }
    @Test public void globalFusionDoesNotStarveLaterCommunitiesAndKeepsCompositeIds() throws Exception {
        List<JSONObject> candidates=new java.util.ArrayList<>();
        for(String id:new String[]{"a-electronics","b-math","c-physics","stackoverflow","superuser"}) {
            double cosine=id.equals("stackoverflow")?.95:.1;
            candidates.add(new JSONObject().put("package_id",id).put("chunk_id",42).put("_lexical_rank",1).put("_semantic_score",cosine));
        }
        JSONArray result=KnowledgeSearch.fusePackages(candidates);
        assertEquals(3,result.length());assertEquals("stackoverflow",result.getJSONObject(0).getString("package_id"));
        assertFalse(result.getJSONObject(0).has("_semantic_score"));
        java.util.Collections.reverse(candidates);
        assertEquals(result.toString(),KnowledgeSearch.fusePackages(candidates).toString());
        assertNotEquals(result.getJSONObject(0).getString("package_id"),result.getJSONObject(1).getString("package_id"));
        assertEquals(42,result.getJSONObject(1).getLong("chunk_id"));
    }
    @Test public void realPythonExportHasLexicalAndHybridHitsWithSources() throws Exception {
        for(File vector:new File[]{null,vectors}) {
            JSONArray hits=new KnowledgeSearch(database,vector).search("Photosynthese",()->false).getJSONArray("hits");
            assertTrue(hits.length()>0);assertTrue(hits.length()<=3);
            JSONObject first=hits.getJSONObject(0);
            assertEquals("2",first.getString("article_id"));
            assertEquals("fixture://bonsai/2",first.getString("source"));
            assertTrue(first.getString("heading").contains("Überblick"));
            assertTrue(first.getString("text").contains("Lichtenergie"));
        }
    }
    @Test public void int8RankingMatchesPythonFixtureAndCancellationPropagates() throws Exception {
        float[] query=new KnowledgeSearch.SampleEncoder().encode("Photosynthese");
        List<Long> ids=search().vectorRanks(query,()->false);
        assertEquals(Long.valueOf(2),ids.get(0));
        try(java.io.InputStream in=getClass().getResourceAsStream("/rag/rankings.json")) {
            JSONArray rankings=new JSONArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            for(int row=0;row<rankings.length();row++) {
                JSONObject expected=rankings.getJSONObject(row);
                List<Long> actual=search().vectorRanks(new KnowledgeSearch.SampleEncoder().encode(expected.getString("query")),()->false);
                JSONArray expectedIds=expected.getJSONArray("ids");
                assertEquals(expected.getString("query"),expectedIds.length(),actual.size());
                for(int i=0;i<actual.size();i++)assertEquals(expectedIds.getLong(i),actual.get(i).longValue());
            }
        }
        try{search().vectorRanks(query,()->true);fail();}catch(CancellationException expected){}
        try{search().vectorRanks(new float[255],()->false);fail();}catch(java.io.IOException expected){}
    }
    @Test public void noMatchAndFtsOperatorsAreSafe() throws Exception {
        KnowledgeSearch lexical=new KnowledgeSearch(database,null);
        assertEquals(0,lexical.search("unfindbarzzzzxx",()->false).getJSONArray("hits").length());
        assertEquals(0,lexical.search("\" OR * ()",()->false).getJSONArray("hits").length());
        assertEquals(0,search().search("!!!",()->false).getJSONArray("hits").length());
    }
    @Test public void missingDatabaseIsNotCreated() throws Exception {
        File missing=new File(directory,"absent.sqlite");
        try{new KnowledgeSearch(missing,vectors).search("Berlin",()->false);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Wissensdatenbank fehlt"));}
        assertFalse(missing.exists());
    }
    @Test public void mismatchDisablesVectorSearchWithVisibleToolWarning() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("UPDATE metadata SET value='different-model' WHERE key='embedding_model'");
        }
        JSONObject reply=search().search("Photosynthese",()->false);
        assertTrue(reply.getString("warning").contains("Query-Encoder"));
        assertEquals("2",reply.getJSONArray("hits").getJSONObject(0).getString("article_id"));
    }
    @Test public void fts4CompatibilityIndexWorksWithoutFts5() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            try { db.execSQL("DROP TABLE chunks_fts"); }
            catch(android.database.sqlite.SQLiteException unavailable) {
                // Native Robolectric SQLite itself omits FTS5, as some Android builds do.
                assertTrue(unavailable.getMessage().contains("no such module: fts5"));
            }
        }
        JSONArray hits=new KnowledgeSearch(database,null).search("Photosynthese",()->false).getJSONArray("hits");
        assertEquals("2",hits.getJSONObject(0).getString("article_id"));
    }
    @Test public void textAndMetadataAreBoundedWithoutBrokenSurrogates() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DeflaterOutputStream zip=new DeflaterOutputStream(bytes)){zip.write(("🌳".repeat(1000)).getBytes(StandardCharsets.UTF_8));}
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("UPDATE chunks SET text_zlib=?,heading=?,source=? WHERE id=2",new Object[]{bytes.toByteArray(),"Ü".repeat(1000),"s".repeat(1000)});
        }
        JSONObject hit=search().search("Photosynthese",()->false).getJSONArray("hits").getJSONObject(0);
        assertTrue(hit.getString("text").length()<=KnowledgeSearch.MAX_TEXT);
        assertTrue(hit.getString("heading").length()<=KnowledgeSearch.MAX_HEADING);
        assertTrue(hit.getString("source").length()<=KnowledgeSearch.MAX_SOURCE);
        String text=hit.getString("text");
        assertFalse(Character.isHighSurrogate(text.charAt(text.length()-2)));
    }
    @Test public void corruptVectorsReportErrorInsteadOfReturningWrongHits() throws Exception {
        Files.write(vectors.toPath(),new byte[16]);
        try{search().search("Berlin",()->false);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Vektordatei"));}
    }
    @Test public void configurableInternalPathAndEscapingAreChecked() throws Exception {
        File renamed=new File(directory,"custom.sqlite");Files.move(database.toPath(),renamed.toPath());
        File config=new File(directory,"config.properties");
        Files.write(config.toPath(),"database=custom.sqlite\nvectors=absent.i8\n".getBytes(StandardCharsets.UTF_8));
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            assertTrue(KnowledgeSearch.forStore(store).search("Berlin",()->false).getJSONArray("hits").length()>0);
            Files.write(config.toPath(),"database=/tmp/outside.sqlite\n".getBytes(StandardCharsets.UTF_8));
            try{KnowledgeSearch.forStore(store);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("App-internen"));}
        }
    }
    @Test public void toolRejectsInvalidArgumentsAndMissingDatabaseWithoutLeakingJson() throws Exception {
        for(String args:new String[]{"{}","{\"query\":\"\"}","{\"query\":7}","{\"query\":\"x\",\"extra\":\"x\"}","{\"query\":\""+"x".repeat(257)+"\"}"}) {
            AtomicInteger rounds=new AtomicInteger();
            try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
                ToolLoop.Result answer=ToolLoop.run(Collections.emptyList(),store,(r,t,g)->{
                    if(rounds.getAndIncrement()==0)return result(call(args));
                    assertFalse(reply(t).getBoolean("ok"));return result("Fehler erklärt.");
                },()->false);
                assertEquals("Fehler erklärt.",answer.text);
            }
        }
        Files.delete(database.toPath());AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            ToolLoop.run(Collections.emptyList(),store,(r,t,g)->{
                if(rounds.getAndIncrement()==0)return result(call("{\"query\":\"Berlin\"}"));
                assertTrue(reply(t).getString("error").contains("Wissensdatenbank fehlt"));return result("Daten fehlen.");
            },()->false);
        }
    }
    @Test public void toolReturnsEmptyHitsWithoutAddingSources() throws Exception {
        Files.delete(vectors.toPath());AtomicInteger rounds=new AtomicInteger();
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            ToolLoop.Result answer=ToolLoop.run(Collections.emptyList(),store,(r,t,g)->{
                if(rounds.getAndIncrement()==0)return result(call("{\"query\":\"zzzzxxxyyy\"}"));
                JSONObject response=reply(t);assertTrue(response.getBoolean("ok"));
                assertEquals(0,response.getJSONObject("result").getJSONArray("hits").length());
                return result("Keine passenden Treffer.");
            },()->false);
            assertEquals("Keine passenden Treffer.",answer.text);
        }
    }
    @Test public void toolSourcesSurviveFinalEnvelopesAndOnlyCitedSourcesAreListed() throws Exception {
        for(boolean grammar:new boolean[]{false,true}) {
            AtomicInteger rounds=new AtomicInteger();
            try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
                ToolLoop.Result answer=ToolLoop.run(Collections.emptyList(),store,(r,t,g)->{
                    if(rounds.getAndIncrement()==0)return result(call("{\"query\":\"Photosynthese\"}"));
                    JSONObject reply=reply(t);assertTrue(reply.getBoolean("ok"));
                    assertEquals(1,reply.getJSONObject("result").getJSONArray("hits").getJSONObject(0).getInt("citation"));
                    return result(grammar?"<final>Pflanzen nutzen Licht. [1]</final>":"Pflanzen nutzen Licht. [1]");
                },()->false,grammar);
                assertTrue(answer.text.contains("\n\nQuellen:\n[1] Photosynthese"));
                assertTrue(answer.text.contains("fixture://bonsai/2"));assertFalse(answer.text.contains("tool_result"));
                assertFalse(answer.text.contains("<final>"));assertTrue(store.chats().isEmpty());
            }
        }
    }
    @Test public void sourcesDeduplicateAndStayBoundedAcrossTwoSearches() throws Exception {
        SearchSources sources=new SearchSources();
        for(int round=0;round<2;round++) {
            JSONArray hits=new JSONArray();
            for(int i=1;i<=3;i++)hits.put(new JSONObject().put("chunk_id",round*3+i).put("heading","H"+i).put("source","S"+i));
            JSONObject reply=new JSONObject().put("name","suche").put("ok",true).put("result",new JSONObject().put("hits",hits));
            sources.collect(reply);
            assertEquals(3,reply.getJSONObject("result").getJSONArray("hits").length());
        }
        String text=sources.finish(result("Antwort")).text;
        assertTrue(text.contains("Suchquellen:"));assertTrue(text.contains("[6]"));assertFalse(text.contains("[7]"));
        assertTrue(ToolGrammar.forRound(false).contains("search ::="));
        assertTrue(ToolGrammar.forRound(false).contains("suche"));
    }
}
