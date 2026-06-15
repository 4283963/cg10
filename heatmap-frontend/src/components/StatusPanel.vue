<template>
  <div class="status-panel">
    <div class="panel-title">
      <span class="title-icon">📊</span>
      <span>系统运行状态</span>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">总扫描数据</div>
        <div class="stat-value">{{ formatNum(systemStatus?.totalScansReceived || 0) }}</div>
        <div class="stat-unit">条</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">异常次数</div>
        <div class="stat-value warn">{{ systemStatus?.anomalyCount || 0 }}</div>
        <div class="stat-unit">次</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">热力补偿</div>
        <div class="stat-value comp">{{ systemStatus?.activeCompensations || 0 }}</div>
        <div class="stat-unit">次</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">在线烘缸</div>
        <div class="stat-value ok">{{ onlineCount }}/{{ totalCount }}</div>
        <div class="stat-unit">台</div>
      </div>
    </div>

    <div class="cylinder-summary">
      <div class="summary-title">烘缸状态分布</div>
      <div class="summary-bars">
        <div class="bar-item">
          <div class="bar-label">正常</div>
          <div class="bar-track">
            <div class="bar-fill ok" :style="{ width: normalPct + '%' }"></div>
          </div>
          <div class="bar-count">{{ normalCount }}</div>
        </div>
        <div class="bar-item">
          <div class="bar-label">预警</div>
          <div class="bar-track">
            <div class="bar-fill warn" :style="{ width: warningPct + '%' }"></div>
          </div>
          <div class="bar-count">{{ warningCount }}</div>
        </div>
        <div class="bar-item">
          <div class="bar-label">严重</div>
          <div class="bar-track">
            <div class="bar-fill danger" :style="{ width: criticalPct + '%' }"></div>
          </div>
          <div class="bar-count">{{ criticalCount }}</div>
        </div>
        <div class="bar-item">
          <div class="bar-label">离线</div>
          <div class="bar-track">
            <div class="bar-fill offline" :style="{ width: offlinePct + '%' }"></div>
          </div>
          <div class="bar-count">{{ offlineCount }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  systemStatus: Object
})

function formatNum(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return n
}

const cylinders = computed(() => props.systemStatus?.cylinders || [])
const totalCount = computed(() => cylinders.value.length || 40)
const onlineCount = computed(() => cylinders.value.filter(c => c.state !== 'offline').length)
const normalCount = computed(() => cylinders.value.filter(c => c.state === 'normal').length)
const warningCount = computed(() => cylinders.value.filter(c => c.state === 'warning').length)
const criticalCount = computed(() => cylinders.value.filter(c => c.state === 'critical').length)
const offlineCount = computed(() => cylinders.value.filter(c => c.state === 'offline').length)

function pct(n) {
  return totalCount.value > 0 ? (n / totalCount.value * 100) : 0
}

const normalPct = computed(() => pct(normalCount.value))
const warningPct = computed(() => pct(warningCount.value))
const criticalPct = computed(() => pct(criticalCount.value))
const offlinePct = computed(() => pct(offlineCount.value))
</script>

<style scoped>
.status-panel {
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
}

.title-icon {
  font-size: 16px;
}

.stat-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 14px;
}

.stat-card {
  background: linear-gradient(135deg, rgba(30, 58, 138, 0.15) 0%, rgba(15, 23, 42, 0.5) 100%);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 6px;
  padding: 10px 12px;
  position: relative;
}

.stat-label {
  font-size: 11px;
  color: rgba(148, 163, 184, 0.7);
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #60a5fa;
  font-family: 'Courier New', monospace;
  margin-top: 2px;
}

.stat-value.ok { color: #4ade80; }
.stat-value.warn { color: #f87171; }
.stat-value.comp { color: #fbbf24; }

.stat-unit {
  position: absolute;
  right: 10px;
  bottom: 8px;
  font-size: 10px;
  color: rgba(148, 163, 184, 0.5);
}

.cylinder-summary {
  background: rgba(15, 23, 42, 0.4);
  border-radius: 6px;
  padding: 10px 12px;
}

.summary-title {
  font-size: 12px;
  color: rgba(148, 163, 184, 0.8);
  margin-bottom: 10px;
}

.summary-bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bar-item {
  display: grid;
  grid-template-columns: 40px 1fr 30px;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.bar-label {
  color: rgba(148, 163, 184, 0.7);
}

.bar-track {
  height: 8px;
  background: rgba(30, 41, 59, 0.6);
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s ease;
}

.bar-fill.ok { background: linear-gradient(90deg, #16a34a, #4ade80); }
.bar-fill.warn { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
.bar-fill.danger { background: linear-gradient(90deg, #dc2626, #f87171); }
.bar-fill.offline { background: linear-gradient(90deg, #475569, #64748b); }

.bar-count {
  text-align: right;
  color: #cbd5e1;
  font-weight: 600;
  font-family: 'Courier New', monospace;
}
</style>
