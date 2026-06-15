package com.cg10.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemStatus {
    private boolean tcpServerRunning;
    private int connectedClients;
    private long totalScansReceived;
    private long scansPerSecond;
    private int anomalyCount;
    private int activeCompensations;
    private boolean influxDbConnected;
    private List<CylinderStatus> cylinders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CylinderStatus {
        private int id;
        private double avgTemp;
        private String state;
        private int anomalyZones;
    }
}
