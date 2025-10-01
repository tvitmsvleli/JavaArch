package com.jvmplayground.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class JVMMonitor {
    private final MemoryMXBean memoryMXBean;
    private final ScheduledExecutorService scheduler;
    private final List<GCEvent> gcEvents;
    private PrintWriter gcLogWriter;
    private boolean gcLoggingEnabled = false;
    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private boolean appLoadRunning = false;
    private Timer appLoadTimer;

    // Load testing fields
    private boolean loadTestRunning = false;
    private long loadTestStartTime;
    private LoadTestConfig loadTestConfig;
    private int loadTestIteration;
    private final java.util.concurrent.ExecutorService loadTestExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // For simulating different GC types
    private final Random random = new Random();
    private int gcSequence = 0;

    // GC Configuration parameters
    private int initiatingHeapOccupancyPercent = 45;
    private int maxGCPauseMillis = 200;

    public JVMMonitor() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.gcEvents = Collections.synchronizedList(new ArrayList<>());
        initializeGCLog();
        startContinuousMonitoring();
    }

    private void initializeGCLog() {
        try {
            File logsDir = new File("gc-logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            File gcLogFile = new File(logsDir, "gc_" + timestamp + ".log");
            gcLogWriter = new PrintWriter(new FileWriter(gcLogFile, true));
            System.out.println("GC log initialized: " + gcLogFile.getAbsolutePath());

            // Write GCViewer compatible header
            logToGCLog("Java HotSpot(TM) 64-Bit Server VM (25.0-b) for bsd-amd64 JRE (1.8.0), built on Mar 12 2024 12:13:12 by \"java_re\" with gcc 4.2.1 (Based on Apple Inc. build 5658) (LLVM build 2336.11.00)");
            logToGCLog("Memory: 4k page, physical 16777216k(12481232k free)");
            logToGCLog("CommandLine flags: -XX:InitialHeapSize=268435456 -XX:MaxHeapSize=4294967296 -XX:+PrintGC -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+UseCompressedClassPointers -XX:+UseCompressedOops -XX:+UseG1GC");
        } catch (IOException e) {
            System.err.println("Failed to initialize GC log: " + e.getMessage());
        }
    }

    private void startContinuousMonitoring() {
        scheduler.scheduleAtFixedRate(this::logMemoryUsage, 0, 2, TimeUnit.SECONDS);
    }

    public void setGCLoggingEnabled(boolean enabled) {
        this.gcLoggingEnabled = enabled;
    }

    public void setGCConfiguration(String gcMethod, String minHeap, String maxHeap, String youngGen, String oldRatio,
                                   String initiatingHeapPercent, String maxGCPauseMillis) {
        String configInfo = String.format("GC Configuration Applied - Method: %s, Heap: %s-%s, Young: %s, Ratio: %s, InitiatingHeapOccupancyPercent: %s, MaxGCPauseMillis: %s",
                gcMethod, minHeap, maxHeap, youngGen.isEmpty() ? "default" : youngGen,
                oldRatio.isEmpty() ? "default" : oldRatio,
                initiatingHeapPercent.isEmpty() ? "default" : initiatingHeapPercent,
                maxGCPauseMillis.isEmpty() ? "default" : maxGCPauseMillis);

        System.out.println(configInfo);

        // Parse and store the advanced GC parameters
        try {
            if (!initiatingHeapPercent.isEmpty()) {
                this.initiatingHeapOccupancyPercent = Integer.parseInt(initiatingHeapPercent);
            }
            if (!maxGCPauseMillis.isEmpty()) {
                this.maxGCPauseMillis = Integer.parseInt(maxGCPauseMillis);
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid GC configuration parameters: " + e.getMessage());
        }
    }

    private void logMemoryUsage() {
        // Only log to console, not to GC log file
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        long heapUsed = heapMemoryUsage.getUsed() / (1024 * 1024);
        long heapMax = heapMemoryUsage.getMax() / (1024 * 1024);
        double heapPressure = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;

        if (heapPressure > 70) {
            System.out.printf("[%s] High Memory Pressure: %.1f%% | Heap Used: %d MB | Max: %d MB%n",
                    java.time.LocalDateTime.now(), heapPressure, heapUsed, heapMax);
        }
    }

    public void triggerGC() {
        MemoryUsage beforeGC = memoryMXBean.getHeapMemoryUsage();
        long beforeUsed = beforeGC.getUsed();
        long beforeMax = beforeGC.getMax();

        long startTime = System.currentTimeMillis();
        System.gc();
        long endTime = System.currentTimeMillis();

        MemoryUsage afterGC = memoryMXBean.getHeapMemoryUsage();
        long afterUsed = afterGC.getUsed();

        long duration = endTime - startTime;
        long memoryFreed = beforeUsed - afterUsed;

        GCEvent event = new GCEvent("System.gc()", duration, beforeUsed, afterUsed, beforeMax, memoryFreed);
        gcEvents.add(event);

        logStandardGCEvent(event);
    }

    // New method to simulate automatic GC events during load
    private void simulateGCEvent() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax();
        double usagePercent = (double) used / max * 100;

        // Simulate different types of GC events based on configuration
        gcSequence++;
        String gcType;
        long duration;
        long memoryFreed;

        // Apply InitiatingHeapOccupancyPercent logic - start concurrent cycle earlier
        boolean shouldStartConcurrentCycle = usagePercent >= initiatingHeapOccupancyPercent;

        if (shouldStartConcurrentCycle && gcSequence % 3 == 0) {
            // Concurrent GC cycle (like G1 concurrent marking)
            gcType = "GC (Concurrent Cycle)";
            duration = Math.max(50, random.nextInt(maxGCPauseMillis)); // Respect max pause time
            memoryFreed = used / 3 + random.nextInt((int)(used / 3)); // Free 33-66% of memory
        } else if (gcSequence % 5 == 0) {
            // Full GC
            gcType = "Full GC";
            duration = Math.min(500, 100 + random.nextInt(maxGCPauseMillis * 2)); // Cap at 500ms
            memoryFreed = used / 4 + random.nextInt((int)(used / 2)); // Free 25-75% of memory
        } else if (gcSequence % 3 == 0) {
            // Young GC
            gcType = "GC";
            duration = Math.max(5, random.nextInt(maxGCPauseMillis / 2)); // Respect max pause time
            memoryFreed = used / 10 + random.nextInt((int)(used / 5)); // Free 10-30% of memory
        } else {
            // Minor GC
            gcType = "GC";
            duration = Math.max(2, random.nextInt(maxGCPauseMillis / 4)); // Very short pauses
            memoryFreed = used / 20 + random.nextInt((int)(used / 10)); // Free 5-15% of memory
        }

        long afterUsed = Math.max(used - memoryFreed, max / 10); // Don't go below 10% of max

        GCEvent event = new GCEvent(gcType, duration, used, afterUsed, max, memoryFreed);
        gcEvents.add(event);

        logStandardGCEvent(event);
    }

    private void logStandardGCEvent(GCEvent event) {
        long beforeKB = event.getBeforeBytes() / 1024;
        long afterKB = event.getAfterBytes() / 1024;
        long maxKB = event.getMaxBytes() / 1024;
        double durationSec = event.getDurationMs() / 1000.0;

        String timestamp = logDateFormat.format(new java.util.Date());

        // Format for GCViewer compatibility - use only standard GC types
        String gcLogLine;
        if (event.getGcType().contains("Full")) {
            gcLogLine = String.format("%s: [Full GC (System.gc()) %s->%s(%s), %.3f secs]",
                    timestamp,
                    formatMemory(beforeKB),
                    formatMemory(afterKB),
                    formatMemory(maxKB),
                    durationSec);
        } else {
            // For all other GC types, use standard "GC" type with Allocation Failure
            gcLogLine = String.format("%s: [GC (Allocation Failure) %s->%s(%s), %.3f secs]",
                    timestamp,
                    formatMemory(beforeKB),
                    formatMemory(afterKB),
                    formatMemory(maxKB),
                    durationSec);
        }

        System.out.println(gcLogLine);

        if (gcLoggingEnabled && gcLogWriter != null) {
            logToGCLog(gcLogLine);
        }
    }

    private String formatMemory(long kb) {
        if (kb < 1024) {
            return kb + "K";
        } else {
            return (kb / 1024) + "M";
        }
    }

    private void logToGCLog(String message) {
        if (gcLogWriter != null) {
            gcLogWriter.println(message);
            gcLogWriter.flush();
        }
    }

    public void startAppLoad() {
        if (!appLoadRunning) {
            appLoadRunning = true;
            System.out.println("Application load simulation started at: " + new java.util.Date());
            System.out.println("GC Configuration: InitiatingHeapOccupancyPercent=" + initiatingHeapOccupancyPercent +
                    "%, MaxGCPauseMillis=" + maxGCPauseMillis + "ms");

            appLoadTimer = new Timer();
            appLoadTimer.scheduleAtFixedRate(new TimerTask() {
                private int iteration = 0;
                private final List<byte[]> memoryChunks = new ArrayList<>();

                @Override
                public void run() {
                    iteration++;

                    // Create objects to increase memory pressure
                    for (int i = 0; i < 20; i++) {
                        memoryChunks.add(new byte[2 * 1024 * 1024]); // 2MB chunks
                    }

                    // Log to console only
                    if (iteration % 5 == 0) {
                        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
                        long usedMB = heap.getUsed() / (1024 * 1024);
                        long maxMB = heap.getMax() / (1024 * 1024);
                        double usagePercent = (double) usedMB / maxMB * 100;

                        String loadLog = String.format("[App Load] Iteration: %d, Memory Usage: %.1f%% (%d/%d MB), GC Threshold: %d%%",
                                iteration, usagePercent, usedMB, maxMB, initiatingHeapOccupancyPercent);
                        System.out.println(loadLog);

                        // Simulate GC events more frequently based on configuration
                        if (iteration % 3 == 0 || usagePercent > initiatingHeapOccupancyPercent) {
                            simulateGCEvent();
                        }
                    }

                    // Remove some objects to create churn and trigger GC
                    if (memoryChunks.size() > 50) {
                        memoryChunks.subList(0, 25).clear();
                        simulateGCEvent(); // Trigger GC after cleanup
                    }

                    // Force GC every 10 iterations
                    if (iteration % 10 == 0) {
                        triggerGC();
                    }
                }
            }, 0, 300);
        }
    }

    public void stopAppLoad() {
        if (appLoadRunning) {
            appLoadRunning = false;
            if (appLoadTimer != null) {
                appLoadTimer.cancel();
            }
            System.out.println("Application load simulation stopped at: " + new java.util.Date());
            triggerGC(); // Final GC to clean up
        }
    }

    // Load Testing Methods
    public void startLoadTest(LoadTestConfig config) {
        if (!loadTestRunning) {
            loadTestRunning = true;
            loadTestStartTime = System.currentTimeMillis();
            loadTestConfig = config;
            loadTestIteration = 0;

            System.out.println("Load test started with configuration: " + config.loadPattern);
            System.out.println("Object Size: " + config.objectSizeKB + "KB, Objects/Iteration: " + config.objectsPerIteration);
            System.out.println("Duration: " + config.testDurationSeconds + "s, GC Threshold: " + config.memoryThresholdPercent + "%");
            System.out.println("GC Configuration: InitiatingHeapOccupancyPercent=" + initiatingHeapOccupancyPercent +
                    "%, MaxGCPauseMillis=" + maxGCPauseMillis + "ms");

            loadTestExecutor.execute(() -> {
                runLoadTest(config);
            });
        }
    }

    public void stopLoadTest() {
        if (loadTestRunning) {
            loadTestRunning = false;
            System.out.println("Load test stopped by user");
        }
    }

    public boolean isLoadTestRunning() {
        return loadTestRunning;
    }

    public int getLoadTestProgress() {
        if (!loadTestRunning || loadTestConfig == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - loadTestStartTime;
        long total = loadTestConfig.testDurationSeconds * 1000L;
        return Math.min(100, (int) ((elapsed * 100) / total));
    }

    private void runLoadTest(LoadTestConfig config) {
        final List<byte[]> memoryPool = new ArrayList<>();
        final Random random = new Random();
        int baseObjects = config.objectsPerIteration;

        try {
            while (loadTestRunning &&
                    (System.currentTimeMillis() - loadTestStartTime) < (config.testDurationSeconds * 1000L)) {

                loadTestIteration++;
                int objectsToCreate = calculateObjectsForPattern(config, baseObjects, loadTestIteration, random);

                // Create objects to stress memory
                for (int i = 0; i < objectsToCreate && loadTestRunning; i++) {
                    byte[] object = new byte[config.objectSizeKB * 1024];
                    memoryPool.add(object);

                    // Simulate some object usage
                    for (int j = 0; j < object.length; j += 1024) {
                        object[j] = (byte) random.nextInt(256);
                    }
                }

                // Apply memory churn if enabled
                if (config.memoryChurn && memoryPool.size() > baseObjects) {
                    int removeCount = Math.min(baseObjects / 2, memoryPool.size() / 3);
                    memoryPool.subList(0, removeCount).clear();
                    // Simulate GC after churn
                    if (loadTestIteration % 2 == 0) {
                        simulateGCEvent();
                    }
                }

                // Check memory threshold and trigger GC if needed
                MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
                long usedMB = heap.getUsed() / (1024 * 1024);
                long maxMB = heap.getMax() / (1024 * 1024);
                double usagePercent = (double) usedMB / maxMB * 100;

                // Use the configured initiatingHeapOccupancyPercent for GC decisions
                boolean shouldTriggerGC = config.autoGC &&
                        (usagePercent > Math.min(config.memoryThresholdPercent, initiatingHeapOccupancyPercent) ||
                                loadTestIteration % 5 == 0);

                if (shouldTriggerGC) {
                    System.out.println(String.format("[Load Test] Memory at %.1f%%, GC threshold %d%%, triggering GC",
                            usagePercent, initiatingHeapOccupancyPercent));
                    simulateGCEvent();
                }

                // Log progress to console only
                if (loadTestIteration % 10 == 0) {
                    String progressLog = String.format(
                            "[Load Test] Iteration: %d, Objects: %d, Memory: %.1f%%, GC Config: IHOP=%d%%, MaxPause=%dms",
                            loadTestIteration, memoryPool.size(), usagePercent,
                            initiatingHeapOccupancyPercent, maxGCPauseMillis
                    );
                    System.out.println(progressLog);
                }

                // Force periodic GC regardless of memory usage
                if (loadTestIteration % 15 == 0) {
                    triggerGC();
                }

                Thread.sleep(config.iterationDelayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            loadTestRunning = false;
            memoryPool.clear();
            triggerGC(); // Final cleanup GC
            System.out.println("Load test completed. Total iterations: " + loadTestIteration);
        }
    }

    private int calculateObjectsForPattern(LoadTestConfig config, int baseObjects,
                                           int iteration, Random random) {
        switch (config.loadPattern) {
            case "Constant Load":
                return baseObjects;

            case "Increasing Load":
                return baseObjects + (iteration / 5);

            case "Spike Load":
                if (iteration % 10 == 0) {
                    return baseObjects * 8;
                }
                return baseObjects;

            case "Random Load":
                return baseObjects + random.nextInt(baseObjects * 3);

            default:
                return baseObjects;
        }
    }

    public void close() {
        if (gcLogWriter != null) {
            gcLogWriter.close();
        }
        if (appLoadTimer != null) {
            appLoadTimer.cancel();
        }
        loadTestExecutor.shutdown();
        scheduler.shutdown();
    }

    public List<GCEvent> getGCEvents() {
        return new ArrayList<>(gcEvents);
    }

    public boolean isAppLoadRunning() {
        return appLoadRunning;
    }

    // Timer class for app load
    private class Timer {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        public void scheduleAtFixedRate(TimerTask task, long initialDelay, long period) {
            executor.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.MILLISECONDS);
        }

        public void cancel() {
            executor.shutdown();
        }
    }

    private abstract class TimerTask implements Runnable {
        public abstract void run();
    }

    // Load Test Configuration class
    public static class LoadTestConfig {
        public final int objectSizeKB;
        public final int objectsPerIteration;
        public final int iterationDelayMs;
        public final int memoryThresholdPercent;
        public final int testDurationSeconds;
        public final boolean autoGC;
        public final boolean memoryChurn;
        public final String loadPattern;

        public LoadTestConfig(int objectSizeKB, int objectsPerIteration, int iterationDelayMs,
                              int memoryThresholdPercent, int testDurationSeconds,
                              boolean autoGC, boolean memoryChurn, String loadPattern) {
            this.objectSizeKB = objectSizeKB;
            this.objectsPerIteration = objectsPerIteration;
            this.iterationDelayMs = iterationDelayMs;
            this.memoryThresholdPercent = memoryThresholdPercent;
            this.testDurationSeconds = testDurationSeconds;
            this.autoGC = autoGC;
            this.memoryChurn = memoryChurn;
            this.loadPattern = loadPattern;
        }
    }

    public static class GCEvent {
        private final String gcType;
        private final long durationMs;
        private final long beforeBytes;
        private final long afterBytes;
        private final long maxBytes;
        private final long memoryFreed;

        public GCEvent(String gcType, long durationMs, long beforeBytes, long afterBytes, long maxBytes, long memoryFreed) {
            this.gcType = gcType;
            this.durationMs = durationMs;
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
            this.maxBytes = maxBytes;
            this.memoryFreed = memoryFreed;
        }

        public String getGcType() { return gcType; }
        public long getDurationMs() { return durationMs; }
        public long getBeforeBytes() { return beforeBytes; }
        public long getAfterBytes() { return afterBytes; }
        public long getMaxBytes() { return maxBytes; }
        public long getMemoryFreed() { return memoryFreed; }
    }
}