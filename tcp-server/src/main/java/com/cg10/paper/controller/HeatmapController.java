package com.cg10.paper.controller;

import com.cg10.paper.config.AppProperties;
import com.cg10.paper.model.CylinderHeatmap;
import com.cg10.paper.model.CylinderPhase;
import com.cg10.paper.model.SystemStatus;
import com.cg10.paper.model.ValveCommand;
import com.cg10.paper.plc.PlcControlService;
import com.cg10.paper.service.InfluxDBStorageService;
import com.cg10.paper.service.PhaseManager;
import com.cg10.paper.service.TemperatureProcessor;
import com.cg10.paper.tcpserver.TcpServer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HeatmapController {

    private final TemperatureProcessor temperatureProcessor;
    private final TcpServer tcpServer;
    private final InfluxDBStorageService storageService;
    private final PlcControlService plcControlService;
    private final AppProperties appProps;
    private final PhaseManager phaseManager;

    @GetMapping("/heatmaps")
    public Map<String, Object> getAllHeatmaps() {
        Map<Integer, CylinderHeatmap> heatmaps = temperatureProcessor.getAllHeatmaps();
        List<CylinderHeatmap> sorted = heatmaps.values().stream()
                .sorted(Comparator.comparingInt(CylinderHeatmap::getCylinderId))
                .collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("total", appProps.getCylinder().getCount());
        result.put("scanned", sorted.size());
        result.put("scanPoints", appProps.getCylinder().getScanPoints());
        result.put("baseTemperature", appProps.getCylinder().getBaseTemperature());
        result.put("lowThreshold", appProps.getCylinder().getLowThreshold());
        result.put("highThreshold", appProps.getCylinder().getHighThreshold());
        result.put("heatmaps", sorted);
        return result;
    }

    @GetMapping("/heatmaps/{cylinderId}")
    public CylinderHeatmap getHeatmap(@PathVariable int cylinderId) {
        return temperatureProcessor.getAllHeatmaps().get(cylinderId);
    }

    @GetMapping("/status")
    public SystemStatus getSystemStatus() {
        Map<Integer, CylinderHeatmap> heatmaps = temperatureProcessor.getAllHeatmaps();
        List<SystemStatus.CylinderStatus> cylinderStatuses = new ArrayList<>();

        for (int i = 1; i <= appProps.getCylinder().getCount(); i++) {
            CylinderHeatmap hm = heatmaps.get(i);
            SystemStatus.CylinderStatus status;
            if (hm == null) {
                status = SystemStatus.CylinderStatus.builder()
                        .id(i)
                        .avgTemp(0)
                        .state("offline")
                        .anomalyZones(0)
                        .build();
            } else {
                String state = "normal";
                if (!hm.getAnomalies().isEmpty()) {
                    boolean hasCritical = hm.getAnomalies().stream()
                            .anyMatch(a -> "critical".equals(a.getSeverity()));
                    state = hasCritical ? "critical" : "warning";
                }
                status = SystemStatus.CylinderStatus.builder()
                        .id(i)
                        .avgTemp(hm.getAvgTemp())
                        .state(state)
                        .anomalyZones(hm.getAnomalies().size())
                        .build();
            }
            cylinderStatuses.add(status);
        }

        return SystemStatus.builder()
                .tcpServerRunning(tcpServer.isRunning())
                .connectedClients(tcpServer.getConnectedClients())
                .totalScansReceived(tcpServer.getTotalScansReceived())
                .scansPerSecond(tcpServer.getScansPerSecond())
                .anomalyCount(temperatureProcessor.getAnomalyCount())
                .activeCompensations(temperatureProcessor.getActiveCompensations())
                .influxDbConnected(storageService.isConnected())
                .cylinders(cylinderStatuses)
                .build();
    }

    @GetMapping("/valve-commands")
    public List<ValveCommand> getRecentValveCommands(
            @RequestParam(defaultValue = "50") int limit) {
        return plcControlService.getRecentCommands(Math.min(limit, 200));
    }

    @GetMapping("/valve-openings")
    public Map<Integer, Double> getValveOpenings() {
        return temperatureProcessor.getCylinderValveOpenings();
    }

    @PostMapping("/valve-commands/manual")
    public Map<String, Object> sendManualCommand(@RequestBody Map<String, Object> body) {
        int cylinderId = (Integer) body.get("cylinderId");
        double delta = ((Number) body.get("delta")).doubleValue();
        int zoneIndex = body.containsKey("zoneIndex") ? (Integer) body.get("zoneIndex") : 0;

        if (phaseManager.isCompensationLocked(cylinderId)) {
            CylinderPhase phase = phaseManager.getPhase(cylinderId);
            Map<String, Object> reject = new HashMap<>();
            reject.put("success", false);
            reject.put("reason", "避让保护生效");
            reject.put("phase", phase.getPhase());
            reject.put("phaseLabel", phase.getPhaseLabel());
            reject.put("message", String.format("烘缸 #%d 当前为「%s」，蒸汽阀门控制已锁定，禁止手动操作",
                    cylinderId, phase.getPhaseLabel()));
            return reject;
        }

        double current = temperatureProcessor.getCylinderValveOpenings()
                .getOrDefault(cylinderId, 50.0);
        double target = Math.min(100.0, Math.max(0.0, current + delta));

        ValveCommand cmd = ValveCommand.builder()
                .timestamp(System.currentTimeMillis())
                .cylinderId(cylinderId)
                .adjustments(List.of(ValveCommand.ZoneAdjustment.builder()
                        .zoneIndex(zoneIndex)
                        .zoneName("手动")
                        .currentOpening(current)
                        .targetOpening(target)
                        .delta(delta)
                        .temperatureDeficit(0)
                        .build()))
                .reason("手动干预")
                .emergency(false)
                .build();

        plcControlService.sendCommand(cmd);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("command", cmd);
        return resp;
    }

    @GetMapping("/phases")
    public Map<Integer, CylinderPhase> getAllPhases() {
        return phaseManager.getAllPhases();
    }

    @GetMapping("/phases/{cylinderId}")
    public CylinderPhase getPhase(@PathVariable int cylinderId) {
        return phaseManager.getPhase(cylinderId);
    }

    @PutMapping("/phases/{cylinderId}")
    public CylinderPhase setPhase(
            @PathVariable int cylinderId,
            @RequestBody Map<String, String> body) {
        String phase = body.getOrDefault("phase", CylinderPhase.PRODUCTION);
        String operator = body.getOrDefault("operator", "web");
        return phaseManager.setPhase(cylinderId, phase, operator);
    }

    @PutMapping("/phases/batch")
    public Map<String, Object> batchSetPhase(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("cylinderIds");
        String phase = (String) body.getOrDefault("phase", CylinderPhase.PRODUCTION);
        String operator = (String) body.getOrDefault("operator", "web");
        phaseManager.batchSetPhase(ids, phase, operator);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("updatedCount", ids.size());
        resp.put("phase", phase);
        resp.put("phaseLabel", CylinderPhase.getPhaseLabel(phase));
        return resp;
    }
}
