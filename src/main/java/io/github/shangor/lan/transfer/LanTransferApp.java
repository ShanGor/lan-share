package io.github.shangor.lan.transfer;

import io.github.shangor.lan.transfer.ui.ModeSelectorFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

public final class LanTransferApp {
    private static final Logger logger = LoggerFactory.getLogger(LanTransferApp.class);

    private LanTransferApp() {
    }

    public static void main(String[] args) {
        logger.info("Starting Lan Transfer Application");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                logger.debug("Look and feel set to system default");
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
                     UnsupportedLookAndFeelException e) {
                logger.warn("Failed to set system look and feel, falling back to default", e);
            }
            logger.debug("Creating mode selector frame");
            new ModeSelectorFrame();
        });
    }
}
