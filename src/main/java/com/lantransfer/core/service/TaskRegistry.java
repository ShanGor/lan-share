package com.lantransfer.core.service;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskRegistry {
    private final Map<String, TransferTask> tasks = new ConcurrentHashMap<>();

    public TransferTask add(TransferTask task) {
        tasks.put(task.getTaskId(), task);
        return task;
    }

    public TransferTask get(String id) {
        return tasks.get(id);
    }

    public Collection<TransferTask> all() {
        return Collections.unmodifiableCollection(tasks.values());
    }

    public void updateStatus(String taskId, TransferStatus status) {
        TransferTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(status);
        }
    }
}
