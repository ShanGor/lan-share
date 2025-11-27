# Lan Transfer Project Context

## Project Overview

Lan Transfer is a Java-based LAN file transfer tool that enables reliable folder/file transfers between devices on the same local network. The application features a Swing-based GUI with separate sender and receiver modes, using UDP transport via Netty for communication.

### Key Features
- **GUI-based operation**: Java Swing interface for sender and receiver modes
- **UDP transport**: Uses Netty 4.2.7.Final for UDP communication
- **Reliable transfer**: Implements chunked file transfer with MD5 verification
- **Resumable tasks**: Persists transfer state to allow resumption after restart
- **Chunk obfuscation**: XOR-obscured chunk bodies for basic obfuscation
- **Bitmap-based resend**: Tracks missing chunks and requests retransmission
- **Path traversal protection**: Validates destination paths to prevent directory traversal

### Architecture
- **Sender mode**: Initiates file transfers by selecting a source folder and specifying receiver host/port
- **Receiver mode**: Listens on a configured UDP port and accepts/rejects incoming transfer offers
- **Protocol**: Binary UDP messages with specific message types for transfer negotiation, metadata, chunks, and control signals
- **Storage**: Persists transfer state and chunk bitmaps to enable resume functionality

## Building and Running

### Prerequisites
- Java 17
- Maven 3.9+

### Build Commands
```bash
# Clean build
mvn clean package

# Build runnable jar with dependencies
mvn clean package
# Output: target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Run Commands
```bash
# Run via Maven
mvn exec:java -Dexec.mainClass=com.lantransfer.LanTransferApp

# Run packaged jar
java -jar target/lan-transfer-0.1.0-SNAPSHOT.jar

# Run fat jar with dependencies
java -jar target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Development Structure

### Source Code Organization
```
src/main/java/com/lantransfer/
├── core/           # Core functionality
│   ├── model/      # Data models
│   ├── net/        # Network utilities
│   ├── protocol/   # Protocol implementation
│   └── service/    # Core services
├── ui/             # Swing UI components
│   ├── common/     # Shared UI components
│   ├── receiver/   # Receiver-specific UI
│   └── sender/     # Sender-specific UI
└── LanTransferApp.java  # Main application entry point
```

### Dependencies
- Netty (4.2.7.Final) - UDP transport implementation
- Java Swing - GUI components
- Maven - Build and dependency management

### Configuration
The project is configured via Maven's `pom.xml` with:
- Java 17 source/target compatibility
- Netty dependency version 4.2.7.Final
- JAR packaging with main class `com.lantransfer.LanTransferApp`
- Assembly plugin for fat JAR creation

## Protocol Details

### Message Types
- `TRANSFER_OFFER`: Sender → Receiver, contains transfer request details
- `TRANSFER_RESPONSE`: Receiver → Sender, accept/reject response with task ID
- `FILE_META`: Sender → Receiver, file metadata (path, size, MD5)
- `FILE_CHUNK`: Sender → Receiver, file data chunk with 11-byte header
- `FILE_SEND_DONE`: Sender → Receiver, indicates all chunks sent for a file
- `FILE_COMPLETE`: Receiver → Sender, file validation result (OK/FAIL)
- `FILE_RESEND_REQUEST`: Receiver → Sender, request for specific file resend
- `TASK_COMPLETE`: Receiver → Sender, all files completed
- `TASK_CANCEL`: Sender → Receiver, cancel transfer

### Chunk Format (1011 bytes total)
- Bytes 0-7: `long` sequence number (64-bit)
- Byte 8: `byte` XOR key for obfuscation
- Bytes 9-10: `short` body size (16-bit, 0-1000)
- Bytes 11+: Chunk body (up to 1000 bytes), XOR'd with key

### Reliability Features
- Bitmap-based chunk tracking with persistence
- Missing chunk detection and retransmission requests
- Per-file MD5 validation
- Graceful shutdown with resource cleanup

## Development Conventions

### Coding Standards
- Java 17 with standard Maven project structure
- Netty for UDP networking implementation
- Swing for GUI development
- Maven for build and dependency management

### Testing and Validation
- Path traversal protection at destination validation
- Per-file MD5 verification for integrity
- Resumable transfers with persistent state

### Outstanding Work
According to the README, the following work is still outstanding:
- Stronger reliability (sliding window/ACK)
- Persistent task store
- Richer error surfacing/logging

## Key Files

- `pom.xml`: Maven project configuration
- `README.md`: Project overview and build/run instructions
- `requirements/lan-transfer-requirements.md`: Detailed functional and technical requirements
- `src/main/java/com/lantransfer/LanTransferApp.java`: Application entry point
- `src/main/java/com/lantransfer/ui/ModeSelectorFrame.java`: Initial UI frame