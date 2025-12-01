package io.github.shangor.lan.transfer.ui.sender;

import io.github.shangor.lan.transfer.core.model.TransferStatus;
import io.github.shangor.lan.transfer.core.model.TransferTask;
import io.github.shangor.lan.transfer.core.service.TaskRegistry;
import io.github.shangor.lan.transfer.core.service.TransferSenderService;
import io.github.shangor.lan.transfer.ui.common.ProgressCellRenderer;
import io.github.shangor.lan.transfer.ui.common.TaskTableModel;
import io.github.shangor.lan.transfer.core.util.UserPreferences;
import io.github.shangor.lan.transfer.core.util.UserPreferences.SenderSettings;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.LogManager;

public class SenderFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(SenderFrame.class);

    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final TaskTableModel tableModel = new TaskTableModel();
    private final TransferSenderService senderService = new TransferSenderService(taskRegistry, tableModel);

    public SenderFrame() {
        super("Lan Transfer - Sender");

        // Initialize sender-specific logging
        try {
            LogManager.getLogManager().readConfiguration(
                SenderFrame.class.getResourceAsStream("/sender-logging.properties")
            );
            logger.info("Sender logging initialized");
        } catch (Exception e) {
            System.err.println("Failed to load sender logging configuration: " + e.getMessage());
        }
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel hostLabel = new JLabel("Receiver Host/IP:");
        JTextField hostField = new JTextField();
        JLabel portLabel = new JLabel("Port:");
        JTextField portField = new JTextField("9000");
        JLabel folderLabel = new JLabel("Folder:");
        JTextField folderField = new JTextField();
        folderField.setEditable(false);
        JButton browseButton = new JButton("Browse...");
        JButton sendButton = new JButton("Send");

        SenderSettings senderPrefs = UserPreferences.loadSenderSettings();
        hostField.setText(senderPrefs.host());
        portField.setText(Integer.toString(senderPrefs.port()));
        folderField.setText(senderPrefs.folder());

        gbc.gridx = 0; gbc.gridy = 0; form.add(hostLabel, gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1; form.add(hostField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; form.add(portLabel, gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1; form.add(portField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; form.add(folderLabel, gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1; form.add(folderField, gbc);
        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0; form.add(browseButton, gbc);
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0; form.add(sendButton, gbc);

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

        JButton pauseTaskButton = new JButton("Pause");
        JButton resumeTaskButton = new JButton("Resume");
        JButton continueTaskButton = new JButton("Continue");
        JButton removeTaskButton = new JButton("Remove");

        JPanel actionsPanel = new JPanel();
        actionsPanel.add(pauseTaskButton);
        actionsPanel.add(resumeTaskButton);
        actionsPanel.add(continueTaskButton);
        actionsPanel.add(removeTaskButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(detailPanel, BorderLayout.CENTER);
        bottomPanel.add(actionsPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);

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

        senderService.start(0); // QUIC client no bind needed

        browseButton.addActionListener(e -> {
            logger.debug("Browse button clicked");
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String selectedPath = chooser.getSelectedFile().getAbsolutePath();
                folderField.setText(selectedPath);
                logger.info("Source folder selected: {}", selectedPath);
            } else {
                logger.debug("Browse action cancelled");
            }
        });

        sendButton.addActionListener(e -> {
            logger.info("Send button clicked");
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            String folder = folderField.getText().trim();
            if (host.isEmpty() || portText.isEmpty() || folder.isEmpty()) {
                logger.warn("Validation failed: host, port, or folder empty");
                JOptionPane.showMessageDialog(this, "Host, port, and folder are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int port = Integer.parseInt(portText);
                logger.info("Starting transfer to {}:{} from folder {}", host, port, folder);
                senderService.sendFolder(Path.of(folder), new InetSocketAddress(host, port), tableModel);
                UserPreferences.saveSenderSettings(new SenderSettings(host, port, folder));
                logger.info("Transfer initiated successfully");
            } catch (NumberFormatException ex) {
                logger.warn("Invalid port number: {}", portText);
                JOptionPane.showMessageDialog(this, "Port must be a number.", "Validation", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                logger.error("Failed to start transfer", ex);
                JOptionPane.showMessageDialog(this, "Failed to start transfer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        pauseTaskButton.addActionListener(e -> {
            TransferTask task = getSelectedTask(table);
            if (task == null) {
                JOptionPane.showMessageDialog(this, "Select a task to pause.", "Pause", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!isActiveStatus(task.getStatus())) {
                JOptionPane.showMessageDialog(this, "Only active tasks can be paused.", "Pause", JOptionPane.WARNING_MESSAGE);
                return;
            }
            senderService.pauseTask(task.getTaskId());
        });

        resumeTaskButton.addActionListener(e -> {
            TransferTask task = getSelectedTask(table);
            if (task == null) {
                JOptionPane.showMessageDialog(this, "Select a task to resume.", "Resume", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            senderService.resumeTask(task.getTaskId());
        });

        continueTaskButton.addActionListener(e -> {
            TransferTask task = getSelectedTask(table);
            if (task == null) {
                JOptionPane.showMessageDialog(this, "Select a task to continue.", "Continue", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!task.hasRemoteEndpoint()) {
                JOptionPane.showMessageDialog(this, "Selected task does not have remote information to continue.", "Continue", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!Files.exists(task.getSource())) {
                JOptionPane.showMessageDialog(this, "Source folder is no longer available.", "Continue", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                senderService.sendFolder(task.getSource(),
                        new InetSocketAddress(task.getRemoteHost(), task.getRemotePort()), tableModel);
            } catch (Exception ex) {
                logger.error("Failed to continue task {}", task.getTaskId(), ex);
                JOptionPane.showMessageDialog(this, "Failed to continue task: " + ex.getMessage(), "Continue", JOptionPane.ERROR_MESSAGE);
            }
        });

        removeTaskButton.addActionListener(e -> {
            TransferTask task = getSelectedTask(table);
            if (task == null) {
                JOptionPane.showMessageDialog(this, "Select a task to remove.", "Remove", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (isActiveStatus(task.getStatus())) {
                JOptionPane.showMessageDialog(this, "Stop or pause the task before removing it.", "Remove", JOptionPane.WARNING_MESSAGE);
                return;
            }
            taskRegistry.remove(task.getTaskId());
            tableModel.removeTask(task.getTaskId());
        });

        setSize(720, 480);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                logger.info("Sender window closing");
                try {
                    senderService.close();
                    logger.info("Sender service closed");
                } catch (Exception ex) {
                    logger.error("Error closing sender service", ex);
                }
            }
        });
    }

    private TransferTask getSelectedTask(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return tableModel.getTaskAt(row);
    }

    private boolean isActiveStatus(TransferStatus status) {
        return status == TransferStatus.IN_PROGRESS
                || status == TransferStatus.RESENDING
                || status == TransferStatus.PAUSED
                || status == TransferStatus.PENDING;
    }
}
