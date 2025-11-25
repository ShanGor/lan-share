package com.lantransfer.core.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public class UdpEndpoint implements AutoCloseable {
    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private Channel channel;

    public void bind(int port, BiConsumer<InetSocketAddress, byte[]> onMessage) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                                ByteBuf buf = packet.content();
                                byte[] data = new byte[buf.readableBytes()];
                                buf.readBytes(data);
                                onMessage.accept(packet.sender(), data);
                            }
                        });
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        channel = future.channel();
    }

    public void send(InetSocketAddress recipient, byte[] data) {
        if (channel == null) {
            throw new IllegalStateException("Channel not bound");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        channel.writeAndFlush(new DatagramPacket(buf, recipient));
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
    }
}
