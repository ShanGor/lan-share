# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Building the Project
```bash
# Clean build with dependencies
mvn clean package

# Build the runnable JAR with all dependencies
mvn clean package
# Output: target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Running the Application
```bash
# Run via Maven
mvn exec:java -Dexec.mainClass=com.lantransfer.LanTransferApp

# Run the packaged JAR
java -jar target/lan-transfer-0.1.0-SNAPSHOT.jar

# Run the fat JAR with dependencies
java -jar target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Requirements
- Java 17
- Maven 3.9+
- Network access to Maven Central (downloads Netty 4.2.7.Final and BouncyCastle)

## Architecture Overview

This is a LAN file transfer tool with a Swing UI that uses QUIC protocol (via Netty) for reliable file transfers. The architecture follows a dual-port design:

### Core Architecture Pattern
1. **Control Port (Configurable)**: Used strictly for task negotiation - transfer offers, accept/reject decisions, and cancellation signals
2. **Data Port (Ephemeral)**: Once a receiver accepts a task, it spins up a dedicated QUIC data port and shares the ephemeral port back to the sender. All file metadata, chunks, and acknowledgments ride on this separate channel

### Key Components

**Protocol Layer (`com.lantransfer.core.protocol`)**:
- Message-based protocol with defined types: `TransferOfferMessage`, `TransferResponseMessage`, `FileMetaMessage`, `FileChunkMessage`, `ChunkAckMessage`, `FileCompleteMessage`, `TaskCompleteMessage`, `TaskCancelMessage`
- `ProtocolIO` handles serialization/deserialization
- Messages use a common `ProtocolMessage` base with type identification via `ProtocolMessageType`

**Transport Layer (`com.lantransfer.core.net`)**:
- `QuicMessageUtil` - Central utility for QUIC channel management, message sending/receiving
- Migrated from TCP/UDP endpoints to QUIC for better reliability and performance
- Uses Netty's QUIC implementation with BouncyCastle for SSL/TLS

**Service Layer (`com.lantransfer.core.service`)**:
- `TransferSenderService` - Manages outgoing transfers, chunking, and retry logic
- `TransferReceiverService` - Handles incoming transfers, chunk validation, and file reconstruction
- `TaskRegistry` - Central task coordination and state management
- `ChecksumService` - MD5 validation for file integrity

**Data Model (`com.lantransfer.core.model`)**:
- `TransferTask` - Represents a transfer operation with metadata
- `TransferStatus` - Enum for task states (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED)
- `FileChunkBitmap` - BitSet-based tracking of received chunks for resume capability

**UI Layer (`com.lantransfer.ui`)**:
- `ModeSelectorFrame` - Entry point for choosing sender/receiver mode
- `SenderFrame`/`ReceiverFrame` - Main UI windows with Swing components
- `TaskTableModel` - Table model for displaying transfer progress
- `ProgressCellRenderer` - Custom cell renderer for progress bars

### Key Design Patterns

**Chunked Transfer with Bitmap Tracking**:
- Files are split into configurable chunk sizes (default 64KB)
- `FileChunkBitmap` uses BitSet to track received chunks
- Supports resume after restart by persisting bitmap state
- Missing chunks are requested via `FileResendRequestMessage`

**Reliability Features**:
- Chunk acknowledgment with `ChunkAckMessage` supporting ranges for efficiency
- Simple retry/backoff for file-send-done and missing-chunk requests
- MD5 validation per-file with `FileCompleteMessage` signaling
- Graceful shutdown closes UDP/scheduler resources

**Security Measures**:
- Path traversal protection via destination root check
- QUIC provides built-in encryption and integrity
- Self-signed certificates generated via `CertificateBuilder` for QUIC SSL context

**State Persistence**:
- Last-used sender (host/port/folder) and receiver (port/destination) settings stored in `~/.lan-transfer`
- Bitmap persistence enables transfer resume after restart

### QUIC Implementation Details
- Uses Netty's QUIC codec with `NioDatagramChannel` as transport
- `InsecureQuicTokenHandler` for development (bypasses TLS validation)
- Separate QUIC channels for control and data streams
- `QuicStreamType.BIDIRECTIONAL` for full-duplex communication

### Message Flow
1. Sender sends `TransferOfferMessage` on control port
2. Receiver responds with `TransferResponseMessage` (accept/reject) including ephemeral data port
3. If accepted: Both peers establish QUIC connection on data port
4. Sender sends `FileMetaMessage` for each file
5. File data transferred via `FileChunkMessage` with acknowledgments
6. `FileCompleteMessage` signals per-file completion
7. `TaskCompleteMessage` or `TaskCancelMessage` ends the transfer

### Current Limitations
- Basic reliability (sliding window/ACK not fully implemented)
- No persistent task store (tasks lost on restart except bitmap state)
- Limited error surface/logging to UI
- Single transfer per receiver instance (no concurrent transfers)