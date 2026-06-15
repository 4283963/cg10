<template>
  <div class="log-panel">
    <div class="panel-title">
      <span class="title-icon">🔧</span>
      <span>PLC 蒸汽阀门补偿指令</span>
      <span class="log-count">共 {{ commands.length }} 条</span>
    </div>
    <div class="log-list" ref="listRef">
      <div v-if="!commands.length" class="empty-log">
        <div>暂无补偿指令</div>
        <div class="sub">系统运行正常时不会产生指令</div>
      </div>
      <div
        v-for="(cmd, idx) in commands"
        :key="cmd.timestamp + '-' + idx"
        class="log-item"
        :class="{ emergency: cmd.emergency }"
      >
        <div class="log-header">
          <span class="cmd-id">#{{ commands.length - idx }}</span>
          <span class="cylinder">烘缸 #{{ String(cmd.cylinderId).padStart(2, '0') }}</span>
          <span class="time">{{ formatTime(cmd.timestamp) }}</span>
          <span v-if="cmd.emergency" class="emergency-tag">紧急</span>
        </div>
        <div class="log-reason">{{ cmd.reason }}</div>
        <div class="adjustments">
          <div
            v-for="(adj, i) in cmd.adjustments"
            :key="i"
            class="adj-item"
          >
            <span class="zone">{{ adj.zoneName }}</span>
            <span class="opening">
              {{ adj.currentOpening.toFixed(1) }}°
              <span class="arrow">→</span>
              <span class="target">{{ adj.targetOpening.toFixed(1) }}°</span>
            </span>
            <span class="delta" :class="adj.delta >= 0 ? 'up' : 'down'">
              {{ adj.delta >= 0 ? '▲' : '▼' }}{{ Math.abs(adj.delta).toFixed(1) }}°
            </span>
            <span class="deficit">缺温 {{ adj.temperatureDeficit.toFixed(1) }}°C</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  commands: { type: Array, default: () => [] }
})

function formatTime(ts) {
  const d = new Date(ts)
  const pad = n => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<style scoped>
.log-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 14px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #cbd5e1;
  padding-bottom: 12px;
  margin-bottom: 10px;
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
}

.title-icon { font-size: 16px; }

.log-count {
  margin-left: auto;
  font-weight: normal;
  font-size: 11px;
  color: rgba(148, 163, 184, 0.6);
}

.log-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.empty-log {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: rgba(148, 163, 184, 0.5);
  font-size: 13px;
}

.empty-log .sub {
  font-size: 11px;
  margin-top: 4px;
  color: rgba(100, 116, 139, 0.5);
}

.log-item {
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(71, 85, 105, 0.3);
  border-left: 3px solid #3b82f6;
  border-radius: 6px;
  padding: 10px 12px;
}

.log-item.emergency {
  border-left-color: #ef4444;
  background: rgba(127, 29, 29, 0.15);
  animation: emergBlink 1s infinite;
}

@keyframes emergBlink {
  0%, 100% { border-color: rgba(239, 68, 68, 0.4); border-left-color: #ef4444; }
  50% { border-color: rgba(239, 68, 68, 0.8); border-left-color: #f87171; }
}

.log-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  margin-bottom: 6px;
}

.cmd-id {
  color: rgba(148, 163, 184, 0.6);
  font-family: 'Courier New', monospace;
}

.cylinder {
  font-weight: 600;
  color: #93c5fd;
}

.time {
  font-family: 'Courier New', monospace;
  color: rgba(148, 163, 184, 0.7);
  margin-left: auto;
  font-size: 11px;
}

.emergency-tag {
  background: rgba(239, 68, 68, 0.3);
  color: #fca5a5;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 600;
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.log-reason {
  font-size: 11px;
  color: #fbbf24;
  margin-bottom: 8px;
  padding: 4px 8px;
  background: rgba(146, 64, 14, 0.2);
  border-radius: 3px;
  display: inline-block;
}

.adjustments {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.adj-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  padding: 4px 8px;
  background: rgba(15, 23, 42, 0.5);
  border-radius: 3px;
}

.zone {
  font-weight: 600;
  color: #cbd5e1;
  min-width: 40px;
}

.opening {
  color: rgba(148, 163, 184, 0.8);
  font-family: 'Courier New', monospace;
}

.arrow {
  margin: 0 4px;
  color: rgba(148, 163, 184, 0.5);
}

.target {
  color: #4ade80;
  font-weight: 600;
}

.delta {
  font-weight: 700;
  font-family: 'Courier New', monospace;
}

.delta.up { color: #4ade80; }
.delta.down { color: #f87171; }

.deficit {
  margin-left: auto;
  color: rgba(248, 113, 113, 0.8);
  font-size: 10px;
}
</style>
