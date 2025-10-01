package com.jvmplayground.ui;

import javax.swing.*;
import java.awt.*;

public class MainFrame extends JFrame {
    private final JVMMonitorPanel monitorPanel;

    public MainFrame() {
        setTitle("JVM Memory Monitor & GC Analyzer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        monitorPanel = new JVMMonitorPanel();
        add(monitorPanel, BorderLayout.CENTER);
    }

    public JVMMonitorPanel getMonitorPanel() {
        return monitorPanel;
    }
}