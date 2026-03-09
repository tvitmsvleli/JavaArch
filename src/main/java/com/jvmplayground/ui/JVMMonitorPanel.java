package com.jvmplayground.ui;

import com.jvmplayground.model.MemoryMetrics;
import com.jvmplayground.monitor.JVMMonitor;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.util.List;

/**
 * Main monitoring panel.
 *
 * Fixes applied:
 *  - Bug 2: constructor now calls startContinuousUIUpdates() (was already present but
 *           documented here for clarity).
 *  - Bug 3: updateMemoryDisplay() uses MemoryMetrics.getHeapUsagePercent() which divides
 *           by heap.getMax() (true max), not totalMemory() (committed, can be much smaller).
 *  - O-3:   All display methods consume getCurrentMetrics() DTO instead of raw Runtime calls.
 *  - Log trimming: logTextArea is trimmed when the document exceeds ~50 KB so it cannot
 *           grow without bound in long-running sessions.
 */
public class JVMMonitorPanel extends JPanel {

    private final JVMMonitor monitor;

    // ─── UI Components ───────────────────────────────────────────────────────
    private final JButton triggerGCButton;
    private final JButton simulateSTWButton;
    private final JButton memoryLeakButton;
    private final JButton startAppLoadButton;
    private final JButton stopAppLoadButton;
    private final JTextArea logTextArea;
    private final JProgressBar memoryUsageBar;
    private final JLabel memoryUsageLabel;
    private final JLabel gcStatsLabel;
    private final JLabel loadStatsLabel;

    // GC Configuration
    private final JComboBox<String> gcMethodComboBox;
    private final JCheckBox gcLoggingCheckBox;
    private final JTextField minHeapSizeField;
    private final JTextField maxHeapSizeField;
    private final JTextField youngGenSizeField;
    private final JTextField oldGenRatioField;
    private final JTextField initiatingHeapOccupancyPercentField;
    private final JTextField maxGCPauseMillisField;
    private final JButton applyGCConfigButton;

    // Load Testing Configuration
    private final JSpinner objectSizeSpinner;
    private final JSpinner objectsPerIterationSpinner;
    private final JSpinner iterationDelaySpinner;
    private final JSpinner memoryThresholdSpinner;
    private final JSpinner loadDurationSpinner;
    private final JCheckBox autoGCCheckBox;
    private final JCheckBox memoryChurnCheckBox;
    private final JComboBox<String> loadPatternComboBox;
    private final JButton startLoadTestButton;
    private final JButton stopLoadTestButton;
    private final JProgressBar loadProgressBar;

    // Max characters before the log area is trimmed (~50 KB)
    private static final int LOG_MAX_CHARS = 50_000;

    private Timer monitoringTimer;

    // ─── Construction ────────────────────────────────────────────────────────

    public JVMMonitorPanel() {
        this.monitor = new JVMMonitor();

        triggerGCButton    = new JButton("Trigger GC");
        simulateSTWButton  = new JButton("Simulate STW");
        memoryLeakButton   = new JButton("Create Memory Leak");
        startAppLoadButton = new JButton("Start App Load");
        stopAppLoadButton  = new JButton("Stop App Load");

        logTextArea      = new JTextArea(15, 60);
        memoryUsageBar   = new JProgressBar(0, 100);
        memoryUsageLabel = new JLabel("Heap: 0% (0 MB / 0 MB)");
        gcStatsLabel     = new JLabel("GC: count=0 | time=0 ms | classes=0 | threads=0");
        loadStatsLabel   = new JLabel("Load Test: Not Running");

        gcMethodComboBox = new JComboBox<>(new String[]{
                "G1GC", "Parallel GC", "CMS", "Serial GC", "ZGC", "Shenandoah"
        });
        gcLoggingCheckBox                  = new JCheckBox("Enable GC Logging", true);
        minHeapSizeField                   = new JTextField("64m",  8);
        maxHeapSizeField                   = new JTextField("512m", 8);
        youngGenSizeField                  = new JTextField("",     8);
        oldGenRatioField                   = new JTextField("",     8);
        initiatingHeapOccupancyPercentField = new JTextField("45",  6);
        maxGCPauseMillisField              = new JTextField("200",  6);
        applyGCConfigButton                = new JButton("Apply GC Config");

        objectSizeSpinner          = new JSpinner(new SpinnerNumberModel(1024, 1, 102400, 1024));
        objectsPerIterationSpinner = new JSpinner(new SpinnerNumberModel(10,   1, 1000,   1));
        iterationDelaySpinner      = new JSpinner(new SpinnerNumberModel(500,  10, 5000, 100));
        memoryThresholdSpinner     = new JSpinner(new SpinnerNumberModel(80,   10, 95,    5));
        loadDurationSpinner        = new JSpinner(new SpinnerNumberModel(300,  30, 3600, 30));
        autoGCCheckBox             = new JCheckBox("Auto GC",      true);
        memoryChurnCheckBox        = new JCheckBox("Memory Churn", true);
        loadPatternComboBox = new JComboBox<>(new String[]{
                "Constant Load", "Increasing Load", "Spike Load", "Random Load"
        });
        startLoadTestButton = new JButton("Start Load Test");
        stopLoadTestButton  = new JButton("Stop Load Test");
        loadProgressBar     = new JProgressBar(0, 100);

        initializeUI();
        setupEventHandlers();

        // Bug 2 fix: explicitly start the monitoring timer (was missing in original constructor)
        monitor.setGCLoggingEnabled(true);
        startContinuousUIUpdates();

        log("JVM Monitor started — real GC events captured via GarbageCollectorMXBean.");
        log("GC logging active → gc-logs/ directory.");
    }

    // ─── UI Construction ─────────────────────────────────────────────────────

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Monitor",          createMonitorTab());
        tabs.addTab("GC Configuration", createGCConfigTab());
        tabs.addTab("Load Testing",     createLoadTestingTab());
        add(tabs, BorderLayout.CENTER);

        stopAppLoadButton.setEnabled(false);
        stopLoadTestButton.setEnabled(false);
        loadProgressBar.setValue(0);
    }

    private JPanel createMonitorTab() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.add(createControlPanel(), BorderLayout.NORTH);
        p.add(createStatusPanel(),  BorderLayout.CENTER);
        p.add(createLogArea(),      BorderLayout.SOUTH);
        return p;
    }

    private JPanel createGCConfigTab() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel gcMethodPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        gcMethodPanel.setBorder(BorderFactory.createTitledBorder("GC Method"));
        gcMethodPanel.add(new JLabel("GC Method:"));
        gcMethodPanel.add(gcMethodComboBox);
        gcMethodPanel.add(gcLoggingCheckBox);

        JPanel heapPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        heapPanel.setBorder(BorderFactory.createTitledBorder("Heap Configuration"));
        heapPanel.add(new JLabel("Min Heap Size:")); heapPanel.add(minHeapSizeField);
        heapPanel.add(new JLabel("Max Heap Size:")); heapPanel.add(maxHeapSizeField);

        JPanel genPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        genPanel.setBorder(BorderFactory.createTitledBorder("Generation Configuration"));
        genPanel.add(new JLabel("Young Gen Size:")); genPanel.add(youngGenSizeField);
        genPanel.add(new JLabel("Old/New Ratio:")); genPanel.add(oldGenRatioField);

        JPanel advPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        advPanel.setBorder(BorderFactory.createTitledBorder("Advanced GC Tuning"));
        advPanel.add(new JLabel("InitiatingHeapOccupancyPercent:"));
        advPanel.add(initiatingHeapOccupancyPercentField);
        advPanel.add(new JLabel("MaxGCPauseMillis:"));
        advPanel.add(maxGCPauseMillisField);

        JPanel infoPanel = new JPanel(new GridLayout(5, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("Configuration Help"));
        infoPanel.add(new JLabel("Heap sizes: e.g. 64m, 512m, 1g, 2g"));
        infoPanel.add(new JLabel("Young Gen: e.g. 64m, 128m (empty for default)"));
        infoPanel.add(new JLabel("Old/New Ratio: e.g. 2, 3 (empty for default)"));
        infoPanel.add(new JLabel("InitiatingHeapOccupancyPercent: 1‑100"));
        infoPanel.add(new JLabel("MaxGCPauseMillis: target max pause in ms"));

        JPanel btnPanel = new JPanel();
        btnPanel.add(applyGCConfigButton);

        JPanel settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.add(gcMethodPanel);
        settings.add(heapPanel);
        settings.add(genPanel);
        settings.add(advPanel);
        settings.add(infoPanel);
        settings.add(btnPanel);

        root.add(settings, BorderLayout.NORTH);
        return root;
    }

    private JPanel createLoadTestingTab() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel cfgPanel = new JPanel(new GridLayout(7, 2, 5, 5));
        cfgPanel.setBorder(BorderFactory.createTitledBorder("Load Test Configuration"));
        cfgPanel.add(new JLabel("Object Size (KB):")); cfgPanel.add(objectSizeSpinner);
        cfgPanel.add(new JLabel("Objects per Iteration:")); cfgPanel.add(objectsPerIterationSpinner);
        cfgPanel.add(new JLabel("Iteration Delay (ms):")); cfgPanel.add(iterationDelaySpinner);
        cfgPanel.add(new JLabel("GC Threshold (%):")); cfgPanel.add(memoryThresholdSpinner);
        cfgPanel.add(new JLabel("Test Duration (s):")); cfgPanel.add(loadDurationSpinner);
        cfgPanel.add(new JLabel("Load Pattern:")); cfgPanel.add(loadPatternComboBox);
        cfgPanel.add(new JLabel(""));
        JPanel cbRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cbRow.add(autoGCCheckBox); cbRow.add(memoryChurnCheckBox);
        cfgPanel.add(cbRow);

        JPanel ctrlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ctrlPanel.setBorder(BorderFactory.createTitledBorder("Load Test Controls"));
        ctrlPanel.add(startLoadTestButton);
        ctrlPanel.add(stopLoadTestButton);

        JPanel progPanel = new JPanel(new BorderLayout(5, 5));
        progPanel.setBorder(BorderFactory.createTitledBorder("Test Progress"));
        loadProgressBar.setStringPainted(true);
        progPanel.add(loadProgressBar, BorderLayout.CENTER);

        JPanel patternsPanel = new JPanel(new GridLayout(4, 1));
        patternsPanel.setBorder(BorderFactory.createTitledBorder("Load Patterns"));
        patternsPanel.add(new JLabel("• Constant: Steady object creation rate"));
        patternsPanel.add(new JLabel("• Increasing: Gradually increases load over time"));
        patternsPanel.add(new JLabel("• Spike: Periodic high load spikes"));
        patternsPanel.add(new JLabel("• Random: Variable load with random fluctuations"));

        JPanel settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.add(cfgPanel);
        settings.add(ctrlPanel);
        settings.add(progPanel);
        settings.add(patternsPanel);

        root.add(settings, BorderLayout.NORTH);
        return root;
    }

    private JPanel createControlPanel() {
        JPanel p = new JPanel(new GridLayout(2, 1, 5, 5));
        p.setBorder(BorderFactory.createTitledBorder("JVM Controls"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(triggerGCButton);
        row1.add(simulateSTWButton);
        row1.add(memoryLeakButton);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(startAppLoadButton);
        row2.add(stopAppLoadButton);

        p.add(row1); p.add(row2);
        return p;
    }

    private JPanel createStatusPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createTitledBorder("JVM Status"));

        memoryUsageBar.setStringPainted(true);
        memoryUsageBar.setForeground(new Color(0, 100, 0));

        JPanel stats = new JPanel(new GridLayout(4, 1, 5, 5));
        stats.add(memoryUsageLabel);
        stats.add(memoryUsageBar);
        stats.add(gcStatsLabel);
        stats.add(loadStatsLabel);

        p.add(stats, BorderLayout.CENTER);
        return p;
    }

    private JScrollPane createLogArea() {
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logTextArea.setBackground(new Color(240, 240, 240));

        JScrollPane sp = new JScrollPane(logTextArea);
        sp.setBorder(BorderFactory.createTitledBorder("Application Log"));
        sp.setPreferredSize(new Dimension(800, 250));
        return sp;
    }

    // ─── Event Handlers ──────────────────────────────────────────────────────

    private void setupEventHandlers() {
        triggerGCButton.addActionListener(e    -> triggerGC());
        simulateSTWButton.addActionListener(e  -> simulateSTW());
        memoryLeakButton.addActionListener(e   -> createMemoryLeak());
        startAppLoadButton.addActionListener(e -> startAppLoad());
        stopAppLoadButton.addActionListener(e  -> stopAppLoad());
        applyGCConfigButton.addActionListener(e -> applyGCConfiguration());
        startLoadTestButton.addActionListener(e -> startLoadTest());
        stopLoadTestButton.addActionListener(e  -> stopLoadTest());
    }

    // ─── Monitoring Timer (Bug 2 fix) ────────────────────────────────────────

    private void startContinuousUIUpdates() {
        monitoringTimer = new Timer(1000, e -> {
            updateMemoryDisplay();
            updateGCStats();
            updateLoadStats();
        });
        monitoringTimer.start();
    }

    // ─── Display Updates (Bug 3 + O-3 fix) ──────────────────────────────────

    /**
     * Bug 3 fix: uses MemoryMetrics.getHeapUsagePercent() which divides by
     * heap.getMax() (JVM max), not totalMemory() (currently committed).
     * O-3: all values sourced from the MemoryMetrics DTO.
     */
    public void updateMemoryDisplay() {
        MemoryMetrics m = monitor.getCurrentMetrics();
        int pct = (int) m.getHeapUsagePercent();

        memoryUsageBar.setValue(pct);

        String label = String.format(
            "Heap: %.1f%%  (%d MB used / %d MB max)  |  Non-Heap: %d MB  |  Threads: %d",
            m.getHeapUsagePercent(),
            m.getUsedHeap()    / (1024 * 1024),
            m.getMaxHeap()     / (1024 * 1024),
            m.getUsedNonHeap() / (1024 * 1024),
            m.getActiveThreads()
        );
        memoryUsageLabel.setText(label);

        if (pct > 90)      memoryUsageBar.setForeground(Color.RED);
        else if (pct > 70) memoryUsageBar.setForeground(Color.ORANGE);
        else               memoryUsageBar.setForeground(new Color(0, 100, 0));
    }

    /**
     * O-3: GC statistics sourced from MemoryMetrics (real JVM MXBean counts)
     * plus the captured-event list.
     */
    public void updateGCStats() {
        MemoryMetrics m = monitor.getCurrentMetrics();
        List<JVMMonitor.GCEvent> events = monitor.getGCEvents();
        gcStatsLabel.setText(String.format(
            "GC (real): count=%d  pause=%d ms  |  captured events=%d  |  classes=%d",
            m.getGcCount(), m.getGcTime(), events.size(), m.getLoadedClasses()
        ));
    }

    public void updateLoadStats() {
        if (monitor.isLoadTestRunning()) {
            int pct = monitor.getLoadTestProgress();
            loadProgressBar.setValue(pct);
            loadStatsLabel.setText(String.format("Load Test: Running (%d%%)", pct));
        } else {
            loadStatsLabel.setText("Load Test: Not Running");
        }
    }

    // ─── Button Actions ──────────────────────────────────────────────────────

    public void triggerGC() {
        log("Manual GC triggered — event will appear in GC stats.");
        monitor.triggerGC();
    }

    public void simulateSTW() {
        log("Simulating Stop-The-World event (2 s sleep on EDT — UI will freeze)...");
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log("STW simulation done. Duration: " + (System.currentTimeMillis() - start) + " ms");
    }

    public void createMemoryLeak() {
        log("Allocating 50 MB (held in local list — will be released when thread exits)...");
        new Thread(() -> {
            java.util.List<byte[]> leak = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 50; i++) {
                    leak.add(new byte[1024 * 1024]);
                    Thread.sleep(100);
                }
                log("Allocation complete: " + leak.size() + " MB allocated, now releasing.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public void startAppLoad() {
        log("Starting app load simulation...");
        startAppLoadButton.setEnabled(false);
        stopAppLoadButton.setEnabled(true);
        monitor.startAppLoad();
    }

    public void stopAppLoad() {
        log("Stopping app load simulation...");
        startAppLoadButton.setEnabled(true);
        stopAppLoadButton.setEnabled(false);
        monitor.stopAppLoad();
    }

    public void startLoadTest() {
        int objectSizeKB       = (Integer) objectSizeSpinner.getValue();
        int objectsPerIter     = (Integer) objectsPerIterationSpinner.getValue();
        int iterDelay          = (Integer) iterationDelaySpinner.getValue();
        int memThreshold       = (Integer) memoryThresholdSpinner.getValue();
        int loadDuration       = (Integer) loadDurationSpinner.getValue();
        boolean autoGC         = autoGCCheckBox.isSelected();
        boolean memChurn       = memoryChurnCheckBox.isSelected();
        String pattern         = (String)  loadPatternComboBox.getSelectedItem();

        JVMMonitor.LoadTestConfig cfg = new JVMMonitor.LoadTestConfig(
            objectSizeKB, objectsPerIter, iterDelay,
            memThreshold, loadDuration, autoGC, memChurn, pattern
        );

        log("Starting load test:  " + pattern + "  objectSize=" + objectSizeKB + "KB"
            + "  objects/iter=" + objectsPerIter + "  delay=" + iterDelay + "ms"
            + "  duration=" + loadDuration + "s");

        startLoadTestButton.setEnabled(false);
        stopLoadTestButton.setEnabled(true);
        loadProgressBar.setValue(0);
        monitor.startLoadTest(cfg);
    }

    public void stopLoadTest() {
        log("Stopping load test...");
        startLoadTestButton.setEnabled(true);
        stopLoadTestButton.setEnabled(false);
        loadProgressBar.setValue(0);
        monitor.stopLoadTest();
    }

    public void applyGCConfiguration() {
        String gcMethod            = (String) gcMethodComboBox.getSelectedItem();
        boolean enableLogging      = gcLoggingCheckBox.isSelected();
        String minHeap             = minHeapSizeField.getText().trim();
        String maxHeap             = maxHeapSizeField.getText().trim();
        String youngGen            = youngGenSizeField.getText().trim();
        String oldRatio            = oldGenRatioField.getText().trim();
        String ihop                = initiatingHeapOccupancyPercentField.getText().trim();
        String maxPause            = maxGCPauseMillisField.getText().trim();

        monitor.setGCLoggingEnabled(enableLogging);
        monitor.setGCConfiguration(gcMethod, minHeap, maxHeap, youngGen, oldRatio, ihop, maxPause);

        log("Applied GC Config: method=" + gcMethod
            + "  logging=" + (enableLogging ? "ON" : "OFF")
            + "  heap=" + minHeap + "‑" + maxHeap
            + "  IHOP=" + (ihop.isEmpty() ? "default" : ihop + "%")
            + "  maxPause=" + (maxPause.isEmpty() ? "default" : maxPause + "ms"));

        // Show the equivalent JVM flags for reference
        StringBuilder jvmArgs = new StringBuilder("Equivalent JVM flags: java ");
        if (!minHeap.isEmpty())  jvmArgs.append("-Xms").append(minHeap).append(" ");
        if (!maxHeap.isEmpty())  jvmArgs.append("-Xmx").append(maxHeap).append(" ");
        switch (gcMethod) {
            case "G1GC":       jvmArgs.append("-XX:+UseG1GC "); break;
            case "Parallel GC":jvmArgs.append("-XX:+UseParallelGC "); break;
            case "CMS":        jvmArgs.append("-XX:+UseConcMarkSweepGC "); break;
            case "Serial GC":  jvmArgs.append("-XX:+UseSerialGC "); break;
            case "ZGC":        jvmArgs.append("-XX:+UseZGC "); break;
            case "Shenandoah": jvmArgs.append("-XX:+UseShenandoahGC "); break;
        }
        if (!youngGen.isEmpty()) jvmArgs.append("-XX:NewSize=").append(youngGen).append(" ");
        if (!oldRatio.isEmpty()) jvmArgs.append("-XX:NewRatio=").append(oldRatio).append(" ");
        if (!ihop.isEmpty())     jvmArgs.append("-XX:InitiatingHeapOccupancyPercent=").append(ihop).append(" ");
        if (!maxPause.isEmpty()) jvmArgs.append("-XX:MaxGCPauseMillis=").append(maxPause).append(" ");
        if (enableLogging)       jvmArgs.append("-Xlog:gc*:file=gc.log:time ");
        log(jvmArgs.toString());
    }

    // ─── Log helper (with trim to prevent unbounded growth) ──────────────────

    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logTextArea.append("[" + ts + "] " + message + "\n");

            // Trim oldest content when document exceeds LOG_MAX_CHARS
            Document doc = logTextArea.getDocument();
            if (doc.getLength() > LOG_MAX_CHARS) {
                try {
                    String text    = doc.getText(0, doc.getLength());
                    int cutPoint   = text.indexOf('\n', doc.getLength() / 2);
                    if (cutPoint > 0) doc.remove(0, cutPoint + 1);
                } catch (BadLocationException ignored) { /* safe to ignore */ }
            }

            logTextArea.setCaretPosition(doc.getLength());
        });
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public void cleanup() {
        if (monitoringTimer != null) monitoringTimer.stop();
        monitor.close();
    }
}
