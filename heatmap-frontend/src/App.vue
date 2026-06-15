<template>
  <div class="dashboard">
    <DashboardHeader :systemStatus="systemStatus" />

    <div v-if="backendState !== 'ok'" class="backend-alert" :class="backendState">
      <template v-if="backendState === 'open'">
        🚨 后端服务不可用！熔断器已触发，
        {{ (circuitInfo.openRemaining / 1000).toFixed(1) }}秒后尝试恢复（连续失败
        {{ circuitInfo.consecutiveFailures }}次）
      </template>
      <template v-else-if="backendState === 'failing'">
        ⚠️ 后端服务异常，正在重试...（失败 {{ circuitInfo.consecutiveFailures }}次）
      </template>
      <template v-else>
        🔍 正在探测后端服务恢复情况...
      </template>
    </div>

    <div class="main-grid" :class="{ 'dimmed': backendState !== 'ok' }">
      <div class="left-panel">
        <StatusPanel :systemStatus="systemStatus" class="panel" />
        <CylinderOverview
          :heatmaps="heatmaps"
          :selectedId="selectedCylinderId"
          @select="selectCylinder"
          class="panel overview-panel"
        />
        <RealtimeStats :systemStatus="systemStatus" class="panel" />
      </div>

      <div class="center-panel">
        <CylinderDetail
          v-if="selectedHeatmap"
          :heatmap="selectedHeatmap"
          :baseTemperature="config.baseTemperature"
          :lowThreshold="config.lowThreshold"
          @set-phase="handleSetPhase"
          class="panel detail-panel"
        />
        <div v-else class="panel detail-panel empty-panel">
          <div class="empty-tip">
            <div class="empty-icon">🌡️</div>
            <div>请从左侧选择烘缸查看详细温度热力图</div>
          </div>
        </div>
      </div>

      <div class="right-panel">
        <CommandLog :commands="recentCommands" class="panel log-panel" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, computed, watch } from 'vue'
import DashboardHeader from './components/DashboardHeader.vue'
import StatusPanel from './components/StatusPanel.vue'
import CylinderOverview from './components/CylinderOverview.vue'
import CylinderDetail from './components/CylinderDetail.vue'
import CommandLog from './components/CommandLog.vue'
import RealtimeStats from './components/RealtimeStats.vue'
import { api, CircuitBreaker } from './utils/api'

const systemStatus = ref(null)
const heatmaps = ref([])
const recentCommands = ref([])
const selectedCylinderId = ref(7)
const wsReconnectAttempts = ref(0)

const config = reactive({
  baseTemperature: 105,
  lowThreshold: 95,
  highThreshold: 130
})

const circuitInfo = reactive({
  state: 'closed',
  consecutiveFailures: 0,
  openRemaining: 0
})

const backendState = computed(() => circuitInfo.state)

const selectedHeatmap = computed(() => {
  return heatmaps.value.find(h => h.cylinderId === selectedCylinderId.value)
})

function selectCylinder(id) {
  selectedCylinderId.value = id
}

async function handleSetPhase({ cylinderId, phase }) {
  try {
    const r = await api.put(`/api/phases/${cylinderId}`, { phase, operator: 'web' })
    const updated = r.data
    const idx = heatmaps.value.findIndex(h => h.cylinderId === cylinderId)
    if (idx >= 0) {
      const hm = { ...heatmaps.value[idx] }
      hm.phase = updated.phase
      hm.phaseLabel = updated.phaseLabel
      hm.compensationLocked = updated.compensationLocked
      heatmaps.value.splice(idx, 1, hm)
    }
  } catch (e) {
    console.warn('设置阶段失败', e)
  }
}

let ws = null
let wsReconnectTimer = null
let statusTimer = null
let commandTimer = null
let circuitCheckTimer = null

function updateCircuitInfo() {
  const info = CircuitBreaker.getInfo()
  circuitInfo.state = info.state
  circuitInfo.consecutiveFailures = info.consecutiveFailures
  circuitInfo.openRemaining = info.openRemaining
}

function safePoll(interval, fn) {
  let stopped = false
  let timer = null

  async function tick() {
    if (stopped) return
    updateCircuitInfo()
    if (circuitInfo.state === 'open') {
      timer = setTimeout(tick, 500)
      return
    }
    try {
      await fn()
    } catch (_) {
      if (circuitInfo.state === 'half_open') {
        timer = setTimeout(tick, 2000)
        return
      }
    }
    const delay = circuitInfo.state === 'failing'
      ? Math.min(interval * Math.max(1, circuitInfo.consecutiveFailures), interval * 5)
      : interval
    timer = setTimeout(tick, delay)
  }

  tick()
  return () => {
    stopped = true
    if (timer) clearTimeout(timer)
  }
}

async function loadInitialData() {
  try {
    const [statusData, heatmapData, cmdData] = await Promise.all([
      api.get('/api/status'),
      api.get('/api/heatmaps'),
      api.get('/api/valve-commands?limit=30')
    ])
    systemStatus.value = statusData.data
    heatmaps.value = heatmapData.data.heatmaps || []
    config.baseTemperature = heatmapData.data.baseTemperature || 105
    config.lowThreshold = heatmapData.data.lowThreshold || 95
    config.highThreshold = heatmapData.data.highThreshold || 130
    recentCommands.value = cmdData.data
    if (!selectedHeatmap.value && heatmaps.value.length > 0) {
      selectedCylinderId.value = heatmaps.value[0].cylinderId
    }
    wsReconnectAttempts.value = 0
    updateCircuitInfo()
    return true
  } catch (e) {
    updateCircuitInfo()
    return false
  }
}

function connectWebSocket() {
  if (ws && ws.readyState <= 1) return
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = location.hostname || '127.0.0.1'
  const port = location.port || '5173'

  try {
    ws = new WebSocket(`${proto}//${host}:${port}/ws/heatmap`)
  } catch (e) {
    scheduleWsReconnect()
    return
  }

  let opened = false
  let _wsLogSuppress = 0

  ws.onopen = () => {
    opened = true
    wsReconnectAttempts.value = 0
  }

  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data)
      const idx = heatmaps.value.findIndex(h => h.cylinderId === data.cylinderId)
      if (idx >= 0) {
        heatmaps.value.splice(idx, 1, data)
      } else {
        heatmaps.value.push(data)
      }
    } catch (_) { }
  }

  ws.onclose = () => {
    scheduleWsReconnect()
  }

  ws.onerror = () => {
    const now = Date.now()
    if (now - _wsLogSuppress > 30000) {
      console.warn(`[WS] 连接错误，已尝试 ${wsReconnectAttempts.value} 次`)
      _wsLogSuppress = now
    }
    if (!opened) scheduleWsReconnect()
  }
}

function scheduleWsReconnect() {
  if (wsReconnectTimer) return
  wsReconnectAttempts.value++
  const delay = Math.min(1000 * Math.pow(1.6, Math.min(wsReconnectAttempts.value - 1, 8)), 30000)
  wsReconnectTimer = setTimeout(() => {
    wsReconnectTimer = null
    if (circuitInfo.state !== 'open') {
      connectWebSocket()
    } else {
      wsReconnectTimer = setTimeout(() => {
        wsReconnectTimer = null
        scheduleWsReconnect()
      }, 5000)
    }
  }, delay)
}

watch(() => circuitInfo.state, (newState, oldState) => {
  if (oldState === 'open' && (newState === 'half_open' || newState === 'closed')) {
    loadInitialData()
    if (!ws || ws.readyState > 1) {
      wsReconnectAttempts.value = 0
      connectWebSocket()
    }
  }
})

onMounted(async () => {
  await loadInitialData()
  connectWebSocket()

  statusTimer = safePoll(2000, async () => {
    const r = await api.get('/api/status')
    systemStatus.value = r.data
  })

  commandTimer = safePoll(3000, async () => {
    const r = await api.get('/api/valve-commands?limit=30')
    recentCommands.value = r.data
  })

  circuitCheckTimer = setInterval(updateCircuitInfo, 500)
})

onUnmounted(() => {
  if (ws) {
    try { ws.close() } catch (_) { }
  }
  if (wsReconnectTimer) clearTimeout(wsReconnectTimer)
  if (typeof statusTimer === 'function') statusTimer()
  if (typeof commandTimer === 'function') commandTimer()
  if (circuitCheckTimer) clearInterval(circuitCheckTimer)
})
</script>

<style scoped>
.dashboard {
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #0a0e1a 0%, #0f172a 50%, #0a0e1a 100%);
}

.backend-alert {
  flex-shrink: 0;
  padding: 10px 24px;
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  animation: slideDown 0.3s ease;
  border-bottom: 1px solid;
}

.backend-alert.open {
  background: linear-gradient(90deg, rgba(127, 29, 29, 0.8), rgba(153, 27, 27, 0.9), rgba(127, 29, 29, 0.8));
  color: #fecaca;
  border-color: rgba(239, 68, 68, 0.5);
  animation: alertBlink 1.5s infinite;
}

.backend-alert.failing {
  background: linear-gradient(90deg, rgba(146, 64, 14, 0.7), rgba(180, 83, 9, 0.8), rgba(146, 64, 14, 0.7));
  color: #fde68a;
  border-color: rgba(245, 158, 11, 0.5);
}

.backend-alert.half_open {
  background: linear-gradient(90deg, rgba(30, 64, 175, 0.6), rgba(37, 99, 235, 0.7), rgba(30, 64, 175, 0.6));
  color: #bfdbfe;
  border-color: rgba(59, 130, 246, 0.5);
}

@keyframes alertBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.85; }
}

@keyframes slideDown {
  from { transform: translateY(-100%); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}

.main-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 340px 1fr 360px;
  gap: 12px;
  padding: 12px;
  min-height: 0;
  transition: opacity 0.3s, filter 0.3s;
}

.main-grid.dimmed {
  opacity: 0.55;
  filter: grayscale(0.3);
  pointer-events: none;
}

.left-panel, .center-panel, .right-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.panel {
  background: rgba(15, 23, 42, 0.7);
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 8px;
  backdrop-filter: blur(10px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.overview-panel {
  flex: 1;
  min-height: 0;
}

.detail-panel {
  flex: 1;
  min-height: 0;
}

.log-panel {
  flex: 1;
  min-height: 0;
}

.empty-panel {
  justify-content: center;
  align-items: center;
}

.empty-tip {
  text-align: center;
  color: rgba(224, 230, 240, 0.4);
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
  opacity: 0.5;
}
</style>
