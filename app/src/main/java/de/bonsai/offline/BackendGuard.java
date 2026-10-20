package de.bonsai.offline;

import org.json.JSONArray;
import org.json.JSONObject;

/** Differential checks against scalar ternary dots and unfused, dense Hadamard rotations. */
final class BackendGuard {
    static String identity(BonsaiApp a) {
        return a.modelFile().length()+":"+a.modelFile().lastModified()+":"+a.context()+":"+BuildConfig.VERSION_CODE+":"+BuildConfig.RUNTIME_REVISION+":"+android.os.Build.FINGERPRINT+":dense-hadamard-v1";
    }
    static boolean matches(JSONObject reference,JSONObject candidate) throws Exception {
        JSONArray expected=reference.getJSONArray("signature"), actual=candidate.getJSONArray("signature");
        JSONArray expectedTop=reference.getJSONArray("top_tokens"), actualTop=candidate.getJSONArray("top_tokens");
        if(expected.length()==0 || expectedTop.length()==0 || actual.length()<expected.length() || actualTop.length()<expectedTop.length())return false;
        double squaredError=0, squaredReference=0;int sameTop=0;
        for(int i=0;i<expected.length();i++) {
            double x=expected.getDouble(i),y=actual.getDouble(i);
            if(!Double.isFinite(x)||!Double.isFinite(y))return false;
            squaredError+=(x-y)*(x-y);squaredReference+=x*x;
        }
        for(int i=0;i<expectedTop.length();i++)if(expectedTop.getInt(i)==actualTop.getInt(i))sameTop++;
        return Math.sqrt(squaredError/Math.max(squaredReference,1e-9))<=.025 && sameTop>=Math.ceil(expectedTop.length()*.8);
    }
    static JSONObject reference(BonsaiApp a) {
        JSONObject r=a.cpuReference;
        if(r==null)r=a.readReport("cpu-reference.json");
        if(r!=null && identity(a).equals(r.optString("identity"))) {a.cpuReference=r;return r;}
        return null;
    }
    static String load(BonsaiApp a,long handle) throws Exception {
        KernelOptimizer.Profile requested=KernelOptimizer.current(a);
        JSONObject reference=reference(a);
        String backend="CPU";
        a.cpuCompatibility=true;
        backend=KernelOptimizer.load(a,handle,new KernelOptimizer.Profile(false,requested.threads,requested.batchThreads,1));
        if(reference==null) {
            a.main.post(()->{a.status="Unabhängige CPU-Referenz wird geprüft …";a.notifyUi();});
            reference=KernelOptimizer.probe(handle,8);
            reference.put("identity",identity(a));
            KernelOptimizer.write(a,"cpu-reference.json",reference);a.cpuReference=reference;
        }
        String notice="";
        try {
            if(a.cancelled)throw new IllegalStateException("Abgebrochen.");
            if(a.prefs.getBoolean("cpuCompatibility",false) && !requested.gpu) {
                KernelOptimizer.configure(handle,requested);
                a.main.post(()->a.backendNotice="CPU-Kompatibilitätsmodus: skalare Bonsai-Kernel sind aktiv.");
                return backend;
            }
            a.cpuCompatibility=false;
            NativeEngine.referenceCpu(false);
            if(requested.gpu) backend=KernelOptimizer.load(a,handle,requested);
            else { KernelOptimizer.configure(handle,requested); backend="CPU"; }
            JSONObject candidate=KernelOptimizer.probe(handle,8);
            if(!matches(reference,candidate))throw new IllegalStateException("Testlogits weichen von der CPU-Referenz ab.");
        } catch(Exception e) {
            if(a.cancelled)throw e;
            notice=(requested.gpu?"Vulkan":"Der optimierte CPU-Pfad")+" hat die unabhängige Rechenprüfung nicht bestanden. Skalare Bonsai-Kernel aktiviert. Ursache: "+e.getMessage();
            NativeEngine.resetStop(handle);
            a.cpuCompatibility=true;
            backend=KernelOptimizer.load(a,handle,new KernelOptimizer.Profile(false,requested.threads,requested.batchThreads,1));
            if(!matches(reference,KernelOptimizer.probe(handle,8)))
                throw new IllegalStateException("Auch die CPU-Referenz ist nicht reproduzierbar. Modell bitte neu importieren.");
            a.prefs.edit().putBoolean("gpu",false).putBoolean("cpuCompatibility",true).putInt("microBatch",1).remove("optimizedFor").apply();
        }
        String note=notice;
        a.main.post(()->{a.backendNotice=note;});
        return backend;
    }
}
