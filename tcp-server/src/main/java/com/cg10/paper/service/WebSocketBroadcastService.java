package com.cg10.paper.service;

import com.cg10.paper.config.AppProperties;
import com.cg10.paper.model.CylinderHeatmap;
import com.cg10.paper.web.HeatmapWebSocketHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketBroadcastService {

    private final HeatmapWebSocketHandler wsHandler;
    private final AppProperties appProps;

    private final ConcurrentHashMap<Integer, CylinderHeatmap> pendingBroadcasts = new ConcurrentHashMap<>();
    private final AtomicLong broadcastCount = new AtomicLong(0);

    @PostConstruct
    public void startBroadcaster() {
        int intervalMs = appProps.getWebsocket().getBroadcastIntervalMs();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-broadcaster");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::flushBroadcasts, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("WebSocket 广播服务已启动，间隔: {}ms", intervalMs);
    }

    public void broadcastHeatmap(CylinderHeatmap heatmap) {
        pendingBroadcasts.put(heatmap.getCylinderId(), heatmap);
    }

    private void flushBroadcasts() {
        if (pendingBroadcasts.isEmpty()) return;
        if (wsHandler.getConnectedCount() == 0) {
            pendingBroadcasts.clear();
            return;
        }
        for (CylinderHeatmap heatmap : pendingBroadcasts.values()) {
            wsHandler.broadcast(heatmap);
            broadcastCount.incrementAndGet();
        }
        pendingBroadcasts.clear();
    }

    public long getBroadcastCount() {
        return broadcastCount.get();
    }
}
