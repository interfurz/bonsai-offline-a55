package de.bonsai.offline;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real host JNI capability check; timings are deliberately not reported as device results. */
public final class PerformanceOptionsSmoke {
    static JSONObject probe(long handle) throws Exception {
        return new JSONObject(new String(NativeEngine.probe(handle,8),StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        long h=NativeEngine.create();
        try {
            NativeEngine.load(h,args[0].getBytes(StandardCharsets.UTF_8),1024,2,false);
            try {
                NativeEngine.configureAdvanced(h,2,2,32,false,true);
                throw new AssertionError("Q8 V without Flash Attention accepted");
            } catch(IllegalStateException expected) { }
            for(boolean q8:new boolean[]{false,true}) {
                NativeEngine.configureAdvanced(h,2,2,32,true,q8);
                JSONObject result=probe(h);
                if(!result.getBoolean("flash_attention_requested") || !result.getString("kv_cache_type").equals(q8?"q8_0":"f16"))throw new AssertionError(result);
                if(result.getJSONArray("signature").length()==0)throw new AssertionError("Missing probe logits");
            }
            NativeEngine.configure(h,2,2,32);
            JSONObject restored=probe(h);
            if(restored.getBoolean("flash_attention_requested") || !restored.getString("kv_cache_type").equals("f16"))throw new AssertionError("Defaults not restored");
            System.out.println("Host JNI: FA/F16 and FA/Q8 contexts and probes succeed; Q8 without FA rejected; defaults restored. No A55 measurement.");
        } finally {NativeEngine.unload(h);}
    }
}
