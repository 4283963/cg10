<template>
  <div class="detail-panel">
    <div class="panel-title">
      <span class="title-icon">🌡️</span>
      <span>烘缸 #{{ String(heatmap.cylinderId).padStart(2, '0') }} 表面温度分布</span>
      <div class="title-stats">
        <span class="stat"><span class="label">最低</span><span class="val cold">{{ heatmap.minTemp?.toFixed(1) }}°C</span></span>
        <span class="stat"><span class="label">平均</span><span class="val">{{ heatmap.avgTemp?.toFixed(1) }}°C</span></span>
        <span class="stat"><span class="label">最高</span><span class="val hot">{{ heatmap.maxTemp?.toFixed(1) }}°C</span></span>
      </div>
    </div>

    <div class="phase-bar" :class="heatmap.phase">
      <div class="phase-left">
        <span class="phase-label">设备操作阶段</span>
        <select
          class="phase-select"
          :value="heatmap.phase"
          @change="onPhaseChange($event)"
        >
          <option value="production">生产运行中</option>
          <option value="warming_up">开机热机中</option>
          <option value="cooling_down">维护冷机中</option>
        </select>
        <span class="phase-badge" :class="heatmap.phase">
          {{ heatmap.phaseLabel || '生产运行中' }}
        </span>
      </div>
      <div class="phase-right">
        <div class="valve-status" :class="{ locked: heatmap.compensationLocked }">
          <span class="valve-icon">{{ heatmap.compensationLocked ? '🔒' : '🔓' }}</span>
          <span class="valve-text">
            {{ heatmap.compensationLocked ? '蒸汽阀门已锁定 · 非生产避让状态' : '蒸汽阀门正常响应' }}
          </span>
        </div>
      </div>
    </div>

    <div v-if="heatmap.compensationLocked" class="avoidance-notice">
      <div class="notice-icon">⚠️</div>
      <div class="notice-content">
        <div class="notice-title">热机状态智能避让保护已生效</div>
        <div class="notice-desc">
          当前阶段为「{{ heatmap.phaseLabel }}」，热传导计算与热力图刷新正常进行，
          但自动热力补偿下发蒸汽阀门微调的逻辑已完全锁死不响应，
          保护物理阀门不在非生产阶段被误操作拧坏。
        </div>
      </div>
    </div>

    <div class="heatmap-area">
      <v-chart :option="chartOption" autoresize class="chart" />
    </div>

    <div class="anomaly-section" v-if="heatmap.anomalies && heatmap.anomalies.length">
      <div class="anomaly-title">
        <span>⚠️ 温度异常区域</span>
        <span class="anomaly-count">{{ heatmap.anomalies.length }} 个区域</span>
        <span v-if="heatmap.compensationLocked" class="lock-hint">
          🔒 避让保护中 · 补偿指令已拦截
        </span>
      </div>
      <div class="anomaly-list">
        <div
          v-for="(a, idx) in heatmap.anomalies"
          :key="idx"
          class="anomaly-item"
          :class="[a.severity, { avoided: heatmap.compensationLocked }]"
        >
          <span class="zone-name">{{ a.zoneName }}区</span>
          <span class="deviation" :class="a.direction">
            {{ a.direction === 'low' ? '偏低' : '偏高' }} {{ Math.abs(a.deviation).toFixed(1) }}°C
          </span>
          <span class="severity-badge">{{ severityText(a.severity) }}</span>
          <span v-if="heatmap.compensationLocked" class="avoid-badge">已拦截</span>
        </div>
      </div>
    </div>

    <div class="anomaly-section normal" v-else>
      <div class="normal-state">✅ 温度分布均匀，各区域运行正常</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { HeatmapChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  VisualMapComponent
} from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, HeatmapChart, GridComponent, TooltipComponent, VisualMapComponent])

const props = defineProps({
  heatmap: Object,
  baseTemperature: { type: Number, default: 105 },
  lowThreshold: { type: Number, default: 95 }
})

const emit = defineEmits(['set-phase'])

function severityText(s) {
  return { critical: '严重', warning: '预警', info: '提示' }[s] || s
}

function onPhaseChange(event) {
  const newPhase = event.target.value
  emit('set-phase', { cylinderId: props.heatmap.cylinderId, phase: newPhase })
}

const chartOption = computed(() => {
  const temps = props.heatmap?.temperatures || []
  const rows = 8
  const cols = temps.length

  const data = []
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const baseT = temps[c] || props.baseTemperature
      const noise = (Math.sin(r * 1.5 + c * 0.3) * 0.8 + (Math.random() - 0.5) * 0.6)
      data.push([c, r, +(baseT + noise).toFixed(2)])
    }
  }

  const minT = Math.max(60, (props.heatmap?.minTemp || 90) - 5)
  const maxT = Math.min(150, (props.heatmap?.maxTemp || 120) + 5)

  return {
    backgroundColor: 'transparent',
    tooltip: {
      position: 'top',
      backgroundColor: 'rgba(15, 23, 42, 0.95)',
      borderColor: 'rgba(59, 130, 246, 0.3)',
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      formatter: (p) => `扫描点 ${p.data[0] + 1} / 行 ${p.data[1] + 1}<br/>温度: <b style="color:#fbbf24">${p.data[2].toFixed(2)}°C</b>`
    },
    grid: {
      left: 50,
      right: 60,
      top: 20,
      bottom: 50
    },
    xAxis: {
      type: 'category',
      data: Array.from({ length: cols }, (_, i) => i + 1),
      axisLabel: {
        color: 'rgba(148, 163, 184, 0.7)',
        fontSize: 10,
        interval: 7,
        formatter: (v) => `P${v}`
      },
      axisLine: { lineStyle: { color: 'rgba(100, 116, 139, 0.2)' } },
      splitLine: { show: false },
      name: '横向扫描点 (左 → 右)',
      nameLocation: 'middle',
      nameGap: 32,
      nameTextStyle: { color: 'rgba(148, 163, 184, 0.6)', fontSize: 11 }
    },
    yAxis: {
      type: 'category',
      data: Array.from({ length: rows }, (_, i) => `L${rows - i}`),
      axisLabel: { color: 'rgba(148, 163, 184, 0.7)', fontSize: 10 },
      axisLine: { lineStyle: { color: 'rgba(100, 116, 139, 0.2)' } },
      splitLine: { show: false },
      name: '烘缸轴向',
      nameTextStyle: { color: 'rgba(148, 163, 184, 0.6)', fontSize: 11 }
    },
    visualMap: {
      min: minT,
      max: maxT,
      calculable: true,
      orient: 'vertical',
      right: 10,
      top: 'center',
      inRange: {
        color: [
          '#1e40af', '#2563eb', '#3b82f6', '#60a5fa', '#93c5fd',
          '#fef3c7', '#fbbf24', '#f59e0b', '#ef4444', '#dc2626'
        ]
      },
      textStyle: { color: 'rgba(148, 163, 184, 0.8)', fontSize: 10 },
      formatter: (v) => `${v.toFixed(0)}°C`
    },
    series: [{
      name: '温度',
      type: 'heatmap',
      data: data,
      label: { show: false },
      emphasis: {
        itemStyle: {
          shadowBlur: 10,
          shadowColor: 'rgba(0, 0, 0, 0.5)'
        }
      },
      itemStyle: { borderWidth: 0 }
    }]
  }
})
</script>

<style scoped>
.detail-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 14px 18px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: #cbd5e1;
  padding-bottom: 12px;
  margin-bottom: 10px;
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
}

.title-icon { font-size: 18px; }

.title-stats {
  margin-left: auto;
  display: flex;
  gap: 16px;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
}

.stat .label {
  color: rgba(148, 163, 184, 0.6);
  font-weight: normal;
}

.stat .val {
  font-family: 'Courier New', monospace;
  font-weight: 700;
  font-size: 15px;
  color: #e2e8f0;
}

.stat .val.cold { color: #60a5fa; }
.stat .val.hot { color: #f87171; }

.phase-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  border-radius: 6px;
  margin-bottom: 10px;
  border: 1px solid;
  gap: 12px;
}

.phase-bar.production {
  background: rgba(22, 101, 52, 0.12);
  border-color: rgba(34, 197, 94, 0.25);
}

.phase-bar.warming_up {
  background: rgba(146, 64, 14, 0.15);
  border-color: rgba(251, 191, 36, 0.35);
}

.phase-bar.cooling_down {
  background: rgba(59, 130, 246, 0.1);
  border-color: rgba(96, 165, 250, 0.3);
}

.phase-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.phase-label {
  font-size: 12px;
  color: rgba(148, 163, 184, 0.7);
  white-space: nowrap;
}

.phase-select {
  background: rgba(15, 23, 42, 0.8);
  color: #e2e8f0;
  border: 1px solid rgba(100, 116, 139, 0.4);
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  outline: none;
  transition: border-color 0.2s;
}

.phase-select:hover {
  border-color: rgba(96, 165, 250, 0.6);
}

.phase-select:focus {
  border-color: #60a5fa;
  box-shadow: 0 0 0 2px rgba(96, 165, 250, 0.2);
}

.phase-select option {
  background: #1e293b;
  color: #e2e8f0;
}

.phase-badge {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.phase-badge.production {
  background: rgba(34, 197, 94, 0.2);
  color: #86efac;
}

.phase-badge.warming_up {
  background: rgba(251, 191, 36, 0.2);
  color: #fde68a;
  animation: warmPulse 2s infinite;
}

.phase-badge.cooling_down {
  background: rgba(96, 165, 250, 0.2);
  color: #bfdbfe;
}

@keyframes warmPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.phase-right {
  display: flex;
  align-items: center;
}

.valve-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 4px;
  background: rgba(34, 197, 94, 0.12);
  border: 1px solid rgba(34, 197, 94, 0.25);
}

.valve-status.locked {
  background: rgba(100, 116, 139, 0.15);
  border: 1px solid rgba(148, 163, 184, 0.3);
}

.valve-icon {
  font-size: 14px;
}

.valve-text {
  color: #86efac;
  font-weight: 600;
}

.valve-status.locked .valve-text {
  color: rgba(148, 163, 184, 0.7);
  font-weight: 600;
}

.avoidance-notice {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 10px;
  background: linear-gradient(135deg, rgba(146, 64, 14, 0.15), rgba(180, 83, 9, 0.08));
  border: 1px solid rgba(251, 191, 36, 0.3);
  border-radius: 6px;
  animation: noticeFadeIn 0.4s ease;
}

@keyframes noticeFadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.notice-icon {
  font-size: 22px;
  flex-shrink: 0;
  margin-top: 1px;
}

.notice-content {
  flex: 1;
}

.notice-title {
  font-size: 13px;
  font-weight: 700;
  color: #fbbf24;
  margin-bottom: 4px;
}

.notice-desc {
  font-size: 11px;
  color: rgba(253, 230, 138, 0.75);
  line-height: 1.5;
}

.heatmap-area {
  flex: 1;
  min-height: 0;
  position: relative;
}

.chart {
  width: 100%;
  height: 100%;
}

.anomaly-section {
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid rgba(239, 68, 68, 0.2);
}

.anomaly-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #fca5a5;
  margin-bottom: 8px;
  font-weight: 600;
  gap: 8px;
  flex-wrap: wrap;
}

.anomaly-count {
  font-weight: normal;
  font-size: 11px;
  color: rgba(252, 165, 165, 0.7);
}

.lock-hint {
  margin-left: auto;
  font-size: 11px;
  font-weight: 600;
  color: rgba(148, 163, 184, 0.6);
  background: rgba(71, 85, 105, 0.25);
  padding: 2px 10px;
  border-radius: 10px;
  white-space: nowrap;
}

.anomaly-list {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.anomaly-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: rgba(127, 29, 29, 0.2);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 4px;
  font-size: 12px;
}

.anomaly-item.warning {
  background: rgba(146, 64, 14, 0.2);
  border-color: rgba(245, 158, 11, 0.3);
}
.anomaly-item.info {
  background: rgba(30, 64, 175, 0.2);
  border-color: rgba(59, 130, 246, 0.3);
}

.anomaly-item.avoided {
  opacity: 0.55;
  border-style: dashed;
}

.zone-name {
  font-weight: 600;
  color: #e2e8f0;
}

.deviation { color: #fca5a5; }
.deviation.high { color: #fbbf24; }

.severity-badge {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 600;
  background: rgba(239, 68, 68, 0.3);
  color: #fecaca;
}

.anomaly-item.warning .severity-badge {
  background: rgba(245, 158, 11, 0.3);
  color: #fde68a;
}
.anomaly-item.info .severity-badge {
  background: rgba(59, 130, 246, 0.3);
  color: #bfdbfe;
}

.avoid-badge {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 600;
  background: rgba(100, 116, 139, 0.25);
  color: rgba(148, 163, 184, 0.7);
  border: 1px dashed rgba(148, 163, 184, 0.3);
}

.anomaly-section.normal {
  border-top-color: rgba(34, 197, 94, 0.2);
}

.normal-state {
  font-size: 13px;
  color: #86efac;
  text-align: center;
  padding: 8px;
}
</style>
