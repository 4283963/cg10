package com.cg10.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CylinderPhase {
    public static final String PRODUCTION = "production";
    public static final String WARMING_UP = "warming_up";
    public static final String COOLING_DOWN = "cooling_down";

    private int cylinderId;
    private String phase;
    private String phaseLabel;
    private boolean compensationLocked;
    private long changedAt;
    private String changedBy;

    public static String getPhaseLabel(String phase) {
        return switch (phase) {
            case PRODUCTION -> "生产运行中";
            case WARMING_UP -> "开机热机中";
            case COOLING_DOWN -> "维护冷机中";
            default -> "未知";
        };
    }

    public static boolean isCompensationLocked(String phase) {
        return !PRODUCTION.equals(phase);
    }
}
