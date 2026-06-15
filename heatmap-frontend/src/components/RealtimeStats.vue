<template>
  <div class="stats-panel">
    <div class="panel-title">
      <span class="title-icon">📈</span>
      <span>实时扫描吞吐</span>
    </div>
    <div class="stats-content">
      <div class="big-number">
        <span class="num">{{ currentSps }}</span>
        <span class="unit">条/秒</span>
      </div>
      <div class="mini-chart">
        <v-chart :option="sparkOption" autoresize class="spark" />
      </div>
      <div class="stats-row">
        <div class="mini-stat">
          <div class="label">平均温度</div>
          <div class="value">{{ avgTemp.toFixed(1) }}°C</div>
        </div>
        <div class="mini-stat">
          <div class="label">温度区间</div>
          <div class="value range">{{ minTemp.toFixed(0) }}° ~ {{ maxTemp.toFixed(0) }}°</div>
        </div>
        <div class="mini-stat">
          <div class="label">异常烘缸</div>
          <div class="value danger">{{ anomalyCylinders }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, GridComponent])

const props = defineProps({
  systemStatus: Object
})

const history = ref([])

watch(() => props.systemStatus?.scansPerSecond, (val) => {
  if (val != null) {
    history.value.push({ t: Date.now(), v: val })
    if (history.value.length > 30) history.value.shift()
  }
}, { immediate: true })

const currentSps = computed(() => props.systemStatus?.scansPerSecond || 0)

const allTemps = computed(() => {
  return (props.systemStatus?.cylinders || [])
    .filter(c => c.state !== 'offline')
    .map(c => c.avgTemp)
})

const avgTemp = computed(() => {
  const t = allTemps.value
  return t.length ? t.reduce((a, b) => a + b, 0) / t.length : 105
})

const minTemp = computed(() => {
  const t = allTemps.value
  return t.length ? Math.min(...t) : 90
})

const maxTemp = computed(() => {
  const t = allTemps.value
  return t.length ? Math.max(...t) : 120
})

const anomalyCylinders = computed(() => {
  return (props.systemStatus?.cylinders || [])
    .filter(c => c.state === 'warning' || c.state === 'critical').length
})

const sparkOption = computed(() => ({
  backgroundColor: 'transparent',
  grid: { left: 0, right: 0, top: 5, bottom: 0 },
  xAxis: { type: 'category', show: false, data: history.value.map(h => h.t) },
  yAxis: { type: 'value', show: false, min: 0 },
  series: [{
    type: 'line',
    data: history.value.map(h => h.v),
    smooth: true,
    showSymbol: false,
    lineStyle: {
      color: '#60a5fa',
      width: 2
    },
    areaStyle: {
      color: {
        type: 'linear',
        x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(96, 165, 250, 0.4)' },
          { offset: 1, color: 'rgba(96, 165, 250, 0.02)' }
        ]
      }
    }
  }]
}))
</script>

<style scoped>
.stats-panel {
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

.stats-content {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.big-number {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 12px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15) 0%, rgba(15, 23, 42, 0.5) 100%);
  border-radius: 6px;
  border: 1px solid rgba(59, 130, 246, 0.2);
}

.big-number .num {
  font-size: 32px;
  font-weight: 700;
  color: #60a5fa;
  font-family: 'Courier New', monospace;
  text-shadow: 0 0 20px rgba(96, 165, 250, 0.3);
}

.big-number .unit {
  font-size: 12px;
  color: rgba(148, 163, 184, 0.6);
}

.mini-chart {
  height: 50px;
}

.spark {
  width: 100%;
  height: 100%;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.mini-stat {
  background: rgba(30, 41, 59, 0.5);
  border-radius: 4px;
  padding: 6px 8px;
  text-align: center;
}

.mini-stat .label {
  font-size: 10px;
  color: rgba(148, 163, 184, 0.6);
  margin-bottom: 2px;
}

.mini-stat .value {
  font-size: 13px;
  font-weight: 700;
  color: #e2e8f0;
  font-family: 'Courier New', monospace;
}

.mini-stat .value.range {
  font-size: 11px;
}

.mini-stat .value.danger {
  color: #f87171;
}
</style>
