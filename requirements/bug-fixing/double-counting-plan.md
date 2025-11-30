# Plan to Fix Double Counting and Add Duration

## Problem Analysis
1.  **Double Counting of Bytes**: The sender's `TransferSenderService.java` counts bytes in two places:
    *   `writeToChannel`: `ctx.task.addBytesTransferred(data.length);` (Line 526)
    *   `streamFile`: `ctx.task.addBytesTransferred(body.length);` (Line 416)
    *   Since `streamFile` calls `sendData`, which calls `writeToChannel`, the bytes for file chunks are counted twice.
2.  **Missing Duration**: The UI does not show the total duration of the transfer.

## Implemented Solution

### 1. Fix Double Counting in `TransferSenderService.java`
*   Removed `ctx.task.addBytesTransferred(data.length);` from `writeToChannel`.
*   Relied solely on `streamFile` to count bytes for file chunks.
*   This avoids counting protocol overhead and double counting.

### 2. Add Duration Column to UI
*   **`TransferTask.java`**: Added `getDuration()` method.
    *   If status is `IN_PROGRESS` or `RESENDING`, duration = `now - createdAt`.
    *   If status is `COMPLETED`, `FAILED`, `CANCELED`, duration = `updatedAt - createdAt`.
*   **`TaskTableModel.java`**:
    *   Added "Duration" to `columns` array.
    *   Updated `getValueAt` to return formatted duration string (e.g., "HH:mm:ss").
    *   Updated `getColumnClass` if necessary.

## Detailed Changes

### `TransferSenderService.java`
*   **Method `writeToChannel`**: Removed `ctx.task.addBytesTransferred(data.length);`.

### `TransferTask.java`
*   Added `getDuration()` method returning `java.time.Duration`.

### `TaskTableModel.java`
*   Added "Duration" column.
*   Implemented duration formatting logic.

## Verification
*   Sender "Transferred" matches "Total" when complete.
*   "Duration" column shows elapsed time and stops updating when task is done.