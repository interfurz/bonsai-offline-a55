package de.bonsai.offline;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** One user turn, including every internal tool round. No prompts in diagnostic logs. */
final class BenchmarkMetrics {
    final long started = SystemClock.elapsedRealtime();
    final JSONArray rounds = new JSONArray();
    private double visibleMs;
    void visible(String text) {
        if (visibleMs == 0 && !text.isEmpty()) visibleMs = Math.max(.001, SystemClock.elapsedRealtime()-started);
    }
    void add(JSONObject stats) { rounds.put(stats); }
    JSONObject finish(JSONObject last, double commitMs) throws Exception {
        JSONObject result = new JSONObject(last.toString());
        for (String key : new String[]{"output_tokens","reasoning_tokens","reused_prompt_tokens","decoded_prompt_tokens","prefill_ms","generation_ms","cleanup_ms"}) {
            double total=0;
            for(int i=0;i<rounds.length();i++) total+=rounds.getJSONObject(i).optDouble(key,0);
            result.put(key,total);
        }
        double generation=result.optDouble("generation_ms");
        // Older test/runtime fixtures may lack generation_ms.
        if(generation>0) result.put("tokens_per_second",result.optDouble("output_tokens")*1000/generation);
        result.put("ttft_ms",visibleMs>0?visibleMs:JSONObject.NULL)
            .put("turn_ms",SystemClock.elapsedRealtime()-started).put("commit_ms",commitMs).put("rounds",rounds);
        return result;
    }
    static String detail(JSONObject stats) {
        double ttft=stats.optDouble("ttft_ms",stats.optDouble("first_visible_token_ms",stats.optDouble("first_token_ms")));
        return String.format(Locale.GERMAN,"%.1f Tokens/s · %d Tokens · %.1f s bis zum ersten Token · Prompt: %d wiederverwendet / %d neu · Prefill %.2f s%s",
            stats.optDouble("tokens_per_second"),stats.optInt("output_tokens"),ttft/1000,
            stats.optInt("reused_prompt_tokens"),stats.optInt("decoded_prompt_tokens"),stats.optDouble("prefill_ms")/1000,
            stats.optBoolean("stopped")?" · abgebrochen":stats.optBoolean("limit_reached")?" · Antwortlimit erreicht":"");
    }
}
