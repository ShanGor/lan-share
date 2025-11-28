package com.lantransfer.core.protocol;

public enum ProtocolMessageType {
    TRANSFER_OFFER,
    TRANSFER_RESPONSE,
    FILE_META,
    FILE_CHUNK,
    FILE_COMPLETE,
    FILE_RESEND_REQUEST,
    TASK_COMPLETE,
    TASK_CANCEL,
    CHUNK_ACK,
    CHUNK_REQUEST,
    DIR_CREATE,
    HEARTBEAT_ACK
}
