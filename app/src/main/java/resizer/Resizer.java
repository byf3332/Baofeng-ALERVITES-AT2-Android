package resizer;

import go.Seq;

public abstract class Resizer {
    static {
        Seq.touch();
        _init();
    }

    private Resizer() {}

    private static native void _init();

    public static native byte[] resizeImage(byte[] imageBytes, long maxSize, long quality) throws Exception;

    public static void touch() {
    }
}
