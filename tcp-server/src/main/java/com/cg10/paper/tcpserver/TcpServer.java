package com.cg10.paper.tcpserver;

import com.cg10.paper.model.ScanPayload;
import com.cg10.paper.service.TemperatureProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
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

    @Value("${app.cylinder.scan-points:64}")
    private int scanPoints;

    @Value("${netty.tcp.max-frame-size:1048576}")
    private int maxFrameSize;

    private final TemperatureProcessor temperatureProcessor;
    private final ObjectMapper objectMapper;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectedClients = new AtomicInteger(0);
    private final AtomicLong totalScansReceived = new AtomicLong(0);
    private final AtomicLong windowScans = new AtomicLong(0);
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private volatile long scansPerSecond = 0;

    private ThreadPoolExecutor businessExecutor;
    private final ScanDataHandler sharableHandler = new ScanDataHandler();

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        System.setProperty("io.netty.allocator.type", "pooled");
        System.setProperty("io.netty.allocator.maxOrder", "9");
        System.setProperty("io.netty.allocator.tinyCacheSize", "0");
        System.setProperty("io.netty.allocator.smallCacheSize", "0");
        System.setProperty("io.netty.allocator.normalCacheSize", "0");
        System.setProperty("io.netty.maxDirectMemory", "0");
    }

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(bossThreads, new NamedThreadFactory("netty-boss"));
        workerGroup = new NioEventLoopGroup(workerThreads, new NamedThreadFactory("netty-worker"));

        int businessThreads = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);
        businessExecutor = new ThreadPoolExecutor(
                businessThreads,
                businessThreads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20000),
                new NamedThreadFactory("tcp-business"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        businessExecutor.prestartAllCoreThreads();

        Thread serverThread = new Thread(this::runServer, "tcp-server-thread");
        serverThread.setDaemon(true);
        serverThread.start();

        Thread statsThread = new Thread(this::statsLoop, "tcp-stats-thread");
        statsThread.setDaemon(true);
        statsThread.start();

        Thread monitorThread = new Thread(this::memoryMonitorLoop, "tcp-memory-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void runServer() {
        try {
            final int receiveBufferSize = 256 * 1024;
            final WriteBufferWaterMark waterMark = new WriteBufferWaterMark(
                    512 * 1024,
                    8 * 1024 * 1024
            );
            final int maxPayloadSize = Math.min(maxFrameSize,
                    Math.max(65536, scanPoints * 8 * 10 + 1024));

            log.info("Netty TCP 参数: workerThreads={}, maxPayloadSize={} bytes, " +
                            "recvBuf={}KB, waterMark=[512KB, 8MB], businessThreads={}",
                    workerThreads, maxPayloadSize, receiveBufferSize / 1024,
                    businessExecutor.getCorePoolSize());

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                    .childOption(ChannelOption.SO_RCVBUF, receiveBufferSize)
                    .childOption(ChannelOption.SO_SNDBUF, 128 * 1024)
                    .childOption(ChannelOption.AUTO_CLOSE, true)
                    .childOption(ChannelOption.MAX_MESSAGES_PER_READ, 16)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
                            ch.pipeline()
                                    .addLast("idleState", new IdleStateHandler(60, 0, 0))
                                    .addLast("frameDecoder", new SafeProtocolFrameDecoder(maxPayloadSize))
                                    .addLast("waterMark", new WriteWatermarkHandler())
                                    .addLast("dataHandler", sharableHandler);
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();
            running.set(true);
            log.info("TCP 服务器已启动，监听端口: {}, 堆外内存检测级别: ADVANCED", port);
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
        long lastRejected = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                long count = windowScans.getAndSet(0);
                scansPerSecond = count;
                long rej = rejectedCount.get();
                long rejDelta = rej - lastRejected;
                if (count > 0 || rejDelta > 0) {
                    log.info("TCP 吞吐: {} msg/s, 队列: {}, 拒绝: {} (Δ+{}), 客户端: {}",
                            count,
                            businessExecutor.getQueue().size(),
                            rej, rejDelta,
                            connectedClients.get());
                }
                lastRejected = rej;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void memoryMonitorLoop() {
        int warnCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(5000);
                Runtime rt = Runtime.getRuntime();
                long usedHeap = rt.totalMemory() - rt.freeMemory();
                long maxHeap = rt.maxMemory();
                double heapUsage = (double) usedHeap / maxHeap * 100;

                int qSize = businessExecutor.getQueue().size();
                int qCapacity = 20000;

                if (heapUsage > 85 || qSize > qCapacity * 0.8) {
                    warnCount++;
                    log.warn("⚠️ 内存压力警报 #{}: 堆内存使用 {:.1f}% ({}MB / {}MB), " +
                                    "业务队列 {} / {} ({}%), 拒绝总数: {}",
                            warnCount,
                            heapUsage, usedHeap / 1024 / 1024, maxHeap / 1024 / 1024,
                            qSize, qCapacity, (int) ((double) qSize / qCapacity * 100),
                            rejectedCount.get());
                    if (heapUsage > 92) {
                        log.error("🚨 紧急内存保护: 触发主动 GC 释放内存");
                        System.gc();
                    }
                } else {
                    warnCount = 0;
                }
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
        if (businessExecutor != null) {
            businessExecutor.shutdownNow();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(2, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(2, 5, TimeUnit.SECONDS);
        }
        log.info("TCP 服务器已关闭, 总处理: {} 条扫描, 拒绝: {} 条",
                totalScansReceived.get(), rejectedCount.get());
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

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public int getQueueSize() {
        return businessExecutor != null ? businessExecutor.getQueue().size() : 0;
    }

    private class SafeProtocolFrameDecoder extends ByteToMessageDecoder {
        private static final int HEADER_SIZE = 8;
        private static final byte[] MAGIC = {'C', 'G', '1', '0'};
        private static final int MAX_MAGIC_SCAN_BYTES = 4096;

        private final int maxPayloadLen;
        private long discardedCorruptBytes = 0;
        private int frameErrors = 0;

        SafeProtocolFrameDecoder(int maxPayloadLen) {
            this.maxPayloadLen = maxPayloadLen;
            setCumulator(COMPOSITE_CUMULATOR);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.readableBytes() >= HEADER_SIZE) {
                int readerIndex = in.readerIndex();

                int magicOffset = findMagicOffset(in, readerIndex);
                if (magicOffset < 0) {
                    int skipLen = Math.max(0, in.readableBytes() - 3);
                    if (skipLen > 0) {
                        in.skipBytes(skipLen);
                        discardedCorruptBytes += skipLen;
                    }
                    checkDiscardAlarm(ctx);
                    break;
                }
                if (magicOffset > 0) {
                    in.skipBytes(magicOffset);
                    discardedCorruptBytes += magicOffset;
                    readerIndex = in.readerIndex();
                    checkDiscardAlarm(ctx);
                    if (in.readableBytes() < HEADER_SIZE) break;
                }

                int payloadLen = in.getInt(readerIndex + 4);
                if (payloadLen <= 0 || payloadLen > maxPayloadLen) {
                    frameErrors++;
                    discardedCorruptBytes += 1;
                    in.skipBytes(1);
                    if (frameErrors % 100 == 0) {
                        log.warn("[{}] 帧长度异常: {} bytes (允许: 1~{}), 累计错误: {}, 丢弃字节: {}",
                                ctx.channel().remoteAddress(), payloadLen, maxPayloadLen,
                                frameErrors, discardedCorruptBytes);
                    }
                    continue;
                }

                int totalLen = HEADER_SIZE + payloadLen;
                if (in.readableBytes() < totalLen) break;

                in.skipBytes(HEADER_SIZE);
                byte[] payload = new byte[payloadLen];
                in.readBytes(payload);
                out.add(payload);
            }
        }

        private int findMagicOffset(ByteBuf in, int startIdx) {
            int limit = Math.min(startIdx + MAX_MAGIC_SCAN_BYTES, in.writerIndex() - 3);
            for (int i = startIdx; i < limit; i++) {
                if (in.getByte(i) == MAGIC[0]
                        && in.getByte(i + 1) == MAGIC[1]
                        && in.getByte(i + 2) == MAGIC[2]
                        && in.getByte(i + 3) == MAGIC[3]) {
                    return i - startIdx;
                }
            }
            return -1;
        }

        private void checkDiscardAlarm(ChannelHandlerContext ctx) {
            if (discardedCorruptBytes > 1024 * 1024 && frameErrors > 1000) {
                log.error("[{}] 协议同步失败, 累计丢弃 {} bytes, 错误 {} 次, 强制断开连接",
                        ctx.channel().remoteAddress(), discardedCorruptBytes, frameErrors);
                ctx.close();
            }
        }
    }

    private class WriteWatermarkHandler extends ChannelDuplexHandler {
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            Channel ch = ctx.channel();
            if (!ch.isWritable()) {
                log.warn("[{}] 通道写缓冲区超过高水位线, 暂停 AUTO_READ 防止内存积压",
                        ch.remoteAddress());
                ch.config().setAutoRead(false);
            } else if (ch.isWritable()) {
                if (!ch.config().isAutoRead()) {
                    log.info("[{}] 通道写缓冲区恢复, 重新启用 AUTO_READ",
                            ch.remoteAddress());
                    ch.config().setAutoRead(true);
                }
            }
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent e) {
                if (e.state() == IdleState.READER_IDLE) {
                    log.warn("[{}] 连接 60 秒无数据, 关闭空闲连接",
                            ctx.channel().remoteAddress());
                    ctx.close();
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    @ChannelHandler.Sharable
    private class ScanDataHandler extends SimpleChannelInboundHandler<byte[]> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            int count = connectedClients.incrementAndGet();
            log.info("✅ 红外探头客户端已连接: {}, 当前连接数: {}",
                    ctx.channel().remoteAddress(), count);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            int count = connectedClients.decrementAndGet();
            log.info("❌ 红外探头客户端已断开: {}, 当前连接数: {}",
                    ctx.channel().remoteAddress(), count);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
            final long scanSeq = totalScansReceived.incrementAndGet();
            windowScans.incrementAndGet();

            try {
                businessExecutor.execute(() -> {
                    try {
                        String json = new String(msg, StandardCharsets.UTF_8);
                        ScanPayload payload = objectMapper.readValue(json, ScanPayload.class);
                        temperatureProcessor.process(payload);
                    } catch (Exception e) {
                        if (scanSeq % 1000 == 0) {
                            log.warn("解析扫描数据失败 #{}", scanSeq, e);
                        }
                    }
                });
            } catch (RejectedExecutionException re) {
                long rej = rejectedCount.incrementAndGet();
                if (rej % 500 == 1) {
                    log.error("⚠️ 业务队列已满! 序列号 #{} 被丢弃, 累计拒绝: {}, " +
                                    "队列大小: {}, 建议: 增加业务线程或降低扫描频率",
                            scanSeq, rej, businessExecutor.getQueue().size());
                }
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            log.warn("[{}] TCP连接异常: {}", ctx.channel().remoteAddress(), msg);
            ctx.close();
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
}
