import axios from 'axios'

const api = axios.create({
  baseURL: '/',
  timeout: 5000
})

const CircuitBreaker = {
  consecutiveFailures: 0,
  lastFailureAt: 0,
  state: 'closed',
  openUntil: 0,
  successThreshold: 3,
  halfOpenSuccesses: 0,

  recordSuccess() {
    this.consecutiveFailures = 0
    if (this.state === 'half_open') {
      this.halfOpenSuccesses++
      if (this.halfOpenSuccesses >= this.successThreshold) {
        this.state = 'closed'
        this.halfOpenSuccesses = 0
        console.log('[熔断器] 服务恢复，切换到 CLOSED 状态')
      }
    }
  },

  recordFailure() {
    this.consecutiveFailures++
    this.lastFailureAt = Date.now()
    this.halfOpenSuccesses = 0

    if (this.consecutiveFailures >= 5 && this.state === 'closed') {
      this.state = 'open'
      this.openUntil = Date.now() + 15000
      console.warn(`[熔断器] 连续失败 ${this.consecutiveFailures} 次，熔断 15 秒`)
    }
  },

  allowRequest() {
    const now = Date.now()
    if (this.state === 'open') {
      if (now >= this.openUntil) {
        this.state = 'half_open'
        this.halfOpenSuccesses = 0
        console.log('[熔断器] 冷却结束，切换到 HALF_OPEN 探测')
        return true
      }
      return false
    }
    return true
  },

  getInfo() {
    return {
      state: this.state,
      consecutiveFailures: this.consecutiveFailures,
      openRemaining: Math.max(0, this.openUntil - Date.now())
    }
  }
}

let _lastErrorLogAt = 0
function suppressedErrorLog(msg, e) {
  const now = Date.now()
  if (now - _lastErrorLogAt > 10000) {
    console.warn(msg, CircuitBreaker.getInfo())
    _lastErrorLogAt = now
  }
}

api.interceptors.request.use((config) => {
  if (!CircuitBreaker.allowRequest()) {
    const info = CircuitBreaker.getInfo()
    const err = new Error(`CIRCUIT_OPEN: 后端服务熔断中，剩余 ${(info.openRemaining / 1000).toFixed(1)}s`)
    err.isCircuitOpen = true
    return Promise.reject(err)
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    CircuitBreaker.recordSuccess()
    return response
  },
  (error) => {
    if (error.isCircuitOpen) {
      suppressedErrorLog('[API熔断] 跳过请求')
      return Promise.reject(error)
    }
    CircuitBreaker.recordFailure()
    const status = error.response?.status || error.code || 'UNKNOWN'
    suppressedErrorLog(`[API失败] ${error.config?.url || ''} status=${status}`)
    return Promise.reject(error)
  }
)

export { api, CircuitBreaker }

export const wsUrl = '/ws/heatmap'
