package de.bonsai.offline;

import android.os.Build;
import android.os.Debug;
import java.io.File;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the real Android tool query code under ART, without LLM inference or network. */
public final class WikidataBenchmark {
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2)throw new IllegalArgumentException("knowledge.sqlite [repeats=30]");
        int repeats=args.length==2?Integer.parseInt(args[1]):30;
        if(repeats<1 || repeats>1000)throw new IllegalArgumentException("repeats: 1..1000");
        File database=new File(args[0]);WikidataKnowledge knowledge=new WikidataKnowledge(database);
        String[] requests={
            "{\"op\":\"facts\",\"entity\":\"Q17\",\"property\":\"P36\"}",
            "{\"op\":\"facts\",\"entity\":\"Q937\",\"property\":\"P569\"}",
            "{\"op\":\"facts\",\"entity\":\"Deutschland\",\"property\":\"P47\"}",
            "{\"op\":\"path\",\"entity\":\"Q937\",\"property\":\"P19/P17/P36\"}",
            "{\"op\":\"greater\",\"entity\":\"Q11344\",\"property\":\"P1086\",\"value\":\"50\",\"unit\":\"1\"}",
            "{\"op\":\"count\",\"entity\":\"Q634\",\"property\":\"P397\",\"value\":\"Q2537\"}"};
        JSONArray rows=new JSONArray();long beforePss=Debug.getPss();
        for(String request:requests) {
            JSONObject query=new JSONObject(request),last=null;double firstMs=0;double[] times=new double[repeats];
            for(int i=-3;i<repeats;i++) {
                long start=System.nanoTime();last=knowledge.query(query,()->false);double ms=(System.nanoTime()-start)/1e6;
                if(i==-3)firstMs=ms;if(i>=0)times[i]=ms;
            }
            Arrays.sort(times);
            rows.put(new JSONObject().put("request",query).put("first_ms",firstMs)
                .put("p50_ms",times[(repeats-1)/2]).put("p95_ms",times[(int)Math.floor((repeats-1)*.95)])
                .put("result",last));
        }
        System.out.println(new JSONObject().put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("runtime",System.getProperty("java.vm.name")).put("database_bytes",database.length())
            .put("scope","real Android query implementation; open/metadata/SQL/JSON included; LLM excluded")
            .put("cache","3 warmups per query; first query does not imply cold filesystem cache")
            .put("memory_scope","standalone process PSS snapshots, not peak or Bonsai plus LLM RAM")
            .put("pss_before_kib",beforePss).put("pss_after_kib",Debug.getPss())
            .put("repeats",repeats).put("rows",rows).toString(2));
    }
}
