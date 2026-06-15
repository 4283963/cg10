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
public class ValveCommand {
    private long timestamp;
    private int cylinderId;
    private List<ZoneAdjustment> adjustments;
    private String reason;
    private boolean emergency;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneAdjustment {
        private int zoneIndex;
        private String zoneName;
        private double currentOpening;
        private double targetOpening;
        private double delta;
        private double temperatureDeficit;
    }
}
