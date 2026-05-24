package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }

    private Universe() {}

    private static native void _init();

    public static void touch() {
    }

    public static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        proxyerror(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public native String error();

        @Override
        public String getMessage() {
            return error();
        }

        @Override
        public final int incRefnum() {
            Seq.incGoRef(refnum, this);
            return refnum;
        }
    }
}
