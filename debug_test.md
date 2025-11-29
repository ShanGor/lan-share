# Debug Test Instructions

## Steps to Reproduce and Debug

1. **Start Receiver First:**
   ```bash
   # In terminal 1
   java -cp target/classes:target/dependency/* com.lantransfer.LanTransferApp
   ```
   - Select "Receiver" mode
   - Set port to 9000
   - Choose a destination directory
   - Click "Start Listening"
   - Watch console logs for: "Starting receiver on port 9000", "SSL certificate created", "QUIC receiver successfully listening"

2. **Start Sender Second:**
   ```bash
   # In terminal 2
   java -cp target/classes:target/dependency/* com.lantransfer.LanTransferApp
   ```
   - Select "Sender" mode
   - Set "Receiver Host/IP" to "localhost"
   - Set "Port" to 9000
   - Choose a folder with some files
   - Click "Send"
   - Watch console logs for: "Establishing control channel to receiver", "UDP channel bound to", "Attempting QUIC connection"

## Expected Log Flow

**Receiver should show:**
```
INFO: Starting receiver on port 9000 with destination: /path/to/dest
INFO: SSL certificate created for server
INFO: SSL context created for server
INFO: Binding QUIC server to port 9000
INFO: QUIC receiver successfully listening on port 9000 at ...
INFO: QUIC channel initialized: ...
INFO: Initializing stream channel: ...
INFO: Receiver received message: TRANSFER_OFFER on channel ... from remote: ...
INFO: Processing TRANSFER_OFFER message: ...
INFO: Transfer offer details - Folder: ..., Files: ..., Bytes: ...
INFO: onOffer called with offer: ...
INFO: Showing accept dialog for offer: ...
```

**Sender should show:**
```
INFO: Establishing control channel to receiver: localhost/127.0.0.1:9000
INFO: UDP channel bound to: /127.0.0.1:...
INFO: Attempting QUIC connection to: localhost/127.0.0.1:9000
INFO: QUIC connection established successfully
INFO: Control stream created successfully for task ...
INFO: Sending control message: TRANSFER_OFFER on channel ...
```

## What to Check If It Fails

1. **If sender shows "Failed to establish QUIC connection":**
   - Receiver may not be listening on the port
   - Firewall blocking the connection
   - SSL/TLS handshake issues

2. **If sender connects but receiver doesn't get the message:**
   - Message serialization/deserialization issues
   - Stream channel problems
   - Application protocol mismatch

3. **If receiver gets message but no dialog appears:**
   - Swing threading issues (EDT problems)
   - Exception in dialog creation
   - Message content issues

## Debugging Tips

- Use `jvisualvm` or `jconsole` to monitor threads
- Check for any exceptions in the console
- Verify both processes are using the same Java version
- Test with different ports to rule out port conflicts
- Try with "127.0.0.1" instead of "localhost"