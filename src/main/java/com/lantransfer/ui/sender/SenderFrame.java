package com.lantransfer.ui.sender;

import com.lantransfer.core.service.TaskRegistry;
import com.lantransfer.core.service.TransferSenderService;
import com.lantransfer.ui.common.ProgressCellRenderer;
import com.lantransfer.ui.common.TaskTableModel;
import com.lantransfer.core.util.UserPreferences;
import com.lantransfer.core.util.UserPreferences.SenderSettings;

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
import java.nio.file.Path;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SenderFrame extends JFrame {
    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final TaskTableModel tableModel = new TaskTableModel();
    private final TransferSenderService senderService = new TransferSenderService(taskRegistry, tableModel);

    public SenderFrame() {
        super("Lan Transfer - Sender");
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

        try {
            senderService.start(0); // bind to any available port
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        sendButton.addActionListener(e -> {
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            String folder = folderField.getText().trim();
            if (host.isEmpty() || portText.isEmpty() || folder.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Host, port, and folder are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int port = Integer.parseInt(portText);
                senderService.sendFolder(Path.of(folder), new InetSocketAddress(host, port), tableModel);
                UserPreferences.saveSenderSettings(new SenderSettings(host, port, folder));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Port must be a number.", "Validation", JOptionPane.WARNING_MESSAGE);
            }
        });

        setSize(720, 480);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                senderService.close();
            }
        });
    }
}
