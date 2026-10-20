package de.bonsai.offline;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BonsaiApp extends Application {
    static final String BENCHMARK_PROMPT = "Erkläre ausführlich auf Deutsch, wie ein Wald Wasser speichert, wie Bäume wachsen und wie Boden, Pilze und Tiere zusammenwirken. Schreibe mindestens 400 Wörter als zusammenhängenden Sachtext. Beziehe die folgenden Hintergrundinformationen ein: ";
    static final String BENCHMARK_CONTEXT = "Ein Wald ist ein Ökosystem mit Bäumen, Sträuchern, Moosen und einem belebten Boden. Niederschlag wird von Kronen aufgefangen, versickert im Boden und wird von Wurzeln aufgenommen. Pflanzen nutzen Sonnenlicht, Wasser und Kohlendioxid für die Photosynthese. Pilze unterstützen die Nährstoffaufnahme. Abgestorbene Blätter werden zersetzt und tragen zur Humusbildung bei. ";
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
    ChatStore store;
    long currentChat;
    final List<ChatStore.Message> messages = new ArrayList<>();
    Runnable listener;
    volatile boolean cancelled;
    boolean busy, loaded;
    String status = "Modell auswählen", error = "", backend = "CPU", benchmarkProgress = "";
    JSONObject benchmark, optimizerReport, cpuReference;
    String backendNotice="";
    boolean modelCheckFinished;
    boolean optimizing;
    float optimizingFraction;
    String optimizingProgress="";
    double liveTokensPerSecond;
    String nativeError = "";
    private long handle;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        store = new ChatStore(this);
        store.recoverInterruptedTurns();
        if(prefs.getBoolean("optimizerRunning",false)) {
            error="Die vorige Optimierung wurde unterbrochen. Das gespeicherte Profil bleibt erhalten.";
            prefs.edit().putBoolean("optimizerRunning",false).apply();
        }
        currentChat = prefs.getLong("chat", 0);
        messages.addAll(store.messages(currentChat));
        try { handle = NativeEngine.create(); }
        catch (LinkageError | RuntimeException e) { nativeError = e.toString(); status = "Inferenz-Unterbau fehlt"; }
        // An interrupted import never replaces the previous model.
        new File(getFilesDir(), "model.gguf.part").delete();
    }
    void notifyUi() { if (listener != null) listener.run(); }
    File modelFile() { return new File(getFilesDir(), "model.gguf"); }
    String modelName() { return prefs.getString("modelName", "Bonsai 2 4B"); }
    int context() { return prefs.getInt("context", 4096); }
    int threads() { return prefs.getInt("threads", 4); }
    int batchThreads() { return prefs.getInt("batchThreads",threads()); }
    int microBatch() { return prefs.getInt("microBatch",gpu()?1:32); }
    String profileIdentity() { return profileIdentity(gpu(),threads(),batchThreads(),microBatch()); }
    String profileIdentity(boolean gpu,int threads,int batchThreads,int microBatch) {
        return modelFile().length()+":"+modelFile().lastModified()+":"+context()+":"+gpu+":"+threads+":"+batchThreads+":"+microBatch+":"+Build.FINGERPRINT+":"+BuildConfig.RUNTIME_REVISION;
    }
    boolean optimized() { return profileIdentity().equals(prefs.getString("optimizedFor","")); }
    JSONObject readReport(String name) {
        try { return new JSONObject(new String(Files.readAllBytes(new File(getFilesDir(),name).toPath()),StandardCharsets.UTF_8)); }
        catch(Exception ignored) { return null; }
    }
    void optimize(boolean compare) {
        if(!loaded) { error="Bitte zuerst ein Modell laden."; notifyUi(); return; }
        if(!begin("Kernel Optimizer startet …")) return;
        optimizing=true; optimizingProgress="Messung wird vorbereitet"; optimizingFraction=0; notifyUi();
        worker.execute(()->KernelOptimizer.run(this,handle,compare));
    }
    void finishOptimization(String issue) { complete(issue); }
    int maxTokens() { return prefs.getInt("maxTokens", 512); }
    boolean gpu() { return BuildConfig.VULKAN_BUILD && prefs.getBoolean("gpu", false); }
    void newChat() {
        if (busy) return;
        currentChat=0; messages.clear(); prefs.edit().putLong("chat",0).apply(); error=""; notifyUi();
    }
    void selectChat(long id) {
        if (busy) return;
        currentChat=id; messages.clear(); messages.addAll(store.messages(id));
        prefs.edit().putLong("chat",id).apply(); error=""; notifyUi();
    }
    void deleteCurrentChat() {
        if (busy) return;
        if(currentChat != 0) store.deleteChat(currentChat);
        newChat();
    }
    void regenerateLastAnswer() {
        if(busy || !loaded || messages.size()<2) return;
        if(handle==0) { error="Das Modell konnte nicht gestartet werden. Bitte lade es erneut.";notifyUi();return; }
        ChatStore.Message question=messages.get(messages.size()-2);
        ChatStore.Message answer=messages.get(messages.size()-1);
        if(!"user".equals(question.role)||!"assistant".equals(answer.role))return;
        // Keep the original conversation intact; a variant owns its own history.
        currentChat=store.forkBeforeLastTurn(currentChat);
        prefs.edit().putLong("chat",currentChat).apply();
        messages.clear();messages.addAll(store.messages(currentChat));
        send(question.text);
    }
    private boolean begin(String label) {
        if (busy) return false;
        if (handle == 0) { error = "Native Runtime konnte nicht geladen werden: " + nativeError; notifyUi(); return false; }
        NativeEngine.resetStop(handle);
        cancelled=false; busy=true; status=label; error=""; notifyUi(); return true;
    }
    void cancel() {
        if (!busy) return;
        cancelled=true; NativeEngine.stop(handle); status="Wird abgebrochen …"; notifyUi();
    }
    private String reason(Throwable e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    private void complete(String issue) {
        busy=false;
        error = cancelled ? "" : issue;
        status = loaded ? "Bereit · " + backend : modelFile().isFile() ? "Modell nicht geladen" : "Modell auswählen";
        notifyUi();
    }
    private void loadOnWorker() throws Exception {
        String actualBackend=BackendGuard.load(this,handle);
        main.post(() -> { loaded=true; backend=actualBackend; });
    }
    void loadModel() {
        if (!modelFile().isFile()) { error="Bitte zuerst eine GGUF-Datei importieren."; notifyUi(); return; }
        if (!begin("Modell wird geladen …")) return;
        loaded=false;
        worker.execute(() -> {
            String issue="";
            try { loadOnWorker(); } catch (Exception | LinkageError e) { issue=reason(e); }
            String result=issue; main.post(() -> complete(result));
        });
    }
    void unloadModel() {
        if (!begin("Modell wird entladen …")) return;
        worker.execute(() -> {
            NativeEngine.unload(handle);
            main.post(() -> { loaded=false; complete(""); });
        });
    }
    void importModel(Uri uri) {
        if (!begin("GGUF wird importiert …")) return;
        worker.execute(() -> {
            String issue="";
            File partial=new File(getFilesDir(),"model.gguf.part");
            try {
                String name="Bonsai.gguf"; long size=-1;
                try(Cursor c=getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                    if(c != null && c.moveToFirst()) {
                        int nameIndex=c.getColumnIndex(OpenableColumns.DISPLAY_NAME), sizeIndex=c.getColumnIndex(OpenableColumns.SIZE);
                        if(nameIndex>=0 && !c.isNull(nameIndex)) name=c.getString(nameIndex);
                        if(sizeIndex>=0 && !c.isNull(sizeIndex)) size=c.getLong(sizeIndex);
                    }
                }
                if(size > 0 && new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes() < size+64L*1024*1024)
                    throw new IllegalStateException("Zu wenig Speicher für den Import. Die App legt eine lokale Kopie an.");
                try(InputStream in=getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(partial)) {
                    if(in==null) throw new IllegalStateException("Datei nicht lesbar.");
                    byte[] magic=new byte[4]; int got=0;
                    while(got<4) { int n=in.read(magic,got,4-got); if(n<0) break; got+=n; }
                    if(got!=4 || !Arrays.equals(magic,new byte[]{'G','G','U','F'})) throw new IllegalStateException("Diese Datei ist kein GGUF-Modell.");
                    out.write(magic); byte[] buffer=new byte[1024*1024]; long copied=4, lastUpdate=0; int n;
                    while(!cancelled && (n=in.read(buffer))!=-1) {
                        out.write(buffer,0,n); copied+=n;
                        if(System.currentTimeMillis()-lastUpdate>250) {
                            lastUpdate=System.currentTimeMillis();
                            String label=String.format(Locale.GERMAN,"Import: %.0f MB%s",copied/1048576.0,size>0 ? String.format(Locale.GERMAN," / %.0f MB",size/1048576.0) : "");
                            main.post(() -> { if(!cancelled) { status=label; notifyUi(); } });
                        }
                    }
                    if(cancelled) throw new IllegalStateException("Abgebrochen.");
                    if(size>=0 && copied!=size) throw new IllegalStateException("Die Datei wurde unvollständig kopiert.");
                    out.getFD().sync();
                }
                if(cancelled) throw new IllegalStateException("Abgebrochen.");
                NativeEngine.unload(handle);
                main.post(() -> { loaded=false; cpuReference=null; });
                Files.move(partial.toPath(),modelFile().toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                prefs.edit().putString("modelName",name==null ? "Bonsai.gguf" : name).remove("optimizedFor").apply();
                main.post(() -> { status="Modell wird geladen …"; notifyUi(); });
                loadOnWorker();
            } catch(Exception | LinkageError e) { issue=reason(e); }
            finally { partial.delete(); }
            String result=issue; main.post(() -> complete(result));
        });
    }
    void send(String input) {
        if(busy || input.trim().isEmpty()) return;
        if(!loaded) { error="Bitte zuerst ein Modell importieren und laden."; notifyUi(); return; }
        if(input.length()>32000) { error="Die Nachricht ist zu lang."; notifyUi(); return; }
        if(!begin("Antwort wird vorbereitet …")) return;
        if(currentChat==0) {
            String title=input.trim().replace('\n',' ');
            currentChat=store.createChat(title.substring(0,Math.min(title.length(),60)));
            prefs.edit().putLong("chat",currentChat).apply();
        }
        final long chat=currentChat;
        long userId=store.add(chat,"user",input,"");
        messages.add(new ChatStore.Message(userId,"user",input,""));
        List<ChatStore.Message> history=new ArrayList<>(messages);
        ChatStore.Message answer=new ChatStore.Message(0,"assistant","",""); messages.add(answer); notifyUi();
        final int limit=maxTokens();
        worker.execute(() -> {
            ByteArrayOutputStream output=new ByteArrayOutputStream(); String issue="", detail="";
            try {
                byte[][] roles=new byte[history.size()+1][], texts=new byte[history.size()+1][];
                roles[0]=utf8("system"); texts[0]=utf8("Du bist ein hilfreicher Assistent. Antworte auf Deutsch, sofern die Person keine andere Sprache wünscht.");
                for(int i=0;i<history.size();i++) { roles[i+1]=utf8(history.get(i).role); texts[i+1]=utf8(history.get(i).text); }
                JSONObject stats=new JSONObject(new String(NativeEngine.generate(handle,roles,texts,limit,-1,chunk -> {
                    output.write(chunk,0,chunk.length); String value=new String(output.toByteArray(),StandardCharsets.UTF_8);
                    main.post(() -> { answer.text=value; if(!cancelled) status="Antwort wird geschrieben …"; notifyUi(); });
                }),StandardCharsets.UTF_8));
                final double speed=stats.getDouble("tokens_per_second");
                main.post(()->liveTokensPerSecond=speed);
                detail=String.format(Locale.GERMAN,"%.1f Tokens/s · %d Tokens · %.1f s bis zum ersten Token%s",stats.getDouble("tokens_per_second"),stats.getInt("output_tokens"),stats.getDouble("first_token_ms")/1000,
                    stats.getBoolean("stopped") ? " · abgebrochen" : stats.getBoolean("limit_reached") ? " · Antwortlimit erreicht" : "");
            } catch(Exception | LinkageError e) { issue=reason(e); detail=cancelled ? "Abgebrochen" : "Unvollständige Antwort"; }
            String text=new String(output.toByteArray(),StandardCharsets.UTF_8);
            if(!text.isEmpty()) store.add(chat,"assistant",text,detail);
            else store.removeMessage(userId);  // Do not leave an unanswered user turn in model history.
            String result=issue;
            main.post(() -> { messages.clear(); messages.addAll(store.messages(chat)); complete(result); });
        });
    }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static long rssKb() {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/status"),StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) if(line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]",""));
        } catch(Exception ignored) { }
        return -1;
    }
    int thermalStatus() {
        return Build.VERSION.SDK_INT>=29 ? ((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus() : -1;
    }
    private double batteryTemperature() {
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery==null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-10)/10.0;
    }
    void runModelCheck() {
        if(!loaded) { error="Bitte zuerst ein Modell laden.";notifyUi();return; }
        if(!begin("Modelltest ohne Chatverlauf …"))return;
        String actualBackend=backend;
        worker.execute(()->{
            JSONObject report=new JSONObject();String issue="";
            try {
                report.put("model",modelName()).put("model_bytes",modelFile().length()).put("backend",actualBackend)
                    .put("app_version",BuildConfig.VERSION_NAME).put("device",Build.MANUFACTURER+" "+Build.MODEL)
                    .put("context",context()).put("threads",threads()).put("batch_threads",batchThreads()).put("micro_batch",microBatch())
                    .put("timestamp",java.time.Instant.now().toString()).put("metadata",readReport("model-metadata.json"));
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                try(InputStream file=new FileInputStream(modelFile())) {
                    byte[] buffer=new byte[1024*1024];int count;
                    while((count=file.read(buffer))!=-1) { if(cancelled)throw new IllegalStateException("Abgebrochen.");digest.update(buffer,0,count); }
                }
                StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));
                report.put("model_sha256",hash.toString());
                String[] questions={"Was ist 2 + 2? Antworte nur mit der Zahl.","Erkläre in drei kurzen Sätzen, warum Bäume Wasser brauchen."};
                JSONArray answers=new JSONArray();report.put("answers",answers);
                for(int i=0;i<questions.length;i++) {
                    if(cancelled)throw new IllegalStateException("Abgebrochen.");
                    String label="Modelltest "+(i+1)+" / 2 · ohne bisherigen Chat";
                    main.post(()->{status=label;notifyUi();});
                    ByteArrayOutputStream text=new ByteArrayOutputStream();
                    JSONObject stats=new JSONObject(new String(NativeEngine.generate(handle,
                        new byte[][]{utf8("system"),utf8("user")},
                        new byte[][]{utf8("Du bist ein hilfreicher Assistent. Antworte auf Deutsch, sofern die Person keine andere Sprache wünscht."),utf8(questions[i])},
                        i==0?32:96,42,chunk->text.write(chunk,0,chunk.length)),StandardCharsets.UTF_8));
                    answers.put(new JSONObject().put("question",questions[i]).put("answer",new String(text.toByteArray(),StandardCharsets.UTF_8)).put("stats",stats));
                }
                report.put("arithmetic_exact",answers.getJSONObject(0).getString("answer").trim().equals("4"));
                report.put("completed",!cancelled);
            } catch(Exception | LinkageError e) { issue=reason(e);try{report.put("error",issue).put("completed",false);}catch(Exception ignored){} }
            try {KernelOptimizer.write(this,"modelcheck.json",report);}catch(Exception e){issue=reason(e);}
            String result=issue;
            main.post(()->{modelCheckFinished=!cancelled;complete(result);});
        });
    }

    void runBenchmark() {
        if(!loaded) { error="Für den Benchmark bitte zuerst ein Modell laden."; notifyUi(); return; }
        if(!begin("Benchmark startet …")) return;
        benchmark=null; benchmarkProgress="Aufwärmdurchlauf …";
        String name=modelName(), actualBackend=backend;
        final int selectedContext=context(), selectedThreads=threads();
        worker.execute(() -> {
            JSONObject report=new JSONObject(); JSONArray runs=new JSONArray(); String issue="";
            long baseline=rssKb(); AtomicLong peak=new AtomicLong(baseline);
            ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> sampling=monitor.scheduleWithFixedDelay(() -> peak.accumulateAndGet(rssKb(),Math::max),0,250,TimeUnit.MILLISECONDS);
            try {
                String prompt=BENCHMARK_PROMPT+BENCHMARK_CONTEXT+BENCHMARK_CONTEXT+BENCHMARK_CONTEXT+BENCHMARK_CONTEXT+BENCHMARK_CONTEXT;
                report.put("schema",1).put("benchmark","bonsai-offline-v1").put("timestamp_utc",java.time.Instant.now().toString())
                    .put("device",Build.MANUFACTURER+" "+Build.MODEL).put("hardware",Build.HARDWARE).put("android_api",Build.VERSION.SDK_INT)
                    .put("app_version",BuildConfig.VERSION_NAME).put("runtime_commit",BuildConfig.RUNTIME_REVISION)
                    .put("model",name).put("model_bytes",modelFile().length()).put("backend",actualBackend)
                    .put("context_tokens",selectedContext).put("threads",selectedThreads).put("max_output_tokens",128)
                    .put("batch_tokens",256).put("batch_threads",batchThreads()).put("microbatch_tokens",microBatch()).put("flash_attention",false)
                    .put("thinking",false).put("seed",42).put("prompt",prompt).put("runs",runs)
                    .put("thermal_before",thermalStatus()).put("battery_celsius_before",batteryTemperature())
                    .put("rss_baseline_kb",baseline).put("rss_sample_interval_ms",250)
                    .put("notes","1 warm-up (32 tokens), then 3 measured runs (up to 128 tokens, EOS allowed). Every run clears context and processes the full prompt. RSS is sampled process RAM; GPU memory and very short peaks may be missing. Tokens/s includes sampling and native loop overhead, excludes prefill. No model SHA computed; model name and byte size are identifiers only.");
                byte[][] roles={utf8("user")}, texts={utf8(prompt)};
                // Warm-up is deliberately excluded from reported timing statistics.
                NativeEngine.generate(handle,roles,texts,32,42,chunk -> {});
                for(int i=0;i<3 && !cancelled;i++) {
                    String progress="Messlauf "+(i+1)+" von 3 …";
                    main.post(() -> { benchmarkProgress=progress; if(!cancelled) status=progress; notifyUi(); });
                    JSONObject run=new JSONObject(new String(NativeEngine.generate(handle,roles,texts,128,42,chunk -> {}),StandardCharsets.UTF_8));
                    run.put("run",i+1); runs.put(run);
                }
                List<Double> speeds=new ArrayList<>(), first=new ArrayList<>(), prefill=new ArrayList<>();
                for(int i=0;i<runs.length();i++) {
                    JSONObject r=runs.getJSONObject(i);
                    if(!r.getBoolean("stopped") && r.getInt("output_tokens")>0) {
                        speeds.add(r.getDouble("tokens_per_second")); first.add(r.getDouble("first_token_ms")); prefill.add(r.getDouble("prefill_ms"));
                    }
                }
                if(!speeds.isEmpty()) {
                    Collections.sort(speeds); Collections.sort(first); Collections.sort(prefill);
                    report.put("median_tokens_per_second",median(speeds)).put("median_first_token_ms",median(first)).put("median_prefill_ms",median(prefill));
                }
                report.put("completed",!cancelled && speeds.size()==3);
            } catch(Exception | LinkageError e) {
                issue=reason(e); try { report.put("completed",false).put("error",issue); } catch(Exception ignored) { }
            } finally {
                sampling.cancel(false); monitor.shutdown();
                try {
                    peak.accumulateAndGet(rssKb(),Math::max);
                    report.put("rss_peak_kb",peak.get()).put("thermal_after",thermalStatus()).put("battery_celsius_after",batteryTemperature());
                    // Exportable result survives an activity recreation and a process restart.
                    try(FileOutputStream out=openFileOutput("benchmark.json",MODE_PRIVATE)) { out.write(utf8(report.toString(2))); }
                } catch(Exception e) { if(issue.isEmpty()) issue=reason(e); }
            }
            String result=issue;
            main.post(() -> { benchmark=report; benchmarkProgress=""; complete(result); });
        });
    }
    static double median(List<Double> sorted) {
        int n=sorted.size(); return n%2==1 ? sorted.get(n/2) : (sorted.get(n/2-1)+sorted.get(n/2))/2;
    }
    JSONObject lastBenchmark() {
        if(benchmark!=null) return benchmark;
        try(InputStream in=openFileInput("benchmark.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
            while((n=in.read(b))!=-1) bytes.write(b,0,n);
            benchmark=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
        return benchmark;
    }
}
