package de.bonsai.offline;

import android.app.Application;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
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
public class IvfSearchTest {
    private File directory,index;
    @Before public void prepare() throws Exception {
        directory=RagFixtures.install(RuntimeEnvironment.getApplication());
        index=new File(directory,"knowledge.ivf");RagFixtures.copy("knowledge.ivf",index);
    }
    @Test public void readsActualPythonIndexAndMatchesTwentyOneQueryProbeRankings() throws Exception {
        KnowledgeSearch.IvfIndex mapped=new KnowledgeSearch.IvfIndex(index);
        assertEquals(50,mapped.count);assertEquals(8,mapped.clusters);assertEquals(1f/127,mapped.scale,0);
        try(InputStream in=getClass().getResourceAsStream("/rag/ivf-rankings.json")) {
            JSONArray rows=new JSONArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(21,rows.length());
            for(int row=0;row<rows.length();row++) {
                JSONObject expected=rows.getJSONObject(row);
                float[] query=new KnowledgeSearch.SampleEncoder().encode(expected.getString("query"));
                KnowledgeSearch.IvfResult found=mapped.search(query,expected.getInt("nprobe"),10,()->false,false,false);
                JSONArray ids=expected.getJSONArray("ids"),dots=expected.getJSONArray("dots");
                assertEquals(ids.length(),found.ids.length);assertEquals(expected.getLong("scanned"),found.scanned);
                for(int i=0;i<ids.length();i++) {
                    assertEquals(ids.getLong(i),found.ids[i]);assertEquals(dots.getInt(i),found.dots[i]);
                }
                assertTrue(found.centroidNanos>=0);assertTrue(found.scanNanos>=0);
            }
        }
    }
    @Test public void allClustersEqualFullScanAndTopKCanExceedCorpus() throws Exception {
        KnowledgeSearch.IvfIndex mapped=new KnowledgeSearch.IvfIndex(index);
        float[] query=new KnowledgeSearch.SampleEncoder().encode("Was weißt du über Berlin?");
        KnowledgeSearch.IvfResult all=mapped.search(query,8,100,()->false,false,false);
        KnowledgeSearch.IvfResult full=mapped.search(query,1,100,()->false,false,true);
        assertEquals(50,all.ids.length);assertEquals(50,all.scanned);
        assertArrayEquals(full.ids,all.ids);assertArrayEquals(full.dots,all.dots);
    }
    @Test public void mmapHandlesSegmentsAndVectorOffsetsBeyondTwoGiBWithoutHeapCopy() throws Exception {
        File sparse=new File(directory,"sparse.ivf");
        long count=(1L<<31)/256+1;
        writeSparse(sparse,count,2,new long[]{0,count-2,count});
        KnowledgeSearch.IvfIndex mapped=new KnowledgeSearch.IvfIndex(sparse);
        float[] query=new float[256];query[0]=1;
        KnowledgeSearch.IvfResult found=mapped.search(query,1,10,()->false,false,false);
        assertTrue(sparse.length()>(1L<<31));assertEquals(2,found.scanned);
        assertArrayEquals(new long[]{3,7},found.ids);assertArrayEquals(new int[]{16129,8128},found.dots);
    }
    @Test public void emptyClustersAndEmptyCorpusAreValid() throws Exception {
        File sparse=new File(directory,"empty-clusters.ivf");
        writeSparse(sparse,2,4,new long[]{0,0,1,1,2});
        KnowledgeSearch.IvfIndex mapped=new KnowledgeSearch.IvfIndex(sparse);
        assertEquals(0,mapped.search(new float[256],1,10,()->false,false,false).ids.length);
        assertEquals(2,mapped.search(new float[256],4,10,()->false,false,false).ids.length);
        File empty=new File(directory,"empty.ivf");writeSparse(empty,0,1,new long[]{0,0});
        assertEquals(0,new KnowledgeSearch.IvfIndex(empty).search(new float[256],16,10,()->false,false,false).ids.length);
    }
    @Test public void missingTruncatedAndCorruptFilesGiveClearIoErrors() throws Exception {
        try{new KnowledgeSearch.IvfIndex(new File(directory,"absent.ivf"));fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("IVF-Index fehlt"));}
        byte[] original=Files.readAllBytes(index.toPath());
        for(int field:new int[]{8,16,32,40,128}) {
            byte[] corrupt=original.clone();ByteBuffer bytes=ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(field,field==32?0:99);Files.write(index.toPath(),corrupt);
            try{new KnowledgeSearch.IvfIndex(index);fail("field "+field);}
            catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Ungültiger IVF-Index"));}
        }
        Files.write(index.toPath(),java.util.Arrays.copyOf(original,original.length-1));
        try{new KnowledgeSearch.IvfIndex(index);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("IVF-Index"));}
    }
    @Test public void cancellationAndInvalidSearchParametersPropagateClearly() throws Exception {
        KnowledgeSearch.IvfIndex mapped=new KnowledgeSearch.IvfIndex(index);
        try{mapped.search(new float[256],16,10,()->true,false,false);fail();}catch(CancellationException expected){}
        for(int[] args:new int[][]{{0,10},{16,0}}) {
            try{mapped.search(new float[256],args[0],args[1],()->false,false,false);fail();}
            catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Query/nprobe"));}
        }
        float[] query=new float[256];query[0]=Float.NaN;
        try{mapped.search(query,16,10,()->false,false,false);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Query-Vektor"));}
    }
    @Test public void toolUsesConfiguredIvfAndRrfWhileReportingMissingExplicitIndex() throws Exception {
        File config=new File(directory,"config.properties");
        Files.write(config.toPath(),"ivf=knowledge.ivf\nnprobe=4\nrrf_k=20\n".getBytes(StandardCharsets.UTF_8));
        try(ChatStore store=new ChatStore(RuntimeEnvironment.getApplication())) {
            JSONArray hits=KnowledgeSearch.forStore(store).search("Photosynthese",()->false).getJSONArray("hits");
            assertTrue(hits.length()>0);assertEquals("2",hits.getJSONObject(0).getString("article_id"));
            Files.write(config.toPath(),"ivf=absent.ivf\n".getBytes(StandardCharsets.UTF_8));
            try{KnowledgeSearch.forStore(store);fail();}
            catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("IVF-Index fehlt"));}
            Files.write(config.toPath(),"nprobe=0\n".getBytes(StandardCharsets.UTF_8));
            try{KnowledgeSearch.forStore(store);fail();}
            catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Suchkonfiguration"));}
        }
    }
    private static void writeSparse(File file,long count,int clusters,long[] offsets) throws Exception {
        long centers=128+(clusters+1L)*8,ids=centers+clusters*256L*4;
        long vectors=((ids+count*8+4095)/4096)*4096,fileBytes=vectors+count*256;
        ByteBuffer header=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        header.put("BRAGIVF1".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(128).putInt(256).putInt(clusters)
            .putLong(count).putFloat(1f/127).putInt(1).putLong(128).putLong(centers).putLong(ids)
            .putLong(vectors).putLong(fileBytes).putLong(42);
        try(RandomAccessFile output=new RandomAccessFile(file,"rw")) {
            output.setLength(fileBytes);output.write(header.array());
            for(long offset:offsets)output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(offset).array());
            if(clusters==2) {
                output.seek(centers+256*4);output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).array());
            }
            if(count>=2)for(int row=0;row<2;row++) {
                long position=count-2+row;
                output.seek(ids+position*8);output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(row==0?3:7).array());
                output.seek(vectors+position*256);output.writeByte(row==0?127:64);
            }
        }
    }
}
