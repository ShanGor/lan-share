package com.lantransfer;

import com.lantransfer.ui.ModeSelectorFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

public final class LanTransferApp {

    private LanTransferApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
                     UnsupportedLookAndFeelException e) {
                // fall back silently
            }
            new ModeSelectorFrame();
        });
    }
}
