package com.jvmplayground.monitor;

import com.jvmplayground.model.MemoryMetrics;

import java.io.*;
import java.lang.management.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Core JVM monitoring engine.
 *
 * Fixes applied:
 *  - Bug 1 (int overflow in simulateGCEvent): method removed entirely; real GC events
 *    captured via GarbageCollectorMXBean notification listener.
 *  - O-1 (unbounded gcEvents list): replaced ArrayList with ConcurrentLinkedDeque capped
 *    at MAX_GC_EVENTS = 1000.
 *  - O-3 (MemoryMetrics DTO adoption): getCurrentMetrics() exposes a snapshot using the
 *    MemoryMetrics value-object so callers never touch raw MXBean APIs.
 *  - O-7 (real GC hook): registerRealGCListener() attaches a NotificationListener to every
 *    GarbageCollectorMXBean and records real pause times, before/after heap sizes, and GC cause.
 *  - App load allocation rate reduced from 133 MB/s to ~10 MB/s so GC behaviour is
 *    observable rather than instant OOM.
 */
public class JVMMonitor {

    // O-1: hard cap on in-memory GC event history
    private static final int MAX_GC_EVENTS = 1000;

    private final MemoryMXBean memoryMXBean;
    private final ScheduledExecutorService scheduler;

    // O-1: ConcurrentLinkedDeque instead of synchronizedList(ArrayList)
    private final Deque<GCEvent> gcEventDeque = new ConcurrentLinkedDeque<>();

    private PrintWriter gcLogWriter;
    private boolean gcLoggingEnabled = false;
    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private boolean appLoadRunning = false;
    private AppLoadTimer appLoadTimer;

    // Load testing fields
    private boolean loadTestRunning = false;
    private long loadTestStartTime;
    private LoadTestConfig loadTestConfig;
    private int loadTestIteration;
    private final ExecutorService loadTestExecutor = Executors.newSingleThreadExecutor();

    // GC configuration parameters (informational — affects log output)
    private int initiatingHeapOccupancyPercent = 45;
    private int maxGCPauseMillis = 200;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public JVMMonitor() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.scheduler = Executors.newScheduledThreadPool(1);
        initializeGCLog();
        registerRealGCListener();   // O-7
        startContinuousMonitoring();
    }

    // -------------------------------------------------------------------------
    // O-7: Real GC listener via GarbageCollectorMXBean
    // -------------------------------------------------------------------------

    // GC notification type string — avoids importing com.sun.management.GarbageCollectionNotificationInfo
    private static final String GC_NOTIFICATION_TYPE = "com.sun.management.gc.notification";

    /**
     * Attaches a JMX notification listener to every GarbageCollectorMXBean.
     *
     * The notification payload is a CompositeData whose structure mirrors
     * GarbageCollectionNotificationInfo (gcName, gcCause, gcInfo). We parse it
     * directly using only the standard javax.management.openmbean API so that
     * no com.sun.* import is needed — this compiles cleanly under --release N
     * for any N without --add-exports.
     *
     * CompositeData layout (matches GarbageCollectionNotificationInfo):
     *   gcName   : String
     *   gcAction : String
     *   gcCause  : String
     *   gcInfo   : CompositeData
     *     duration              : Long  (real JVM-measured pause, ms)
     *     memoryUsageBeforeGc   : TabularData  (pool name → MemoryUsage)
     *     memoryUsageAfterGc    : TabularData  (pool name → MemoryUsage)
     *
     * Each row in the TabularData is a CompositeData with fields:
     *   key   : String          (memory pool name)
     *   value : CompositeData   (MemoryUsage: init, used, committed, max — all Long)
     */
    private void registerRealGCListener() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        int registered = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            if (gcBean instanceof NotificationEmitter) {
                NotificationEmitter emitter = (NotificationEmitter) gcBean;
                emitter.addNotificationListener((notification, handback) -> {
                    if (!GC_NOTIFICATION_TYPE.equals(notification.getType())) return;
                    try {
                        CompositeData cd     = (CompositeData) notification.getUserData();
                        String gcName        = (String) cd.get("gcName");
                        String gcCause       = (String) cd.get("gcCause");
                        CompositeData gcInfo = (CompositeData) cd.get("gcInfo");
                        long duration        = (Long) gcInfo.get("duration");

                        long beforeBytes = sumUsedMemory((TabularData) gcInfo.get("memoryUsageBeforeGc"));
                        long afterBytes  = sumUsedMemory((TabularData) gcInfo.get("memoryUsageAfterGc"));
                        long maxBytes    = memoryMXBean.getHeapMemoryUsage().getMax();
                        long freed       = Math.max(0, beforeBytes - afterBytes);

                        GCEvent event = new GCEvent(gcName + " (" + gcCause + ")",
                                duration, beforeBytes, afterBytes, maxBytes, freed);
                        addGCEvent(event);
                        logStandardGCEvent(event);

                    } catch (Exception ex) {
                        System.err.println("[GC Listener] Error processing notification: "
                                           + ex.getMessage());
                    }
                }, null, null);
                registered++;
            }
        }
        System.out.println("Real GC listener registered on " + registered
                           + " of " + gcBeans.size() + " GC collector(s).");
    }

    /**
     * Sums the 'used' bytes across all memory pools in a GC notification
     * TabularData (memoryUsageBeforeGc / memoryUsageAfterGc).
     *
     * Each row: CompositeData { key: String, value: CompositeData { used: Long, ... } }
     */
    private long sumUsedMemory(TabularData table) {
        if (table == null) return 0L;
        long total = 0L;
        for (Object row : table.values()) {
            try {
                CompositeData memUsage = (CompositeData) ((CompositeData) row).get("value");
                if (memUsage != null) {
                    Object used = memUsage.get("used");
                    if (used instanceof Long) total += (Long) used;
                }
            } catch (Exception ignored) { /* skip malformed pool entry */ }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // O-1: Bounded event queue
    // -------------------------------------------------------------------------

    /** Thread-safe add that evicts the oldest entry when the cap is reached. */
    private void addGCEvent(GCEvent event) {
        while (gcEventDeque.size() >= MAX_GC_EVENTS) {
            gcEventDeque.pollFirst();
        }
        gcEventDeque.addLast(event);
    }

    // -------------------------------------------------------------------------
    // GC log initialisation
    // -------------------------------------------------------------------------

    private void initializeGCLog() {
        try {
            File logsDir = new File("gc-logs");
            if (!logsDir.exists()) logsDir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File gcLogFile = new File(logsDir, "gc_" + ts + ".log");
            gcLogWriter = new PrintWriter(new FileWriter(gcLogFile, true));
            System.out.println("GC log initialised: " + gcLogFile.getAbsolutePath());

            // GCViewer-compatible header
            logToGCLog("Java HotSpot(TM) 64-Bit Server VM (" + System.getProperty("java.version")
                       + ") for " + System.getProperty("os.name"));
            logToGCLog("Memory: physical max " + (Runtime.getRuntime().maxMemory() / 1024) + "K");
            logToGCLog("CommandLine flags: -XX:+PrintGC -XX:+PrintGCDateStamps "
                       + "-XX:+PrintGCDetails -XX:+UseG1GC");
        } catch (IOException e) {
            System.err.println("Failed to initialise GC log: " + e.getMessage());
        }
    }

    private void startContinuousMonitoring() {
        scheduler.scheduleAtFixedRate(this::logHighPressureWarning, 0, 2, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void setGCLoggingEnabled(boolean enabled) {
        this.gcLoggingEnabled = enabled;
    }

    public void setGCConfiguration(String gcMethod, String minHeap, String maxHeap,
                                   String youngGen, String oldRatio,
                                   String initiatingHeapPercent, String maxGCPauseMillisStr) {
        System.out.printf("GC Config — method: %s, heap: %s‑%s, young: %s, ratio: %s, "
                          + "IHOP: %s, maxPause: %s%n",
                gcMethod, minHeap, maxHeap,
                youngGen.isEmpty()               ? "default" : youngGen,
                oldRatio.isEmpty()               ? "default" : oldRatio,
                initiatingHeapPercent.isEmpty()  ? "default" : initiatingHeapPercent,
                maxGCPauseMillisStr.isEmpty()    ? "default" : maxGCPauseMillisStr);
        try {
            if (!initiatingHeapPercent.isEmpty())
                this.initiatingHeapOccupancyPercent = Integer.parseInt(initiatingHeapPercent);
            if (!maxGCPauseMillisStr.isEmpty())
                this.maxGCPauseMillis = Integer.parseInt(maxGCPauseMillisStr);
        } catch (NumberFormatException e) {
            System.err.println("Invalid GC config value: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // O-3: MemoryMetrics DTO — single call gives everything the UI needs
    // -------------------------------------------------------------------------

    /**
     * Returns a consistent snapshot of the current JVM state.
     * Uses {@code heap.getMax()} so the percentage reflects usage against the
     * true maximum heap, not just the currently-committed portion (Bug 3 fix).
     */
    public MemoryMetrics getCurrentMetrics() {
        MemoryUsage heap    = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();

        long totalGcCount = 0, totalGcTime = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gcBean.getCollectionCount();
            long t = gcBean.getCollectionTime();
            if (c >= 0) totalGcCount += c;
            if (t >= 0) totalGcTime  += t;
        }

        int loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        int activeThreads = ManagementFactory.getThreadMXBean().getThreadCount();

        return new MemoryMetrics(
            heap.getUsed(), heap.getMax(),
            nonHeap.getUsed(),
            totalGcCount, totalGcTime,
            loadedClasses, activeThreads
        );
    }

    // -------------------------------------------------------------------------
    // Manual GC trigger
    // -------------------------------------------------------------------------

    /**
     * Hints the JVM to run a GC cycle. The resulting GC event (including real
     * pause time and before/after heap sizes) is captured automatically by the
     * registered GarbageCollectorMXBean listener.
     */
    public void triggerGC() {
        System.gc();
        System.out.println("Manual System.gc() invoked — event captured by real GC listener.");
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    private void logHighPressureWarning() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long maxMB  = heap.getMax()  / (1024 * 1024);
        long usedMB = heap.getUsed() / (1024 * 1024);
        double pressure = maxMB > 0 ? (double) usedMB / maxMB * 100 : 0;
        if (pressure > 70) {
            System.out.printf("[%s] High Memory Pressure: %.1f%% | Used: %d MB | Max: %d MB%n",
                    java.time.LocalDateTime.now(), pressure, usedMB, maxMB);
        }
    }

    private void logStandardGCEvent(GCEvent event) {
        long beforeKB   = event.getBeforeBytes() / 1024;
        long afterKB    = event.getAfterBytes()  / 1024;
        long maxKB      = event.getMaxBytes()    / 1024;
        double durSec   = event.getDurationMs()  / 1000.0;
        String ts       = logDateFormat.format(new Date());

        boolean isFull  = event.getGcType().contains("Full")
                       || event.getGcType().contains("Pause Full");
        String line = isFull
            ? String.format("%s: [Full GC (%s) %s->%s(%s), %.3f secs]",
                ts, event.getGcType(),
                fmtMem(beforeKB), fmtMem(afterKB), fmtMem(maxKB), durSec)
            : String.format("%s: [GC (%s) %s->%s(%s), %.3f secs]",
                ts, event.getGcType(),
                fmtMem(beforeKB), fmtMem(afterKB), fmtMem(maxKB), durSec);

        System.out.println(line);
        if (gcLoggingEnabled && gcLogWriter != null) logToGCLog(line);
    }

    private String fmtMem(long kb) {
        return kb < 1024 ? kb + "K" : (kb / 1024) + "M";
    }

    private void logToGCLog(String message) {
        if (gcLogWriter != null) {
            gcLogWriter.println(message);
            gcLogWriter.flush();
        }
    }

    // -------------------------------------------------------------------------
    // App Load simulation
    // -------------------------------------------------------------------------

    /**
     * Allocates ~10 MB every 500 ms (was 133 MB/s — too fast to observe GC).
     * Real GC events fire naturally from the pressure; the listener records them.
     */
    public void startAppLoad() {
        if (appLoadRunning) return;
        appLoadRunning = true;
        System.out.printf("App load started. IHOP=%d%%, MaxGCPause=%dms%n",
                initiatingHeapOccupancyPercent, maxGCPauseMillis);

        appLoadTimer = new AppLoadTimer();
        appLoadTimer.scheduleAtFixedRate(new AppLoadTask(), 0, 500);
    }

    public void stopAppLoad() {
        if (!appLoadRunning) return;
        appLoadRunning = false;
        if (appLoadTimer != null) appLoadTimer.cancel();
        System.out.println("App load stopped.");
    }

    private class AppLoadTask implements Runnable {
        private int iteration = 0;
        private final List<byte[]> chunks = new ArrayList<>();

        @Override
        public void run() {
            iteration++;
            // ~10 MB per tick — gentle enough to watch GC kick in
            for (int i = 0; i < 10; i++) {
                chunks.add(new byte[1024 * 1024]);
            }
            if (iteration % 5 == 0) {
                MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
                long usedMB = heap.getUsed() / (1024 * 1024);
                long maxMB  = heap.getMax()  / (1024 * 1024);
                double pct  = maxMB > 0 ? (double) usedMB / maxMB * 100 : 0;
                System.out.printf("[App Load] iter=%d  %.1f%% (%d/%d MB)%n",
                        iteration, pct, usedMB, maxMB);
            }
            // Remove oldest objects to generate churn
            if (chunks.size() > 40) chunks.subList(0, 20).clear();
        }
    }

    // -------------------------------------------------------------------------
    // Load Test
    // -------------------------------------------------------------------------

    public void startLoadTest(LoadTestConfig config) {
        if (loadTestRunning) return;
        loadTestRunning    = true;
        loadTestStartTime  = System.currentTimeMillis();
        loadTestConfig     = config;
        loadTestIteration  = 0;
        System.out.printf("Load test started — pattern: %s, objectSize: %dKB, "
                          + "objects/iter: %d, delay: %dms, duration: %ds%n",
                config.loadPattern, config.objectSizeKB, config.objectsPerIteration,
                config.iterationDelayMs, config.testDurationSeconds);
        loadTestExecutor.execute(() -> runLoadTest(config));
    }

    public void stopLoadTest() {
        if (loadTestRunning) {
            loadTestRunning = false;
            System.out.println("Load test stopped by user.");
        }
    }

    public boolean isLoadTestRunning()  { return loadTestRunning; }

    public int getLoadTestProgress() {
        if (!loadTestRunning || loadTestConfig == null) return 0;
        long elapsed = System.currentTimeMillis() - loadTestStartTime;
        long total   = loadTestConfig.testDurationSeconds * 1000L;
        return Math.min(100, (int) ((elapsed * 100) / total));
    }

    private void runLoadTest(LoadTestConfig config) {
        List<byte[]> pool = new ArrayList<>();
        Random rand       = new Random();
        int base          = config.objectsPerIteration;
        try {
            while (loadTestRunning &&
                   (System.currentTimeMillis() - loadTestStartTime) < config.testDurationSeconds * 1000L) {
                loadTestIteration++;
                int toCreate = objectCountForPattern(config, base, loadTestIteration, rand);

                for (int i = 0; i < toCreate && loadTestRunning; i++) {
                    byte[] obj = new byte[config.objectSizeKB * 1024];
                    // Touch data to prevent dead-code elimination
                    for (int j = 0; j < obj.length; j += 1024) obj[j] = (byte) rand.nextInt(256);
                    pool.add(obj);
                }

                if (config.memoryChurn && pool.size() > base) {
                    int remove = Math.min(base / 2, pool.size() / 3);
                    if (remove > 0) pool.subList(0, remove).clear();
                }

                if (loadTestIteration % 10 == 0) {
                    MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
                    long usedMB = heap.getUsed() / (1024 * 1024);
                    long maxMB  = heap.getMax()  / (1024 * 1024);
                    double pct  = maxMB > 0 ? (double) usedMB / maxMB * 100 : 0;
                    System.out.printf("[Load Test] iter=%d objects=%d mem=%.1f%% IHOP=%d%% maxPause=%dms%n",
                            loadTestIteration, pool.size(), pct,
                            initiatingHeapOccupancyPercent, maxGCPauseMillis);
                }
                Thread.sleep(config.iterationDelayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            loadTestRunning = false;
            pool.clear();
            System.out.println("Load test complete. Iterations: " + loadTestIteration);
        }
    }

    private int objectCountForPattern(LoadTestConfig cfg, int base, int iter, Random rand) {
        switch (cfg.loadPattern) {
            case "Constant Load":   return base;
            case "Increasing Load": return base + (iter / 5);
            case "Spike Load":      return (iter % 10 == 0) ? base * 8 : base;
            case "Random Load":     return base + rand.nextInt(Math.max(1, base * 3));
            default:                return base;
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void close() {
        if (gcLogWriter    != null) gcLogWriter.close();
        if (appLoadTimer   != null) appLoadTimer.cancel();
        loadTestExecutor.shutdown();
        scheduler.shutdown();
    }

    public List<GCEvent> getGCEvents()    { return new ArrayList<>(gcEventDeque); }
    public boolean isAppLoadRunning()     { return appLoadRunning; }

    // -------------------------------------------------------------------------
    // Private timer helpers (replaces java.util.Timer to avoid daemon-thread issues)
    // -------------------------------------------------------------------------

    private class AppLoadTimer {
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        void scheduleAtFixedRate(Runnable task, long init, long period) {
            exec.scheduleAtFixedRate(task, init, period, TimeUnit.MILLISECONDS);
        }
        void cancel() { exec.shutdownNow(); }
    }

    // -------------------------------------------------------------------------
    // Static value objects
    // -------------------------------------------------------------------------

    public static class LoadTestConfig {
        public final int    objectSizeKB;
        public final int    objectsPerIteration;
        public final int    iterationDelayMs;
        public final int    memoryThresholdPercent;
        public final int    testDurationSeconds;
        public final boolean autoGC;
        public final boolean memoryChurn;
        public final String  loadPattern;

        public LoadTestConfig(int objectSizeKB, int objectsPerIteration, int iterationDelayMs,
                int memoryThresholdPercent, int testDurationSeconds,
                boolean autoGC, boolean memoryChurn, String loadPattern) {
            this.objectSizeKB           = objectSizeKB;
            this.objectsPerIteration    = objectsPerIteration;
            this.iterationDelayMs       = iterationDelayMs;
            this.memoryThresholdPercent = memoryThresholdPercent;
            this.testDurationSeconds    = testDurationSeconds;
            this.autoGC                 = autoGC;
            this.memoryChurn            = memoryChurn;
            this.loadPattern            = loadPattern;
        }
    }

    public static class GCEvent {
        private final String gcType;
        private final long   durationMs;
        private final long   beforeBytes;
        private final long   afterBytes;
        private final long   maxBytes;
        private final long   memoryFreed;

        public GCEvent(String gcType, long durationMs, long beforeBytes, long afterBytes,
                       long maxBytes, long memoryFreed) {
            this.gcType      = gcType;
            this.durationMs  = durationMs;
            this.beforeBytes = beforeBytes;
            this.afterBytes  = afterBytes;
            this.maxBytes    = maxBytes;
            this.memoryFreed = memoryFreed;
        }

        public String getGcType()     { return gcType; }
        public long getDurationMs()   { return durationMs; }
        public long getBeforeBytes()  { return beforeBytes; }
        public long getAfterBytes()   { return afterBytes; }
        public long getMaxBytes()     { return maxBytes; }
        public long getMemoryFreed()  { return memoryFreed; }
    }
}
