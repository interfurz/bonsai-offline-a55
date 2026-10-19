package de.bonsai.offline;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Measures the existing native kernels; never changes weights, precision or context size. */
final class KernelOptimizer {
    static final class Profile {
        final boolean gpu;
        final int threads, batchThreads, microBatch;
        Profile(boolean gpu, int threads, int batchThreads, int microBatch) {
            this.gpu=gpu; this.threads=threads; this.batchThreads=batchThreads; this.microBatch=microBatch;
        }
        String key() { return (gpu?"Vulkan":"CPU")+" · "+threads+"T / "+batchThreads+"T · µ"+microBatch; }
        JSONObject json() throws Exception {
            return new JSONObject().put("gpu",gpu).put("threads",threads).put("batch_threads",batchThreads).put("micro_batch",microBatch);
        }
    }
    static Profile current(BonsaiApp a) { return new Profile(a.gpu(),a.threads(),a.batchThreads(),a.microBatch()); }
    static void configure(long handle, Profile p) { NativeEngine.configure(handle,p.threads,p.batchThreads,p.microBatch); }
    static String load(BonsaiApp a,long handle,Profile p) throws Exception {
        NativeEngine.referenceCpu(!p.gpu && a.cpuCompatibility);
        JSONObject r=new JSONObject(new String(NativeEngine.load(handle,BonsaiApp.utf8(a.modelFile().getAbsolutePath()),a.context(),p.threads,p.gpu),StandardCharsets.UTF_8));
        configure(handle,p);
        JSONObject metadata=r.optJSONObject("metadata");
        if(metadata!=null)write(a,"model-metadata.json",metadata);
        return r.getString("backend")+(!p.gpu && a.cpuCompatibility ? " · kompatibel" : "");
    }
    static JSONObject probe(long handle,int n) throws Exception {
        return new JSONObject(new String(NativeEngine.probe(handle,n),StandardCharsets.UTF_8));
    }
    static List<Profile> profiles(Profile original,boolean gpu) {
        Map<String,Profile> unique=new LinkedHashMap<>();
        Profile baseline=original.gpu==gpu?original:new Profile(gpu,4,4,gpu?1:32);
        unique.put(baseline.key(),baseline);
        if(gpu) {
            for(int u:new int[]{1,8,32,128}) { Profile p=new Profile(true,4,4,u); unique.put(p.key(),p); }
        } else {
            for(int t:new int[]{2,4,6,8}) { Profile p=new Profile(false,t,t,32); unique.put(p.key(),p); }
            for(int t:new int[]{4,8}) { Profile p=new Profile(false,t,8,128); unique.put(p.key(),p); }
        }
        return new ArrayList<>(unique.values());
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Durable crash marker, written only on the worker.
    static void run(BonsaiApp a,long handle,boolean compare) {
        Profile original=current(a), winner=original;
        int initialThermal=a.thermalStatus();
        String issue="", actualBackend="";
        boolean ready=false, committed=false;
        JSONObject report=new JSONObject(); JSONArray trials=new JSONArray();
        double best=Double.POSITIVE_INFINITY, originalScore=0;
        String winnerFingerprint="";
        a.prefs.edit().putBoolean("optimizerRunning",true).commit();
        try {
            if(initialThermal>=2) throw new IllegalStateException("Das Gerät ist bereits warm. Bitte abkühlen lassen und erneut optimieren.");
            report.put("schema",1).put("app_version",BuildConfig.VERSION_NAME).put("runtime",BuildConfig.RUNTIME_REVISION)
                .put("device",Build.MANUFACTURER+" "+Build.MODEL).put("android_api",Build.VERSION.SDK_INT)
                .put("model",a.modelName()).put("model_bytes",a.modelFile().length()).put("model_modified",a.modelFile().lastModified())
                .put("context",a.context()).put("thermal_before",initialThermal).put("timestamp",java.time.Instant.now().toString())
                .put("objective","192 prompt tokens + estimated 128 decode tokens; fixed token replay, 2 measured runs after warm-up")
                .put("precision","Original model weights; F16 KV; flash attention disabled").put("trials",trials);
            List<Profile> all=profiles(original,original.gpu);
            if(compare && BuildConfig.VULKAN_BUILD) all.addAll(profiles(original,!original.gpu));
            boolean loadedBackend=original.gpu, haveContext=a.loaded;
            String reference=""; Boolean referenceGpu=null;
            for(int i=0;i<all.size();i++) {
                if(a.cancelled) throw new IllegalStateException("Abgebrochen.");
                int thermal=a.thermalStatus();
                if(thermal>=2 && thermal>initialThermal) throw new IllegalStateException("Optimierung pausiert: Das Gerät wird zu warm. Das bisherige Profil bleibt erhalten.");
                Profile p=all.get(i);
                final String label="Profil "+(i+1)+" / "+all.size()+" · "+p.key();
                final int completed=i, total=all.size();
                a.main.post(()->{ a.optimizingProgress=label; a.optimizingFraction=completed/(float)total; a.status=label; a.notifyUi(); });
                JSONObject trial=p.json().put("label",p.key());
                trials.put(trial);
                try {
                    if(!haveContext || loadedBackend!=p.gpu) {
                        haveContext=false;
                        actualBackend=load(a,handle,p); loadedBackend=p.gpu; haveContext=true;
                    } else configure(handle,p);
                    probe(handle,8);
                    JSONObject one=probe(handle,32), two=probe(handle,32);
                    JSONObject cpuReference=BackendGuard.reference(a);
                    if(cpuReference==null || !BackendGuard.matches(cpuReference,one) || !BackendGuard.matches(cpuReference,two))
                        throw new IllegalStateException("Kernel weicht von der CPU-Referenz ab.");
                    if(!one.getString("fingerprint").equals(two.getString("fingerprint")))
                        throw new IllegalStateException("Kernel-Ergebnisse waren nicht reproduzierbar.");
                    if(referenceGpu==null || referenceGpu!=p.gpu) { reference=one.getString("fingerprint"); referenceGpu=p.gpu; }
                    if(!reference.equals(one.getString("fingerprint")))
                        throw new IllegalStateException("Kernel-Ergebnis weicht vom Referenzprofil dieses Backends ab.");
                    double score=(one.getDouble("score_ms")+two.getDouble("score_ms"))/2;
                    if(Math.abs(one.getDouble("score_ms")-two.getDouble("score_ms"))>score*.2)
                        throw new IllegalStateException("Messwerte schwanken um mehr als 20 Prozent.");
                    trial.put("runs",new JSONArray().put(one).put(two)).put("score_ms",score).put("valid",true)
                        .put("tokens_per_second",(one.getDouble("tokens_per_second")+two.getDouble("tokens_per_second"))/2)
                        .put("thermal",a.thermalStatus());
                    one.remove("signature");one.remove("top_tokens");two.remove("signature");two.remove("top_tokens");
                    if(i==0) originalScore=score;
                    // Keep a known profile when a tiny timing difference could be noise.
                    if(score<best*.97) { best=score; winner=p; winnerFingerprint=one.getString("fingerprint"); }
                } catch(Exception e) {
                    if(a.cancelled) throw e;
                    trial.put("valid",false).put("error",e.getMessage());
                    haveContext=false;
                    NativeEngine.resetStop(handle);
                }
            }
            if(!Double.isFinite(best)) throw new IllegalStateException("Kein Profil hat die Kernel-Prüfung bestanden.");
            if(a.cancelled) throw new IllegalStateException("Abgebrochen.");
            if(a.thermalStatus()>=2 && a.thermalStatus()>initialThermal)
                throw new IllegalStateException("Gerät zu warm für ein verlässliches Ergebnis. Bisheriges Profil bleibt erhalten.");
            actualBackend=load(a,handle,winner);
            JSONObject confirmation=probe(handle,32);
            if(!BackendGuard.matches(BackendGuard.reference(a),confirmation) || !winnerFingerprint.equals(confirmation.getString("fingerprint")))
                throw new IllegalStateException("Die Abschlussmessung liefert abweichende Rechenergebnisse.");
            if(confirmation.getDouble("score_ms")>best*1.25)
                throw new IllegalStateException("Die Abschlussmessung schwankt zu stark. Bisheriges Profil bleibt erhalten.");
            if(a.cancelled) throw new IllegalStateException("Abgebrochen.");
            if(a.thermalStatus()>=2 && a.thermalStatus()>initialThermal)
                throw new IllegalStateException("Gerät in der Abschlussmessung zu warm. Bisheriges Profil bleibt erhalten.");
            confirmation.remove("signature");confirmation.remove("top_tokens");
            report.put("thermal_after",a.thermalStatus()).put("winner",winner.json()).put("winner_label",winner.key()).put("confirmation",confirmation)
                .put("score_ms",best).put("baseline_score_ms",originalScore)
                .put("estimated_gain_percent",originalScore>0?(1-best/originalScore)*100:0).put("completed",true);
            if(a.cancelled) throw new IllegalStateException("Abgebrochen.");
            write(a,"optimizer.json",report);
            a.prefs.edit().putBoolean("gpu",winner.gpu).putInt("threads",winner.threads)
                .putInt("batchThreads",winner.batchThreads).putInt("microBatch",winner.microBatch)
                .putString("optimizedFor",a.profileIdentity(winner.gpu,winner.threads,winner.batchThreads,winner.microBatch)).apply();
            committed=true; ready=true;
        } catch(Exception | LinkageError e) {
            issue=e.getMessage()==null?e.toString():e.getMessage();
            try {
                NativeEngine.resetStop(handle);
                actualBackend=load(a,handle,original); ready=true;
            } catch(Exception | LinkageError restore) { issue+="\nBisheriges Modell bitte erneut laden: "+restore.getMessage(); }
            try { report.put("completed",false).put("error",issue); write(a,"optimizer-attempt.json",report); } catch(Exception ignored) { }
        } finally {
            a.prefs.edit().putBoolean("optimizerRunning",false).commit();
            boolean loaded=ready, applied=committed; String error=issue, backend=actualBackend;
            a.main.post(()->{
                a.loaded=loaded;
                if(loaded) { a.backend=backend; if(applied)a.backendNotice=""; }
                a.optimizing=false; a.optimizingFraction=applied?1:0;
                a.optimizingProgress=applied?"Profil gespeichert":"";
                a.optimizerReport=applied?report:a.readReport("optimizer.json");
                a.finishOptimization(error);
            });
        }
    }
    static void write(BonsaiApp a,String filename,JSONObject r) throws Exception {
        File part=new File(a.getFilesDir(),filename+".part");
        try(FileOutputStream out=new FileOutputStream(part)) { out.write(BonsaiApp.utf8(r.toString(2))); out.getFD().sync(); }
        Files.move(part.toPath(),new File(a.getFilesDir(),filename).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
