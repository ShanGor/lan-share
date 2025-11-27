package com.lantransfer.ui.common;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class TaskTableModel extends AbstractTableModel {
    private final List<TransferTask> tasks = new ArrayList<>();
    private final String[] columns = {"Task ID", "Status", "Progress", "Transferred", "Total"};

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
            case 2 -> Double.class;
            case 3, 4 -> Long.class;
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
            case 3 -> formatBytes(task.getBytesTransferred());
            case 4 -> formatBytes(task.getTotalBytes());
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
        fireTableDataChanged();
    }

    private String formatBytes(long value) {
        return String.format("%,d", value);
    }
}
