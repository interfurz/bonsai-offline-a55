package de.bonsai.offline;

/** JNI takes UTF-8 bytes so emoji and NUL cannot be corrupted by Modified UTF-8. */
final class NativeEngine {
    static { System.loadLibrary("bonsai-jni"); }
    interface Stream { void onBytes(byte[] bytes); }
    static native long create();
    static native void resetStop(long handle);
    static native void stop(long handle);
    static native void unload(long handle);
    static native void referenceCpu(boolean enabled);
    static native byte[] load(long handle, byte[] path, int context, int threads, boolean gpu);
    static byte[] generate(long handle, byte[][] roles, byte[][] contents, int maxTokens, int seed, Stream callback) {
        return generate(handle,roles,contents,maxTokens,seed,callback,null);
    }
    static native byte[] generate(long handle, byte[][] roles, byte[][] contents, int maxTokens, int seed, Stream callback, byte[] grammar);
    static native void configure(long handle, int threads, int batchThreads, int microBatch);
    static native byte[] probe(long handle, int steps);
    private NativeEngine() {}
}
