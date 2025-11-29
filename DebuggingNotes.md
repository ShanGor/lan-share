# Debugging Notes: Task Request Not Prompting

## Issue Analysis
The task request from the sender is not prompting at the receiver side. After analyzing the code, here are the potential causes:

## 1. Network Connectivity Issues
The most likely cause is network connectivity between sender and receiver. The sender establishes a QUIC connection to the receiver's control port (default 9000), but if:
- Firewall blocks the connection
- Wrong IP address is used
- Port is already in use or blocked
- Network configuration prevents local connections

## 2. QUIC Stream Management
The application uses QUIC streams where:
- Control channel (port 9000) handles task negotiation (offers, accepts/rejects, cancellation)
- Data channel (ephemeral port) handles file metadata and chunks after acceptance

If there's an issue in the QUIC stream establishment, the TRANSFER_OFFER message won't reach the receiver.

## 3. Potential Code Issue
In `TransferSenderService.sendInternal()`, the `TransferOfferMessage` is created with `listenPort` set to 0:
```java
sendControl(ctx, new TransferOfferMessage(taskId, folder.getFileName().toString(), totalBytes, files.size() + dirs.size(), 0, 'D'));
```

This might be fine since this field might not be used by the receiver, but it's worth noting.

## 4. Swing UI Thread Handling
The `onOffer` method properly delegates to the Swing EDT:
```java
private void onOffer(QuicStreamChannel channel, TransferOfferMessage offer) {
    Runnable uiTask = () -> {
        boolean accept = showAcceptDialog(offer);
        channel.eventLoop().execute(() -> handleOfferDecision(channel, offer, accept));
    };
    if (SwingUtilities.isEventDispatchThread()) {
        uiTask.run();
    } else {
        SwingUtilities.invokeLater(uiTask);
    }
}
```

## 5. Testing the Fix
To test if this is working properly:
1. Start the receiver on one machine/terminal with "Receiver" mode
2. Start the sender on another machine/terminal with "Sender" mode
3. In sender, specify correct IP address of receiver and port 9000
4. Select a folder to transfer
5. Click "Send"
6. The receiver should show a modal dialog asking to accept/reject the transfer

## 6. Common Issues to Check
- Make sure both machines are on the same network
- Verify the IP address and port in the sender configuration
- Check firewall settings to ensure port 9000 is open
- For local testing, use "127.0.0.1" or "localhost" as the receiver address
- Look for any exception messages in the console output

## 7. Solution
The issue is likely network-related rather than code-related. The most important thing is to ensure proper network connectivity between sender and receiver for the initial control channel communication.