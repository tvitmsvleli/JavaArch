package com.jvmplayground.monitor;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Generates GC logs in standard format compatible with GCViewer
 * Supports both Unified Logging (-Xlog:gc*) and Classic (-XX:+PrintGC) formats
 */
public class GCStandardLogManager {
    private static final String GC_LOG_DIR = "gc-logs";
    private File currentLogFile;
    private boolean loggingEnabled = false;
    private PrintWriter logWriter;
    private SimpleDateFormat timestampFormat;
    private long jvmStartTime;

    // GC statistics
    private int youngGCCount = 0;
    private int fullGCCount = 0;
    private long totalGCTime = 0;
    private long totalMemoryFreed = 0;

    public enum LogFormat {
        UNIFIED_LOGGING,  // -Xlog:gc* format (Java 9+)
        CLASSIC_LOGGING   // -XX:+PrintGC format (Java 8 and earlier)
    }

    private LogFormat currentFormat = LogFormat.UNIFIED_LOGGING;

    public GCStandardLogManager() {
        this.jvmStartTime = System.currentTimeMillis();
        createGCLogDirectory();
        timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    }

    /**
     * Enable GC logging with specified format
     */
    public void enableGCLogging(LogFormat format) {
        if (loggingEnabled) {
            return;
        }

        this.currentFormat = format;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String logFileName = String.format("gc_standard_%s.log", timestamp);
        currentLogFile = new File(GC_LOG_DIR, logFileName);

        try {
            logWriter = new PrintWriter(new FileWriter(currentLogFile, true));
            loggingEnabled = true;

            // Write log header based on format
            writeLogHeader();

            System.out.println("GC Standard Logging enabled: " + currentLogFile.getAbsolutePath());
            System.out.println("Format: " + format);

        } catch (IOException e) {
            System.err.println("Failed to create GC log file: " + e.getMessage());
        }
    }

    private void writeLogHeader() {
        if (currentFormat == LogFormat.UNIFIED_LOGGING) {
            logWriter.println("# Unified JVM Logging Format - Compatible with GCViewer");
            logWriter.println("# JVM: " + System.getProperty("java.vm.name"));
            logWriter.println("# Version: " + System.getProperty("java.version"));
            logWriter.println("# StartTime: " + timestampFormat.format(new Date(jvmStartTime)));
            logWriter.println("# Fields: uptime, level, tags, message");
        } else {
            logWriter.println("# Java HotSpot(TM) GC Log - Compatible with GCViewer");
            logWriter.println("# Java HotSpot(TM) 64-Bit Server VM (24.0.1) for bsd-amd64 JRE (24.0.1+9)");
            logWriter.println("# Memory: 4k page, physical 16777216k(10031108k free)");
            logWriter.println("# CommandLine flags: -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps");
        }
        logWriter.flush();
    }

    /**
     * Log a Young GC event in standard format
     */
    public void logYoungGC(long timestamp, long duration, long youngGenBefore, long youngGenAfter,
                           long heapBefore, long heapAfter, long heapSize) {
        if (!loggingEnabled || logWriter == null) return;

        youngGCCount++;
        totalGCTime += duration;
        long memoryFreed = youngGenBefore - youngGenAfter;
        totalMemoryFreed += memoryFreed;

        switch (currentFormat) {
            case UNIFIED_LOGGING:
                logUnifiedYoungGC(timestamp, duration, youngGenBefore, youngGenAfter, heapBefore, heapAfter, heapSize);
                break;
            case CLASSIC_LOGGING:
                logClassicYoungGC(timestamp, duration, youngGenBefore, youngGenAfter, heapBefore, heapAfter, heapSize);
                break;
        }
    }

    /**
     * Log a Full GC event in standard format
     */
    public void logFullGC(long timestamp, long duration, long heapBefore, long heapAfter,
                          long heapSize, long permGenBefore, long permGenAfter) {
        if (!loggingEnabled || logWriter == null) return;

        fullGCCount++;
        totalGCTime += duration;
        long memoryFreed = heapBefore - heapAfter;
        totalMemoryFreed += memoryFreed;

        switch (currentFormat) {
            case UNIFIED_LOGGING:
                logUnifiedFullGC(timestamp, duration, heapBefore, heapAfter, heapSize, permGenBefore, permGenAfter);
                break;
            case CLASSIC_LOGGING:
                logClassicFullGC(timestamp, duration, heapBefore, heapAfter, heapSize, permGenBefore, permGenAfter);
                break;
        }
    }

    /**
     * Unified Logging Format (Java 9+)
     */
    private void logUnifiedYoungGC(long timestamp, long duration, long youngGenBefore, long youngGenAfter,
                                   long heapBefore, long heapAfter, long heapSize) {
        double uptime = (timestamp - jvmStartTime) / 1000.0;

        String logEntry = String.format("[%.3fs][info][gc] GC(%d) Pause Young (G1 Evacuation Pause) " +
                        "%.1fms [%s] [%s] [%s] [%s]",
                uptime, youngGCCount, duration / 1000.0,
                formatMemory(youngGenBefore, youngGenAfter),
                formatMemory(heapBefore, heapAfter),
                formatMemoryUsage(heapBefore, heapAfter, heapSize),
                formatMemory(0, 0)); // Metaspace (not tracked)

        logWriter.println(logEntry);
        logWriter.flush();
    }

    private void logUnifiedFullGC(long timestamp, long duration, long heapBefore, long heapAfter,
                                  long heapSize, long permGenBefore, long permGenAfter) {
        double uptime = (timestamp - jvmStartTime) / 1000.0;

        String logEntry = String.format("[%.3fs][info][gc] GC(%d) Pause Full (System.gc()) " +
                        "%.1fms [%s] [%s] [%s]",
                uptime, fullGCCount, duration / 1000.0,
                formatMemory(heapBefore, heapAfter),
                formatMemoryUsage(heapBefore, heapAfter, heapSize),
                formatMemory(permGenBefore, permGenAfter));

        logWriter.println(logEntry);
        logWriter.flush();
    }

    /**
     * Classic Logging Format (Java 8 and earlier)
     */
    private void logClassicYoungGC(long timestamp, long duration, long youngGenBefore, long youngGenAfter,
                                   long heapBefore, long heapAfter, long heapSize) {
        double gcTime = (timestamp - jvmStartTime) / 1000.0;

        String logEntry = String.format("%.3f: [GC (System.gc()) [PSYoungGen: %s] %s, %.3f secs]",
                gcTime,
                formatMemoryClassic(youngGenBefore, youngGenAfter),
                formatMemoryClassic(heapBefore, heapAfter),
                duration / 1000.0);

        logWriter.println(logEntry);
        logWriter.flush();
    }

    private void logClassicFullGC(long timestamp, long duration, long heapBefore, long heapAfter,
                                  long heapSize, long permGenBefore, long permGenAfter) {
        double gcTime = (timestamp - jvmStartTime) / 1000.0;

        String logEntry = String.format("%.3f: [Full GC (System.gc()) [PSYoungGen: %s] " +
                        "[ParOldGen: %s] %s [PSPermGen: %s], %.3f secs]",
                gcTime,
                formatMemoryClassic(0, 0), // Young gen cleared in Full GC
                formatMemoryClassic(heapBefore - (heapBefore / 3), heapAfter - (heapAfter / 3)), // Approximate Old Gen
                formatMemoryClassic(heapBefore, heapAfter),
                formatMemoryClassic(permGenBefore, permGenAfter),
                duration / 1000.0);

        logWriter.println(logEntry);
        logWriter.flush();
    }

    /**
     * Format memory for Unified Logging
     */
    private String formatMemory(long before, long after) {
        return String.format("%dK->%dK(%dK)",
                before / 1024, after / 1024, (before + after) / 2048); // Approximate total
    }

    private String formatMemoryUsage(long before, long after, long total) {
        return String.format("%dK->%dK(%dK)",
                before / 1024, after / 1024, total / 1024);
    }

    /**
     * Format memory for Classic Logging
     */
    private String formatMemoryClassic(long before, long after) {
        return String.format("%dK->%dK(%dK)",
                before / 1024, after / 1024, Math.max(before, after) / 1024);
    }

    /**
     * Log heap information (for initial state)
     */
    public void logHeapInfo(long timestamp, long heapSize, long maxHeap) {
        if (!loggingEnabled || logWriter == null) return;

        if (currentFormat == LogFormat.CLASSIC_LOGGING) {
            double gcTime = (timestamp - jvmStartTime) / 1000.0;
            String logEntry = String.format("Heap%s", formatMemoryClassic(heapSize, maxHeap));
            logWriter.printf("%.3f: %s%n", gcTime, logEntry);
            logWriter.flush();
        }
    }

    /**
     * Get GC statistics
     */
    public String getGCStats() {
        return String.format("GC Statistics: Young GC: %d, Full GC: %d, Total GC Time: %d ms, Memory Freed: %d MB",
                youngGCCount, fullGCCount, totalGCTime, totalMemoryFreed / (1024 * 1024));
    }

    /**
     * Get current log file
     */
    public File getCurrentLogFile() {
        return currentLogFile;
    }

    /**
     * Disable GC logging
     */
    public void disableGCLogging() {
        if (loggingEnabled && logWriter != null) {
            // Write summary statistics
            logWriter.println("# GC Log Summary");
            logWriter.println("# " + getGCStats());
            logWriter.println("# Log End: " + timestampFormat.format(new Date()));
            logWriter.close();
            logWriter = null;
        }
        loggingEnabled = false;
        System.out.println("GC Standard Logging disabled");
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    private void createGCLogDirectory() {
        File logDir = new File(GC_LOG_DIR);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }

    /**
     * Get JVM arguments for the selected log format
     */
    public String getJVMArguments() {
        StringBuilder args = new StringBuilder();

        switch (currentFormat) {
            case UNIFIED_LOGGING:
                args.append("-Xlog:gc*=debug:file=").append(currentLogFile.getAbsolutePath())
                        .append(":time,uptime,level,tags:filecount=5,filesize=10M");
                break;
            case CLASSIC_LOGGING:
                args.append("-XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps ")
                        .append("-Xloggc:").append(currentLogFile.getAbsolutePath());
                break;
        }

        return args.toString();
    }
}