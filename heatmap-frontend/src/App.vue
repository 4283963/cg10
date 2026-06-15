<template>
  <div class="dashboard">
    <DashboardHeader :systemStatus="systemStatus" />

    <div class="main-grid">
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

<script setup>import { ref, reactive, onMounted, onUnmounted, computed } from 'vue';
import DashboardHeader from './components/DashboardHeader.vue';
import StatusPanel from './components/StatusPanel.vue';
import CylinderOverview from './components/CylinderOverview.vue';
import CylinderDetail from './components/CylinderDetail.vue';
import CommandLog from './components/CommandLog.vue';
import RealtimeStats from './components/RealtimeStats.vue';
import { api, wsUrl } from './utils/api';
const systemStatus = ref(null);
const heatmaps = ref([]);
const recentCommands = ref([]);
const selectedCylinderId = ref(7);
const config = reactive({
 baseTemperature: 105,
 lowThreshold: 95,
 highThreshold: 130
});
const selectedHeatmap = computed(() => {
 return heatmaps.value.find(h => h.cylinderId === selectedCylinderId.value);
});
let ws = null;
let statusTimer = null;
let commandTimer = null;
function selectCylinder(id) {
 selectedCylinderId.value = id;
}
async function loadInitialData() {
 try {
 const [statusData, heatmapData, cmdData] = await Promise.all([
 api.get('/api/status'),
 api.get('/api/heatmaps'),
 api.get('/api/valve-commands?limit=30')
 ]);
 systemStatus.value = statusData.data;
 heatmaps.value = heatmapData.data.heatmaps || [];
 config.baseTemperature = heatmapData.data.baseTemperature || 105;
 config.lowThreshold = heatmapData.data.lowThreshold || 95;
 config.highThreshold = heatmapData.data.highThreshold || 130;
 recentCommands.value = cmdData.data;
 if (!selectedHeatmap.value && heatmaps.value.length > 0) {
 selectedCylinderId.value = heatmaps.value[0].cylinderId;
 }
 }
 catch (e) {
 console.error('加载初始数据失败', e);
 }
}
function connectWebSocket() {
 const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
 const host = location.hostname || '127.0.0.1';
 const port = location.port || '5173';
 ws = new WebSocket(`${proto}//${host}:${port}/ws/heatmap`);
 ws.onmessage = (ev) => {
 try {
 const data = JSON.parse(ev.data);
 const idx = heatmaps.value.findIndex(h => h.cylinderId === data.cylinderId);
 if (idx >= 0) {
 heatmaps.value.splice(idx, 1, data);
 }
 else {
 heatmaps.value.push(data);
 }
 }
 catch (e) {
 console.warn('解析WS消息失败', e);
 }
 };
 ws.onclose = () => {
 console.log('WS断开，3秒后重连');
 setTimeout(connectWebSocket, 3000);
 };
 ws.onerror = (e) => {
 console.warn('WS错误', e);
 };
}
onMounted(async () => {
 await loadInitialData();
 connectWebSocket();
 statusTimer = setInterval(async () => {
 try {
 const r = await api.get('/api/status');
 systemStatus.value = r.data;
 }
 catch (e) { }
 }, 2000);
 commandTimer = setInterval(async () => {
 try {
 const r = await api.get('/api/valve-commands?limit=30');
 recentCommands.value = r.data;
 }
 catch (e) { }
 }, 3000);
});
onUnmounted(() => {
 if (ws)
 ws.close();
 if (statusTimer)
 clearInterval(statusTimer);
 if (commandTimer)
 clearInterval(commandTimer);
});
</script>

<style scoped>
.dashboard {
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #0a0e1a 0%, #0f172a 50%, #0a0e1a 100%);
}

.main-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 340px 1fr 360px;
  gap: 12px;
  padding: 12px;
  min-height: 0;
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
