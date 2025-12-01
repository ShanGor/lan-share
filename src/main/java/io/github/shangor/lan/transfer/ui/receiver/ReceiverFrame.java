package io.github.shangor.lan.transfer.ui.receiver;

import io.github.shangor.lan.transfer.core.service.TaskRegistry;
import io.github.shangor.lan.transfer.core.service.TransferReceiverService;
import io.github.shangor.lan.transfer.core.util.UserPreferences;
import io.github.shangor.lan.transfer.core.util.UserPreferences.ReceiverSettings;
import io.github.shangor.lan.transfer.ui.common.ProgressCellRenderer;
import io.github.shangor.lan.transfer.ui.common.TaskTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.LogManager;

public class ReceiverFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(ReceiverFrame.class);

    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final TaskTableModel tableModel = new TaskTableModel();
    private final TransferReceiverService receiverService = new TransferReceiverService(taskRegistry, tableModel);
    private final JLabel statusLabel = new JLabel("Status: Idle");

    public ReceiverFrame() {
        super("Lan Transfer - Receiver");

        // Initialize receiver-specific logging
        try {
            LogManager.getLogManager().readConfiguration(
                ReceiverFrame.class.getResourceAsStream("/receiver-logging.properties")
            );
            logger.info("Receiver logging initialized");
        } catch (Exception e) {
            System.err.println("Failed to load receiver logging configuration: " + e.getMessage());
        }
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel portLabel = new JLabel("Listen Port:");
        JTextField portField = new JTextField("9000");
        JLabel destLabel = new JLabel("Destination Folder:");
        JTextField destField = new JTextField();
        destField.setEditable(false);
        JButton browseButton = new JButton("Browse...");
        JButton listenButton = new JButton("Start Listening");
        JButton stopButton = new JButton("Stop Listening");
        stopButton.setEnabled(false);

        ReceiverSettings prefs = UserPreferences.loadReceiverSettings();
        portField.setText(Integer.toString(prefs.port()));
        destField.setText(prefs.destination());

        gbc.gridx = 0; gbc.gridy = 0; form.add(portLabel, gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1; form.add(portField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; form.add(destLabel, gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1; form.add(destField, gbc);
        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0; form.add(browseButton, gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0; form.add(listenButton, gbc);
        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0; form.add(stopButton, gbc);
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0; form.add(statusLabel, gbc);

        add(form, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.getColumnModel().getColumn(2).setCellRenderer(new ProgressCellRenderer());
        // Adjust column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(60); // Task ID
        table.getColumnModel().getColumn(1).setPreferredWidth(80); // Status
        table.getColumnModel().getColumn(2).setPreferredWidth(100); // Progress
        table.getColumnModel().getColumn(3).setPreferredWidth(80); // Speed
        table.getColumnModel().getColumn(4).setPreferredWidth(80); // Transferred
        table.getColumnModel().getColumn(5).setPreferredWidth(80); // Total
        table.getColumnModel().getColumn(6).setPreferredWidth(80); // Duration
        table.getColumnModel().getColumn(7).setPreferredWidth(200); // Sending Path
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Add detail panel to show current file
        JPanel detailPanel = new JPanel();
        detailPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Current File"));
        JLabel currentFileLabel = new JLabel(" ");
        detailPanel.add(currentFileLabel);
        add(detailPanel, BorderLayout.SOUTH);

        new Timer(1000, e -> {
            tableModel.refresh();
            // Update current file display for active task
            String currentFile = "";
            // Look for any active/in-progress task
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String taskId = (String) tableModel.getValueAt(i, 0);
                String status = tableModel.getValueAt(i, 1).toString();
                if (status.equals("IN_PROGRESS") || status.equals("RESENDING")) {
                    // Get the current file being processed
                    currentFile = tableModel.getCurrentFile(taskId);
                    if (currentFile != null && !currentFile.isEmpty()) {
                        break; // Use first active task found
                    }
                }
            }
            // If no active task file found, try selected row as fallback
            if ((currentFile == null || currentFile.isEmpty()) && table.getSelectedRow() >= 0) {
                int selectedRow = table.getSelectedRow();
                String selectedTaskId = (String) tableModel.getValueAt(selectedRow, 0);
                currentFile = tableModel.getCurrentFile(selectedTaskId);
            }
            if (currentFile != null && !currentFile.isEmpty()) {
                currentFileLabel.setText(currentFile);
            } else {
                currentFileLabel.setText(" ");
            }
        }).start();

        browseButton.addActionListener(e -> {
            logger.debug("Browse button clicked");
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String selectedPath = chooser.getSelectedFile().getAbsolutePath();
                destField.setText(selectedPath);
                logger.info("Destination folder selected: {}", selectedPath);
            } else {
                logger.debug("Browse action cancelled");
            }
        });

        listenButton.addActionListener(e -> {
            logger.info("Start listening button clicked");
            String portText = portField.getText().trim();
            String dest = destField.getText().trim();
            if (portText.isEmpty() || dest.isEmpty()) {
                logger.warn("Validation failed: port or destination empty");
                JOptionPane.showMessageDialog(this, "Port and destination folder are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int port = Integer.parseInt(portText);
                logger.info("Starting receiver service on port {} with destination {}", port, dest);
                receiverService.start(port, Path.of(dest));
                listenButton.setEnabled(false);
                stopButton.setEnabled(true);
                portField.setEnabled(false);
                browseButton.setEnabled(false);
                statusLabel.setText("Status: Listening on port " + port);
                UserPreferences.saveReceiverSettings(new ReceiverSettings(port, dest));
                logger.info("Receiver service started successfully");
            } catch (NumberFormatException ex) {
                logger.warn("Invalid port number: {}", portText);
                JOptionPane.showMessageDialog(this, "Port must be a number.", "Validation", JOptionPane.WARNING_MESSAGE);
            } catch (InterruptedException ex) {
                logger.error("Start listening interrupted", ex);
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                logger.error("Failed to start listening", ex);
                JOptionPane.showMessageDialog(this, "Failed to start listening: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        stopButton.addActionListener(e -> {
            logger.info("Stop listening button clicked");
            try {
                receiverService.close();
                logger.info("Receiver service stopped");
            } catch (Exception ex) {
                logger.error("Error stopping receiver service", ex);
            }
            listenButton.setEnabled(true);
            stopButton.setEnabled(false);
            portField.setEnabled(true);
            browseButton.setEnabled(true);
            statusLabel.setText("Status: Stopped");
        });

        // Auto-start listening on the default port if destination is set
        if (!prefs.destination().isEmpty()) {
            try {
                receiverService.start(prefs.port(), Path.of(prefs.destination()));
                listenButton.setEnabled(false);
                stopButton.setEnabled(true);
                portField.setEnabled(false);
                browseButton.setEnabled(false);
                statusLabel.setText("Status: Listening on port " + prefs.port());
            } catch (Exception ex) {
                // Log the error but continue with manual start
                statusLabel.setText("Status: Auto-start failed, click Start Listening");
            }
        }

        setSize(720, 480);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                receiverService.close();
            }
        });
    }
}
