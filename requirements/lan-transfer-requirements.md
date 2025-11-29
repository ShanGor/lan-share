# Lan Transfer – Requirements

Single-purpose LAN file transfer tool with GUI. Sender chooses a folder, Receiver accepts or rejects, files move over QUIC with file-level MD5 verification. The receiver’s configured port is only for task negotiation; after a task is accepted, the receiver advertises a dedicated data port for the actual file metadata/chunk traffic.

## Scope and Goals
- Reliable folder/file transfer between two devices on the same LAN.
- Manual addressing: sender supplies receiver host/IP and port.
- GUI-only operation via Java Swing; no CLI required initially.
- Transport: QUIC using Netty (`io.netty:netty-all:4.2.7.Final`) for built-in reliability. Control traffic (offers/responses/cancel) stays on the configured listen port; each accepted task spins up a separate ephemerally bound QUIC data port that the sender connects to for metadata/chunk transfer.
- Platform: Java 17 with Maven build.
- Network: IPv4-only; IPv6 support is not required.
- Security model: trusted LAN; traffic is unencrypted and unauthenticated.
- No explicit limit on individual file size or total transfer size (subject to OS/filesystem limits).
- Transfers must be resumable after application restarts by persisting state on disk.

## Operating Modes
- **Receiver mode**: Listens on configured QUIC port; prompts user to accept/reject incoming transfer offers; writes files to chosen destination folder.
- **Sender mode**: User selects source folder, enters receiver host/port, sends transfer offer, tracks task progress/status.
- The app may run in either mode; it is acceptable to run two app instances (one per laptop) rather than a dual-mode UI.

## User Flow (Happy Path)
1) Receiver starts in receiver mode, chooses listen port and default destination directory, begins listening.
2) Sender starts in sender mode, chooses a folder, enters receiver host/IP and port, clicks Send.
3) Receiver gets a transfer offer dialog (folder name, total size, file count) and Accept/Reject.
4) If rejected, sender marks task as Rejected and stops.
5) If accepted, sender keeps using its generated 16-bit task id (shown in the UI). Receiver echoes it in the response, and transfer begins.
6) Sender walks the folder recursively, sending each file.
7) For each file, sender sends metadata (relative path, size, MD5) before full file content.
8) Receiver writes full files and validates MD5 after complete file receipt.
9) If file MD5 fails, receiver requests a resend of that file; sender retransmits the complete file.
10) When all files succeed, receiver signals completion; sender marks task Completed.

## Functional Requirements
- **Task management (sender)**: Show list with task id, receiver, status (Pending, In-Progress, Rejected, Failed, Completed, Resending, Canceled), per-file progress, total progress, start/end timestamps. Allow cancel of an active task.
- **Listening (receiver)**: Configure listen port; show current listening status; start/stop listening; list active/finished transfers with task id, sender address, status, per-file progress.
- **Session memory**: Remember last-used sender inputs (host, port, folder) and receiver inputs (port, destination) and reload them at startup.
- **Folder traversal**: Recursive; preserve relative paths when writing on receiver.
- **Task scope**: Sender can initiate a task for either a single file or a directory. Task metadata includes task type (F=file, D=directory) and the sender’s listening port.
- **File metadata**: For each entry send type (F/D), name/path, MD5 hex (for files), and size (for files). Receiver uses MD5 to verify after transfer.
- **Transport**:
  - Protocol task id: unsigned 16-bit number (2 bytes). Sender derives it from the low 16 bits of the current timestamp (retrying on collision). Every protocol message carries this task id.
  - File id: unsigned 32-bit number (4 bytes) assigned incrementally per file within a task.
  - QUIC streams handle reliability, flow control, and ordering automatically.
- **Checksum rules**:
  - Integrity is ensured by MD5 per file after full receipt; on failure receiver requests full-file resend.
- **Resend behavior**:
  - On MD5 verification failure or size mismatch, receiver requests resend of the specific file.
  - Sender retransmits the complete file content when requested.
  - Directories are signaled via metadata type=D; receiver creates the directory and sends an ACK (`FILE_COMPLETE` success=true) so the sender can proceed.
  - When a file passes MD5 verification, receiver sends a file-done signal (`FILE_COMPLETE`) so sender and receiver can log completion and update task progress.
- **Storage**: Receiver writes to destination directory chosen by user; create subfolders to mirror relative paths; avoid overwriting partial files from failed attempts (e.g., use temp names until success). Persist per-task metadata to allow resume after restart.
- **Concurrency**: Support multiple simultaneous tasks; the receiver may process multiple transfers in parallel, subject to resource limits.
- **Limits**: Support files larger than memory; stream to disk using QUIC's built-in flow control.

## Protocol (QUIC Messages)
Message bodies use a simple binary framing with length prefix; all multi-byte fields in big-endian. Control-channel messages:
- `TRANSFER_OFFER`: sender→receiver; includes task id request, folder name, total bytes, file count.
- `TRANSFER_RESPONSE`: receiver→sender; Accept/Reject plus ephemeral data-port assignment on accept.
- `TASK_CANCEL`: sender→receiver (or receiver→sender) to abort a task.

Data-channel messages (sent over the per-task port negotiated above):
- `FILE_META`: sender→receiver; includes task id, entry type (F/D), relative path, file size (files), MD5 (files), file index/order.
- `FILE_CHUNK`: sender→receiver; chunked file payloads with XOR-obscured bodies and sequence numbers.
- `META_ACK`: receiver→sender; confirms metadata receipt.
- `CHUNK_ACK`: receiver→sender; selective ack ranges for received chunks.
- `FILE_COMPLETE`: receiver→sender; acknowledges file receipt and MD5 result (OK/FAIL).
- `FILE_RESEND_REQUEST`: receiver→sender; identifies file needing resend (if MD5 fails).
- `TASK_COMPLETE`: receiver→sender; all files received and validated.

## Reliability and Ordering (QUIC)
- Use QUIC's built-in reliability and ordering features instead of custom implementation.
- QUIC handles packet loss, retransmission, and ordering automatically.
- If file verification fails, use application-level resend requests.
- File-level verification with size check and MD5 validation after complete file transfer.

## UI Requirements (Swing)
- Sender main view: source folder picker, receiver host/port fields, Send button, task list with progress bars, status, and cancel control; detail pane showing current file, speed, ETA. Display live transfer speed per task (KB/s) based on recent data.
- Receiver main view: listen port field, destination folder picker, Start/Stop Listening buttons, incoming offer dialog (accept/reject), task list with status/progress, detail pane with current file and errors.
- Notifications: surface rejections, failures, checksum mismatches, and completed tasks.
- Input validation: host/port required; show clear errors.
- UI should adopt the platform’s look & feel (e.g., use `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())`) so controls match the host OS styling.
- Show current processing file path(s): Both sender and receiver UIs should display the full path of the file currently being processed in the details pane.

## Non-Functional Requirements
- Performance: Aim for efficient LAN throughput with minimal overhead using QUIC's built-in flow control.
- Reliability: Must survive network conditions using QUIC's built-in reliability; detect corruption via file-level MD5.
- Usability: Clear progress and error messages; no silent failures.
- Logging: Structured logs per task (info/warn/error) for troubleshooting; include task id and file path.
- Security: LAN use; no encryption/authentication; validate paths to prevent directory traversal.
- Resume: Persist enough state (e.g., task list, per-file progress) to resume in-progress tasks after application restart.
- Packaging: Provide a runnable artifact that includes all dependencies (fat jar).

## Assumptions and Decisions
- IPv4-only support; IPv6 is out of scope.
- No encryption or authentication; traffic is plaintext on a trusted LAN.
- No explicit constraints on maximum file size or total transfer size beyond OS/filesystem limits.
- Simultaneous tasks are allowed; the receiver can handle multiple active transfers in parallel.
- Partial progress must be persisted across app restarts so transfers can be resumed.
- No protocol-level bandwidth throttling or per-file concurrency caps are required.
- No special filename/path normalization (e.g., Windows ↔ UNIX separators, Unicode normalization); use the platform's native path handling while preventing directory traversal outside the destination root.
- Leverage QUIC's features for reliability instead of implementing custom chunking and retransmission logic.
