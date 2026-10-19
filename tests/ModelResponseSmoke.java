package de.bonsai.offline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Real GGUF regression using the app's sampler and the reported A55 CPU settings. */
public final class ModelResponseSmoke {
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) {
        if(args.length!=2 || !(args[1].equals("scalar") || args[1].equals("optimized")))
            throw new IllegalArgumentException("Pass model.gguf scalar|optimized");
        boolean scalar=args[1].equals("scalar");
        long handle=NativeEngine.create();
        try {
            NativeEngine.referenceCpu(scalar);
            System.out.println(new String(NativeEngine.load(handle,utf8(args[0]),4096,4,false),StandardCharsets.UTF_8));
            NativeEngine.configure(handle,4,4,scalar?1:32);
            String[] questions={"1+1","Was ist 2 + 2? Antworte nur mit der Zahl."};
            String[] expected={"2","4"};
            for(int i=0;i<questions.length;i++) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                String stats=new String(NativeEngine.generate(handle,
                    new byte[][]{utf8("system"),utf8("user")},
                    new byte[][]{utf8("Du bist ein hilfreicher Assistent. Antworte auf Deutsch, sofern die Person keine andere Sprache wünscht."),utf8(questions[i])},
                    32,42,chunk->out.write(chunk,0,chunk.length)),StandardCharsets.UTF_8);
                String answer=new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
                System.out.println(questions[i]+" -> "+answer+"; "+stats);
                boolean correct=i==0 ? answer.startsWith("2") || answer.startsWith("1+1 = 2") || answer.startsWith("1 + 1 = 2") : answer.equals(expected[i]);
                if(!correct) throw new AssertionError("Unexpected arithmetic response: "+answer);
            }
            System.out.println("Real GGUF responses passed: "+args[0]+" ("+args[1]+", 4 threads, context 4096)");
        } finally { NativeEngine.unload(handle); }
    }
}
