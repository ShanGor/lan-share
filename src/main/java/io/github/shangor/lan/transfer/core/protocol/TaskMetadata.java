package io.github.shangor.lan.transfer.core.protocol;

public class TaskMetadata {
    private final String taskId;
    private final String folderName;
    private final long totalBytes;
    private final int fileCount;

    public TaskMetadata(String taskId, String folderName, long totalBytes, int fileCount) {
        this.taskId = taskId;
        this.folderName = folderName;
        this.totalBytes = totalBytes;
        this.fileCount = fileCount;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getFolderName() {
        return folderName;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getFileCount() {
        return fileCount;
    }
}
