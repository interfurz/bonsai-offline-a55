package de.bonsai.offline;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same Java mmap scanner as the tool; runnable by app_process or a host JVM. */
public final class VectorBenchmark {
    public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>5)throw new IllegalArgumentException("index.ivf queries.f32 [nprobe=16] [warmup=2] [repeats=1]");
        int nprobe=args.length>2?Integer.parseInt(args[2]):16;
        int warmup=args.length>3?Integer.parseInt(args[3]):2;
        int repeats=args.length>4?Integer.parseInt(args[4]):1;
        System.out.println(run(new File(args[0]),new File(args[1]),nprobe,warmup,repeats).toString(2));
    }
    public static JSONObject run(File indexFile,File queryFile,int nprobe,int warmup,int repeats) throws Exception {
        if(nprobe<1 || warmup<0 || repeats<1)throw new IllegalArgumentException("Invalid benchmark parameters");
        float[][] queries=queries(queryFile);
        long start=System.nanoTime();
        KnowledgeSearch.IvfIndex index=new KnowledgeSearch.IvfIndex(indexFile);
        double openMs=(System.nanoTime()-start)/1e6;
        JSONArray rows=new JSONArray();JSONObject summaries=new JSONObject();
        for(boolean full:new boolean[]{true,false}) {
            String mode=full?"full":"ivf";
            double[] centroid=new double[queries.length*repeats],scan=new double[centroid.length],total=new double[centroid.length],scanned=new double[centroid.length];
            for(int step=0;step<warmup+centroid.length;step++) {
                start=System.nanoTime();
                KnowledgeSearch.IvfResult result=index.search(queries[step%queries.length],nprobe,20,()->false,false,full);
                double totalMs=(System.nanoTime()-start)/1e6;
                if(step>=warmup) {
                    int position=step-warmup;
                    centroid[position]=result.centroidNanos/1e6;scan[position]=result.scanNanos/1e6;
                    total[position]=totalMs;scanned[position]=result.scanned;
                    rows.put(new JSONObject().put("mode",mode).put("query",step%queries.length)
                        .put("centroid_ms",centroid[position]).put("scan_ms",scan[position])
                        .put("total_ms",totalMs).put("scanned",result.scanned));
                }
            }
            summaries.put(mode,new JSONObject().put("centroid_ms",percentiles(centroid)).put("scan_ms",percentiles(scan))
                .put("total_ms",percentiles(total)).put("scanned",percentiles(scanned)));
        }
        return new JSONObject().put("runtime",System.getProperty("java.vm.name")).put("arch",System.getProperty("os.arch"))
            .put("scope","actual Java mmap scanner; vector-only; query encoding/FTS/RRF excluded")
            .put("cache","full pass before IVF; warm page cache expected; no cold-cache guarantee")
            .put("count",index.count).put("clusters",index.clusters).put("nprobe",Math.min(nprobe,index.clusters))
            .put("open_validate_ms",openMs).put("warmup",warmup).put("repeats",repeats).put("queries",queries.length)
            .put("summary",summaries).put("rows",rows);
    }
    private static JSONObject percentiles(double[] values) throws Exception {
        Arrays.sort(values);return new JSONObject().put("p50",percentile(values,.5)).put("p95",percentile(values,.95));
    }
    private static double percentile(double[] sorted,double fraction) {
        double at=fraction*(sorted.length-1);int low=(int)at,high=(int)Math.ceil(at);
        return sorted[low]+(sorted[high]-sorted[low])*(at-low);
    }
    private static float[][] queries(File file) throws Exception {
        try(RandomAccessFile input=new RandomAccessFile(file,"r");FileChannel channel=input.getChannel()) {
            if(channel.size()<16 || channel.size()>16+10000L*256*4)throw new IllegalArgumentException("Invalid query file size");
            ByteBuffer buffer=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size()).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic=new byte[8];buffer.get(magic);int dimensions=buffer.getInt(),count=buffer.getInt();
            if(!new String(magic,StandardCharsets.US_ASCII).equals("BRAGQ001") || dimensions!=256 || count<1
                || channel.size()!=16+(long)count*256*4)throw new IllegalArgumentException("Invalid query header");
            float[][] values=new float[count][256];
            for(float[] vector:values)for(int d=0;d<256;d++)vector[d]=buffer.getFloat();
            return values;
        }
    }
    private VectorBenchmark() {}
}
