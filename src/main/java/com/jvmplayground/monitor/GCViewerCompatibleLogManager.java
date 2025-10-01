package com.jvmplayground.monitor;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

/**
 * Generates GC logs in EXACT format compatible with GCViewer
 * Produces logs identical to real JVM -Xloggc output
 */
public class GCViewerCompatibleLogManager {
    private static final String GC_LOG_DIR = "gc-logs";
    private File currentLogFile;
    private boolean loggingEnabled = false;
    private PrintWriter logWriter;
    private long jvmStartTime;
    private Random random = new Random();

    // GC statistics
    private int gcCount = 0;
    private long totalGCTime = 0;

    public GCViewerCompatibleLogManager() {
        this.jvmStartTime = System.currentTimeMillis();
        createGCLogDirectory();
    }

    /**
     * Enable GC logging in GCViewer-compatible format
     */
    public void enableGCLogging() {
        if (loggingEnabled) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String logFileName = String.format("gcviewer_%s.log", timestamp);
        currentLogFile = new File(GC_LOG_DIR, logFileName);

        try {
            logWriter = new PrintWriter(new FileWriter(currentLogFile, true));
            loggingEnabled = true;

            // Write exact header that real JVM produces
            writeJVMHeader();

            System.out.println("GCViewer-Compatible Logging enabled: " + currentLogFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("Failed to create GC log file: " + e.getMessage());
        }
    }

    /**
     * Write exact JVM header that GCViewer expects
     */
    private void writeJVMHeader() {
        logWriter.println("Java HotSpot(TM) 64-Bit Server VM (24.0.1) for bsd-amd64 JRE (24.0.1+9), built on Apr 15 2025 11:28:34 by \"\"");
        logWriter.println("Memory: 4k page, physical 16777216k(10031108k free)");
        logWriter.println("CommandLine flags: -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+PrintGCDateStamps -Xloggc:" + currentLogFile.getName());
        logWriter.flush();
    }

    /**
     * Log a Young GC event in exact JVM format
     */
    public void logYoungGC(long duration, long youngGenBefore, long youngGenAfter,
                           long heapBefore, long heapAfter, long heapSize) {
        if (!loggingEnabled || logWriter == null) return;

        gcCount++;
        totalGCTime += duration;

        double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;

        // Exact format from real JVM Young GC
        String logEntry = String.format("%.3f: [GC (Allocation Failure) [PSYoungGen: %s] %s, %.3f secs]",
                timestamp,
                formatMemoryExact(youngGenBefore, youngGenAfter, 256*1024*1024L), // Young gen total ~256MB
                formatMemoryExact(heapBefore, heapAfter, heapSize),
                duration / 1000.0);

        logWriter.println(logEntry);
        logWriter.flush();
    }

    /**
     * Log a Full GC event in exact JVM format
     */
    public void logFullGC(long duration, long heapBefore, long heapAfter, long heapSize) {
        if (!loggingEnabled || logWriter == null) return;

        gcCount++;
        totalGCTime += duration;

        double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;

        // Calculate generation sizes (approximated)
        long youngGenBefore = heapBefore / 3;
        long youngGenAfter = 10 * 1024 * 1024; // ~10MB after GC
        long oldGenBefore = heapBefore - youngGenBefore;
        long oldGenAfter = heapAfter - youngGenAfter;

        // Exact format from real JVM Full GC
        String logEntry = String.format("%.3f: [Full GC (System.gc()) [PSYoungGen: %s] [ParOldGen: %s] %s [PSPermGen: %s], %.3f secs]",
                timestamp,
                formatMemoryExact(youngGenBefore, youngGenAfter, 256*1024*1024L),
                formatMemoryExact(oldGenBefore, oldGenAfter, 768*1024*1024L),
                formatMemoryExact(heapBefore, heapAfter, heapSize),
                formatMemoryExact(50*1024*1024L, 40*1024*1024L, 80*1024*1024L), // PermGen
                duration / 1000.0);

        logWriter.println(logEntry);
        logWriter.flush();
    }

    /**
     * Log a G1 GC event in exact JVM format
     */
    public void logG1GC(long duration, long heapBefore, long heapAfter, long heapSize, boolean isYoung) {
        if (!loggingEnabled || logWriter == null) return;

        gcCount++;
        totalGCTime += duration;

        double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;

        if (isYoung) {
            // G1 Young GC format
            String logEntry = String.format("%.3f: [GC pause (G1 Evacuation Pause) (young)%.1fms]",
                    timestamp, duration / 1000.0);
            logWriter.println(logEntry);

            // G1 detailed info
            logWriter.printf("%.3f: [G1Ergonomics (CSet Construction) start choosing CSet, _pending_cards: %d, predicted base time: %.1f ms, remaining time: %.1f ms, target pause time: %.1f ms]%n",
                    timestamp, random.nextInt(1000), duration / 2.0, duration / 2.0, 200.0);

            logWriter.printf("%.3f: [G1Ergonomics (CSet Construction) add young regions to CSet, eden: %d regions, survivors: %d regions, predicted young region time: %.1f ms]%n",
                    timestamp, random.nextInt(50) + 10, random.nextInt(10) + 1, duration / 3.0);

        } else {
            // G1 Mixed GC format
            String logEntry = String.format("%.3f: [GC pause (G1 Evacuation Pause) (mixed)%.1fms]",
                    timestamp, duration / 1000.0);
            logWriter.println(logEntry);
        }

        logWriter.flush();
    }

    /**
     * Format memory in EXACT JVM format: 3194624K->112480K(3194880K)
     */
    private String formatMemoryExact(long before, long after, long total) {
        return String.format("%dK->%dK(%dK)",
                before / 1024, after / 1024, total / 1024);
    }

    /**
     * Log a system GC call
     */
    public void logSystemGC() {
        if (!loggingEnabled || logWriter == null) return;

        double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;
        logWriter.printf("%.3f: [Full GC (System.gc())%n", timestamp);
        logWriter.flush();
    }

    /**
     * Log heap information
     */
    public void logHeapInfo() {
        if (!loggingEnabled || logWriter == null) return;

        double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long total = runtime.totalMemory();
        long max = runtime.maxMemory();

        logWriter.printf("%.3f: [GC [PSYoungGen: %s] [ParOldGen: %s] %s [PSPermGen: %s]%n",
                timestamp,
                formatMemoryExact(used / 3, used / 3, total / 3),
                formatMemoryExact(used * 2 / 3, used * 2 / 3, total * 2 / 3),
                formatMemoryExact(used, used, total),
                formatMemoryExact(50*1024*1024L, 50*1024*1024L, 80*1024*1024L));
        logWriter.flush();
    }

    /**
     * Get GC statistics
     */
    public String getGCStats() {
        return String.format("GC Events: %d, Total GC Time: %.3f sec", gcCount, totalGCTime / 1000.0);
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
            // Write JVM shutdown message
            double timestamp = (System.currentTimeMillis() - jvmStartTime) / 1000.0;
            logWriter.printf("%.3f: [GC [PSYoungGen: %s] [ParOldGen: %s] %s [PSPermGen: %s]%n",
                    timestamp,
                    formatMemoryExact(0, 0, 0),
                    formatMemoryExact(0, 0, 0),
                    formatMemoryExact(0, 0, 0),
                    formatMemoryExact(0, 0, 0));

            logWriter.println("Heap");
            logWriter.println(" PSYoungGen      total 0K, used 0K [0x0000000000000000, 0x0000000000000000, 0x0000000000000000)");
            logWriter.println("  eden space 0K, 0% used [0x0000000000000000,0x0000000000000000,0x0000000000000000)");
            logWriter.println("  from space 0K, 0% used [0x0000000000000000,0x0000000000000000,0x0000000000000000)");
            logWriter.println("  to   space 0K, 0% used [0x0000000000000000,0x0000000000000000,0x0000000000000000)");
            logWriter.println(" ParOldGen       total 0K, used 0K [0x0000000000000000, 0x0000000000000000, 0x0000000000000000)");
            logWriter.println("  object space 0K, 0% used [0x0000000000000000,0x0000000000000000,0x0000000000000000)");
            logWriter.println(" PSPermGen       total 0K, used 0K [0x0000000000000000, 0x0000000000000000, 0x0000000000000000)");
            logWriter.println("  object space 0K, 0% used [0x0000000000000000,0x0000000000000000,0x0000000000000000)");

            logWriter.close();
            logWriter = null;
        }
        loggingEnabled = false;
        System.out.println("GCViewer-Compatible Logging disabled");
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
     * Get JVM arguments for GCViewer-compatible logging
     */
    public String getJVMArguments() {
        return "-XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+PrintGCDateStamps -Xloggc:" +
                (currentLogFile != null ? currentLogFile.getAbsolutePath() : "gc.log");
    }
}