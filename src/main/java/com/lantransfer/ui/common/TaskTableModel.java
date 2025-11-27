package com.lantransfer.ui.common;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskTableModel extends AbstractTableModel {
    private final List<TransferTask> tasks = new ArrayList<>();
    private final String[] columns = {"Task ID", "Status", "Progress", "Speed (KB/s)", "Transferred", "Total"};
    private final Map<String, Long> lastBytes = new HashMap<>();
    private final Map<String, Long> lastTimes = new HashMap<>();
    private final Map<String, Double> speeds = new HashMap<>();

    @Override
    public int getRowCount() {
        return tasks.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
            case 2, 3 -> Double.class;
            default -> String.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        TransferTask task = tasks.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> task.getTaskId();
            case 1 -> task.getStatus();
            case 2 -> progress(task);
            case 3 -> speed(task);
            case 4 -> formatBytes(task.getBytesTransferred());
            case 5 -> formatBytes(task.getTotalBytes());
            default -> "";
        };
    }

    private double progress(TransferTask task) {
        long total = task.getTotalBytes();
        if (total <= 0) {
            return 0d;
        }
        return (task.getBytesTransferred() * 100.0d) / total;
    }

    public void addTask(TransferTask task) {
        tasks.add(task);
        lastBytes.put(task.getTaskId(), task.getBytesTransferred());
        lastTimes.put(task.getTaskId(), System.currentTimeMillis());
        speeds.put(task.getTaskId(), 0d);
        fireTableDataChanged();
    }

    public void updateStatus(String taskId, TransferStatus status) {
        for (TransferTask task : tasks) {
            if (task.getTaskId().equals(taskId)) {
                task.setStatus(status);
                break;
            }
        }
        fireTableDataChanged();
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        for (TransferTask task : tasks) {
            String id = task.getTaskId();
            long currentBytes = task.getBytesTransferred();
            Long prevBytes = lastBytes.get(id);
            Long prevTime = lastTimes.get(id);
            if (prevBytes != null && prevTime != null) {
                long deltaBytes = currentBytes - prevBytes;
                long deltaTime = now - prevTime;
                if (deltaTime > 0 && deltaBytes >= 0) {
                    double kbps = (deltaBytes / 1024.0d) / (deltaTime / 1000.0d);
                    speeds.put(id, kbps);
                }
            }
            lastBytes.put(id, currentBytes);
            lastTimes.put(id, now);
        }
        fireTableDataChanged();
    }

    private double speed(TransferTask task) {
        return speeds.getOrDefault(task.getTaskId(), 0d);
    }

    private String formatBytes(long value) {
        return String.format("%,d", value);
    }
}
