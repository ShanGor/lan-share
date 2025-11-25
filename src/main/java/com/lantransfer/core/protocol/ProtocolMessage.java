package com.lantransfer.core.protocol;

import java.io.DataOutputStream;
import java.io.IOException;

public interface ProtocolMessage {
    ProtocolMessageType type();

    void write(DataOutputStream out) throws IOException;
}
