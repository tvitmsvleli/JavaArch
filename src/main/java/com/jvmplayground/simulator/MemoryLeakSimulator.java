package com.jvmplayground.simulator;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simulates memory leaks by holding references to objects
 */
public class MemoryLeakSimulator {
    private static final List<Object> STATIC_LEAK = new CopyOnWriteArrayList<Object>();
    private final Map<String, List<byte[]>> leakStore = new HashMap<String, List<byte[]>>();
    private final List<Thread> leakThreads = new ArrayList<Thread>();

    private volatile boolean leaking = false;
    private int leakRate = 1024;
    private String currentLeakType = "none";

    public static enum LeakType {
        STATIC_REFERENCE("Static references"),
        COLLECTION_LEAK("Unbounded collection"),
        THREAD_LOCAL("ThreadLocal not cleared"),
        LISTENER_LEAK("Listener/callback accumulation");

        private final String description;

        LeakType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Start memory leak simulation
     */
    public void startLeak(LeakType leakType, int bytesPerSecond) {
        stopLeak();

        this.leaking = true;
        this.leakRate = bytesPerSecond;
        this.currentLeakType = leakType.name();

        System.out.println("Starting " + leakType.getDescription() + " leak at " + bytesPerSecond + " bytes/sec");

        switch (leakType) {
            case STATIC_REFERENCE:
                startStaticLeak();
                break;
            case COLLECTION_LEAK:
                startCollectionLeak();
                break;
            case THREAD_LOCAL:
                startThreadLocalLeak();
                break;
            case LISTENER_LEAK:
                startListenerLeak();
                break;
        }
    }

    /**
     * Stop all memory leaks and cleanup
     */
    public void stopLeak() {
        this.leaking = false;

        for (Thread thread : leakThreads) {
            thread.interrupt();
        }
        leakThreads.clear();

        STATIC_LEAK.clear();
        leakStore.clear();

        System.gc();

        System.out.println("Stopped memory leak simulation");
    }

    /**
     * Simulate static reference memory leak
     */
    private void startStaticLeak() {
        Thread leakThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (leaking && !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] leakyData = new byte[leakRate];
                        Arrays.fill(leakyData, (byte) 0xFF);
                        STATIC_LEAK.add(leakyData);

                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (OutOfMemoryError e) {
                        System.out.println("OutOfMemoryError: Static leak filled heap");
                        leaking = false;
                    }
                }
            }
        }, "Static-Leak-Simulator");

        leakThreads.add(leakThread);
        leakThread.start();
    }

    /**
     * Simulate collection-based memory leak
     */
    private void startCollectionLeak() {
        final List<byte[]> leakyCollection = new ArrayList<byte[]>();
        leakStore.put("collection", leakyCollection);

        Thread leakThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (leaking && !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] data = new byte[Math.min(leakRate, 1024 * 1024)];
                        leakyCollection.add(data);

                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "Collection-Leak-Simulator");

        leakThreads.add(leakThread);
        leakThread.start();
    }

    /**
     * Simulate ThreadLocal memory leak
     */
    private void startThreadLocalLeak() {
        final ThreadLocal<byte[]> threadLocal = new ThreadLocal<byte[]>();

        Thread leakThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (leaking && !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] data = new byte[leakRate];
                        threadLocal.set(data);

                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "ThreadLocal-Leak-Simulator");

        leakThreads.add(leakThread);
        leakThread.start();
    }

    /**
     * Simulate listener/callback accumulation leak
     */
    private void startListenerLeak() {
        final List<Runnable> listeners = new ArrayList<Runnable>();
        leakStore.put("listeners", new ArrayList<byte[]>());

        Thread leakThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (leaking && !Thread.currentThread().isInterrupted()) {
                    try {
                        final int id = listeners.size();
                        listeners.add(new Runnable() {
                            @Override
                            public void run() {
                                System.out.println("Listener " + id);
                            }
                        });

                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "Listener-Leak-Simulator");

        leakThreads.add(leakThread);
        leakThread.start();
    }

    /**
     * Get current leak statistics
     */
    public Map<String, Object> getLeakStats() {
        Map<String, Object> stats = new HashMap<String, Object>();
        stats.put("leaking", leaking);
        stats.put("leakType", currentLeakType);
        stats.put("leakRate", leakRate);
        stats.put("staticLeakSize", STATIC_LEAK.size());

        List<byte[]> collectionLeak = leakStore.get("collection");
        stats.put("collectionLeakSize", collectionLeak != null ? collectionLeak.size() : 0);

        return stats;
    }

    /**
     * Create heap pressure by allocating large objects
     */
    public void createHeapPressure(final int sizeMB, final int durationSeconds) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Creating heap pressure: " + sizeMB + "MB for " + durationSeconds + "s");

                List<byte[]> pressure = new ArrayList<byte[]>();
                long startTime = System.currentTimeMillis();

                try {
                    while (System.currentTimeMillis() - startTime < durationSeconds * 1000L) {
                        pressure.add(new byte[sizeMB * 1024 * 1024]);
                        Thread.sleep(100);
                    }
                } catch (OutOfMemoryError e) {
                    System.out.println("Heap pressure triggered OutOfMemoryError");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                System.out.println("Heap pressure released");
            }
        }, "Heap-Pressure-Simulator").start();
    }
}