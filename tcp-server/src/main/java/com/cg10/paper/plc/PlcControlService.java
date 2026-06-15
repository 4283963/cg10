package com.cg10.paper.plc;

import com.cg10.paper.model.ValveCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class PlcControlService {

    private final ConcurrentLinkedDeque<ValveCommand> commandHistory = new ConcurrentLinkedDeque<>();
    private final AtomicLong commandCount = new AtomicLong(0);
    private final AtomicLong emergencyCount = new AtomicLong(0);

    public void sendCommand(ValveCommand command) {
        long id = commandCount.incrementAndGet();

        if (command.isEmergency()) {
            emergencyCount.incrementAndGet();
            log.error("""
                            
                    ╔══════════════════════════════════════════════════════════════╗
                    ║  🚨 紧急蒸汽补偿指令 #{} 已下发至 PLC                          ║
                    ╠══════════════════════════════════════════════════════════════╣
                    ║  烘缸: #{}   时间: {}                                         ║
                    ║  原因: {}                                                     ║
                    ║  调整: {}                                                     ║
                    ╚══════════════════════════════════════════════════════════════╝
                    """, id, command.getCylinderId(),
                    Instant.ofEpochMilli(command.getTimestamp()),
                    command.getReason(),
                    formatAdjustments(command));
        } else {
            log.info("""
                            
                    ┌──────────────────────────────────────────────────────────────┐
                    │  ⚙ 蒸汽补偿指令 #{} 已下发至 PLC                              │
                    ├──────────────────────────────────────────────────────────────┤
                    │  烘缸: #{}   时间: {}                                         │
                    │  原因: {}                                                     │
                    │  调整: {}                                                     │
                    └──────────────────────────────────────────────────────────────┘
                    """, id, command.getCylinderId(),
                    Instant.ofEpochMilli(command.getTimestamp()),
                    command.getReason(),
                    formatAdjustments(command));
        }

        commandHistory.addFirst(command);
        while (commandHistory.size() > 500) {
            commandHistory.removeLast();
        }

        executePlcWrite(command);
    }

    private String formatAdjustments(ValveCommand command) {
        StringBuilder sb = new StringBuilder();
        for (ValveCommand.ZoneAdjustment adj : command.getAdjustments()) {
            sb.append(String.format("[%s: %.1f°→%.1f° (Δ%+.1f°) 缺温%.1f°C] ",
                    adj.getZoneName(), adj.getCurrentOpening(),
                    adj.getTargetOpening(), adj.getDelta(),
                    adj.getTemperatureDeficit()));
        }
        return sb.toString().trim();
    }

    private void executePlcWrite(ValveCommand command) {
        // TODO: 实际生产环境这里通过 Modbus TCP / OPC UA / S7 协议写入PLC
        // 例如:
        // modbusClient.writeSingleRegister(cylinderId * 100 + zoneIndex, (int)(targetOpening * 10));
        log.debug("PLC写入模拟: 缸#{} 调整 {} 个区域阀门", command.getCylinderId(),
                command.getAdjustments().size());
    }

    public List<ValveCommand> getRecentCommands(int limit) {
        List<ValveCommand> result = new ArrayList<>();
        int count = 0;
        for (ValveCommand cmd : commandHistory) {
            if (count++ >= limit) break;
            result.add(cmd);
        }
        return result;
    }

    public long getCommandCount() {
        return commandCount.get();
    }

    public long getEmergencyCount() {
        return emergencyCount.get();
    }
}
