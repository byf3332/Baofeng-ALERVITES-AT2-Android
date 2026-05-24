package go;

import android.content.Context;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.logging.Logger;

public final class Seq {
    private static final int NULL_REFNUM = 0x29;
    private static Logger log = Logger.getLogger("GoSeq");
    public static final Ref nullRef = new Ref(NULL_REFNUM, null);
    private static final GoRefQueue goRefQueue = new GoRefQueue();
    static final RefTracker tracker;

    static {
        System.loadLibrary("gojni");
        init();
        Universe.touch();
        tracker = new RefTracker();
    }

    private Seq() {}

    static Logger access$000() {
        return log;
    }

    public static native void destroyRef(int refnum);

    public static Ref getRef(int refnum) {
        return tracker.get(refnum);
    }

    public static int incGoObjectRef(GoObject obj) {
        return obj.incRefnum();
    }

    public static native void incGoRef(int refnum, GoObject obj);

    public static int incRef(Object obj) {
        return tracker.inc(obj);
    }

    public static void incRefnum(int refnum) {
        tracker.incRefnum(refnum);
    }

    private static native void init();

    static native void setContext(Object context);

    public static void setContext(Context context) {
        setContext((Object) context);
    }

    public static void touch() {
    }

    public static void trackGoRef(int refnum, GoObject obj) {
        if (refnum <= 0) {
            goRefQueue.track(refnum, obj);
            return;
        }
        throw new RuntimeException("trackGoRef called with Java refnum " + refnum);
    }

    static void decRef(int refnum) {
        tracker.dec(refnum);
    }

    public interface GoObject {
        int incRefnum();
    }

    public interface Proxy extends GoObject {
    }

    public static final class Ref {
        public final int refnum;
        public final Object obj;
        private int refcnt;

        Ref(int refnum, Object obj) {
            if (refnum < 0) {
                throw new RuntimeException("Ref instantiated with a Go refnum " + refnum);
            }
            this.refnum = refnum;
            this.refcnt = 0;
            this.obj = obj;
        }

        static int access$100(Ref ref) {
            return ref.refcnt;
        }

        static int access$110(Ref ref) {
            int current = ref.refcnt;
            ref.refcnt = current - 1;
            return current;
        }

        void inc() {
            if (refcnt == Integer.MAX_VALUE) {
                throw new RuntimeException("refnum " + refnum + " overflow");
            }
            refcnt += 1;
        }
    }

    static final class RefTracker {
        private static final int REF_OFFSET = 0x2a;
        private int next = REF_OFFSET;
        private final RefMap javaObjs = new RefMap();
        private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

        synchronized int inc(Object obj) {
            if (obj == null) {
                return NULL_REFNUM;
            }
            if (obj instanceof Proxy) {
                return ((Proxy) obj).incRefnum();
            }
            Integer refnum = javaRefs.get(obj);
            if (refnum == null) {
                int n = next;
                if (n == Integer.MAX_VALUE) {
                    throw new RuntimeException("createRef overflow for " + obj);
                }
                next = n + 1;
                refnum = Integer.valueOf(n);
                javaRefs.put(obj, refnum);
            }
            int n = refnum.intValue();
            Ref ref = javaObjs.get(n);
            if (ref == null) {
                ref = new Ref(n, obj);
                javaObjs.put(n, ref);
            }
            ref.inc();
            return n;
        }

        synchronized void incRefnum(int refnum) {
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            ref.inc();
        }

        synchronized void dec(int refnum) {
            if (refnum <= 0) {
                access$000().severe("dec request for Go object " + refnum);
                return;
            }
            if (refnum == nullRef.refnum) {
                return;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            Ref.access$110(ref);
            if (Ref.access$100(ref) <= 0) {
                javaObjs.remove(refnum);
                javaRefs.remove(ref.obj);
            }
        }

        synchronized Ref get(int refnum) {
            if (refnum < 0) {
                throw new RuntimeException("ref called with Go refnum " + refnum);
            }
            if (refnum == NULL_REFNUM) {
                return nullRef;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("unknown java Ref: " + refnum);
            }
            return ref;
        }
    }

    static final class RefMap {
        private int next;
        private int live;
        private int[] keys;
        private Ref[] objs;

        RefMap() {
            this.next = 0;
            this.live = 0;
            this.keys = new int[16];
            this.objs = new Ref[16];
        }

        Ref get(int key) {
            int idx = Arrays.binarySearch(keys, 0, next, key);
            return idx >= 0 ? objs[idx] : null;
        }

        void remove(int key) {
            int idx = Arrays.binarySearch(keys, 0, next, key);
            if (idx >= 0 && objs[idx] != null) {
                objs[idx] = null;
                live -= 1;
            }
        }

        void put(int key, Ref ref) {
            if (ref == null) {
                throw new RuntimeException("put a null ref (with key " + key + ")");
            }
            int idx = Arrays.binarySearch(keys, 0, next, key);
            if (idx >= 0) {
                if (objs[idx] == null) {
                    objs[idx] = ref;
                    live += 1;
                }
                if (objs[idx] == ref) {
                    return;
                }
                throw new RuntimeException("replacing an existing ref (with key " + key + ")");
            }
            if (next >= keys.length) {
                grow();
                idx = Arrays.binarySearch(keys, 0, next, key);
            }
            idx = ~idx;
            if (idx < next) {
                System.arraycopy(keys, idx, keys, idx + 1, next - idx);
                System.arraycopy(objs, idx, objs, idx + 1, next - idx);
            }
            keys[idx] = key;
            objs[idx] = ref;
            live += 1;
            next += 1;
        }

        private void grow() {
            int newSize = roundPow2(live) * 2;
            int[] newKeys;
            Ref[] newObjs;
            if (newSize > keys.length) {
                newKeys = new int[keys.length * 2];
                newObjs = new Ref[objs.length * 2];
            } else {
                newKeys = keys;
                newObjs = objs;
            }
            int write = 0;
            for (int i = 0; i < keys.length; i++) {
                Ref ref = objs[i];
                if (ref != null) {
                    newKeys[write] = keys[i];
                    newObjs[write] = ref;
                    write += 1;
                }
            }
            for (int i = write; i < newKeys.length; i++) {
                newKeys[i] = 0;
                newObjs[i] = null;
            }
            keys = newKeys;
            objs = newObjs;
            next = write;
            if (live != write) {
                throw new RuntimeException("bad state: live=" + live + ", next=" + next);
            }
        }

        private static int roundPow2(int n) {
            int p = 1;
            while (p < n) {
                p *= 2;
            }
            return p;
        }
    }

    static final class GoRefQueue extends ReferenceQueue<GoObject> {
        private final Collection<GoRef> refs = Collections.synchronizedCollection(new HashSet<GoRef>());

        GoRefQueue() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            GoRef ref = (GoRef) GoRefQueue.this.remove();
                            refs.remove(ref);
                            Seq.destroyRef(ref.refnum);
                            ref.clear();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.setName("GoRefQueue Finalizer Thread");
            thread.start();
        }

        static Collection<GoRef> access$200(GoRefQueue queue) {
            return queue.refs;
        }

        void track(int refnum, GoObject obj) {
            refs.add(new GoRef(refnum, obj, this));
        }
    }

    static final class GoRef extends PhantomReference<GoObject> {
        final int refnum;

        GoRef(int refnum, GoObject obj, GoRefQueue queue) {
            super(obj, queue);
            if (refnum > 0) {
                throw new RuntimeException("GoRef instantiated with a Java refnum " + refnum);
            }
            this.refnum = refnum;
        }
    }
}
