package com.jvmplayground.simulator;

import java.util.*;
import java.util.concurrent.*;

/**
 * Simulates common memory-leak archetypes.
 *
 * Bug 4 fix: startThreadLocalLeak() rewritten to actually leak.
 *
 *   Old behaviour: a single thread called ThreadLocal.set() in a loop, replacing the
 *   previous value each iteration. Only one array was ever retained — no growth.
 *
 *   New behaviour: a 5-thread pool simulates a web-server thread pool. A class-level
 *   ThreadLocal<List<byte[]>> uses initialValue() so each pool thread owns its own List.
 *   Tasks submitted to the pool keep appending to that per-thread list but never call
 *   remove(). Since pool threads are long-lived (never die), the lists grow indefinitely —
 *   exactly the classic ThreadLocal leak pattern seen in application servers.
 *
 *   On stopLeak() the thread-pool is shut down (threads die → ThreadLocalMaps are
 *   collected), correctly demonstrating how the leak is resolved.
 */
public class MemoryLeakSimulator {

    // Static reference leak (LeakType.STATIC_REFERENCE)
    private static final List<Object> STATIC_LEAK = new CopyOnWriteArrayList<>();

    // Collection leak store
    private final Map<String, List<byte[]>> leakStore = new HashMap<>();

    // All background controller threads
    private final List<Thread> leakThreads = new ArrayList<>();

    // Bug 4 fix: pool is tracked so we can shut it down in stopLeak()
    private ExecutorService threadLocalLeakPool;

    private volatile boolean leaking     = false;
    private int leakRate                 = 1024;   // bytes per second per leak thread
    private String currentLeakType       = "none";

    // ─── Leak type enum ──────────────────────────────────────────────────────

    public enum LeakType {
        STATIC_REFERENCE("Static references"),
        COLLECTION_LEAK ("Unbounded collection"),
        THREAD_LOCAL    ("ThreadLocal not cleared"),
        LISTENER_LEAK   ("Listener/callback accumulation");

        private final String description;
        LeakType(String d) { this.description = d; }
        public String getDescription() { return description; }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void startLeak(LeakType leakType, int bytesPerSecond) {
        stopLeak();
        this.leaking         = true;
        this.leakRate        = bytesPerSecond;
        this.currentLeakType = leakType.name();
        System.out.println("Starting " + leakType.getDescription() + " leak at "
                           + bytesPerSecond + " bytes/sec");
        switch (leakType) {
            case STATIC_REFERENCE: startStaticLeak();     break;
            case COLLECTION_LEAK:  startCollectionLeak(); break;
            case THREAD_LOCAL:     startThreadLocalLeak();break;
            case LISTENER_LEAK:    startListenerLeak();   break;
        }
    }

    public void stopLeak() {
        leaking = false;

        // Interrupt all controller threads
        for (Thread t : leakThreads) t.interrupt();
        leakThreads.clear();

        // Bug 4 fix: shut down the thread-local pool so its threads die and
        // the ThreadLocalMaps (and their accumulated byte[] values) are collected.
        if (threadLocalLeakPool != null) {
            threadLocalLeakPool.shutdownNow();
            threadLocalLeakPool = null;
        }

        STATIC_LEAK.clear();
        leakStore.clear();
        System.gc();
        System.out.println("Memory leak simulation stopped.");
    }

    // ─── Static reference leak ───────────────────────────────────────────────

    private void startStaticLeak() {
        Thread t = new Thread(() -> {
            while (leaking && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] data = new byte[leakRate];
                    Arrays.fill(data, (byte) 0xFF);
                    STATIC_LEAK.add(data);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (OutOfMemoryError e) {
                    System.out.println("OutOfMemoryError: static leak filled heap.");
                    leaking = false;
                }
            }
        }, "Static-Leak-Simulator");
        leakThreads.add(t);
        t.start();
    }

    // ─── Unbounded collection leak ───────────────────────────────────────────

    private void startCollectionLeak() {
        List<byte[]> collection = new ArrayList<>();
        leakStore.put("collection", collection);

        Thread t = new Thread(() -> {
            while (leaking && !Thread.currentThread().isInterrupted()) {
                try {
                    collection.add(new byte[Math.min(leakRate, 1024 * 1024)]);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Collection-Leak-Simulator");
        leakThreads.add(t);
        t.start();
    }

    // ─── ThreadLocal leak (Bug 4 fix) ────────────────────────────────────────

    /**
     * Real ThreadLocal leak pattern:
     *
     *  1. A shared ThreadLocal<List<byte[]>> is declared with an initialValue() so
     *     each thread gets its own list automatically on first access.
     *  2. A fixed thread pool (5 threads, simulating an app-server pool) executes
     *     tasks that append to the calling thread's list.
     *  3. remove() is NEVER called — the list on each thread grows without bound.
     *  4. Because pool threads are reused and never die, the lists cannot be GC'd.
     *
     * Observed effect: heap usage climbs continuously at ~leakRate * 5 bytes/sec.
     * Stopping the leak calls pool.shutdownNow() so threads terminate and their
     * ThreadLocalMaps (including all byte[]) become unreachable.
     */
    private void startThreadLocalLeak() {
        // Each pool thread will own one instance of this list via ThreadLocal
        ThreadLocal<List<byte[]>> accumulator = ThreadLocal.withInitial(ArrayList::new);

        // 5-thread pool — simulates a web-server / executor pool
        threadLocalLeakPool = Executors.newFixedThreadPool(5);

        Thread controller = new Thread(() -> {
            while (leaking && !Thread.currentThread().isInterrupted()) {
                try {
                    final int chunkSize = Math.max(1, leakRate / 5); // spread across 5 threads
                    for (int i = 0; i < 5; i++) {
                        threadLocalLeakPool.submit(() -> {
                            // Each pool-thread appends to its own list; remove() is never called
                            accumulator.get().add(new byte[chunkSize]);
                        });
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RejectedExecutionException ignored) {
                    // Pool was shut down — exit cleanly
                }
            }
        }, "ThreadLocal-Leak-Controller");

        leakThreads.add(controller);
        controller.start();
    }

    // ─── Listener / callback accumulation leak ───────────────────────────────

    private void startListenerLeak() {
        List<Runnable> listeners = new ArrayList<>();
        leakStore.put("listeners", new ArrayList<>());

        Thread t = new Thread(() -> {
            while (leaking && !Thread.currentThread().isInterrupted()) {
                try {
                    final int id = listeners.size();
                    // Anonymous class captures 'id' — grows the listeners list indefinitely
                    listeners.add(new Runnable() {
                        @Override public void run() {
                            System.out.println("Listener " + id);
                        }
                    });
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Listener-Leak-Simulator");
        leakThreads.add(t);
        t.start();
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    public Map<String, Object> getLeakStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("leaking",            leaking);
        stats.put("leakType",           currentLeakType);
        stats.put("leakRate",           leakRate);
        stats.put("staticLeakSize",     STATIC_LEAK.size());
        List<byte[]> col = leakStore.get("collection");
        stats.put("collectionLeakSize", col != null ? col.size() : 0);
        return stats;
    }

    /**
     * Creates immediate heap pressure by allocating large objects over a timed window.
     * The allocation is released when the duration expires.
     */
    public void createHeapPressure(int sizeMB, int durationSeconds) {
        new Thread(() -> {
            System.out.println("Creating heap pressure: " + sizeMB + "MB for " + durationSeconds + "s");
            List<byte[]> pressure = new ArrayList<>();
            long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
            try {
                while (System.currentTimeMillis() < deadline) {
                    pressure.add(new byte[sizeMB * 1024 * 1024]);
                    Thread.sleep(100);
                }
            } catch (OutOfMemoryError e) {
                System.out.println("Heap pressure triggered OutOfMemoryError.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Heap pressure released.");
        }, "Heap-Pressure-Simulator").start();
    }
}
