package de.bonsai.offline;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
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
public class EmbeddingGemma2EncoderTest {
    private File directory,database,vectors;
    @Before public void prepare() throws Exception {
        directory=RagFixtures.install(RuntimeEnvironment.getApplication());
        database=new File(directory,"knowledge.sqlite");vectors=new File(directory,"vectors.i8");
    }
    private void gemmaMetadata(boolean profile) {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("UPDATE metadata SET value=? WHERE key='embedding_model'",new Object[]{EmbeddingGemma2Encoder.MODEL});
            if(profile)db.execSQL("INSERT OR REPLACE INTO metadata VALUES('embedding_profile',?)",new Object[]{EmbeddingGemma2Encoder.PROFILE});
        }
    }
    @Test public void truncationHappensBeforeRenormalizationAndRejectsInvalidOutputs() throws Exception {
        float[] full=new float[768];full[0]=3;full[255]=4;full[256]=1000;
        float[] reduced=EmbeddingGemma2Encoder.reduce(full);
        assertEquals(256,reduced.length);assertEquals(.6f,reduced[0],1e-6);assertEquals(.8f,reduced[255],1e-6);
        for(float[] bad:new float[][]{new float[256],new float[768],null}) {
            try{EmbeddingGemma2Encoder.reduce(bad);fail();}catch(IOException expected){}
        }
        full[700]=Float.NaN;
        try{EmbeddingGemma2Encoder.reduce(full);fail();}catch(IOException expected){}
    }
    @Test public void realModelIsSelectedByDefaultAndMissingWeightsPreserveFts() throws Exception {
        gemmaMetadata(true);
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            JSONObject response=KnowledgeSearch.forStore(store).search("Photosynthese",()->false);
            assertTrue(response.getString("warning").contains("GGUF fehlt"));
            assertEquals("2",response.getJSONArray("hits").getJSONObject(0).getString("article_id"));
        }
    }
    @Test public void oldOrUnmarkedIndexNeverCallsEncoder() throws Exception {
        gemmaMetadata(false);AtomicInteger calls=new AtomicInteger();
        KnowledgeSearch.QueryEncoder encoder=new KnowledgeSearch.QueryEncoder() {
            public String model(){return EmbeddingGemma2Encoder.MODEL;}
            public float[] encode(String query){calls.incrementAndGet();return new float[256];}
        };
        assertTrue(new KnowledgeSearch(database,vectors,encoder).search("Berlin",()->false).getString("warning").contains("embedding_profile"));
        assertEquals(0,calls.get());
    }
    @Test public void matchingModelRunsThroughFullScanAndIvfAndReceivesCancellation() throws Exception {
        gemmaMetadata(true);AtomicInteger calls=new AtomicInteger();
        RagFixtures.copy("knowledge.ivf",new File(directory,"knowledge.ivf"));
        KnowledgeSearch.QueryEncoder encoder=new KnowledgeSearch.QueryEncoder() {
            public String model(){return EmbeddingGemma2Encoder.MODEL;}
            public float[] encode(String query){throw new AssertionError("Cancellation-aware overload required");}
            public float[] encode(String query,java.util.function.BooleanSupplier cancelled) {
                assertFalse(cancelled.getAsBoolean());calls.incrementAndGet();
                return new KnowledgeSearch.SampleEncoder().encode("Photosynthese");
            }
        };
        for(File ivf:new File[]{null,new File(directory,"knowledge.ivf")}) {
            JSONObject response=new KnowledgeSearch(database,vectors,ivf,encoder,16,60).search("zzzxxyy",()->false);
            assertFalse(response.has("warning"));
            assertEquals("2",response.getJSONArray("hits").getJSONObject(0).getString("article_id"));
        }
        assertEquals(2,calls.get());
    }
    @Test public void failureFallsBackButCancellationPropagates() throws Exception {
        gemmaMetadata(true);
        for(boolean stop:new boolean[]{false,true}) {
            KnowledgeSearch.QueryEncoder encoder=new KnowledgeSearch.QueryEncoder() {
                public String model(){return EmbeddingGemma2Encoder.MODEL;}
                public float[] encode(String query) throws Exception {
                    if(stop)throw new CancellationException("Abgebrochen.");
                    throw new IOException("Defekte Gewichte");
                }
            };
            try {
                JSONObject response=new KnowledgeSearch(database,vectors,encoder).search("Berlin",()->false);
                assertFalse(stop);assertTrue(response.getString("warning").contains("Defekte Gewichte"));
                assertTrue(response.getJSONArray("hits").length()>0);
            } catch(CancellationException expected){assertTrue(stop);}
        }
    }
    @Test public void configurationRejectsEncoderPathEscapeUnknownModelAndInvalidThreads() throws Exception {
        for(String config:new String[]{"encoder_path=/tmp/outside.gguf\n","encoder_threads=0\n","encoder_threads=9\n","encoder_threads=x\n","encoder_model=other\n"}) {
            Files.write(new File(directory,"config.properties").toPath(),config.getBytes(StandardCharsets.UTF_8));
            try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
                try{KnowledgeSearch.forStore(store);fail(config);}catch(IOException expected){}
            }
        }
        EmbeddingGemma2Encoder encoder=new EmbeddingGemma2Encoder(new File(directory,"missing.gguf"),4);
        try{encoder.encode("Berlin",()->true);fail();}catch(CancellationException expected){}
    }
}
