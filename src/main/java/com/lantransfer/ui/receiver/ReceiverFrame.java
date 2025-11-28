package com.lantransfer.ui.receiver;

import com.lantransfer.core.service.TaskRegistry;
import com.lantransfer.core.service.TransferReceiverService;
import com.lantransfer.core.util.UserPreferences;
import com.lantransfer.core.util.UserPreferences.ReceiverSettings;
import com.lantransfer.ui.common.ProgressCellRenderer;
import com.lantransfer.ui.common.TaskTableModel;

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

public class ReceiverFrame extends JFrame {
    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final TaskTableModel tableModel = new TaskTableModel();
    private final TransferReceiverService receiverService = new TransferReceiverService(taskRegistry, tableModel);
    private final JLabel statusLabel = new JLabel("Status: Idle");

    public ReceiverFrame() {
        super("Lan Transfer - Receiver");
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
                    currentFile = tableModel.getCurrentFile(taskId);
                    if (currentFile != null && !currentFile.isEmpty()) {
                        break; // Use first active task found
                    }
                }
            }
            // If no active task file found, try selected row as fallback
            if ((currentFile == null || currentFile.isEmpty()) && table.getSelectedRow() >= 0) {
                int selectedRow = table.getSelectedRow();
                String taskId = (String) tableModel.getValueAt(selectedRow, 0);
                currentFile = tableModel.getCurrentFile(taskId);
            }
            if (currentFile != null && !currentFile.isEmpty()) {
                currentFileLabel.setText(currentFile);
            } else {
                currentFileLabel.setText(" ");
            }
        }).start();

        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                destField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        listenButton.addActionListener(e -> {
            String portText = portField.getText().trim();
            String dest = destField.getText().trim();
            if (portText.isEmpty() || dest.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Port and destination folder are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int port = Integer.parseInt(portText);
                receiverService.start(port, Path.of(dest));
                listenButton.setEnabled(false);
                stopButton.setEnabled(true);
                portField.setEnabled(false);
                browseButton.setEnabled(false);
                statusLabel.setText("Status: Listening on port " + port);
                UserPreferences.saveReceiverSettings(new ReceiverSettings(port, dest));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Port must be a number.", "Validation", JOptionPane.WARNING_MESSAGE);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to start listening: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        stopButton.addActionListener(e -> {
            receiverService.close();
            listenButton.setEnabled(true);
            stopButton.setEnabled(false);
            portField.setEnabled(true);
            browseButton.setEnabled(true);
            statusLabel.setText("Status: Stopped");
        });

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
