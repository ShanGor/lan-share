# Plan to Fix Transfer Speed and UI Issues

## Problem Analysis
1.  **Sender reaches 100% too quickly**: The sender reads files and writes to the Netty channel without checking if the channel is writable (`isWritable()`). This causes the data to be buffered in memory (Netty's outbound buffer) rather than sent over the network. The progress bar reflects "bytes buffered" rather than "bytes sent".
2.  **Receiver is slow**: The receiver accepts data as fast as the network allows and queues disk write tasks into an unbounded thread pool (`Executors.newFixedThreadPool`). This can lead to high memory usage and doesn't provide backpressure to the sender.
3.  **UI not updating**: Since the sender buffers everything instantly, it thinks the task is done (or nearly done) and moves to the next file/state, while the actual data transfer is still happening in the background.

## Implemented Solution

### 1. Implement Flow Control in Sender (`TransferSenderService.java`)
Modified the `streamFile` method to check `ctx.dataStreamChannel.isWritable()` before sending chunks. If the channel is not writable (buffer full), the sender thread pauses briefly. This ensures the sender only reads and sends data at the rate the network can handle.

### 2. Implement Backpressure in Receiver (`TransferReceiverService.java`)
Replaced the unbounded `workerPool` with a `ThreadPoolExecutor` using a **bounded queue** and a **CallerRunsPolicy**.
*   **Bounded Queue**: Limits the number of pending disk write tasks.
*   **CallerRunsPolicy**: If the queue is full, the IO thread (which receives the data) will execute the write task itself. This effectively pauses network reads, triggering QUIC flow control, which in turn signals the sender to slow down.

## Detailed Changes

### `TransferReceiverService.java`
*   **Import**: Added `java.util.concurrent.ThreadPoolExecutor`, `java.util.concurrent.LinkedBlockingQueue`.
*   **Change**: Replaced `Executors.newFixedThreadPool` with a custom `ThreadPoolExecutor`.
    ```java
    private final ExecutorService workerPool = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(50), // Limit pending writes
            new ThreadPoolExecutor.CallerRunsPolicy() // Apply backpressure
    );
    ```

### `TransferSenderService.java`
*   **Change**: In `streamFile` method, inside the `while` loop:
    ```java
    // Flow control
    while (!ctx.dataStreamChannel.isWritable()) {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
    }
    ```

## Verification
*   Sender progress bar moves smoothly and reflects actual transfer speed.
*   "Current File" in UI updates correctly and stays visible while the file is actually transferring.
*   Receiver is not overwhelmed.