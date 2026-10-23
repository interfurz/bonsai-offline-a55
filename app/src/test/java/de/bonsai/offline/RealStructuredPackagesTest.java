package de.bonsai.offline;

import android.app.Application;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Optional multi-gigabyte host integration, never downloads test data in CI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class RealStructuredPackagesTest {
    @Test public void importsCompleteDictionaryFoodAndSiPackagesAndQueriesReadOnly() throws Exception {
        String path=System.getenv("BONSAI_TEST_PACKAGE_DIR");
        Assume.assumeTrue("Optional real structured packages",path!=null && new File(path).isDirectory());
        File directory=new File(path),files=RuntimeEnvironment.getApplication().getFilesDir();
        org.robolectric.shadows.ShadowStatFs.registerStats(files.getAbsolutePath(),1000000000,1000000000,1000000000);
        JSONArray imports=new JSONArray();
        for(String name:new String[]{"bonsai-dewiktionary-20261001-v1.bonsai","bonsai-food-germany-20240624-v1.bonsai","bonsai-constants-codata2022-v3.bonsai"}) {
            File archive=new File(directory,name);assertTrue("Missing real package "+name,archive.isFile());long started=System.nanoTime();
            try(InputStream input=new FileInputStream(archive)) {
                JSONObject manifest=KnowledgePackages.install(files,input,()->false,s->{});
                imports.put(new JSONObject().put("package_id",manifest.getString("package_id")).put("archive_bytes",archive.length()).put("host_import_ms",(System.nanoTime()-started)/1000000));
            }
        }
        JSONObject evidence=new JSONObject().put("scope","Windows Robolectric native SQLite, complete real packages; no A55 measurements").put("imports",imports);
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            JSONObject dictionary=StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word","Konvergenz").put("language","Deutsch"),()->false);
            assertEquals("Konvergenz",dictionary.getJSONArray("rows").getJSONObject(0).getString("lemma"));
            assertTrue(dictionary.getJSONArray("rows").getJSONObject(0).getJSONArray("details").length()>0);
            JSONObject alias=StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word","Nordatlantic").put("language","Deutsch"),()->false);
            assertEquals("Nordatlantik",alias.getJSONArray("rows").getJSONObject(0).getString("lemma"));
            JSONObject ambiguous=StructuredKnowledge.query(store,"woerterbuch",new JSONObject().put("word","Bank").put("language","Deutsch"),()->false);
            assertTrue(ambiguous.getJSONArray("rows").length()>=2);
            JSONObject food=StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","food").put("name","Haferflocken"),()->false);
            assertTrue(food.getJSONArray("rows").length()>0);
            JSONObject convert=StructuredKnowledge.query(store,"tabellen",new JSONObject().put("op","convert").put("value","3").put("from","Lichtjahre").put("to","m"),()->false);
            assertEquals("28382191417742400",convert.getJSONArray("rows").getJSONObject(0).getString("value"));
            evidence.put("dictionary",dictionary).put("spelling_variant",alias).put("ambiguous_word",ambiguous).put("food",food).put("conversion",convert);
        }
        String output=System.getenv("BONSAI_TEST_EVIDENCE_DIR");
        if(output!=null)Files.write(new File(output,"android-real-structured-packages.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
