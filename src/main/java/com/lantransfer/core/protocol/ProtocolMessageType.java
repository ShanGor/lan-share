package com.lantransfer.core.protocol;

public enum ProtocolMessageType {
    TRANSFER_OFFER,
    TRANSFER_RESPONSE,
    FILE_META,
    FILE_CHUNK,
    FILE_SEND_DONE,
    FILE_COMPLETE,
    FILE_RESEND_REQUEST,
    TASK_COMPLETE,
    TASK_CANCEL,
    DIR_CREATE,
    HEARTBEAT_ACK
}
