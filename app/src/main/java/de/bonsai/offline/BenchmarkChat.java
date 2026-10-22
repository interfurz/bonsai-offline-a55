package de.bonsai.offline;

import android.os.Build;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed five-turn conversation through the same tools, stream and commit path as chat. */
final class BenchmarkChat {
    static final String[] QUESTIONS={
        "Erkläre in drei kurzen Sätzen, wie ein Wald Wasser speichert.",
        "Welche Rolle spielen dabei die Wurzeln? Antworte in drei kurzen Sätzen.",
        "Und welche Rolle spielen Pilze? Antworte in drei kurzen Sätzen.",
        "Was verändert eine lange Trockenzeit daran? Antworte in drei kurzen Sätzen.",
        "Fasse unsere bisherigen Antworten in drei kurzen Sätzen zusammen."
    };
    static void environment(BonsaiApp app,JSONObject json,String suffix) throws Exception {
        double temperature=app.batteryTemperature();
        json.put("battery_celsius"+suffix,temperature<0?JSONObject.NULL:temperature)
            .put("thermal_status"+suffix,app.thermalStatus()).put("rss_kb"+suffix,BonsaiApp.rssKb());
    }
    static void run(BonsaiApp app,long handle) {
        JSONObject report=new JSONObject();JSONArray turns=new JSONArray();String issue="";
        boolean grammar=app.gbnfTools();
        java.util.concurrent.atomic.AtomicLong peak=new java.util.concurrent.atomic.AtomicLong(BonsaiApp.rssKb());
        java.util.concurrent.ScheduledExecutorService monitor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ScheduledFuture<?> sampling=monitor.scheduleWithFixedDelay(()->peak.accumulateAndGet(BonsaiApp.rssKb(),Math::max),0,250,java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            report.put("schema",3).put("benchmark","bonsai-chat-5-v1").put("timestamp_utc",java.time.Instant.now().toString())
                .put("app_version",BuildConfig.VERSION_NAME).put("app_version_code",BuildConfig.VERSION_CODE)
                .put("runtime_commit",BuildConfig.RUNTIME_REVISION).put("device",Build.MANUFACTURER+" "+Build.MODEL)
                .put("fingerprint",Build.FINGERPRINT).put("android_api",Build.VERSION.SDK_INT)
                .put("model",app.modelName()).put("model_sha256",app.modelSha256()).put("backend",app.backend)
                .put("requested_profile",KernelOptimizer.current(app).json()).put("context_tokens",app.context())
                .put("threads",app.threads()).put("rss_sample_interval_ms",250).put("seed",42).put("max_output_tokens",128).put("reasoning_budget",grammar?0:600).put("gbnf",grammar)
                .put("prefix_warmup",app.prefs.getBoolean("prefixWarmup",true)).put("turns",turns)
                .put("notes","One isolated connected conversation; reset once before turn 1, no untimed generation. Prefix warm-up is reported separately. TTFT is first visible final-answer callback across all tool rounds. Tokens/s includes reasoning and all native generation rounds, excludes prefill, cleanup and commit. No A55 result is implied by host tests.");
            NativeEngine.clearCache(handle);
            long warmStart=SystemClock.elapsedRealtime();app.warmPrefixOnWorker();
            report.put("prefix_warmup_wall_ms",SystemClock.elapsedRealtime()-warmStart)
                .put("prefix_warmup_stats",app.prefixWarmupStats==null?JSONObject.NULL:app.prefixWarmupStats);
            List<ChatStore.Message> history=new ArrayList<>();
            for(int i=0;i<QUESTIONS.length;i++) {
                if(app.cancelled) throw new IllegalStateException("Abgebrochen.");
                String progress="Chat-Turn "+(i+1)+" / "+QUESTIONS.length;
                app.main.post(()->{app.benchmarkProgress=progress;app.status=progress;app.notifyUi();});
                JSONObject turn=new JSONObject().put("turn",i+1).put("question",QUESTIONS[i]);turns.put(turn);
                environment(app,turn,"_before");
                history.add(new ChatStore.Message(0,"user",QUESTIONS[i],""));
                BenchmarkMetrics metrics=new BenchmarkMetrics();final int[] budget={grammar?0:600};
                ToolLoop.Result result=ToolLoop.run(history,app.store,(roles,texts,gbnf)->{
                    ReplyStream output=new ReplyStream(grammar,metrics::visible);
                    JSONObject stats=new JSONObject(new String(NativeEngine.generate(handle,roles,texts,128,42,output,gbnf,budget[0]),StandardCharsets.UTF_8));
                    budget[0]=Math.max(0,budget[0]-stats.optInt("reasoning_tokens"));metrics.add(stats);
                    return new ToolLoop.Result(output.text(),stats);
                },()->app.cancelled,grammar);
                String answer=ReasoningText.strip(result.text);
                long commit=SystemClock.elapsedRealtime();JSONObject committed=app.commitCleanTurn(history,answer,grammar);
                JSONObject stats=metrics.finish(result.stats,SystemClock.elapsedRealtime()-commit,committed);
                // Flat per-turn metrics make two exports straightforward to compare.
                java.util.Iterator<String> keys=stats.keys();while(keys.hasNext()) {String key=keys.next();turn.put(key,stats.get(key));}
                turn.put("answer",answer);environment(app,turn,"_after");
                history.add(new ChatStore.Message(0,"assistant",answer,""));
                if(app.prefs.getBoolean("turnDebugLog",false)) android.util.Log.d("BonsaiTurn",stats.toString());
            }
            List<Double> speeds=new ArrayList<>(),first=new ArrayList<>(),prefill=new ArrayList<>();
            for(int i=0;i<turns.length();i++) {
                JSONObject turn=turns.getJSONObject(i);
                if(!turn.isNull("ttft_ms"))first.add(turn.getDouble("ttft_ms"));
                speeds.add(turn.getDouble("tokens_per_second"));prefill.add(turn.getDouble("prefill_ms"));
            }
            java.util.Collections.sort(speeds);java.util.Collections.sort(first);java.util.Collections.sort(prefill);
            if(!first.isEmpty())report.put("median_first_token_ms",BonsaiApp.median(first));
            report.put("median_tokens_per_second",BonsaiApp.median(speeds)).put("median_prefill_ms",BonsaiApp.median(prefill));
            report.put("completed",!app.cancelled && turns.length()==5);
        } catch(Exception | LinkageError e) {
            issue=e.getMessage()==null?e.toString():e.getMessage();
            try {report.put("completed",false).put("error",issue);}catch(Exception ignored) { }
        } finally {
            sampling.cancel(false);monitor.shutdown();
            try {report.put("rss_peak_kb",peak.accumulateAndGet(BonsaiApp.rssKb(),Math::max));}catch(Exception ignored) { }
            try {KernelOptimizer.write(app,"benchmark.json",report);}catch(Exception e) {issue=e.toString();}
            // The user's persisted chat is untouched; its next prompt will be resynchronized.
            String result=issue;
            app.main.post(()->{app.benchmark=report;app.benchmarkProgress="";app.complete(result);BenchmarkControls.results(app);});
        }
    }
}
