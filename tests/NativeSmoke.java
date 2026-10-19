package de.bonsai.offline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Real-model JNI smoke test. Host results are not Galaxy A55 benchmarks. */
public final class NativeSmoke {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String value(String json,String key) {
        String marker="\""+key+"\":\"";int start=json.indexOf(marker)+marker.length();return json.substring(start,json.indexOf('"',start));
    }
    public static void main(String[] args) {
        if(args.length!=1) throw new IllegalArgumentException("Pass the path to the PQ2_0 GGUF");
        long handle=NativeEngine.create();
        try {
            try {
                NativeEngine.load(handle,bytes("/nonexistent/bonsai.gguf"),2048,2,false);
                throw new AssertionError("Invalid model must fail");
            } catch(IllegalStateException expected) { System.out.println("Missing model rejected"); }
            NativeEngine.resetStop(handle);
            System.out.println(new String(NativeEngine.load(handle,bytes(args[0]),2048,2,false),StandardCharsets.UTF_8));
            try {
                NativeEngine.configure(handle,2,2,3);
                throw new AssertionError("Non-power-of-two microbatch must fail");
            } catch(IllegalStateException expected) { System.out.println("Invalid optimizer profile rejected"); }
            NativeEngine.configure(handle,2,2,32);
            String probeA=new String(NativeEngine.probe(handle,4),StandardCharsets.UTF_8);
            NativeEngine.configure(handle,4,4,128);
            String probeB=new String(NativeEngine.probe(handle,4),StandardCharsets.UTF_8);
            String fingerprintA=value(probeA,"fingerprint"), fingerprintB=value(probeB,"fingerprint");
            if(!fingerprintA.equals(fingerprintB)) throw new AssertionError("Kernel profiles changed replay results: "+probeA+" vs "+probeB);
            if(!probeB.contains("\"decode_tokens\":4")) throw new AssertionError("Incomplete kernel replay");
            System.out.println("Real kernel replay/profile invariance passed: "+fingerprintB);
            NativeEngine.stop(handle);
            try { NativeEngine.probe(handle,4); throw new AssertionError("Stopped probe must fail"); }
            catch(IllegalStateException expected) { System.out.println("Optimizer cancellation passed"); }
            NativeEngine.resetStop(handle);
            NativeEngine.configure(handle,2,2,32);
            byte[][] roles={bytes("user")};
            byte[][] texts={bytes("Was ist 2 + 2? Antworte nur mit der Zahl.")};
            NativeEngine.stop(handle);
            String cancelled=new String(NativeEngine.generate(handle,roles,texts,32,42,c->{}),StandardCharsets.UTF_8);
            if(!cancelled.contains("\"stopped\":true")) throw new AssertionError("Cancellation not reported");
            System.out.println("Cancellation passed");
            NativeEngine.resetStop(handle);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            String stats=new String(NativeEngine.generate(handle,roles,texts,32,42,c->output.write(c,0,c.length)),StandardCharsets.UTF_8);
            String answer=new String(output.toByteArray(),StandardCharsets.UTF_8);
            System.out.println("Answer: "+answer);
            System.out.println("Stats: "+stats);
            if(!answer.trim().equals("4")) throw new AssertionError("Unexpected math response: "+answer);
            if(answer.contains("\ufffd")) throw new AssertionError("Broken UTF-8");
            NativeEngine.resetStop(handle);
            ByteArrayOutputStream follow=new ByteArrayOutputStream();
            byte[][] followRoles={bytes("user"),bytes("assistant"),bytes("user")};
            byte[][] followTexts={texts[0],bytes(answer),bytes("Addiere 3 dazu. Antworte nur mit der Zahl.")};
            NativeEngine.generate(handle,followRoles,followTexts,32,42,c->follow.write(c,0,c.length));
            String second=new String(follow.toByteArray(),StandardCharsets.UTF_8);
            System.out.println("Follow-up: "+second);
            if(!second.trim().equals("7")) throw new AssertionError("Unexpected follow-up: "+second);
            NativeEngine.referenceCpu(true);
            NativeEngine.configure(handle,2,2,1);
            String scalarProbe=new String(NativeEngine.probe(handle,4),StandardCharsets.UTF_8);
            if(!fingerprintA.equals(value(scalarProbe,"fingerprint"))) throw new AssertionError("Scalar reference differs: "+scalarProbe);
            ByteArrayOutputStream scalar=new ByteArrayOutputStream();
            NativeEngine.generate(handle,roles,texts,32,42,c->scalar.write(c,0,c.length));
            String scalarAnswer=new String(scalar.toByteArray(),StandardCharsets.UTF_8);
            if(!scalarAnswer.trim().equals("4")) throw new AssertionError("Scalar math failed: "+scalarAnswer);
            System.out.println("Scalar compatibility mode: math 4, probe fingerprint "+value(scalarProbe,"fingerprint"));
            System.out.println("Real PQ2_0 JNI smoke passed (host CPU, not A55)");
        } finally { NativeEngine.unload(handle); }
    }
}
