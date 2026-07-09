package com.daicg.hearingaid;

final class AiNoiseSuppressor implements AutoCloseable {
    private static final boolean AVAILABLE;
    private long handle;

    static {
        boolean loaded;
        try {
            System.loadLibrary("hearingaid_native");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private AiNoiseSuppressor(long handle) {
        this.handle = handle;
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static int frameSize() {
        if (!AVAILABLE) {
            return 480;
        }
        return nativeFrameSize();
    }

    static AiNoiseSuppressor create() {
        if (!AVAILABLE) {
            return null;
        }
        long handle = nativeCreate();
        if (handle == 0L) {
            return null;
        }
        return new AiNoiseSuppressor(handle);
    }

    int processInPlace(short[] buffer, int length) {
        if (handle == 0L) {
            return 0;
        }
        return nativeProcessInPlace(handle, buffer, length);
    }

    @Override
    public void close() {
        long oldHandle = handle;
        handle = 0L;
        if (oldHandle != 0L && AVAILABLE) {
            nativeDestroy(oldHandle);
        }
    }

    private static native long nativeCreate();

    private static native void nativeDestroy(long handle);

    private static native int nativeFrameSize();

    private static native int nativeProcessInPlace(long handle, short[] samples, int length);
}
