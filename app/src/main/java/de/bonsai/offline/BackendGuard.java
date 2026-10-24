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
        if(a.cancelled)throw new IllegalStateException("Abgebrochen.");
        KernelOptimizer.Profile requested=KernelOptimizer.current(a);
        a.cpuCompatibility=a.prefs.getBoolean("cpuCompatibility",false) && !requested.gpu;
        // Normal startup loads the selected backend once, without inference probes.
        String backend=KernelOptimizer.load(a,handle,requested);
        String notice=a.cpuCompatibility?"CPU-Kompatibilitätsmodus: skalare Bonsai-Kernel sind aktiv.":"";
        a.main.post(()->a.backendNotice=notice);
        return backend;
    }
    /** Returns true when a temporary scalar context was loaded; the caller must reload its profile. */
    static boolean prepareReference(BonsaiApp a,long handle) throws Exception {
        if(a.cancelled)throw new IllegalStateException("Abgebrochen.");
        if(reference(a)!=null)return false;
        KernelOptimizer.Profile requested=KernelOptimizer.current(a);
        boolean compatibility=a.cpuCompatibility;
        try {
            a.cpuCompatibility=true;
            KernelOptimizer.load(a,handle,new KernelOptimizer.Profile(false,requested.threads,requested.batchThreads,1));
            a.main.post(()->{a.status="Unabhängige CPU-Referenz wird geprüft …";a.notifyUi();});
            JSONObject reference=KernelOptimizer.probe(handle,8);
            if(a.cancelled)throw new IllegalStateException("Abgebrochen.");
            reference.put("identity",identity(a));
            KernelOptimizer.write(a,"cpu-reference.json",reference);a.cpuReference=reference;
            return true;
        } finally { a.cpuCompatibility=compatibility; }
    }
}
