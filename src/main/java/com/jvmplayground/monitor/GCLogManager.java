package com.jvmplayground.monitor;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Manages GC logging configuration and file handling
 * Enables classic GC logging to files for analysis
 */
public class GCLogManager {
    private static final String GC_LOG_DIR = "gc-logs";
    private File currentLogFile;
    private boolean loggingEnabled = false;
    private PrintWriter logWriter;

    public GCLogManager() {
        createGCLogDirectory();
    }

    /**
     * Enable GC logging - creates actual log file and starts capturing GC events
     */
    public void enableGCLogging() {
        if (loggingEnabled) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String logFileName = String.format("gc_%s.log", timestamp);
        currentLogFile = new File(GC_LOG_DIR, logFileName);

        try {
            logWriter = new PrintWriter(new FileWriter(currentLogFile, true));
            loggingEnabled = true;

            // Write initial log header
            logWriter.println("=== JVM GC Log Started ===");
            logWriter.println("Timestamp: " + new Date());
            logWriter.println("JVM Version: " + System.getProperty("java.version"));
            logWriter.println("JVM Name: " + System.getProperty("java.vm.name"));
            logWriter.println("=== GC Events ===");
            logWriter.flush();

            System.out.println("GC Logging enabled: " + currentLogFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to create GC log file: " + e.getMessage());
        }
    }

    /**
     * Disable GC logging
     */
    public void disableGCLogging() {
        if (loggingEnabled && logWriter != null) {
            logWriter.println("=== JVM GC Log Ended ===");
            logWriter.println("Timestamp: " + new Date());
            logWriter.close();
            logWriter = null;
        }
        loggingEnabled = false;
        System.out.println("GC Logging disabled");
    }

    /**
     * Log a GC event manually (since we can't hook into actual GC events easily)
     */
    public void logGCEvent(String gcType, long duration, long memoryBefore, long memoryAfter) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            long memoryFreed = memoryBefore - memoryAfter;

            logWriter.printf("[%s] GC Event: %s | Duration: %d ms | Memory Freed: %d KB | Heap Before: %d MB | Heap After: %d MB%n",
                    timestamp, gcType, duration, memoryFreed / 1024,
                    memoryBefore / (1024 * 1024), memoryAfter / (1024 * 1024));
            logWriter.flush();
        }
    }

    /**
     * Log a manual GC call
     */
    public void logManualGC() {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] Manual GC invoked%n", timestamp);
            logWriter.flush();
        }
    }

    /**
     * Log memory pressure event
     */
    public void logMemoryPressure(String description, long memoryUsed) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] Memory Pressure: %s | Memory Used: %d MB%n",
                    timestamp, description, memoryUsed / (1024 * 1024));
            logWriter.flush();
        }
    }

    /**
     * Get current GC log content
     */
    public String getGCLogContent() {
        if (currentLogFile == null || !currentLogFile.exists()) {
            return "No GC log file available\nEnable GC logging first and generate some GC events.";
        }

        try {
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(currentLogFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            return content.toString();
        } catch (IOException e) {
            return "Error reading GC log: " + e.getMessage();
        }
    }

    /**
     * Get GC log statistics
     */
    public String getGCLogStats() {
        if (currentLogFile == null || !currentLogFile.exists()) {
            return "GC Log: Not active";
        }

        long fileSize = currentLogFile.length();
        long lastModified = currentLogFile.lastModified();
        int lineCount = countLines();

        return String.format("GC Log: %s (Size: %.2f KB, Lines: %d, Modified: %tT)",
                currentLogFile.getName(),
                fileSize / 1024.0,
                lineCount,
                new Date(lastModified));
    }

    /**
     * Parse GC log for important events
     */
    public String analyzeGCLog() {
        if (currentLogFile == null || !currentLogFile.exists()) {
            return "No GC log to analyze\nEnable GC logging first and generate some GC events.";
        }

        try {
            int manualGCCount = 0;
            int gcEventCount = 0;
            int memoryPressureCount = 0;
            long totalMemoryFreed = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(currentLogFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Manual GC invoked")) {
                        manualGCCount++;
                    } else if (line.contains("GC Event:")) {
                        gcEventCount++;
                        // Extract memory freed (simplified parsing)
                        if (line.contains("Memory Freed:")) {
                            String[] parts = line.split("Memory Freed:");
                            if (parts.length > 1) {
                                String memoryPart = parts[1].split(" ")[0];
                                try {
                                    long memory = Long.parseLong(memoryPart);
                                    totalMemoryFreed += memory;
                                } catch (NumberFormatException e) {
                                    // Ignore parsing errors
                                }
                            }
                        }
                    } else if (line.contains("Memory Pressure:")) {
                        memoryPressureCount++;
                    }
                }
            }

            return String.format("GC Log Analysis:\n" +
                            "- Manual GC Calls: %d\n" +
                            "- GC Events: %d\n" +
                            "- Memory Pressure Events: %d\n" +
                            "- Total Memory Freed: %d MB",
                    manualGCCount, gcEventCount, memoryPressureCount, totalMemoryFreed / 1024);

        } catch (IOException e) {
            return "Error analyzing GC log: " + e.getMessage();
        }
    }
    /**
     * Log STW (Stop-The-World) events
     */
    public void logSTWEvent(String description, long duration) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] STW Event: %s | Duration: %d ms%n",
                    timestamp, description, duration);
            logWriter.flush();
        }
    }
    private int countLines() {
        if (currentLogFile == null || !currentLogFile.exists()) {
            return 0;
        }

        try {
            int lines = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(currentLogFile))) {
                while (reader.readLine() != null) {
                    lines++;
                }
            }
            return lines;
        } catch (IOException e) {
            return 0;
        }
    }

    private void createGCLogDirectory() {
        File logDir = new File(GC_LOG_DIR);
        if (!logDir.exists()) {
            boolean created = logDir.mkdirs();
            if (created) {
                System.out.println("Created GC log directory: " + logDir.getAbsolutePath());
            }
        }
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }
    /**
     * Log maximum load event
     */
    public void logMaximumLoad(String description, int objects, int objectSizeKB, int threads, int duration) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] MAX LOAD: %s | Objects: %d (%dKB each) | Threads: %d | Duration: %ds%n",
                    timestamp, description, objects, objectSizeKB, threads, duration);
            logWriter.flush();
        }
    }

    /**
     * Log heap fragmentation event
     */
    public void logFragmentation(String description, int fragments, int fragmentSizeMB, int duration) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] FRAGMENTATION: %s | Fragments: %d (%dMB each) | Duration: %ds%n",
                    timestamp, description, fragments, fragmentSizeMB, duration);
            logWriter.flush();
        }
    }

    /**
     * Log memory leak storm event
     */
    public void logLeakStorm(String description, int leakRate, int duration, int totalLeaks) {
        if (loggingEnabled && logWriter != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logWriter.printf("[%s] LEAK STORM: %s | Rate: %d/sec | Duration: %ds | Total: %d objects%n",
                    timestamp, description, leakRate, duration, totalLeaks);
            logWriter.flush();
        }
    }
    public File getCurrentLogFile() {
        return currentLogFile;
    }
}
