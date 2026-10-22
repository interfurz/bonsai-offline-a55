package de.bonsai.offline;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
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
public class WikidataKnowledgeTest {
    private File database;
    private WikidataKnowledge knowledge;
    @Before public void install() throws Exception {
        File directory=new File(RuntimeEnvironment.getApplication().getFilesDir(),"wikidata");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        database=new File(directory,"knowledge.sqlite");
        try(InputStream in=getClass().getResourceAsStream("/wikidata/knowledge.sqlite")) {
            assertNotNull(in);Files.copy(in,database.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
        knowledge=new WikidataKnowledge(database);
    }
    private JSONObject args(String op,String entity,String... rest) throws Exception {
        JSONObject args=new JSONObject().put("op",op).put("entity",entity);
        for(int i=0;i<rest.length;i+=2)args.put(rest[i],rest[i+1]);return args;
    }
    private JSONObject query(String op,String entity,String... rest) throws Exception {return knowledge.query(args(op,entity,rest),()->false);}
    private JSONObject first(JSONObject result) throws Exception {assertEquals("ok",result.getString("status"));return result.getJSONArray("rows").getJSONObject(0);}
    private void mutate(String sql,Object... bindings) {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {db.execSQL(sql,bindings);}
    }
    @Test public void capitalBirthdayBordersAndAliasReadThePythonDatabase() throws Exception {
        JSONObject japan=query("facts","ＪＡＰＡＮ","property","capital");
        assertEquals("2026-10-01",japan.getString("dataset_date"));
        assertEquals("Tokio",first(japan).getJSONObject("value").getString("label"));
        JSONObject birth=first(query("facts","Albert Einstein","property","Geburtsdatum"));
        assertEquals("+1879-03-14T00:00:00Z",birth.getString("time"));assertEquals(11,birth.getInt("precision"));
        assertEquals("Q1985727",birth.getJSONObject("calendar").getString("id"));
        assertEquals(9,query("facts","BRD","property","P47").getJSONArray("rows").length());
        assertEquals("österreich",WikidataKnowledge.normalize("  ÖSTERREICH\u00a0"));
    }
    @Test public void ambiguityNeverSilentlyChoosesAPersonAndUnknownDoesNotMeanFalse() throws Exception {
        JSONObject ambiguous=query("facts","Einstein","property","P569");
        assertEquals("ambiguous",ambiguous.getString("status"));
        assertEquals(2,ambiguous.getJSONArray("candidates").length());assertFalse(ambiguous.has("rows"));
        assertEquals("not_found",query("facts","Albert Einstien","property","P569").getString("status"));
        assertEquals("no_local_facts",query("facts","Q17","property","P569").getString("status"));
        assertEquals("resolved",query("resolve","Germany").getString("status"));
        assertEquals("ambiguous",query("resolve","Einstein").getString("status"));
    }
    @Test public void threeStepPathPreservesEvidenceAndUsesIdsBetweenSteps() throws Exception {
        JSONObject result=query("path","Albert Einstein","property","P19/P17/P36");
        assertEquals("Q64",first(result).getString("id"));assertEquals(3,result.getJSONArray("edges").length());
        assertEquals("Q3012",result.getJSONArray("edges").getJSONObject(0).getJSONObject("value").getString("id"));
        assertTrue(result.getJSONArray("edges").getJSONObject(0).has("references"));
    }
    @Test public void numericClassFilterHasStrictThresholdAndUnitBoundary() throws Exception {
        JSONObject result=query("greater","chemical element","property","atomic number","value","50","unit","1");
        assertEquals(1,result.getJSONArray("rows").length());assertEquals("+53",first(result).getString("amount"));
        assertEquals("1",first(result).getString("unit"));
        assertEquals("no_local_facts",query("greater","Q11344","property","P1086","value","53","unit","1").getString("status"));
        assertEquals("+53",first(query("largest","Q11344","property","P1086","unit","1")).getString("amount"));
        assertEquals("no_local_facts",query("largest","Q11344","property","P1086","unit","Q11573").getString("status"));
    }
    @Test public void exactDecimalComparisonAndOrderingSurviveRealIndexTies() throws Exception {
        mutate("UPDATE statement SET amount='+53.000000000000000002',number=53 WHERE subject=131 AND property=-1086");
        mutate("UPDATE statement SET amount='+53.000000000000000001',number=53 WHERE subject=130 AND property=-1086");
        JSONObject result=query("greater","Q11344","property","P1086","value","53.000000000000000001","unit","1");
        assertEquals(1,result.getJSONArray("rows").length());assertEquals("Q131",first(result).getJSONObject("entity").getString("id"));
        assertEquals("Q131",first(query("largest","Q11344","property","P1086","unit","1")).getJSONObject("entity").getString("id"));
    }
    @Test public void preferredRanksUnitsQualifiersAndReferencesAreReturned() throws Exception {
        JSONObject result=query("facts","Eiffelturm","property","height");
        assertEquals(1,result.getJSONArray("rows").length());JSONObject row=first(result);
        assertEquals("+330.00",row.getString("amount"));assertEquals("preferred",row.getString("rank"));
        assertEquals("Meter",row.getJSONObject("unit").getString("label"));
        assertEquals("+2022-01-01T00:00:00Z",row.getJSONObject("qualifiers").getJSONArray("P585").getJSONObject(0)
            .getJSONObject("datavalue").getJSONObject("value").getString("time"));
        assertEquals(1,row.getJSONArray("references").length());
        assertEquals("+330.00",row.getJSONObject("datavalue").getJSONObject("value").getString("upperBound"));
    }
    @Test public void incomingCountsIncludeSubclassesAndExplicitLocalCoverage() throws Exception {
        JSONObject result=query("count","Planet","property","P397","value","Q2537");
        assertEquals("Q319",first(result).getJSONObject("entity").getString("id"));assertEquals(2,first(result).getInt("local_count"));
        assertEquals(1,result.getJSONArray("rows").getJSONObject(1).getInt("local_count"));
        assertTrue(result.getString("count_scope").contains("not_total_known_moons"));
        // Duplicate assertions about the same moon are never double-counted.
        mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) SELECT subject,property,rank,kind,target,detail FROM statement WHERE subject=3123 AND property=-397");
        assertEquals(2,first(query("count","Q634","property","P397","value","Q2537")).getInt("local_count"));
    }
    @Test public void duplicateSubclassStatementsDoNotHideOtherClasses() throws Exception {
        for(int i=0;i<130;i++)mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) VALUES(121750,-279,1,'entity',634,X'00')");
        mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) VALUES(777777,-279,1,'entity',634,X'00')");
        mutate("UPDATE statement SET target=777777 WHERE subject=193 AND property=-31");
        JSONObject result=query("count","Q634","property","P397","value","Q2537");
        assertEquals(2,result.getJSONArray("rows").length());assertEquals(1,result.getJSONArray("rows").getJSONObject(1).getInt("local_count"));
    }
    @Test public void arbitrarySqlUnknownKeysWrongKindsAndBadArgumentsAreRejected() throws Exception {
        byte[] before=Files.readAllBytes(database.toPath());
        for(JSONObject request:new JSONObject[]{args("sql","Q17","property","DROP TABLE entity"),
                args("facts","Q17","property","P36","sql","DELETE FROM statement"),
                args("facts","Q17"),args("path","Q937","property","P19/P17/P36/P17"),
                args("greater","Q11344","property","P1086","value","NaN","unit","1"),
                args("greater","Q11344","property","P1086","value","1e99999","unit","1")}) {
            try{knowledge.query(request,()->false);fail(request.toString());}catch(IllegalArgumentException expected){}
        }
        assertEquals("not_found",query("facts","Japan' OR 1=1 --","property","P36").getString("status"));
        assertEquals("not_found",query("facts","Q17","property","Q17").getString("status"));
        assertArrayEquals(before,Files.readAllBytes(database.toPath()));
    }
    @Test public void missingIncompleteAndWrongSchemaAreClearErrors() throws Exception {
        for(String mutation:new String[]{"UPDATE metadata SET value='false' WHERE key='complete'","PRAGMA user_version=999"}) {
            install();mutate(mutation);
            try{query("facts","Q17","property","P36");fail();}catch(java.io.IOException expected){}
        }
        try{new WikidataKnowledge(new File(database.getParent(),"missing.sqlite")).query(args("resolve","Japan"),()->false);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("fehlt"));}
    }
    @Test public void cancellationPropagatesBeforeAndDuringIndexedQueries() throws Exception {
        for(int limit:new int[]{0,8}) {
            AtomicInteger checks=new AtomicInteger();
            try{knowledge.query(args("path","Q937","property","P19/P17/P36"),()->checks.incrementAndGet()>limit);fail();}
            catch(CancellationException expected){}
        }
    }
    @Test public void widePathsAndClassSetsFailInsteadOfPretendingCompleteResults() throws Exception {
        for(int i=0;i<66;i++)mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) SELECT 17,-36,1,'entity',?,detail FROM statement WHERE subject=17 LIMIT 1",100000+i);
        try{query("path","Q17","property","P36");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("breit"));}
        for(int i=0;i<129;i++)mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) VALUES(?,-279,1,'entity',11344,X'00')",100000+i);
        try{query("largest","Q11344","property","P1086","unit","1");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("breit"));}
    }
    @Test public void largeCompressedDetailsAreOmittedWithoutInflatingUnboundedMemory() throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.util.zip.DeflaterOutputStream zip=new java.util.zip.DeflaterOutputStream(out)) {zip.write(new byte[1_000_000]);}
        mutate("UPDATE statement SET detail=? WHERE subject=17",out.toByteArray());
        JSONObject row=first(query("facts","Q17","property","P36"));
        assertTrue(row.getBoolean("detail_omitted"));assertEquals("Q1490",row.getJSONObject("value").getString("id"));
    }
    @Test public void rowAndCharacterBudgetsAreExplicitlyMarked() throws Exception {
        for(int i=0;i<30;i++)mutate("INSERT INTO statement(subject,property,rank,kind,target,detail) SELECT subject,property,rank,kind,target,detail FROM statement WHERE subject=17 LIMIT 1");
        JSONObject response=query("facts","Q17","property","P36");
        assertTrue(response.getBoolean("more"));assertTrue(response.getJSONArray("rows").length()>0);
        assertTrue(response.getJSONArray("rows").length()<=20);assertTrue(response.toString().length()<=6000);
    }
    @Test public void ambiguousCandidatesAreBoundedWithoutBecomingAnAutomaticMatch() throws Exception {
        String longDescription=String.join("",Collections.nCopies(300,"x"));
        for(int i=0;i<30;i++) {
            mutate("INSERT INTO entity(id) VALUES(?)",200000+i);
            mutate("INSERT INTO term VALUES(?,'de',1,'Shared','shared')",200000+i);
            mutate("INSERT INTO term VALUES(?,'de',2,?,NULL)",200000+i,longDescription);
        }
        JSONObject response=query("resolve","Shared");
        assertEquals("ambiguous",response.getString("status"));assertTrue(response.getBoolean("more"));
        assertTrue(response.getJSONArray("candidates").length()>1);assertTrue(response.toString().length()<=6000);
    }
    @Test public void controllerPassesGraphResultsAndDeterministicProvenanceToFinalAnswer() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            ToolLoop.Result result=ToolLoop.run(Collections.emptyList(),store,(roles,texts,grammar)->{
                if(calls.getAndIncrement()==0)return new ToolLoop.Result("<tool>{\"name\":\"wikidata\",\"args\":{\"op\":\"count\",\"entity\":\"Q634\",\"property\":\"P397\",\"value\":\"Q2537\"}}</tool>",new JSONObject());
                String reply=new String(texts[texts.length-1],StandardCharsets.UTF_8);
                assertTrue(reply.contains("local_count"));assertTrue(reply.contains("2026-10-01"));
                return new ToolLoop.Result("<final>Jupiter: 2 im lokalen Beispiel.</final>",new JSONObject());
            },()->false,true);
            assertTrue(result.text.contains("Dump-Stand 2026-10-01"));assertTrue(result.text.contains("nicht für die Gesamtzahl bekannter Monde"));
            assertTrue(result.text.contains("https://www.wikidata.org/wiki/Q319"));
            assertFalse(result.text.contains("<tool>"));assertTrue(store.chats().isEmpty());
        }
    }
    @Test public void hostRobolectricLatencyAndHeapAreRecordedWithoutDeviceClaims() throws Exception {
        JSONObject request=args("facts","Japan","property","P36");
        for(int i=0;i<3;i++)knowledge.query(request,()->false);
        long[] elapsed=new long[20];Runtime runtime=Runtime.getRuntime();long before=runtime.totalMemory()-runtime.freeMemory();
        for(int i=0;i<elapsed.length;i++) {long start=System.nanoTime();assertEquals("ok",knowledge.query(request,()->false).getString("status"));elapsed[i]=System.nanoTime()-start;}
        long after=runtime.totalMemory()-runtime.freeMemory();Arrays.sort(elapsed);
        System.out.println("WIKIDATA_BENCHMARK="+new JSONObject().put("environment","Linux host / Robolectric native SQLite; not A55")
            .put("database_bytes",database.length()).put("queries",elapsed.length).put("warmup",3)
            .put("p50_ms",elapsed[9]/1e6).put("p95_ms",elapsed[18]/1e6)
            .put("java_used_heap_before",before).put("java_used_heap_after",after)
            .put("heap_note","JVM heap snapshots, includes Robolectric; not peak or Android RSS"));
    }
}
