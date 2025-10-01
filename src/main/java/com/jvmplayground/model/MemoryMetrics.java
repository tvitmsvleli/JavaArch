package com.jvmplayground.model;

/**
 * Represents JVM memory metrics collected via MemoryMXBean
 * Tracks heap and non-heap memory usage, GC activity, and memory pool statistics
 */
public class MemoryMetrics {
    private final long timestamp;
    private final long usedHeap;
    private final long maxHeap;
    private final long usedNonHeap;
    private final long gcCount;
    private final long gcTime;
    private final int loadedClasses;
    private final int activeThreads;

    public MemoryMetrics(long usedHeap, long maxHeap, long usedNonHeap,
                         long gcCount, long gcTime, int loadedClasses, int activeThreads) {
        this.timestamp = System.currentTimeMillis();
        this.usedHeap = usedHeap;
        this.maxHeap = maxHeap;
        this.usedNonHeap = usedNonHeap;
        this.gcCount = gcCount;
        this.gcTime = gcTime;
        this.loadedClasses = loadedClasses;
        this.activeThreads = activeThreads;
    }

    // Getters
    public long getTimestamp() { return timestamp; }
    public long getUsedHeap() { return usedHeap; }
    public long getMaxHeap() { return maxHeap; }
    public long getUsedNonHeap() { return usedNonHeap; }
    public long getGcCount() { return gcCount; }
    public long getGcTime() { return gcTime; }
    public int getLoadedClasses() { return loadedClasses; }
    public int getActiveThreads() { return activeThreads; }

    /**
     * Calculate heap usage percentage
     * @return percentage of heap used (0-100)
     */
    public double getHeapUsagePercent() {
        return maxHeap > 0 ? (usedHeap * 100.0) / maxHeap : 0;
    }

    @Override
    public String toString() {
        return String.format("MemoryMetrics[Heap: %d/%d MB (%.1f%%)]",
                usedHeap / (1024 * 1024), maxHeap / (1024 * 1024), getHeapUsagePercent());
    }
}