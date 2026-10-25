package de.bonsai.offline;

/** Run just the strict three-turn regression against real JNI, without the long budget test. */
public final class ThreeTurnCacheSmoke {
    public static void main(String[] args) throws Exception {
        long handle=NativeEngine.create();
        try {
            NativeEngine.load(handle,CacheSmoke.bytes(args[0]),1024,2,false);
            CacheSmoke.incremental(handle);
            System.out.println("Three uninterrupted JNI turns passed; valid prefix reused, no completion decode or reasoning in KV/history.");
        } finally {NativeEngine.unload(handle);}
    }
}
