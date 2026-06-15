<template>
  <div class="header">
    <div class="title-section">
      <div class="logo-icon">🏭</div>
      <div>
        <div class="main-title">造纸机烘缸群温度监控与蒸汽热力补偿系统</div>
        <div class="sub-title">CG10 · Paper Dryer Cylinder Temperature Monitor</div>
      </div>
    </div>
    <div class="status-section">
      <div class="status-item" :class="{ ok: systemStatus?.tcpServerRunning }">
        <span class="dot"></span>
        <span>TCP服务</span>
        <span class="val">{{ systemStatus?.connectedClients || 0 }} 客户端</span>
      </div>
      <div class="status-item" :class="{ ok: systemStatus?.influxDbConnected }">
        <span class="dot"></span>
        <span>时序库</span>
      </div>
      <div class="status-item">
        <span class="dot scan"></span>
        <span>扫描速率</span>
        <span class="val">{{ systemStatus?.scansPerSecond || 0 }} /s</span>
      </div>
      <div class="status-item warn" v-if="systemStatus?.anomalyCount">
        <span class="dot warn"></span>
        <span>异常</span>
        <span class="val">{{ systemStatus?.anomalyCount }}</span>
      </div>
      <div class="status-item comp" v-if="systemStatus?.activeCompensations">
        <span class="dot comp"></span>
        <span>热力补偿</span>
        <span class="val">{{ systemStatus?.activeCompensations }}</span>
      </div>
      <div class="clock">{{ currentTime }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

defineProps({
  systemStatus: Object
})

const currentTime = ref('')
let timer = null

function updateTime() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  currentTime.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.header {
  height: 64px;
  background: linear-gradient(90deg, rgba(15, 23, 42, 0.95) 0%, rgba(30, 58, 138, 0.3) 50%, rgba(15, 23, 42, 0.95) 100%);
  border-bottom: 1px solid rgba(59, 130, 246, 0.3);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.title-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  font-size: 32px;
}

.main-title {
  font-size: 20px;
  font-weight: 600;
  background: linear-gradient(90deg, #60a5fa, #93c5fd, #60a5fa);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 1px;
}

.sub-title {
  font-size: 11px;
  color: rgba(148, 163, 184, 0.6);
  letter-spacing: 2px;
  margin-top: 2px;
}

.status-section {
  display: flex;
  align-items: center;
  gap: 18px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: rgba(148, 163, 184, 0.8);
  padding: 6px 12px;
  background: rgba(30, 41, 59, 0.5);
  border-radius: 4px;
  border: 1px solid rgba(100, 116, 139, 0.2);
}

.status-item.ok {
  border-color: rgba(34, 197, 94, 0.3);
  color: rgba(134, 239, 172, 0.9);
}

.status-item.warn {
  border-color: rgba(239, 68, 68, 0.4);
  color: rgba(252, 165, 165, 0.95);
  background: rgba(127, 29, 29, 0.2);
}

.status-item.comp {
  border-color: rgba(251, 191, 36, 0.4);
  color: rgba(253, 230, 138, 0.95);
  background: rgba(120, 53, 15, 0.2);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(100, 116, 139, 0.5);
}

.status-item.ok .dot {
  background: #22c55e;
  box-shadow: 0 0 8px rgba(34, 197, 94, 0.6);
}

.status-item.warn .dot {
  background: #ef4444;
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.6);
  animation: blink 1s infinite;
}

.status-item.comp .dot {
  background: #f59e0b;
  box-shadow: 0 0 8px rgba(245, 158, 11, 0.6);
}

.dot.scan {
  background: #3b82f6;
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.6);
  animation: pulse 2s infinite;
}

.val {
  font-weight: 600;
  color: #fff;
}

.clock {
  font-family: 'Courier New', monospace;
  font-size: 14px;
  color: #93c5fd;
  background: rgba(30, 58, 138, 0.2);
  padding: 8px 14px;
  border-radius: 4px;
  border: 1px solid rgba(59, 130, 246, 0.2);
  letter-spacing: 1px;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.3); }
}
</style>
