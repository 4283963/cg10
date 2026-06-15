<template>
  <div class="overview-panel">
    <div class="panel-title">
      <span class="title-icon">🎯</span>
      <span>烘缸总览</span>
      <span class="legend">
        <span class="legend-item"><span class="dot ok"></span>正常</span>
        <span class="legend-item"><span class="dot warn"></span>预警</span>
        <span class="legend-item"><span class="dot danger"></span>严重</span>
        <span class="legend-item"><span class="dot offline"></span>离线</span>
        <span class="legend-item"><span class="dot warm"></span>热机</span>
        <span class="legend-item"><span class="dot cool"></span>冷机</span>
      </span>
    </div>
    <div class="cylinder-grid">
      <div
        v-for="id in 40"
        :key="id"
        class="cylinder-cell"
        :class="[getCellState(id), { selected: id === selectedId }]"
        @click="$emit('select', id)"
      >
        <div class="cell-id">#{{ String(id).padStart(2, '0') }}</div>
        <div class="cell-temp" v-if="getHeatmap(id)">
          {{ getHeatmap(id)?.avgTemp?.toFixed(1) }}°C
        </div>
        <div class="cell-temp offline" v-else>--</div>
        <div class="cell-anomaly" v-if="getHeatmap(id)?.anomalies?.length">
          {{ getHeatmap(id).anomalies.length }}区
        </div>
        <div class="cell-phase" v-if="getHeatmap(id)?.phase && getHeatmap(id).phase !== 'production'"
             :class="getHeatmap(id).phase">
          {{ getHeatmap(id).phase === 'warming_up' ? '热' : '冷' }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  heatmaps: { type: Array, default: () => [] },
  selectedId: Number
})

defineEmits(['select'])

const heatmaps = defineProps({
  heatmaps: { type: Array, default: () => [] }
}).heatmaps

function getHeatmap(id) {
  return heatmaps.find(h => h.cylinderId === id)
}

function getCellState(id) {
  const hm = getHeatmap(id)
  if (!hm) return 'offline'
  if (hm.phase === 'warming_up') return 'warming'
  if (hm.phase === 'cooling_down') return 'cooling'
  if (!hm.anomalies || hm.anomalies.length === 0) return 'ok'
  if (hm.anomalies.some(a => a.severity === 'critical')) return 'danger'
  return 'warn'
}
</script>

<style scoped>
.overview-panel {
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
  margin-bottom: 12px;
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
  flex-wrap: wrap;
}

.title-icon { font-size: 16px; }

.legend {
  margin-left: auto;
  display: flex;
  gap: 10px;
  font-size: 10px;
  color: rgba(148, 163, 184, 0.6);
  font-weight: normal;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.legend .dot.ok { background: #22c55e; }
.legend .dot.warn { background: #f59e0b; }
.legend .dot.danger { background: #ef4444; }
.legend .dot.offline { background: #475569; }
.legend .dot.warm { background: #fbbf24; }
.legend .dot.cool { background: #60a5fa; }

.cylinder-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  grid-auto-rows: 1fr;
  gap: 6px;
  overflow-y: auto;
  min-height: 0;
}

.cylinder-cell {
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(71, 85, 105, 0.3);
  border-radius: 5px;
  padding: 6px 4px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 52px;
  position: relative;
}

.cylinder-cell:hover {
  transform: translateY(-1px);
  border-color: rgba(96, 165, 250, 0.5);
}

.cylinder-cell.selected {
  border-color: #60a5fa;
  box-shadow: 0 0 12px rgba(96, 165, 250, 0.4);
  background: rgba(59, 130, 246, 0.15);
}

.cylinder-cell.ok {
  border-left: 3px solid #22c55e;
}
.cylinder-cell.warn {
  border-left: 3px solid #f59e0b;
  background: rgba(245, 158, 11, 0.08);
  animation: warnPulse 2s infinite;
}
.cylinder-cell.danger {
  border-left: 3px solid #ef4444;
  background: rgba(239, 68, 68, 0.12);
  animation: dangerPulse 1s infinite;
}
.cylinder-cell.offline {
  border-left: 3px solid #475569;
  opacity: 0.5;
}
.cylinder-cell.warming {
  border-left: 3px solid #fbbf24;
  background: rgba(251, 191, 36, 0.08);
}
.cylinder-cell.cooling {
  border-left: 3px solid #60a5fa;
  background: rgba(96, 165, 250, 0.06);
}

.cell-id {
  font-size: 11px;
  font-weight: 600;
  color: rgba(148, 163, 184, 0.9);
  font-family: 'Courier New', monospace;
}

.cell-temp {
  font-size: 13px;
  font-weight: 700;
  color: #e2e8f0;
  font-family: 'Courier New', monospace;
  margin-top: 2px;
}

.cell-temp.offline {
  color: rgba(100, 116, 139, 0.5);
}

.cell-anomaly {
  position: absolute;
  top: 2px;
  right: 4px;
  font-size: 9px;
  color: #f87171;
  font-weight: 600;
}

.cell-phase {
  position: absolute;
  bottom: 2px;
  right: 3px;
  font-size: 8px;
  font-weight: 700;
  padding: 0 4px;
  border-radius: 3px;
  line-height: 14px;
}

.cell-phase.warming_up {
  background: rgba(251, 191, 36, 0.3);
  color: #fde68a;
}

.cell-phase.cooling_down {
  background: rgba(96, 165, 250, 0.3);
  color: #bfdbfe;
}

@keyframes warnPulse {
  0%, 100% { box-shadow: none; }
  50% { box-shadow: 0 0 6px rgba(245, 158, 11, 0.3); }
}

@keyframes dangerPulse {
  0%, 100% { box-shadow: none; }
  50% { box-shadow: 0 0 10px rgba(239, 68, 68, 0.5); }
}
</style>
