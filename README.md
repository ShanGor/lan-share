# Lan Transfer

LAN file transfer tool with Swing UI and UDP transport (Netty). Implements chunked UDP file transfer with XOR-obscured chunk bodies, per-file MD5, bitmap-based resend of missing chunks, and resumable tasks.

## Requirements
- Java 17
- Maven 3.9+

## Build
```bash
mvn clean package
```
Note: this downloads `io.netty:netty-all:4.2.7.Final`. Ensure network access to Maven Central.

To build a runnable jar with dependencies:
```bash
mvn clean package
# output: target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Run
```bash
mvn exec:java -Dexec.mainClass=com.lantransfer.LanTransferApp
```
or run the packaged jar:
```bash
java -jar target/lan-transfer-0.1.0-SNAPSHOT.jar
```

Or run the fat jar with dependencies:
```bash
java -jar target/lan-transfer-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Status
- Sender/receiver Swing windows are wired to UDP services with basic validation and progress display.
- Protocol messages implemented (offer/response, file metadata, chunks, resend requests, file done, task complete).
- Bitmap-based chunk tracking and resend on missing chunks; per-file MD5 validation and file-done signaling.
- Simple retry/backoff for file-send-done and missing-chunk requests; bitmap persistence enables resume after restart.
- Path traversal guarded by destination root check; graceful shutdown closes UDP/scheduler resources.
- Remembers last-used sender (host/port/folder) and receiver (port/destination) settings across restarts via `~/.lan-transfer`.
- Outstanding work: stronger reliability (sliding window/ACK), persistent task store, richer error surfacing/logging.
