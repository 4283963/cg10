package com.cg10.paper.service;

import com.cg10.paper.config.AppProperties;
import com.cg10.paper.model.CylinderHeatmap;
import com.cg10.paper.model.ScanPayload;
import com.cg10.paper.model.ValveCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemperatureProcessor {

    private final AppProperties appProps;
    private final InfluxDBStorageService storageService;
    private final PlcControlService plcControlService;
    private final WebSocketBroadcastService broadcastService;

    private final ConcurrentHashMap<Integer, double[]> latestHeatmaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> lastUpdateTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Double> cylinderValveOpenings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> anomalyCooldown = new ConcurrentHashMap<>();
    private final AtomicInteger anomalyCounter = new AtomicInteger(0);
    private final AtomicInteger activeCompensations = new AtomicInteger(0);

    public void process(ScanPayload payload) {
        int cylinderId = payload.getCid();
        List<Double> temps = payload.getTemps();
        if (temps == null || temps.isEmpty()) {
            return;
        }

        double[] tempArray = temps.stream().mapToDouble(Double::doubleValue).toArray();
        latestHeatmaps.put(cylinderId, tempArray);
        lastUpdateTime.put(cylinderId, payload.getTs());

        double[] zoneTemps = calculateZoneTemperatures(tempArray);
        double avgTemp = Arrays.stream(tempArray).average().orElse(appProps.getCylinder().getBaseTemperature());

        storageService.store(cylinderId, payload.getTs(), tempArray, zoneTemps, avgTemp);

        List<CylinderHeatmap.ZoneAnomaly> anomalies = detectAnomalies(cylinderId, zoneTemps, avgTemp);
        if (!anomalies.isEmpty()) {
            anomalyCounter.addAndGet(anomalies.size());
            Optional<ValveCommand> command = calculateValveAdjustment(cylinderId, zoneTemps, anomalies, avgTemp);
            command.ifPresent(cmd -> {
                plcControlService.sendCommand(cmd);
                activeCompensations.incrementAndGet();
            });
        }

        CylinderHeatmap heatmap = buildHeatmap(cylinderId, payload.getTs(), tempArray, avgTemp, anomalies);
        broadcastService.broadcastHeatmap(heatmap);
    }

    private double[] calculateZoneTemperatures(double[] scanLine) {
        int zoneCount = appProps.getCompensation().getZoneCount();
        int pointsPerZone = scanLine.length / zoneCount;
        double[] zones = new double[zoneCount];

        for (int z = 0; z < zoneCount; z++) {
            int start = z * pointsPerZone;
            int end = (z == zoneCount - 1) ? scanLine.length : start + pointsPerZone;
            double sum = 0.0;
            int count = 0;
            for (int i = start; i < end; i++) {
                sum += scanLine[i];
                count++;
            }
            zones[z] = count > 0 ? sum / count : 0.0;
        }
        return zones;
    }

    private List<CylinderHeatmap.ZoneAnomaly> detectAnomalies(int cylinderId, double[] zoneTemps, double avgTemp) {
        List<CylinderHeatmap.ZoneAnomaly> anomalies = new ArrayList<>();
        double lowThreshold = appProps.getCylinder().getLowThreshold();
        double baseTemp = appProps.getCylinder().getBaseTemperature();
        long now = System.currentTimeMillis();

        for (int z = 0; z < zoneTemps.length; z++) {
            double zoneTemp = zoneTemps[z];
            double deviation = zoneTemp - baseTemp;
            double absDeviation = Math.abs(deviation);

            String cooldownKey = cylinderId + "-" + z;
            Long lastTrigger = anomalyCooldown.get(cooldownKey);
            if (lastTrigger != null && now - lastTrigger < 3000) {
                continue;
            }

            String severity;
            if (zoneTemp < lowThreshold || absDeviation > 10.0) {
                severity = "critical";
            } else if (absDeviation > 6.0) {
                severity = "warning";
            } else if (absDeviation > 3.0) {
                severity = "info";
            } else {
                continue;
            }

            anomalyCooldown.put(cooldownKey, now);
            anomalies.add(CylinderHeatmap.ZoneAnomaly.builder()
                    .zoneIndex(z)
                    .zoneName(getZoneName(z, zoneTemps.length))
                    .deviation(Math.round(deviation * 100.0) / 100.0)
                    .severity(severity)
                    .direction(deviation < 0 ? "low" : "high")
                    .build());

            if ("critical".equals(severity) || "warning".equals(severity)) {
                log.warn("[缸#{}] {}区域温度异常: 偏差{}°C (当前{:.1f}°C, 基准{:.1f}°C), 级别: {}",
                        cylinderId, getZoneName(z, zoneTemps.length),
                        String.format("%+.1f", deviation), zoneTemp, baseTemp, severity);
            }
        }
        return anomalies;
    }

    private String getZoneName(int zoneIndex, int totalZones) {
        if (totalZones < 3) return "Z" + zoneIndex;
        double pos = (double) zoneIndex / (totalZones - 1);
        if (pos < 0.2) return "左" + (zoneIndex + 1);
        if (pos > 0.8) return "右" + (totalZones - zoneIndex);
        if (Math.abs(pos - 0.5) < 0.15) return "中" + (zoneIndex - totalZones / 2 + 1);
        return (pos < 0.5 ? "左中" : "右中") + (zoneIndex + 1);
    }

    private Optional<ValveCommand> calculateValveAdjustment(
            int cylinderId, double[] zoneTemps,
            List<CylinderHeatmap.ZoneAnomaly> anomalies, double avgTemp) {

        double baseTemp = appProps.getCylinder().getBaseTemperature();
        double gain = appProps.getCompensation().getControlGain();
        double maxAdj = appProps.getCompensation().getMaxValveAdjustment();
        double minAdj = appProps.getCompensation().getMinValveAdjustment();

        double currentOpening = cylinderValveOpenings.getOrDefault(cylinderId, 50.0);
        List<ValveCommand.ZoneAdjustment> adjustments = new ArrayList<>();

        boolean hasActionable = false;
        List<String> reasons = new ArrayList<>();

        for (CylinderHeatmap.ZoneAnomaly anomaly : anomalies) {
            if (!"low".equals(anomaly.getDirection())) continue;
            if (!"critical".equals(anomaly.getSeverity()) && !"warning".equals(anomaly.getSeverity())) continue;

            double deficit = Math.abs(anomaly.getDeviation());
            double rawDelta = deficit * gain;
            double delta = Math.max(minAdj, Math.min(maxAdj, rawDelta));

            if (delta < 1.0) continue;

            double targetOpening = Math.min(100.0, currentOpening + delta);
            adjustments.add(ValveCommand.ZoneAdjustment.builder()
                    .zoneIndex(anomaly.getZoneIndex())
                    .zoneName(anomaly.getZoneName())
                    .currentOpening(Math.round(currentOpening * 100.0) / 100.0)
                    .targetOpening(Math.round(targetOpening * 100.0) / 100.0)
                    .delta(Math.round(delta * 100.0) / 100.0)
                    .temperatureDeficit(Math.round(deficit * 100.0) / 100.0)
                    .build());

            reasons.add(String.format("%s区低%.1f°C", anomaly.getZoneName(), deficit));
            hasActionable = true;
        }

        if (!hasActionable) {
            return Optional.empty();
        }

        double newOpening = currentOpening;
        for (ValveCommand.ZoneAdjustment adj : adjustments) {
            newOpening = Math.max(newOpening, adj.getTargetOpening());
        }
        cylinderValveOpenings.put(cylinderId, newOpening);

        boolean emergency = anomalies.stream().anyMatch(a -> "critical".equals(a.getSeverity()));

        return Optional.of(ValveCommand.builder()
                .timestamp(System.currentTimeMillis())
                .cylinderId(cylinderId)
                .adjustments(adjustments)
                .reason(String.join(", ", reasons))
                .emergency(emergency)
                .build());
    }

    private CylinderHeatmap buildHeatmap(int cylinderId, long ts, double[] temps,
                                         double avgTemp, List<CylinderHeatmap.ZoneAnomaly> anomalies) {
        double min = Arrays.stream(temps).min().orElse(avgTemp);
        double max = Arrays.stream(temps).max().orElse(avgTemp);
        List<Double> tempList = new ArrayList<>();
        for (double t : temps) tempList.add(t);

        return CylinderHeatmap.builder()
                .cylinderId(cylinderId)
                .timestamp(ts)
                .temperatures(tempList)
                .minTemp(Math.round(min * 100.0) / 100.0)
                .maxTemp(Math.round(max * 100.0) / 100.0)
                .avgTemp(Math.round(avgTemp * 100.0) / 100.0)
                .anomalies(anomalies)
                .build();
    }

    public Map<Integer, CylinderHeatmap> getAllHeatmaps() {
        Map<Integer, CylinderHeatmap> result = new HashMap<>();
        double baseTemp = appProps.getCylinder().getBaseTemperature();

        for (Map.Entry<Integer, double[]> entry : latestHeatmaps.entrySet()) {
            int id = entry.getKey();
            double[] temps = entry.getValue();
            double avg = Arrays.stream(temps).average().orElse(baseTemp);
            double[] zoneTemps = calculateZoneTemperatures(temps);
            List<CylinderHeatmap.ZoneAnomaly> anomalies = detectAnomaliesQuick(zoneTemps, baseTemp);
            result.put(id, buildHeatmap(id, lastUpdateTime.getOrDefault(id, 0L), temps, avg, anomalies));
        }
        return result;
    }

    private List<CylinderHeatmap.ZoneAnomaly> detectAnomaliesQuick(double[] zoneTemps, double baseTemp) {
        List<CylinderHeatmap.ZoneAnomaly> result = new ArrayList<>();
        for (int z = 0; z < zoneTemps.length; z++) {
            double deviation = zoneTemps[z] - baseTemp;
            if (Math.abs(deviation) > 3.0) {
                result.add(CylinderHeatmap.ZoneAnomaly.builder()
                        .zoneIndex(z)
                        .zoneName(getZoneName(z, zoneTemps.length))
                        .deviation(Math.round(deviation * 100.0) / 100.0)
                        .severity(Math.abs(deviation) > 10.0 ? "critical" : Math.abs(deviation) > 6.0 ? "warning" : "info")
                        .direction(deviation < 0 ? "low" : "high")
                        .build());
            }
        }
        return result;
    }

    public int getAnomalyCount() {
        return anomalyCounter.get();
    }

    public int getActiveCompensations() {
        return activeCompensations.get();
    }

    public Map<Integer, Double> getCylinderValveOpenings() {
        return new HashMap<>(cylinderValveOpenings);
    }
}
