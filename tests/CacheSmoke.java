package de.bonsai.offline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Real JNI/KV checks on a small GGUF; this is not an A55 performance benchmark. */
public final class CacheSmoke {
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String answer;
    static JSONObject generate(long handle,List<String> roles,List<String> texts,String grammar,int budget) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        byte[][] r=roles.stream().map(CacheSmoke::bytes).toArray(byte[][]::new),t=texts.stream().map(CacheSmoke::bytes).toArray(byte[][]::new);
        JSONObject stats=new JSONObject(new String(NativeEngine.generate(handle,r,t,32,42,c->output.write(c,0,c.length),grammar==null?null:bytes(grammar),budget),StandardCharsets.UTF_8));
        answer=output.toString(StandardCharsets.UTF_8);
        if(answer.contains("think") || answer.contains("hidden"))throw new AssertionError("Reasoning leaked: "+answer);
        return stats;
    }
    static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        long handle=NativeEngine.create();
        try {
            NativeEngine.load(handle,bytes(args[0]),1024,2,false);
            List<String> roles=new ArrayList<>(List.of("system","user")),texts=new ArrayList<>(List.of("You are helpful.","Say hello."));
            JSONObject first=generate(handle,roles,texts,"root ::= \"Hello.\"",0);
            require(first.getInt("reused_prompt_tokens")==0,"First turn must be cold");
            NativeEngine.commitTurn(handle,new byte[][]{bytes("system"),bytes("user"),bytes("assistant")},
                new byte[][]{bytes(texts.get(0)),bytes(texts.get(1)),bytes("<think>hidden</think>"+answer)});
            roles.add("assistant");texts.add(answer);roles.add("user");texts.add("What is two plus two? Answer briefly.");
            JSONObject warm=generate(handle,roles,texts,null,0);String warmAnswer=answer;
            require(warm.getInt("reused_prompt_tokens")>10,"Follow-up did not reuse KV: "+warm);
            require(warm.getInt("decoded_prompt_tokens")<warm.getInt("prompt_tokens"),"Full prefill repeated");
            NativeEngine.clearCache(handle);
            JSONObject cold=generate(handle,roles,texts,null,0);
            require(cold.getInt("reused_prompt_tokens")==0,"Explicit reset did not clear KV");
            require(warmAnswer.equals(answer),"Cold and cached answers differ: "+warmAnswer+" vs "+answer);
            NativeEngine.clearCache(handle);
            JSONObject capped=generate(handle,List.of("user"),List.of("Answer."),"root ::= \"<think>\" (\" hidden\")* \"</think>OK\"",0);
            require(answer.equals("OK"),"Forced close failed: "+answer);
            require(capped.getBoolean("reasoning_capped") && capped.getInt("injected_tokens")>0,"Reasoning close was not inserted");
            NativeEngine.clearCache(handle);
            JSONObject longReasoning=generate(handle,List.of("user"),List.of("Answer."),"root ::= \"<think>\" (\" hidden\"){590,1000} \"</think>OK\"",600);
            require(answer.equals("OK"),"Long reasoning did not finish: "+answer);
            require(longReasoning.getInt("reasoning_tokens")>=590 && longReasoning.getInt("reasoning_tokens")<=605,"Reasoning exceeded budget: "+longReasoning);
            require(longReasoning.getInt("cached_tokens")<100 && longReasoning.getInt("kv_positions")==longReasoning.getInt("cached_tokens"),"Reasoning remained in KV: "+longReasoning);
            // A subsequent plain final answer must reuse the cleaned, reasoning-free context.
            JSONObject clean=generate(handle,List.of("user","assistant","user"),List.of("Answer.","OK","Continue."),"root ::= \"Done.\"",0);
            require(clean.getInt("reused_prompt_tokens")>0,"Cleaned prefix missing");
            NativeEngine.clearCache(handle);
            roles=new ArrayList<>(List.of("system"));texts=new ArrayList<>(List.of("You are helpful."));
            boolean dropped=false;
            for(int turn=0;turn<10;turn++) {
                roles.add("user");texts.add(("context "+turn+" ").repeat(30)+" Answer OK.");
                JSONObject stats=generate(handle,roles,texts,"root ::= \"OK\"",0);
                dropped|=stats.getInt("dropped_turns")>0;
                roles.add("assistant");texts.add(answer);
            }
            require(dropped,"Context overflow did not evict old turns");
            // Repeating the fitted latest turn must reuse the window even after previous evictions.
            roles.add("user");texts.add("Again.");
            JSONObject last=generate(handle,roles,texts,"root ::= \"OK\"",0);
            require(last.getInt("reused_prompt_tokens")>20,"Rolling window stopped being incremental");
            System.out.println("Real JNI KV smoke passed: warm/cold equivalence, prefix reuse, reset, forced reasoning close and rolling context.");
            System.out.println("Reasoning tokens="+longReasoning.getInt("reasoning_tokens")+", capped="+longReasoning.getBoolean("reasoning_capped")+", clean KV positions="+longReasoning.getInt("kv_positions"));
            System.out.println("Warm prompt tokens="+warm.getInt("prompt_tokens")+", reused="+warm.getInt("reused_prompt_tokens")+", decoded="+warm.getInt("decoded_prompt_tokens"));
        } finally { NativeEngine.unload(handle); }
    }
}
