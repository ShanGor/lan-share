package com.lantransfer;

import com.lantransfer.ui.ModeSelectorFrame;

import javax.swing.SwingUtilities;

public final class LanTransferApp {

    private LanTransferApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ModeSelectorFrame::new);
    }
}
