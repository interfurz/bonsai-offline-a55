package de.bonsai.offline;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=ToolChatTest.Engine.class)
public class BenchmarkMetricsTest {
    @Test public void includesAllToolRoundsAndKeepsFinalCacheState() throws Exception {
        BenchmarkMetrics metrics=new BenchmarkMetrics();
        metrics.add(new JSONObject("{output_tokens:10,reasoning_tokens:8,reused_prompt_tokens:100,decoded_prompt_tokens:20,prefill_ms:200,generation_ms:1000}"));
        JSONObject last=new JSONObject("{output_tokens:20,reused_prompt_tokens:130,decoded_prompt_tokens:5,prefill_ms:50,generation_ms:2000,cached_tokens:160,kv_positions:160}");
        metrics.add(last);metrics.visible("Antwort");
        JSONObject result=metrics.finish(last,12);
        assertEquals(30,result.getInt("output_tokens"));assertEquals(8,result.getInt("reasoning_tokens"));
        assertEquals(230,result.getInt("reused_prompt_tokens"));assertEquals(25,result.getInt("decoded_prompt_tokens"));
        assertEquals(250,result.getInt("prefill_ms"));assertEquals(10,result.getDouble("tokens_per_second"),.001);
        assertEquals(160,result.getInt("kv_positions"));assertEquals(2,result.getJSONArray("rounds").length());
        assertTrue(BenchmarkMetrics.detail(result).contains("230 wiederverwendet / 25 neu"));
        assertTrue(result.getDouble("ttft_ms")>0);
    }
    @Test public void missingVisibleTokenIsExplicitlyNull() throws Exception {
        BenchmarkMetrics metrics=new BenchmarkMetrics();
        assertTrue(metrics.finish(new JSONObject(),0).isNull("ttft_ms"));
    }
}
