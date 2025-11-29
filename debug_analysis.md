# Debugging the QUIC Connection Issue

Based on the code analysis, here's what should happen when sender clicks "Send":

1. **Sender Side** (`TransferSenderService.sendInternal()`):
   - Creates a `TransferOfferMessage`
   - Calls `ensureControlChannel()` to establish QUIC connection
   - Sends the `TransferOfferMessage` over the control channel

2. **Receiver Side** (`TransferReceiverService.start()`):
   - Sets up QUIC server with `QuicServerCodecBuilder`
   - Listens for incoming QUIC connections on specified port
   - Should receive the `TransferOfferMessage` and call `onOffer()`
   - `onOffer()` should show the accept/reject dialog

## Potential Issues

### 1. QUIC Connection Establishment
The sender uses:
```java
QuicChannel quicChannel = quicBootstrap.connect().get(15, TimeUnit.SECONDS);
```

If this fails (timeout, connection refused, etc.), the `TransferOfferMessage` won't be sent.

### 2. Receiver Not Properly Listening
The receiver should be listening on the control port, but there might be:
- Port binding issues
- SSL/TLS handshake problems
- Application protocol mismatch

### 3. Message Handling Issues
Even if the connection is established, the message might not be properly:
- Serialized/deserialized
- Routed to the correct handler
- Processed by the UI thread

## Debugging Steps

1. **Add logging** to see if the QUIC connection is being established
2. **Check for exceptions** in the connection establishment
3. **Verify the receiver is actually listening** on the expected port
4. **Test with minimal QUIC client/server** to isolate the issue

## Key Classes to Debug

- `TransferSenderService.ensureControlChannel()` - Line 133
- `TransferReceiverService.start()` - Line 72
- `TransferReceiverService.onOffer()` - Line 169
- `QuicMessageUtil` - Message framing and encoding