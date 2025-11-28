# Lan Transfer – Requirements

Single-purpose LAN file transfer tool with GUI. Sender chooses a folder, Receiver accepts or rejects, files move over UDP with file-level MD5 verification and XOR-obscured chunk bodies.

## Scope and Goals
- Reliable folder/file transfer between two devices on the same LAN.
- Manual addressing: sender supplies receiver host/IP and port.
- GUI-only operation via Java Swing; no CLI required initially.
- Transport: UDP using Netty (`io.netty:netty-all:4.2.7.Final`).
- Platform: Java 17 with Maven build.
- Network: IPv4-only; IPv6 support is not required.
- Security model: trusted LAN; traffic is unencrypted and unauthenticated.
- No explicit limit on individual file size or total transfer size (subject to OS/filesystem limits).
- Transfers must be resumable after application restarts by persisting state on disk.

## Operating Modes
- **Receiver mode**: Listens on configured UDP port; prompts user to accept/reject incoming transfer offers; writes files to chosen destination folder.
- **Sender mode**: User selects source folder, enters receiver host/port, sends transfer offer, tracks task progress/status.
- The app may run in either mode; it is acceptable to run two app instances (one per laptop) rather than a dual-mode UI.

## User Flow (Happy Path)
1) Receiver starts in receiver mode, chooses listen port and default destination directory, begins listening.
2) Sender starts in sender mode, chooses a folder, enters receiver host/IP and port, clicks Send.
3) Receiver gets a transfer offer dialog (folder name, total size, file count) and Accept/Reject.
4) If rejected, sender marks task as Rejected and stops.
5) If accepted, sender keeps using its generated 16-bit task id (shown in the UI). Receiver echoes it in the response, and transfer begins.
6) Sender walks the folder recursively, sending each file.
7) For each file, sender sends metadata (relative path, size, MD5) before data chunks.
8) Receiver writes chunks in order, de-obscures chunk bodies using the XOR byte, and validates MD5 after full file.
9) If file MD5 fails, receiver requests a resend of that file; sender restarts that file.
10) When all files succeed, receiver signals completion; sender marks task Completed.

## Functional Requirements
- **Task management (sender)**: Show list with task id, receiver, status (Pending, In-Progress, Rejected, Failed, Completed, Resending, Canceled), per-file progress, total progress, start/end timestamps. Allow cancel of an active task.
- **Listening (receiver)**: Configure listen port; show current listening status; start/stop listening; list active/finished transfers with task id, sender address, status, per-file progress.
- **Session memory**: Remember last-used sender inputs (host, port, folder) and receiver inputs (port, destination) and reload them at startup.
- **Folder traversal**: Recursive; preserve relative paths when writing on receiver.
- **Task scope**: Sender can initiate a task for either a single file or a directory. Task metadata includes task type (F=file, D=directory) and the sender’s listening port.
- **File metadata**: For each entry send type (F/D), name/path, MD5 hex (for files), and size (for files). Receiver uses MD5 to verify after transfer.
- **Chunking**:
  - Protocol task id: unsigned 16-bit number (2 bytes). Sender derives it from the low 16 bits of the current timestamp (retrying on collision). Every protocol message carries this task id.
  - File id: unsigned 32-bit number (4 bytes) assigned incrementally per file within a task.
  - Chunk sequence: unsigned 64-bit number (8 bytes) per file, starting at 0.
  - Chunk header size: 15 bytes = taskId(2) + fileId(4) + chunkSeq(8) + xorKey(1) + bodySize(2). Body remains up to 1024 bytes, XOR’ed with the xor key; receiver applies the same XOR to recover data.
- **Checksum rules**:
  - No per-chunk CRC32. Integrity is ensured by MD5 per file after full receipt; on failure receiver requests full-file resend.
- **Resend behavior** (pull-based):
  - Receiver maintains a bitmap file per transferring file on disk; 1 bit per chunk (1 = received, 0 = missing). Bitmap persists until file verified and is reused when resuming after restarts.
  - Receiver drives chunk cadence: for file entries, it computes total chunks from file size and issues `CHUNK_REQUEST` messages (taskId + fileId + chunkSeq). If a requested chunk is not received within 2 seconds, it re-requests that chunk. Sender only sends chunks in response to requests; it no longer emits `FILE_SEND_DONE`.
  - Receiver acknowledges each chunk via `CHUNK_ACK` message containing taskId(2) + fileId(4) + chunkSeq(8). Duplicate chunks are detected via the bitmap and re-ACKed without rewriting data; chunk writes execute asynchronously via worker threads to keep ACK latency low even when disk is slow.
  - Directories are signaled via metadata type=D; receiver creates the directory and sends an ACK (`FILE_COMPLETE` success=true) so the sender can proceed.
  - Receiver may request resend of a specific file; sender re-sends metadata and data for that file.
  - When a file passes MD5 verification, receiver sends a file-done signal (`FILE_COMPLETE`) so sender and receiver can log completion and update task progress.
  - Chunk-level retransmission strategy required to cope with UDP loss (see Reliability).
- **Storage**: Receiver writes to destination directory chosen by user; create subfolders to mirror relative paths; avoid overwriting partial files from failed attempts (e.g., use temp names until success). Persist per-task metadata and bitmap files to allow resume after restart.
- **Concurrency**: Support multiple simultaneous tasks; the receiver may process multiple transfers in parallel, subject to resource limits.
- **Limits**: Support files larger than memory; stream to disk.

## Protocol (UDP Messages)
Message bodies use a simple binary framing; all multi-byte fields in big-endian. Suggested message types:
- `TRANSFER_OFFER`: sender→receiver; includes task request id, folder name, total bytes, file count.
- `TRANSFER_RESPONSE`: receiver→sender; Accept/Reject plus receiver-generated task id on accept.
- `FILE_META`: sender→receiver; includes task id, entry type (F/D), relative path, file size (files), MD5 (files), file index/order.
- `FILE_CHUNK`: sender→receiver; payload containing the 15-byte header (taskId, fileId, chunkSeq, xor key, body size) plus XOR’ed body bytes, sent only in response to `CHUNK_REQUEST`.
- `CHUNK_REQUEST`: receiver→sender; requests a specific chunk (taskId(2), fileId(4), chunkSeq(8)).
- `CHUNK_ACK`: receiver→sender; acknowledges a single chunk (taskId(2), fileId(4), chunkSeq(8)).
- `FILE_COMPLETE`: receiver→sender; acknowledges file receipt and MD5 result (OK/FAIL). On OK, both sides log completion and update task lists.
- `FILE_RESEND_REQUEST`: receiver→sender; identifies file needing resend.
- `TASK_COMPLETE`: receiver→sender; all files received and validated.
- `TASK_CANCEL`: sender→receiver; stop transfer.
- `HEARTBEAT/ACK`: optional; used for reliability/keepalive (see Reliability).

## Reliability and Ordering (UDP)
- Implement pull-based acknowledgements:
  - Receiver issues `CHUNK_REQUEST` per needed chunk; sender transmits only when requested. Missing or delayed chunks are re-requested after a timeout (default 2 seconds).
  - Receiver tracks missing sequences via bitmap and requests retransmission if any requested chunks are still outstanding when the file should be complete.
- Retransmission timeouts and max retries should be configurable (defaults reasonable for LAN).
- Ensure in-order reassembly per file; drop duplicates.
- Handle out-of-order delivery.
- If retries exhausted for a file, mark the task as `Failed` and notify user.

## UI Requirements (Swing)
- Sender main view: source folder picker, receiver host/port fields, Send button, task list with progress bars, status, and cancel control; detail pane showing current file, speed, ETA. Display live transfer speed per task (KB/s) based on recent data.
- Receiver main view: listen port field, destination folder picker, Start/Stop Listening buttons, incoming offer dialog (accept/reject), task list with status/progress, detail pane with current file and errors.
- Notifications: surface rejections, failures, checksum mismatches, and completed tasks.
- Input validation: host/port required; show clear errors.
- UI should adopt the platform’s look & feel (e.g., use `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())`) so controls match the host OS styling.
- Show current processing file path(s): Both sender and receiver UIs should display the full path of the file currently being processed in the details pane.

## Non-Functional Requirements
- Performance: Aim for efficient LAN throughput with minimal overhead; allow tuning of chunk window size and timeouts.
- Reliability: Must survive packet loss and mild reordering; detect corruption via file-level MD5.
- Usability: Clear progress and error messages; no silent failures.
- Logging: Structured logs per task (info/warn/error) for troubleshooting; include task id and file path.
- Security: LAN use; no encryption/authentication; validate paths to prevent directory traversal.
- Resume: Persist enough state (e.g., task list, per-file progress, bitmap files) to resume in-progress tasks after application restart.
- Packaging: Provide a runnable artifact that includes all dependencies (fat jar).

## Assumptions and Decisions
- IPv4-only support; IPv6 is out of scope.
- No encryption or authentication; traffic is plaintext on a trusted LAN.
- No explicit constraints on maximum file size or total transfer size beyond OS/filesystem limits.
- Simultaneous tasks are allowed; the receiver can handle multiple active transfers in parallel.
- Partial progress must be persisted across app restarts so transfers can be resumed.
- No protocol-level bandwidth throttling or per-file concurrency caps are required.
- No special filename/path normalization (e.g., Windows ↔ UNIX separators, Unicode normalization); use the platform’s native path handling while preventing directory traversal outside the destination root.
