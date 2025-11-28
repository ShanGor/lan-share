package com.lantransfer.core.service;

import com.lantransfer.core.model.TransferStatus;
import com.lantransfer.core.model.TransferTask;
import com.lantransfer.core.net.QuicMessageUtil;
import com.lantransfer.core.protocol.ChunkAckMessage;
import com.lantransfer.core.protocol.ChunkAckMessage.AckRange;
import com.lantransfer.core.protocol.ChunkHeader;
import com.lantransfer.core.protocol.FileChunkMessage;
import com.lantransfer.core.protocol.FileCompleteMessage;
import com.lantransfer.core.protocol.FileMetaMessage;
import com.lantransfer.core.protocol.MetaAckMessage;
import com.lantransfer.core.protocol.ProtocolMessage;
import com.lantransfer.core.protocol.TaskCancelMessage;
import com.lantransfer.core.protocol.TaskCompleteMessage;
import com.lantransfer.core.protocol.TransferOfferMessage;
import com.lantransfer.core.protocol.TransferResponseMessage;
import com.lantransfer.ui.common.TaskTableModel;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicChannelBootstrap;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import javax.swing.SwingUtilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferSenderService implements AutoCloseable {
    private static final Logger log = Logger.getLogger(TransferSenderService.class.getName());
    private static final int INITIAL_WINDOW_CHUNKS = 64;
    private static final int MAX_WINDOW_CHUNKS = 1024;
    private static final int MIN_WINDOW_CHUNKS = 8;
    private static final int TIMEOUT_SWEEP_INTERVAL_MS = 25;
    private static final long MIN_CHUNK_TIMEOUT_MS = 50;
    private static final long MAX_CHUNK_TIMEOUT_MS = 2000;
    private static final long RTT_GRANULARITY_MS = 5;
    private static final int MAX_CHUNK_RETRY = 8; // Increased to handle more retries with faster requests

    private final TaskRegistry taskRegistry;
    private final TaskTableModel tableModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService sendExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<Integer, SenderContext> contexts = new ConcurrentHashMap<>();
    private final NioEventLoopGroup quicGroup = new NioEventLoopGroup(1);
    private final QuicSslContext clientSslContext;

    public TransferSenderService(TaskRegistry taskRegistry) {
        this(taskRegistry, null);
    }

    public TransferSenderService(TaskRegistry taskRegistry, TaskTableModel tableModel) {
        this.taskRegistry = taskRegistry;
        this.tableModel = tableModel;
        this.clientSslContext = createClientSslContext();
    }

    private static QuicSslContext createClientSslContext() {
        return QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("lan-transfer")
                .build();
    }

    public void start(int port) {
        log.info("TransferSenderService using QUIC client; no local port binding required.");
    }

    public void sendFolder(Path folder, InetSocketAddress receiver, TaskTableModel tableModel) {
        Objects.requireNonNull(folder, "folder");
        executor.submit(() -> sendInternal(folder, receiver, tableModel));
    }

    private void sendInternal(Path folder, InetSocketAddress receiver, TaskTableModel tableModel) {
        int taskId = TaskIdGenerator.nextId();
        SenderContext ctx = null;
        try {
            List<Path> files = listFiles(folder);
            List<Path> dirs = listDirs(folder);
            long totalBytes = totalSize(files);
            TransferTask task = new TransferTask(taskId, folder, Path.of(""), totalBytes);
            task.setStatus(TransferStatus.PENDING);
            taskRegistry.add(task);
            if (tableModel != null) {
                tableModel.addTask(task);
            }
            ctx = new SenderContext(taskId, task, receiver, files, dirs, folder);
            contexts.put(taskId, ctx);
            ensureConnected(ctx);
            send(ctx, new TransferOfferMessage(taskId, folder.getFileName().toString(), totalBytes, files.size() + dirs.size(), 0, 'D'));

            boolean accepted = ctx.acceptFuture.get(30, TimeUnit.SECONDS);
            if (!accepted) {
                task.setStatus(TransferStatus.REJECTED);
                contexts.remove(taskId);
                ctx.cleanup();
                return;
            }
            task.setStatus(TransferStatus.IN_PROGRESS);
            sendEntries(ctx);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Send failed", e);
            if (ctx != null) {
                ctx.task.setStatus(TransferStatus.FAILED);
                contexts.remove(taskId);
                ctx.cleanup();
            }
        } finally {
            TaskIdGenerator.release(taskId);
        }
    }

    private void ensureConnected(SenderContext ctx) throws Exception {
        if (ctx.streamChannel != null && ctx.streamChannel.isActive()) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap();
        Channel udpChannel = bootstrap.group(quicGroup)
                .channel(NioDatagramChannel.class)
                .handler(new QuicClientCodecBuilder()
                        .sslContext(clientSslContext)
                        .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                        .initialMaxData(10_000_000)
                        .initialMaxStreamDataBidirectionalLocal(1_000_000)
                        .build())
                .bind(0)
                .sync()
                .channel();

        QuicChannelBootstrap quicBootstrap = QuicChannel.newBootstrap(udpChannel)
                .remoteAddress(ctx.receiver)
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        // no-op; we explicitly create streams
                    }
                });
        QuicChannel quicChannel = quicBootstrap.connect().get(15, TimeUnit.SECONDS);
        Future<QuicStreamChannel> streamFuture = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(QuicMessageUtil.newFrameDecoder());
                        ch.pipeline().addLast(QuicMessageUtil.newInboundHandler((channel, message) ->
                                handleIncoming(ctx, message)));
                    }
                });
        streamFuture.sync();
        QuicStreamChannel streamChannel = streamFuture.getNow();
        ctx.udpChannel = udpChannel;
        ctx.quicChannel = quicChannel;
        ctx.streamChannel = streamChannel;
        streamChannel.closeFuture().addListener(f -> {
            contexts.remove(ctx.taskId);
            ctx.cleanup();
        });
    }

    private void sendEntries(SenderContext ctx) throws IOException {
        int id = 0;

        // Send directory metadata
        for (Path dir : ctx.directories) {
            String rel = ctx.root.relativize(dir).toString();
            ctx.entries.put(id, new SenderEntry(rel, dir, true, 0));
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'D', rel, 0L, "");
            id++;
        }

        // Send file metadata
        for (Path file : ctx.files) {
            long size = Files.size(file);
            long totalChunks = (size + ChunkHeader.MAX_BODY_SIZE - 1) / ChunkHeader.MAX_BODY_SIZE;
            String rel = ctx.root.relativize(file).toString();
            String md5 = new ChecksumService().md5(file);
            ctx.entries.put(id, new SenderEntry(rel, file, false, totalChunks));
            sendFileMetaWithRetry(ctx, ctx.taskId, id, 'F', rel, size, md5);
            id++;
        }
    }

    private void sendFileMetaWithRetry(SenderContext ctx, int taskId, int fileId, char entryType, String relPath, long size, String md5) throws IOException {
        FileMetaMessage msg = new FileMetaMessage(taskId, fileId, entryType, relPath, size, md5);
        // Update current file path for UI
        SenderEntry entry = ctx.entries.get(fileId);
        if (entry != null) {
            ctx.currentFilePath = entry.path().toString();
            ctx.maybeRefreshUI(tableModel);
        }
        send(ctx, msg);

        // Track that FILE_META was sent and schedule retry if no ACK received
        ScheduledFuture<?> retryFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (Boolean.TRUE.equals(ctx.fileMetaAcked.get(fileId))) {
                // If acknowledged, cancel this retry task
                ScheduledFuture<?> future = ctx.chunkTimeouts.remove(-fileId);
                if (future != null) {
                    future.cancel(false);
                }
                return;
            }
            // Still not acknowledged, retry sending FILE_META
            try {
                // Update current file path for UI during retries
                SenderEntry retryEntry = ctx.entries.get(fileId);
                if (retryEntry != null) {
                    ctx.currentFilePath = retryEntry.path().toString();
                    ctx.maybeRefreshUI(tableModel);
                }
                send(ctx, msg);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to resend FILE_META for fileId " + fileId, e);
            }
        }, 1000, 2000, TimeUnit.MILLISECONDS); // Retry after 1s, then every 2s

        // Store the retry task so we can cancel it when ACK is received
        ctx.chunkTimeouts.put(-fileId, retryFuture);
    }

    private void handleIncoming(SenderContext ctx, ProtocolMessage msg) {
        switch (msg.type()) {
            case TRANSFER_RESPONSE -> onTransferResponse(ctx, (TransferResponseMessage) msg);
            case CHUNK_ACK -> onChunkAck(ctx, (ChunkAckMessage) msg);
            case FILE_COMPLETE -> onFileComplete((FileCompleteMessage) msg);
            case META_ACK -> onMetaAck(ctx, (MetaAckMessage) msg);
            case TASK_COMPLETE -> onTaskComplete((TaskCompleteMessage) msg);
            default -> log.fine("Ignoring message " + msg.type());
        }
    }

    private void onTransferResponse(SenderContext ctx, TransferResponseMessage msg) {
        ctx.acceptFuture.complete(msg.accepted());
    }

    private void onChunkAck(SenderContext ctx, ChunkAckMessage msg) {
        SenderFileState state = ctx.fileStates.get(msg.fileId());
        if (state == null) return;
        boolean shouldSendMore = false;
        long rttSampleNanos = -1;
        synchronized (state.lock) {
            if (state.closed) {
                return;
            }
            if (msg.ranges() != null) {
                for (AckRange range : msg.ranges()) {
                    for (long seq = range.start(); seq <= range.end(); seq++) {
                        Long sentAt = state.inFlight.remove(seq);
                        if (sentAt != null) {
                            state.retryCount.remove(seq);
                            state.onAck();
                            if (rttSampleNanos < 0) {
                                rttSampleNanos = System.nanoTime() - sentAt;
                            }
                        }
                    }
                }
            }
            if (state.inFlight.size() < state.windowLimit() && state.nextSeq < state.totalChunks) {
                shouldSendMore = true;
            }
        }
        if (rttSampleNanos > 0) {
            ctx.updateRtt(rttSampleNanos);
        }
        if (shouldSendMore) {
            sendNextChunks(ctx, msg.fileId());
        }
    }

    private void onMetaAck(SenderContext ctx, MetaAckMessage msg) {
        // Mark that FILE_META for this file ID has been acknowledged
        ctx.fileMetaAcked.put(msg.fileId(), true);
        // Cancel any pending retries for this file meta
        ScheduledFuture<?> retryFuture = ctx.chunkTimeouts.remove(-msg.fileId()); // using negative fileId as key for meta retries
        if (retryFuture != null) {
            retryFuture.cancel(false);
        }
        SenderEntry entry = ctx.entries.get(msg.fileId());
        if (entry != null && !entry.isDirectory()) {
            startFileStreaming(ctx, msg.fileId(), entry);
        }
    }

    private void startFileStreaming(SenderContext ctx, int fileId, SenderEntry entry) {
        SenderFileState state;
        try {
            state = ctx.ensureFileState(fileId, entry);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to open file channel for streaming: " + entry.path(), e);
            ctx.task.setStatus(TransferStatus.FAILED);
            return;
        }
        boolean shouldStart = false;
        synchronized (state.lock) {
            if (!state.closed && !state.streaming) {
                state.streaming = true;
                shouldStart = true;
            }
        }
        if (shouldStart) {
            ctx.currentFilePath = entry.path().toString();
            ctx.maybeRefreshUI(tableModel);
            scheduleChunkTimeout(ctx, fileId);
            sendNextChunks(ctx, fileId);
        }
    }

    private void scheduleChunkTimeout(SenderContext ctx, int fileId) {
        ctx.chunkTimeouts.computeIfAbsent(fileId, id ->
                scheduler.scheduleWithFixedDelay(() -> checkChunkTimeout(ctx, fileId),
                        TIMEOUT_SWEEP_INTERVAL_MS, TIMEOUT_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS));
    }

    private void checkChunkTimeout(SenderContext ctx, int fileId) {
        SenderFileState state = ctx.fileStates.get(fileId);
        if (state == null) return;
        List<Long> resend = new ArrayList<>();
        boolean fail = false;
        boolean windowReduced = false;
        long timeoutMs = ctx.chunkTimeoutMillis();
        synchronized (state.lock) {
            if (state.closed) {
                return;
            }
            long now = System.nanoTime();
            for (Map.Entry<Long, Long> entry : state.inFlight.entrySet()) {
                long seq = entry.getKey();
                long sentAt = entry.getValue();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - sentAt);
                if (elapsedMs >= timeoutMs) {
                    if (!windowReduced) {
                        state.onTimeout();
                        windowReduced = true;
                    }
                    int retry = state.retryCount.getOrDefault(seq, 0);
                    if (retry >= MAX_CHUNK_RETRY) {
                        fail = true;
                        break;
                    }
                    state.retryCount.put(seq, retry + 1);
                    entry.setValue(now);
                    resend.add(seq);
                }
            }
        }
        if (fail) {
            log.log(Level.SEVERE, "Exceeded retry limit for fileId " + fileId + " on task " + ctx.taskId);
            ctx.task.setStatus(TransferStatus.FAILED);
            failContext(ctx);
            return;
        }
        for (long seq : resend) {
            SenderFileState stateRef = ctx.fileStates.get(fileId);
            if (stateRef == null) {
                return;
            }
            sendChunkAsync(ctx, stateRef, seq, true);
        }
    }

    private void sendNextChunks(SenderContext ctx, int fileId) {
        SenderFileState state = ctx.fileStates.get(fileId);
        if (state == null) return;
        List<Long> toSend = new ArrayList<>();
        synchronized (state.lock) {
            if (state.closed) return;
            int windowLimit = state.windowLimit();
            while (state.inFlight.size() < windowLimit && state.nextSeq < state.totalChunks) {
                long seq = state.nextSeq++;
                state.inFlight.put(seq, System.nanoTime());
                state.retryCount.put(seq, 0);
                toSend.add(seq);
            }
        }
        if (toSend.isEmpty()) {
            return;
        }
        for (long seq : toSend) {
            sendChunkAsync(ctx, state, seq, false);
        }
    }

    private void sendChunkAsync(SenderContext ctx, SenderFileState state, long seq, boolean retransmit) {
        sendExecutor.submit(() -> sendChunk(ctx, state, seq, retransmit));
    }

    private void sendChunk(SenderContext ctx, SenderFileState state, long seq, boolean retransmit) {
        byte[] chunk;
        synchronized (state.lock) {
            if (state.closed) {
                return;
            }
            ctx.currentFilePath = state.path.toString();
        }
        try {
            chunk = readChunk(state.channel, seq);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read chunk " + seq + " for file " + state.path, e);
            ctx.task.setStatus(TransferStatus.FAILED);
            failContext(ctx);
            return;
        }
        if (chunk.length == 0) {
            return;
        }
        byte xorKey = (byte) (System.nanoTime() & 0xFF);
        byte[] body = xor(chunk, xorKey);
        try {
            send(ctx, new FileChunkMessage(ctx.taskId, state.fileId, seq, xorKey, body));
            if (!retransmit) {
                ctx.task.addBytesTransferred(body.length);
                ctx.maybeRefreshUI(tableModel);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to send chunk " + seq + " for fileId " + state.fileId, e);
        }
    }

    private void failContext(SenderContext ctx) {
        contexts.remove(ctx.taskId);
        ctx.cleanup();
    }

    private void onFileComplete(FileCompleteMessage msg) {
        SenderContext ctx = contexts.get(msg.taskId());
        if (ctx == null) return;
        if (!msg.success()) {
            ctx.task.setStatus(TransferStatus.RESENDING);
        }
        ctx.cleanupFileState(msg.fileId());
        ctx.completedFiles.add(msg.fileId());
        if (ctx.completedFiles.size() == ctx.entries.size()) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
        }
    }

    private void onTaskComplete(TaskCompleteMessage msg) {
        SenderContext ctx = contexts.remove(msg.taskId());
        if (ctx != null) {
            ctx.task.setStatus(TransferStatus.COMPLETED);
            ctx.cleanup();
        }
    }

    public void cancelTask(int taskId) {
        SenderContext ctx = contexts.remove(taskId);
        if (ctx != null) {
            try {
                send(ctx, new TaskCancelMessage(taskId));
                ctx.task.setStatus(TransferStatus.CANCELED);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to send task cancel message", e);
                ctx.task.setStatus(TransferStatus.CANCELED);
            }
            ctx.cleanup();
            TaskIdGenerator.release(taskId);
        }
    }

    private byte[] readChunk(FileChannel channel, long seq) throws IOException {
        long offset = seq * (long) ChunkHeader.MAX_BODY_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(ChunkHeader.MAX_BODY_SIZE);
        int read = channel.read(buffer, offset);
        if (read <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[read];
        buffer.flip();
        buffer.get(out);
        return out;
    }

    private byte[] xor(byte[] data, byte key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    private List<Path> listFiles(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(folder).filter(Files::isRegularFile).forEach(files::add);
        return files;
    }

    private List<Path> listDirs(Path folder) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walk(folder).filter(Files::isDirectory).filter(p -> !p.equals(folder)).forEach(dirs::add);
        return dirs;
    }

    private long totalSize(List<Path> files) throws IOException {
        long total = 0;
        for (Path p : files) {
            total += Files.size(p);
        }
        return total;
    }

    private void send(SenderContext ctx, ProtocolMessage msg) throws IOException {
        if (ctx.streamChannel == null || !ctx.streamChannel.isActive()) {
            throw new IOException("QUIC stream is not active for task " + ctx.taskId);
        }
        QuicMessageUtil.write(ctx.streamChannel, msg);
    }

    @Override
    public void close() {
        contexts.values().forEach(SenderContext::cleanup);
        contexts.clear();
        executor.shutdownNow();
        sendExecutor.shutdownNow();
        scheduler.shutdownNow();
        quicGroup.shutdownGracefully();
    }

    private static class SenderContext {
        final int taskId;
        final TransferTask task;
        final InetSocketAddress receiver;
        final List<Path> files;
        final List<Path> directories;
        final Path root;
        final CompletableFuture<Boolean> acceptFuture = new CompletableFuture<>();
        final Map<Integer, SenderEntry> entries = new ConcurrentHashMap<>();
        final Map<Integer, ScheduledFuture<?>> chunkTimeouts = new ConcurrentHashMap<>();
        final List<Integer> completedFiles = new ArrayList<>();
        final Map<Integer, Boolean> fileMetaAcked = new ConcurrentHashMap<>(); // Track if FILE_META is acknowledged
        final Map<Integer, SenderFileState> fileStates = new ConcurrentHashMap<>();
        volatile String currentFilePath = "";
        volatile Channel udpChannel;
        volatile QuicChannel quicChannel;
        volatile QuicStreamChannel streamChannel;

        volatile long lastUiUpdateNanos = System.nanoTime();
        volatile long lastTelemetryLogNanos = System.nanoTime();
        volatile long lastTelemetryBytes = 0;
        private final Object rttLock = new Object();
        volatile long smoothedRttNanos = TimeUnit.MILLISECONDS.toNanos(150);
        volatile long rttVarNanos = TimeUnit.MILLISECONDS.toNanos(75);
        volatile long retransmitTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(150);
        volatile boolean rttInitialized = false;

        SenderContext(int taskId, TransferTask task, InetSocketAddress receiver, List<Path> files, List<Path> directories, Path root) {
            this.taskId = taskId;
            this.task = task;
            this.receiver = receiver;
            this.files = files;
            this.directories = directories;
            this.root = root;
        }

        void maybeRefreshUI(TaskTableModel model) {
            long now = System.nanoTime();
            maybeLogTelemetry(now);
            if (model == null) return;
            if (now - lastUiUpdateNanos > 200_000_000L) { // 0.2s - more frequent updates
                lastUiUpdateNanos = now;
                // Update current file path in the UI
                model.setCurrentFile(String.format("%05d", taskId), currentFilePath);
                publishTelemetry(model);
                SwingUtilities.invokeLater(model::refresh);
            }
        }

        long chunkTimeoutMillis() {
            long nanos = retransmitTimeoutNanos;
            if (nanos <= 0) {
                nanos = TimeUnit.MILLISECONDS.toNanos(MIN_CHUNK_TIMEOUT_MS);
            }
            long min = TimeUnit.MILLISECONDS.toNanos(MIN_CHUNK_TIMEOUT_MS);
            long max = TimeUnit.MILLISECONDS.toNanos(MAX_CHUNK_TIMEOUT_MS);
            long clamped = Math.max(min, Math.min(max, nanos));
            long millis = TimeUnit.NANOSECONDS.toMillis(clamped);
            return Math.max(MIN_CHUNK_TIMEOUT_MS, millis);
        }

        void updateRtt(long sampleNanos) {
            if (sampleNanos <= 0) return;
            synchronized (rttLock) {
                if (!rttInitialized) {
                    smoothedRttNanos = sampleNanos;
                    rttVarNanos = sampleNanos / 2;
                    retransmitTimeoutNanos = clampTimeout(smoothedRttNanos + smoothedRttNanos);
                    rttInitialized = true;
                    return;
                }
                long delta = sampleNanos - smoothedRttNanos;
                smoothedRttNanos += delta / 8;
                long absDelta = Math.abs(delta);
                rttVarNanos += (absDelta - rttVarNanos) / 4;
                long granularity = TimeUnit.MILLISECONDS.toNanos(RTT_GRANULARITY_MS);
                long baseTimeout = smoothedRttNanos + Math.max(granularity, 4 * rttVarNanos);
                retransmitTimeoutNanos = clampTimeout(baseTimeout);
            }
        }

        private long clampTimeout(long nanos) {
            long min = TimeUnit.MILLISECONDS.toNanos(MIN_CHUNK_TIMEOUT_MS);
            long max = TimeUnit.MILLISECONDS.toNanos(MAX_CHUNK_TIMEOUT_MS);
            if (nanos < min) return min;
            if (nanos > max) return max;
            return nanos;
        }

        SenderFileState ensureFileState(int fileId, SenderEntry entry) throws IOException {
            SenderFileState existing = fileStates.get(fileId);
            if (existing != null) {
                return existing;
            }
            SenderFileState created = new SenderFileState(fileId, entry.path(), entry.totalChunks());
            SenderFileState prev = fileStates.putIfAbsent(fileId, created);
            if (prev != null) {
                created.close();
                return prev;
            }
            return created;
        }

        void cleanupFileState(int fileId) {
            ScheduledFuture<?> timeout = chunkTimeouts.remove(fileId);
            if (timeout != null) {
                timeout.cancel(false);
            }
            SenderFileState state = fileStates.remove(fileId);
            if (state != null) {
                try {
                    state.close();
                } catch (IOException e) {
                    log.log(Level.FINE, "Failed to close channel for fileId " + fileId, e);
                }
            }
        }

        void cleanup() {
            chunkTimeouts.values().forEach(f -> f.cancel(false));
            chunkTimeouts.clear();
            fileStates.values().forEach(state -> {
                try {
                    state.close();
                } catch (IOException e) {
                    log.log(Level.FINE, "Failed to close sender file state", e);
                }
            });
            fileStates.clear();
            if (streamChannel != null) {
                streamChannel.close();
            }
            if (quicChannel != null) {
                quicChannel.close();
            }
            if (udpChannel != null) {
                udpChannel.close();
            }
        }

        private void publishTelemetry(TaskTableModel model) {
            double rttMillis = currentRttMillis();
            int window = aggregateWindowLimit();
            model.setTelemetry(String.format("%05d", taskId), rttMillis, window);
        }

        private double currentRttMillis() {
            if (!rttInitialized) {
                return 0d;
            }
            return smoothedRttNanos / 1_000_000.0;
        }

        private int aggregateWindowLimit() {
            return fileStates.values().stream()
                    .mapToInt(SenderFileState::windowLimit)
                    .sum();
        }

        private int totalInflight() {
            return fileStates.values().stream()
                    .mapToInt(state -> state.inFlight.size())
                    .sum();
        }

        private void maybeLogTelemetry(long now) {
            long elapsed = now - lastTelemetryLogNanos;
            if (elapsed < 1_000_000_000L) {
                return;
            }
            long bytes = task.getBytesTransferred();
            double seconds = elapsed / 1_000_000_000.0;
            double mbPerSec = seconds > 0 ? ((bytes - lastTelemetryBytes) / 1_048_576.0) / seconds : 0;
            double rtt = currentRttMillis();
            int window = aggregateWindowLimit();
            int inflight = totalInflight();
            log.info(String.format("Task %05d telemetry: RTT=%.2f ms window=%d inflight=%d throughput=%.2f MB/s",
                    taskId, rtt, window, inflight, mbPerSec));
            lastTelemetryBytes = bytes;
            lastTelemetryLogNanos = now;
        }
    }

    private record SenderEntry(String relPath, Path path, boolean isDirectory, long totalChunks) {}

    private static class SenderFileState implements AutoCloseable {
        final int fileId;
        final Path path;
        final long totalChunks;
        final FileChannel channel;
        final Object lock = new Object();
        final Map<Long, Long> inFlight = new HashMap<>();
        final Map<Long, Integer> retryCount = new HashMap<>();
        int windowSlots = INITIAL_WINDOW_CHUNKS;
        int ackSinceWindowIncrease = 0;
        long nextSeq = 0;
        boolean streaming = false;
        boolean closed = false;

        SenderFileState(int fileId, Path path, long totalChunks) throws IOException {
            this.fileId = fileId;
            this.path = path;
            this.totalChunks = totalChunks;
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
        }

        int windowLimit() {
            return Math.max(MIN_WINDOW_CHUNKS, Math.min(MAX_WINDOW_CHUNKS, windowSlots));
        }

        void onAck() {
            int limit = windowLimit();
            if (limit >= MAX_WINDOW_CHUNKS) {
                return;
            }
            ackSinceWindowIncrease++;
            if (ackSinceWindowIncrease >= limit) {
                windowSlots = Math.min(MAX_WINDOW_CHUNKS, windowSlots + 1);
                ackSinceWindowIncrease = 0;
            }
        }

        void onTimeout() {
            windowSlots = Math.max(MIN_WINDOW_CHUNKS, windowSlots / 2);
            ackSinceWindowIncrease = 0;
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            channel.close();
        }
    }
}
