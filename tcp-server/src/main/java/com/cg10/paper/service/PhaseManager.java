package com.cg10.paper.service;

import com.cg10.paper.config.AppProperties;
import com.cg10.paper.model.CylinderPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhaseManager {

    private final AppProperties appProps;

    private final ConcurrentHashMap<Integer, CylinderPhase> phaseMap = new ConcurrentHashMap<>();

    public CylinderPhase getPhase(int cylinderId) {
        return phaseMap.computeIfAbsent(cylinderId, id -> CylinderPhase.builder()
                .cylinderId(id)
                .phase(CylinderPhase.PRODUCTION)
                .phaseLabel(CylinderPhase.getPhaseLabel(CylinderPhase.PRODUCTION))
                .compensationLocked(false)
                .changedAt(System.currentTimeMillis())
                .changedBy("system")
                .build());
    }

    public CylinderPhase setPhase(int cylinderId, String newPhase, String operator) {
        String validated = validatePhase(newPhase);
        CylinderPhase old = getPhase(cylinderId);

        if (old.getPhase().equals(validated)) {
            return old;
        }

        CylinderPhase updated = CylinderPhase.builder()
                .cylinderId(cylinderId)
                .phase(validated)
                .phaseLabel(CylinderPhase.getPhaseLabel(validated))
                .compensationLocked(CylinderPhase.isCompensationLocked(validated))
                .changedAt(System.currentTimeMillis())
                .changedBy(operator)
                .build();
        phaseMap.put(cylinderId, updated);

        if (updated.isCompensationLocked()) {
            log.warn("🔒 [缸#{}] 设备操作阶段切换为「{}」，蒸汽热力补偿自动锁定，阀门控制已避让保护",
                    cylinderId, updated.getPhaseLabel());
        } else {
            log.info("🔓 [缸#{}] 设备操作阶段切换为「{}」，蒸汽热力补偿恢复正常响应",
                    cylinderId, updated.getPhaseLabel());
        }

        return updated;
    }

    public boolean isCompensationLocked(int cylinderId) {
        return getPhase(cylinderId).isCompensationLocked();
    }

    public Map<Integer, CylinderPhase> getAllPhases() {
        Map<Integer, CylinderPhase> result = new HashMap<>();
        for (int i = 1; i <= appProps.getCylinder().getCount(); i++) {
            result.put(i, getPhase(i));
        }
        return result;
    }

    public void batchSetPhase(List<Integer> cylinderIds, String newPhase, String operator) {
        String validated = validatePhase(newPhase);
        for (int id : cylinderIds) {
            setPhase(id, validated, operator);
        }
        log.info("📋 批量设置 {} 个烘缸阶段为「{}」，操作人: {}",
                cylinderIds.size(), CylinderPhase.getPhaseLabel(validated), operator);
    }

    private String validatePhase(String phase) {
        if (CylinderPhase.PRODUCTION.equals(phase)
                || CylinderPhase.WARMING_UP.equals(phase)
                || CylinderPhase.COOLING_DOWN.equals(phase)) {
            return phase;
        }
        log.warn("未知阶段标识「{}」，回退为生产运行中", phase);
        return CylinderPhase.PRODUCTION;
    }
}
