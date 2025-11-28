package com.lantransfer.core.net;

import com.lantransfer.core.protocol.ProtocolIO;
import com.lantransfer.core.protocol.ProtocolMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

public class TcpEndpoint implements AutoCloseable {
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;
    private volatile Channel activeChannel;
    private Consumer<ProtocolMessage> handler;

    public void startServer(int port, Consumer<ProtocolMessage> handler) throws InterruptedException {
        this.handler = handler;
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4))
                                .addLast(new LengthFieldPrepender(4))
                                .addLast(new InboundHandler());
                    }
                });
        serverChannel = b.bind(port).sync().channel();
    }

    public void connect(String host, int port, Consumer<ProtocolMessage> handler) throws InterruptedException {
        this.handler = handler;
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4))
                                .addLast(new LengthFieldPrepender(4))
                                .addLast(new InboundHandler());
                    }
                });
        ChannelFuture f = b.connect(new InetSocketAddress(host, port)).sync();
        activeChannel = f.channel();
    }

    public void send(ProtocolMessage msg) throws IOException {
        Channel ch = activeChannel;
        if (ch == null || !ch.isActive()) {
            throw new IOException("TCP channel not active");
        }
        try {
            byte[] data = ProtocolIO.toByteArray(msg);
            ByteBuf buf = Unpooled.wrappedBuffer(data);
            ch.writeAndFlush(buf);
        } catch (Exception e) {
            throw new IOException("Failed to encode/send message", e);
        }
    }

    @Override
    public void close() {
        if (activeChannel != null) {
            activeChannel.close();
        }
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private class InboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            activeChannel = ctx.channel();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            ProtocolMessage pm = ProtocolIO.fromByteArray(data);
            if (handler != null) {
                handler.accept(pm);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (ctx.channel() == activeChannel) {
                activeChannel = null;
            }
            super.channelInactive(ctx);
        }
    }
}
