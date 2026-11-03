package rexxjvm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.ValType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A run's foreign entries: Java threads that are not wasm threads running code of the
 * module (a call of the embedded mode, a native method called on any Java thread).
 *
 * <p>The module's dispatcher thread ({@code compat/orx_wasi_embed.cpp}) waits in
 * {@code embed_next} for a request, and creates a pthread for it; thread-spawn hands
 * that pthread to the Java thread that asked ({@link #handover}), which runs it itself
 * ({@link Run#runOn}); its start routine calls {@code embed_run}, which runs the request's
 * work on that thread. A request with no caller (a home thread of the embedded mode) runs
 * on a Java thread of its own instead.
 */
final class Entries {

    interface Work<T> { T run(Instance inst) throws Exception; }

    static final class Request {
        static final ThreadLocal<Request> running = new ThreadLocal<>();
        static Request current() { return running.get(); }

        final int id = nextId.getAndIncrement();
        /** The Java thread that runs it; null: a new one (a home thread). */
        final Thread caller;
        final Work<?> work;
        final SynchronousQueue<int[]> handover = new SynchronousQueue<>();
        final CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1),
                             done = new CountDownLatch(1);
        Rexx.Interpreter interpreter;
        Object result;
        volatile Throwable failure;

        Request(Thread caller, Work<?> work) { this.caller = caller; this.work = work; }
    }

    private static final AtomicInteger nextId = new AtomicInteger(1);
    /** Set on a dispatcher's thread between embed_next and the thread-spawn it causes. */
    private static final ThreadLocal<Request> spawning = new ThreadLocal<>();

    private final Run run;
    private final BlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
    private final Map<Integer, Request> pending = new ConcurrentHashMap<>();

    Entries(Run run) { this.run = run; }

    void submit(Request r) {
        pending.put(r.id, r);
        requests.add(r.id);
    }

    Request pending(int id) { return pending.get(id); }

    /** The dispatcher ends (it gets -1). */
    void close() { requests.add(-1); }

    /**
     * Runs work inside the module on the calling Java thread, which is not a wasm
     * thread: a foreign entry through the dispatcher, which must be running (the
     * embedded mode, or orxWasiStartDispatcher beside an ordinary program).
     */
    @SuppressWarnings("unchecked")
    <T> T foreign(Work<T> work) {
        if (run.ended()) throw new IllegalStateException("the run has ended");
        Request r = new Request(Thread.currentThread(), work);
        submit(r);
        int[] ts;
        try {
            ts = r.handover.take();           // (tid, startArg), from thread-spawn
        } catch (InterruptedException x) {
            pending.remove(r.id);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while entering the module", x);
        }
        try {
            if (ts[0] >= 0) run.runOn("wasi_thread_start", ts[0], ts[1]);
        } finally {
            pending.remove(r.id);
        }
        if (r.failure instanceof RuntimeException) throw (RuntimeException) r.failure;
        if (r.failure instanceof Error) throw (Error) r.failure;
        if (r.failure != null) throw new IllegalStateException(r.failure);
        return (T) r.result;
    }

    /** thread-spawn: is this pthread for a foreign entry? Then hand it to its caller. */
    boolean handover(int tid, int startArg) {
        Request r = spawning.get();
        if (r == null) return false;
        spawning.remove();
        if (r.caller == null) return false;   // a home thread: a Java thread of its own
        try {
            r.handover.put(new int[] { tid, startArg });
        } catch (InterruptedException x) {
            throw new IllegalStateException(x);
        }
        return true;
    }

    // ---- the imports (module "rexxjvm") ----

    List<HostFunction> functions() {
        List<ValType> none = List.of(), i32 = List.of(ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "embed_next", none, i32, (inst, a) -> {
                int id;
                try { id = requests.take(); }
                catch (InterruptedException x) { return new long[] { -1 }; }
                if (id > 0) spawning.set(pending.get(id));
                return new long[] { id };
            }),
            new HostFunction("rexxjvm", "embed_run", i32, none, (inst, a) -> {
                Request r = pending.get((int) a[0]);
                if (r == null) return null;
                Request outer = Request.running.get();
                Request.running.set(r);
                try {
                    if (r.caller != null && r.caller != Thread.currentThread()) {
                        // the dispatcher could not create a pthread: tell the caller
                        spawning.remove();
                        r.failure = new IllegalStateException("the module could not create a thread");
                        r.handover.offer(new int[] { -1, -1 }, 10, TimeUnit.SECONDS);
                        return null;
                    }
                    r.result = r.work.run(inst);
                } catch (Throwable x) {
                    r.failure = x;
                } finally {
                    Request.running.set(outer);
                    if (r.caller == null) {       // a home thread ends
                        pending.remove(r.id);
                        r.ready.countDown();
                        r.done.countDown();
                    }
                }
                return null;
            }));
    }
}
