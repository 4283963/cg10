package com.cg10.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CylinderHeatmap {
    private int cylinderId;
    private long timestamp;
    private List<Double> temperatures;
    private double minTemp;
    private double maxTemp;
    private double avgTemp;
    private List<ZoneAnomaly> anomalies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneAnomaly {
        private int zoneIndex;
        private String zoneName;
        private double deviation;
        private String severity;
        private String direction;
    }
}
