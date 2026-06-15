package com.cg10.paper.tcpserver;

import com.cg10.paper.model.ScanPayload;
import com.cg10.paper.service.TemperatureProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class TcpServer {

    @Value("${netty.tcp.port:9000}")
    private int port;

    @Value("${netty.tcp.boss-threads:2}")
    private int bossThreads;

    @Value("${netty.tcp.worker-threads:8}")
    private int workerThreads;

    private final TemperatureProcessor temperatureProcessor;
    private final ObjectMapper objectMapper;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectedClients = new AtomicInteger(0);
    private final AtomicLong totalScansReceived = new AtomicLong(0);
    private final AtomicLong windowScans = new AtomicLong(0);
    private volatile long scansPerSecond = 0;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        Thread serverThread = new Thread(this::runServer, "tcp-server-thread");
        serverThread.setDaemon(true);
        serverThread.start();

        Thread statsThread = new Thread(this::statsLoop, "tcp-stats-thread");
        statsThread.setDaemon(true);
        statsThread.start();
    }

    private void runServer() {
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new ProtocolFrameDecoder())
                                    .addLast(new ScanDataHandler());
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();
            running.set(true);
            log.info("TCP 服务器已启动，监听端口: {}", port);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("TCP 服务器启动失败", e);
        } finally {
            running.set(false);
        }
    }

    private void statsLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                long count = windowScans.getAndSet(0);
                scansPerSecond = count;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @PreDestroy
    public void stop() {
        log.info("正在关闭 TCP 服务器...");
        running.set(false);
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("TCP 服务器已关闭");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getConnectedClients() {
        return connectedClients.get();
    }

    public long getTotalScansReceived() {
        return totalScansReceived.get();
    }

    public long getScansPerSecond() {
        return scansPerSecond;
    }

    private class ProtocolFrameDecoder extends ByteToMessageDecoder {
        private static final int HEADER_SIZE = 8;
        private static final byte[] MAGIC = {'C', 'G', '1', '0'};

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.readableBytes() >= HEADER_SIZE) {
                int readerIndex = in.readerIndex();
                boolean magicMatch = true;
                for (int i = 0; i < 4; i++) {
                    if (in.getByte(readerIndex + i) != MAGIC[i]) {
                        magicMatch = false;
                        break;
                    }
                }
                if (!magicMatch) {
                    in.readByte();
                    continue;
                }
                int payloadLen = in.getInt(readerIndex + 4);
                if (in.readableBytes() < HEADER_SIZE + payloadLen) {
                    break;
                }
                in.skipBytes(HEADER_SIZE);
                byte[] payload = new byte[payloadLen];
                in.readBytes(payload);
                out.add(payload);
            }
        }
    }

    @ChannelHandler.Sharable
    private class ScanDataHandler extends SimpleChannelInboundHandler<byte[]> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            int count = connectedClients.incrementAndGet();
            log.info("红外探头客户端已连接: {}, 当前连接数: {}",
                    ctx.channel().remoteAddress(), count);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            int count = connectedClients.decrementAndGet();
            log.info("红外探头客户端已断开: {}, 当前连接数: {}",
                    ctx.channel().remoteAddress(), count);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
            try {
                String json = new String(msg, StandardCharsets.UTF_8);
                ScanPayload payload = objectMapper.readValue(json, ScanPayload.class);
                totalScansReceived.incrementAndGet();
                windowScans.incrementAndGet();
                temperatureProcessor.process(payload);
            } catch (Exception e) {
                if (totalScansReceived.get() % 1000 == 0) {
                    log.warn("解析扫描数据失败", e);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("TCP连接异常: {}", cause.getMessage());
            ctx.close();
        }
    }
}
