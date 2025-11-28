package com.lantransfer.core.net;

import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Helpers for framing protocol messages over QUIC streams.
 */
public final class QuicMessageUtil {

    private QuicMessageUtil() {
    }

    public static ChannelHandler newFrameDecoder() {
        // Allow up to 64MB per frame.
        return new LengthFieldBasedFrameDecoder(64 * 1024 * 1024, 0, 4, 0, 4);
    }

    public static ChannelHandler newInboundHandler(BiConsumer<Channel, ProtocolMessage> consumer) {
        return new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                byte[] data = new byte[msg.readableBytes()];
                msg.readBytes(data);
                ProtocolMessage protocolMessage = ProtocolIO.fromByteArray(data);
                consumer.accept(ctx.channel(), protocolMessage);
            }
        };
    }

    public static void write(Channel channel, ProtocolMessage message) throws IOException {
        byte[] data = ProtocolIO.toByteArray(message);
        ByteBuf buf = channel.alloc().buffer(4 + data.length);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        channel.writeAndFlush(buf);
    }
}
