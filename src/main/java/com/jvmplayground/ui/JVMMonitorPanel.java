package com.jvmplayground.ui;

import com.jvmplayground.monitor.JVMMonitor;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

public class JVMMonitorPanel extends JPanel {
    private final JVMMonitor monitor;

    // UI Components
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

    // GC Configuration Components
    private final JComboBox<String> gcMethodComboBox;
    private final JCheckBox gcLoggingCheckBox;
    private final JTextField minHeapSizeField;
    private final JTextField maxHeapSizeField;
    private final JTextField youngGenSizeField;
    private final JTextField oldGenRatioField;
    private final JTextField initiatingHeapOccupancyPercentField;
    private final JTextField maxGCPauseMillisField;
    private final JButton applyGCConfigButton;

    // Load Testing Configuration Components
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

    // Monitoring state
    private Timer monitoringTimer;

    public JVMMonitorPanel() {
        this.monitor = new JVMMonitor();

        // Initialize all UI components
        this.triggerGCButton = new JButton("Trigger GC");
        this.simulateSTWButton = new JButton("Simulate STW");
        this.memoryLeakButton = new JButton("Create Memory Leak");
        this.startAppLoadButton = new JButton("Start App Load");
        this.stopAppLoadButton = new JButton("Stop App Load");

        this.logTextArea = new JTextArea(15, 60);
        this.memoryUsageBar = new JProgressBar(0, 100);
        this.memoryUsageLabel = new JLabel("Memory Usage: 0% (0 MB / 0 MB)");
        this.gcStatsLabel = new JLabel("GC Events: 0 | Total GC Time: 0 ms");
        this.loadStatsLabel = new JLabel("Load Test: Not Running");

        // GC Configuration components
        this.gcMethodComboBox = new JComboBox<>(new String[]{
                "G1GC", "Parallel GC", "CMS", "Serial GC", "ZGC", "Shenandoah"
        });
        this.gcLoggingCheckBox = new JCheckBox("Enable GC Logging", true);
        this.minHeapSizeField = new JTextField("64m", 8);
        this.maxHeapSizeField = new JTextField("512m", 8);
        this.youngGenSizeField = new JTextField("", 8);
        this.oldGenRatioField = new JTextField("", 8);
        this.initiatingHeapOccupancyPercentField = new JTextField("45", 6);
        this.maxGCPauseMillisField = new JTextField("200", 6);
        this.applyGCConfigButton = new JButton("Apply GC Config");

        // Load Testing Configuration components
        this.objectSizeSpinner = new JSpinner(new SpinnerNumberModel(1024, 1, 102400, 1024)); // 1KB to 100MB
        this.objectsPerIterationSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
        this.iterationDelaySpinner = new JSpinner(new SpinnerNumberModel(500, 10, 5000, 100)); // ms
        this.memoryThresholdSpinner = new JSpinner(new SpinnerNumberModel(80, 10, 95, 5)); // %
        this.loadDurationSpinner = new JSpinner(new SpinnerNumberModel(300, 30, 3600, 30)); // seconds
        this.autoGCCheckBox = new JCheckBox("Auto GC", true);
        this.memoryChurnCheckBox = new JCheckBox("Memory Churn", true);
        this.loadPatternComboBox = new JComboBox<>(new String[]{
                "Constant Load", "Increasing Load", "Spike Load", "Random Load"
        });
        this.startLoadTestButton = new JButton("Start Load Test");
        this.stopLoadTestButton = new JButton("Stop Load Test");
        this.loadProgressBar = new JProgressBar(0, 100);

        initializeUI();
        setupEventHandlers();

        // Apply initial GC logging setting and start continuous UI updates
        monitor.setGCLoggingEnabled(true);
        startContinuousUIUpdates();

        log("JVM Monitor started. Continuous monitoring is active.");
        log("GC logging is enabled. Logs are being written to gc-logs/ directory.");
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create main panel with tabbed interface
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Monitor", createMonitorTab());
        tabbedPane.addTab("GC Configuration", createGCConfigTab());
        tabbedPane.addTab("Load Testing", createLoadTestingTab());

        add(tabbedPane, BorderLayout.CENTER);

        // Initial state
        stopAppLoadButton.setEnabled(false);
        stopLoadTestButton.setEnabled(false);
        loadProgressBar.setValue(0);
    }

    private JPanel createMonitorTab() {
        JPanel monitorPanel = new JPanel(new BorderLayout(5, 5));

        // Create control panel with buttons
        JPanel controlPanel = createControlPanel();

        // Create status panel with memory usage and GC stats
        JPanel statusPanel = createStatusPanel();

        // Create log area
        JScrollPane logScrollPane = createLogArea();

        monitorPanel.add(controlPanel, BorderLayout.NORTH);
        monitorPanel.add(statusPanel, BorderLayout.CENTER);
        monitorPanel.add(logScrollPane, BorderLayout.SOUTH);

        return monitorPanel;
    }

    private JPanel createGCConfigTab() {
        JPanel configPanel = new JPanel(new BorderLayout(10, 10));
        configPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // GC Method Selection
        JPanel gcMethodPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        gcMethodPanel.setBorder(BorderFactory.createTitledBorder("GC Method"));
        gcMethodPanel.add(new JLabel("GC Method:"));
        gcMethodPanel.add(gcMethodComboBox);
        gcMethodPanel.add(gcLoggingCheckBox);

        // Heap Size Configuration
        JPanel heapSizePanel = new JPanel(new GridLayout(2, 2, 5, 5));
        heapSizePanel.setBorder(BorderFactory.createTitledBorder("Heap Configuration"));

        heapSizePanel.add(new JLabel("Min Heap Size:"));
        heapSizePanel.add(minHeapSizeField);
        heapSizePanel.add(new JLabel("Max Heap Size:"));
        heapSizePanel.add(maxHeapSizeField);

        // Generation Configuration
        JPanel genConfigPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        genConfigPanel.setBorder(BorderFactory.createTitledBorder("Generation Configuration"));

        genConfigPanel.add(new JLabel("Young Gen Size:"));
        genConfigPanel.add(youngGenSizeField);
        genConfigPanel.add(new JLabel("Old/New Ratio:"));
        genConfigPanel.add(oldGenRatioField);

        // Advanced GC Configuration
        JPanel advancedGCPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        advancedGCPanel.setBorder(BorderFactory.createTitledBorder("Advanced GC Tuning"));

        advancedGCPanel.add(new JLabel("InitiatingHeapOccupancyPercent:"));
        advancedGCPanel.add(initiatingHeapOccupancyPercentField);
        advancedGCPanel.add(new JLabel("MaxGCPauseMillis:"));
        advancedGCPanel.add(maxGCPauseMillisField);

        // Info labels
        JPanel infoPanel = new JPanel(new GridLayout(5, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("Configuration Help"));
        infoPanel.add(new JLabel("Heap sizes: e.g., 64m, 512m, 1g, 2g"));
        infoPanel.add(new JLabel("Young Gen: e.g., 64m, 128m (empty for default)"));
        infoPanel.add(new JLabel("Old/New Ratio: e.g., 2, 3 (empty for default)"));
        infoPanel.add(new JLabel("InitiatingHeapOccupancyPercent: 1-100 (start concurrent cycle earlier)"));
        infoPanel.add(new JLabel("MaxGCPauseMillis: target max pause time in ms"));

        // Apply button
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(applyGCConfigButton);

        // Combine all panels
        JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        settingsPanel.add(gcMethodPanel);
        settingsPanel.add(heapSizePanel);
        settingsPanel.add(genConfigPanel);
        settingsPanel.add(advancedGCPanel);
        settingsPanel.add(infoPanel);
        settingsPanel.add(buttonPanel);

        configPanel.add(settingsPanel, BorderLayout.NORTH);

        return configPanel;
    }

    private JPanel createLoadTestingTab() {
        JPanel loadTestPanel = new JPanel(new BorderLayout(10, 10));
        loadTestPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Load Configuration
        JPanel loadConfigPanel = new JPanel(new GridLayout(7, 2, 5, 5));
        loadConfigPanel.setBorder(BorderFactory.createTitledBorder("Load Test Configuration"));

        loadConfigPanel.add(new JLabel("Object Size (KB):"));
        loadConfigPanel.add(objectSizeSpinner);
        loadConfigPanel.add(new JLabel("Objects per Iteration:"));
        loadConfigPanel.add(objectsPerIterationSpinner);
        loadConfigPanel.add(new JLabel("Iteration Delay (ms):"));
        loadConfigPanel.add(iterationDelaySpinner);
        loadConfigPanel.add(new JLabel("GC Threshold (%):"));
        loadConfigPanel.add(memoryThresholdSpinner);
        loadConfigPanel.add(new JLabel("Test Duration (s):"));
        loadConfigPanel.add(loadDurationSpinner);
        loadConfigPanel.add(new JLabel("Load Pattern:"));
        loadConfigPanel.add(loadPatternComboBox);
        loadConfigPanel.add(new JLabel("")); // Empty cell
        JPanel checkBoxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        checkBoxPanel.add(autoGCCheckBox);
        checkBoxPanel.add(memoryChurnCheckBox);
        loadConfigPanel.add(checkBoxPanel);

        // Load Test Controls
        JPanel loadControlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        loadControlPanel.setBorder(BorderFactory.createTitledBorder("Load Test Controls"));
        loadControlPanel.add(startLoadTestButton);
        loadControlPanel.add(stopLoadTestButton);

        // Progress bar
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(BorderFactory.createTitledBorder("Test Progress"));
        loadProgressBar.setStringPainted(true);
        progressPanel.add(loadProgressBar, BorderLayout.CENTER);

        // Load patterns description
        JPanel patternsPanel = new JPanel(new GridLayout(4, 1));
        patternsPanel.setBorder(BorderFactory.createTitledBorder("Load Patterns"));
        patternsPanel.add(new JLabel("• Constant: Steady object creation rate"));
        patternsPanel.add(new JLabel("• Increasing: Gradually increases load over time"));
        patternsPanel.add(new JLabel("• Spike: Periodic high load spikes"));
        patternsPanel.add(new JLabel("• Random: Variable load with random fluctuations"));

        // Combine all panels
        JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        settingsPanel.add(loadConfigPanel);
        settingsPanel.add(loadControlPanel);
        settingsPanel.add(progressPanel);
        settingsPanel.add(patternsPanel);

        loadTestPanel.add(settingsPanel, BorderLayout.NORTH);

        return loadTestPanel;
    }

    private JPanel createControlPanel() {
        JPanel controlPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        controlPanel.setBorder(BorderFactory.createTitledBorder("JVM Controls"));

        // First row of buttons
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(triggerGCButton);
        row1.add(simulateSTWButton);
        row1.add(memoryLeakButton);

        // Second row of buttons - App Load
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(startAppLoadButton);
        row2.add(stopAppLoadButton);

        controlPanel.add(row1);
        controlPanel.add(row2);

        return controlPanel;
    }

    private JPanel createStatusPanel() {
        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusPanel.setBorder(BorderFactory.createTitledBorder("JVM Status"));

        // Configure memory usage bar
        memoryUsageBar.setStringPainted(true);
        memoryUsageBar.setForeground(new Color(0, 100, 0));

        // Create stats panel
        JPanel statsPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        statsPanel.add(memoryUsageLabel);
        statsPanel.add(memoryUsageBar);
        statsPanel.add(gcStatsLabel);
        statsPanel.add(loadStatsLabel);

        statusPanel.add(statsPanel, BorderLayout.CENTER);

        return statusPanel;
    }

    private JScrollPane createLogArea() {
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logTextArea.setBackground(new Color(240, 240, 240));

        JScrollPane scrollPane = new JScrollPane(logTextArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Application Log"));
        scrollPane.setPreferredSize(new Dimension(800, 250));

        return scrollPane;
    }

    private void setupEventHandlers() {
        // Trigger GC Button
        triggerGCButton.addActionListener(e -> triggerGC());

        // Simulate STW Button
        simulateSTWButton.addActionListener(e -> simulateSTW());

        // Memory Leak Button
        memoryLeakButton.addActionListener(e -> createMemoryLeak());

        // App Load Buttons
        startAppLoadButton.addActionListener(e -> startAppLoad());
        stopAppLoadButton.addActionListener(e -> stopAppLoad());

        // Apply GC Config Button
        applyGCConfigButton.addActionListener(e -> applyGCConfiguration());

        // Load Test Buttons
        startLoadTestButton.addActionListener(e -> startLoadTest());
        stopLoadTestButton.addActionListener(e -> stopLoadTest());
    }

    private void startContinuousUIUpdates() {
        // Start UI update timer that runs continuously
        monitoringTimer = new Timer(1000, e -> {
            updateMemoryDisplay();
            updateGCStats();
            updateLoadStats();
        });
        monitoringTimer.start();
    }

    public void triggerGC() {
        log("Manual GC triggered...");
        monitor.triggerGC();
    }

    public void simulateSTW() {
        log("Simulating Stop-The-World (STW) event...");

        long startTime = System.currentTimeMillis();

        try {
            Thread.sleep(2000); // 2 seconds STW
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        long duration = System.currentTimeMillis() - startTime;
        log("STW simulation completed. Duration: " + duration + " ms");
    }

    public void createMemoryLeak() {
        log("Creating memory leak...");

        new Thread(() -> {
            java.util.List<byte[]> memoryLeak = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 50; i++) {
                    memoryLeak.add(new byte[1024 * 1024]); // 1MB chunks
                    Thread.sleep(100);
                }
                log("Memory leak created successfully. " + memoryLeak.size() + " MB allocated.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public void startAppLoad() {
        log("Starting application load simulation with current GC configuration...");
        startAppLoadButton.setEnabled(false);
        stopAppLoadButton.setEnabled(true);

        monitor.startAppLoad();
    }

    public void stopAppLoad() {
        log("Stopping application load simulation...");
        startAppLoadButton.setEnabled(true);
        stopAppLoadButton.setEnabled(false);

        monitor.stopAppLoad();
    }

    public void startLoadTest() {
        int objectSizeKB = (Integer) objectSizeSpinner.getValue();
        int objectsPerIteration = (Integer) objectsPerIterationSpinner.getValue();
        int iterationDelay = (Integer) iterationDelaySpinner.getValue();
        int memoryThreshold = (Integer) memoryThresholdSpinner.getValue();
        int loadDuration = (Integer) loadDurationSpinner.getValue();
        boolean autoGC = autoGCCheckBox.isSelected();
        boolean memoryChurn = memoryChurnCheckBox.isSelected();
        String loadPattern = (String) loadPatternComboBox.getSelectedItem();

        // Use the LoadTestConfig from JVMMonitor class
        JVMMonitor.LoadTestConfig config = new JVMMonitor.LoadTestConfig(
                objectSizeKB, objectsPerIteration, iterationDelay,
                memoryThreshold, loadDuration, autoGC, memoryChurn, loadPattern
        );

        log("Starting load test with configuration:");
        log("  Object Size: " + objectSizeKB + " KB");
        log("  Objects per Iteration: " + objectsPerIteration);
        log("  Iteration Delay: " + iterationDelay + " ms");
        log("  GC Threshold: " + memoryThreshold + "%");
        log("  Test Duration: " + loadDuration + " seconds");
        log("  Auto GC: " + (autoGC ? "Enabled" : "Disabled"));
        log("  Memory Churn: " + (memoryChurn ? "Enabled" : "Disabled"));
        log("  Load Pattern: " + loadPattern);

        startLoadTestButton.setEnabled(false);
        stopLoadTestButton.setEnabled(true);
        loadProgressBar.setValue(0);

        monitor.startLoadTest(config);
    }

    public void stopLoadTest() {
        log("Stopping load test...");
        startLoadTestButton.setEnabled(true);
        stopLoadTestButton.setEnabled(false);
        loadProgressBar.setValue(0);

        monitor.stopLoadTest();
    }

    public void applyGCConfiguration() {
        String gcMethod = (String) gcMethodComboBox.getSelectedItem();
        boolean enableLogging = gcLoggingCheckBox.isSelected();
        String minHeap = minHeapSizeField.getText().trim();
        String maxHeap = maxHeapSizeField.getText().trim();
        String youngGen = youngGenSizeField.getText().trim();
        String oldRatio = oldGenRatioField.getText().trim();
        String initiatingHeapPercent = initiatingHeapOccupancyPercentField.getText().trim();
        String maxGCPauseMillis = maxGCPauseMillisField.getText().trim();

        // Apply GC logging setting immediately
        monitor.setGCLoggingEnabled(enableLogging);

        // Apply GC configuration with new parameters
        monitor.setGCConfiguration(gcMethod, minHeap, maxHeap, youngGen, oldRatio, initiatingHeapPercent, maxGCPauseMillis);

        StringBuilder config = new StringBuilder();
        config.append("Applied GC Configuration:\n");
        config.append("  GC Method: ").append(gcMethod).append("\n");
        config.append("  GC Logging: ").append(enableLogging ? "Enabled" : "Disabled").append("\n");
        config.append("  Min Heap: ").append(minHeap.isEmpty() ? "Default" : minHeap).append("\n");
        config.append("  Max Heap: ").append(maxHeap.isEmpty() ? "Default" : maxHeap).append("\n");
        config.append("  Young Gen: ").append(youngGen.isEmpty() ? "Default" : youngGen).append("\n");
        config.append("  Old/New Ratio: ").append(oldRatio.isEmpty() ? "Default" : oldRatio).append("\n");
        config.append("  InitiatingHeapOccupancyPercent: ").append(initiatingHeapPercent.isEmpty() ? "Default" : initiatingHeapPercent).append("\n");
        config.append("  MaxGCPauseMillis: ").append(maxGCPauseMillis.isEmpty() ? "Default" : maxGCPauseMillis + " ms");

        log(config.toString());

        if (enableLogging) {
            log("GC logging is ACTIVE. GC events will be written to gc-logs/gc_<timestamp>.log");
        }

        // Generate the JVM command line for reference
        generateJVMCommandLine(gcMethod, enableLogging, minHeap, maxHeap, youngGen, oldRatio, initiatingHeapPercent, maxGCPauseMillis);
    }

    private void generateJVMCommandLine(String gcMethod, boolean enableLogging,
                                        String minHeap, String maxHeap,
                                        String youngGen, String oldRatio,
                                        String initiatingHeapPercent, String maxGCPauseMillis) {
        StringBuilder cmd = new StringBuilder("java ");

        // Heap sizes
        if (!minHeap.isEmpty()) cmd.append("-Xms").append(minHeap).append(" ");
        if (!maxHeap.isEmpty()) cmd.append("-Xmx").append(maxHeap).append(" ");

        // GC Method
        switch (gcMethod) {
            case "G1GC":
                cmd.append("-XX:+UseG1GC ");
                break;
            case "Parallel GC":
                cmd.append("-XX:+UseParallelGC ");
                break;
            case "CMS":
                cmd.append("-XX:+UseConcMarkSweepGC ");
                break;
            case "Serial GC":
                cmd.append("-XX:+UseSerialGC ");
                break;
            case "ZGC":
                cmd.append("-XX:+UseZGC ");
                break;
            case "Shenandoah":
                cmd.append("-XX:+UseShenandoahGC ");
                break;
        }

        // Generation sizes
        if (!youngGen.isEmpty()) cmd.append("-XX:NewSize=").append(youngGen).append(" ");
        if (!oldRatio.isEmpty()) cmd.append("-XX:NewRatio=").append(oldRatio).append(" ");

        // Advanced GC parameters
        if (!initiatingHeapPercent.isEmpty()) cmd.append("-XX:InitiatingHeapOccupancyPercent=").append(initiatingHeapPercent).append(" ");
        if (!maxGCPauseMillis.isEmpty()) cmd.append("-XX:MaxGCPauseMillis=").append(maxGCPauseMillis).append(" ");

        // GC Logging
        if (enableLogging) {
            cmd.append("-Xlog:gc*:file=gc.log:time ");
        }

        log("JVM Command Line for future reference:\n" + cmd.toString());
    }

    public void updateMemoryDisplay() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        int usagePercent = totalMemory > 0 ? (int) ((usedMemory * 100) / totalMemory) : 0;

        memoryUsageBar.setValue(usagePercent);

        String memoryText = String.format("Memory Usage: %d%% (%d MB / %d MB)",
                usagePercent,
                usedMemory / (1024 * 1024),
                totalMemory / (1024 * 1024));
        memoryUsageLabel.setText(memoryText);

        if (usagePercent > 90) {
            memoryUsageBar.setForeground(Color.RED);
        } else if (usagePercent > 70) {
            memoryUsageBar.setForeground(Color.ORANGE);
        } else {
            memoryUsageBar.setForeground(new Color(0, 100, 0));
        }
    }

    public void updateGCStats() {
        try {
            List<JVMMonitor.GCEvent> events = monitor.getGCEvents();
            int totalEvents = events.size();
            long totalGCTime = events.stream().mapToLong(e -> e.getDurationMs()).sum();

            gcStatsLabel.setText(String.format("GC Events: %d | Total GC Time: %d ms",
                    totalEvents, totalGCTime));
        } catch (Exception e) {
            // Handle case where getGCEvents might not be available yet
        }
    }

    public void updateLoadStats() {
        if (monitor.isLoadTestRunning()) {
            int progress = monitor.getLoadTestProgress();
            loadProgressBar.setValue(progress);
            loadStatsLabel.setText(String.format("Load Test: Running (%d%%)", progress));
        } else {
            loadStatsLabel.setText("Load Test: Not Running");
        }
    }

    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logTextArea.append("[" + timestamp + "] " + message + "\n");
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
    }

    // Cleanup method
    public void cleanup() {
        if (monitoringTimer != null) {
            monitoringTimer.stop();
        }
        monitor.close();
    }
}