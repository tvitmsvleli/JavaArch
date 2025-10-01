package com.jvmplayground;

import com.jvmplayground.ui.JVMMonitorPanel;
import com.jvmplayground.ui.MainFrame;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = new MainFrame();
            mainFrame.setVisible(true);

            // Add shutdown hook to ensure proper cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (mainFrame.getMonitorPanel() != null) {
                    mainFrame.getMonitorPanel().cleanup();
                }
            }));
        });
    }
}